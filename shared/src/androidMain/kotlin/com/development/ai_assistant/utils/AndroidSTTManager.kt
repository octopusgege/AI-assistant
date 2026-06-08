/**
 * 文件名：AndroidSTTManager.kt
 * 功能描述：Android 平台原生语音识别 (STT - Speech To Text) 引擎的具体实现类。
 * 核心作用：封装系统级 SpeechRecognizer 服务，提供流式的语音捕获、音频解码与文本转换能力。
 * 业务用途：用于在端侧实现“边说边转”的输入交互，将声学信号实时转化为文本指令流交由上层 ViewModel 处理。
 */
package com.development.ai_assistant.utils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class AndroidSTTManager(private val context: Context) : STTManager {

    // 底层语音识别器实例，需严格管控其生命周期以防止内存泄漏
    private var speechRecognizer: SpeechRecognizer? = null

    // 主线程调度器：Android 系统强制要求 SpeechRecognizer 的实例化与回调必须在 Main Looper 中执行
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 激活硬件麦克风并建立语音识别通道
     *
     * @param onResult 识别结果回调。参数 1: 识别出的文本片段 (String)；参数 2: 是否为最终完整结果 (Boolean)。
     * @param onError 异常回调。向外抛出可供 UI 展示的友好的错误提示文本。
     */
    override fun startListening(onResult: (String, Boolean) -> Unit, onError: (String) -> Unit) {
        // 强制将执行上下文切换至主线程 (UI Thread)
        mainHandler.post {
            // 1. 硬件权限动态前置校验
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                onError("缺乏麦克风权限，请前往系统设置开启")
                return@post
            }

            // 2. 检查当前系统环境是否内置了可用的语音识别服务组件
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                // 初始化识别引擎对象
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

                // 3. 构建识别参数意图 (Intent Payload)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    // 采用自由形式语言模型，适配日常对话与指令
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    // 锁定识别语言为简体中文，防止系统语言环境导致识别漂移
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    // 核心参数：强制开启流式返回，允许引擎吐出中间态的残缺文本 (Partial Results)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                // 4. 挂载异步监听状态机
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    // --- 预留的硬件环境与声学缓冲区回调 (暂不处理) ---
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    /**
                     * 硬件或网络层的终端异常拦截
                     */
                    override fun onError(error: Int) {
                        val errorMsg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                            SpeechRecognizer.ERROR_NETWORK -> "语音网络异常"
                            SpeechRecognizer.ERROR_NO_MATCH -> "未能听清"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "说话超时"
                            else -> "识别异常 (代码: $error)"
                        }
                        onError(errorMsg)
                    }

                    /**
                     * 最终识别结果投递 (断句结束或麦克风关闭时触发)
                     */
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            // isFinal 标记为 true，通知上层流式输入已完结
                            onResult(matches[0], true)
                        }
                    }

                    /**
                     * 增量/中间态识别结果投递 (流式推流)
                     */
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            // isFinal 标记为 false，通知上层更新 UI 缓冲但不触发最终逻辑
                            onResult(matches[0], false)
                        }
                    }
                })

                // 5. 正式通电，开启硬件麦克风监听
                speechRecognizer?.startListening(intent)
            } else {
                // 兜底处理：设备遭阉割或缺乏 Google/厂商 语音服务架构
                onError("当前设备不支持系统级语音识别")
            }
        }
    }

    /**
     * 终止音频捕获并销毁底层资源
     * * 业务要求：在手指抬起 (Action_Up) 或页面销毁 (onDestroy) 时必须调用，以释放底层的 AudioRecord 独占锁。
     */
    override fun stopListening() {
        // 销毁操作同样必须受限于系统主线程
        mainHandler.post {
            speechRecognizer?.let {
                it.stopListening() // 发送结束信号，引擎将结算最后一句文本
                it.destroy()       // 彻底卸载组件，释放 C++ 层的硬件占用
            }
            // 引用归零，主动触发 GC 垃圾回收
            speechRecognizer = null
        }
    }
}