/**
 * 文件名：AndroidTTSManager.kt
 * 功能描述：Android 平台原生文字转语音 (TTS - Text To Speech) 引擎的具体实现类。
 * 核心作用：封装系统级的 TextToSpeech 服务，负责将大模型生成的文本转化为声学信号进行物理输出。
 * 业务用途：支持全量单次播报，以及配合 ViewModel 层实现大模型流式输出时的“边下边读”无缝拼接。
 */
package com.development.ai_assistant.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class AndroidTTSManager(context: Context) : TTSManager {

    // 底层语音合成引擎实例
    private var tts: TextToSpeech? = null

    /**
     * 初始化代码块
     * * 机制：TextToSpeech 的初始化是一个异步过程（涉及底层服务的绑定）。
     * 因此必须通过回调函数 (Listener) 来捕获初始化状态，确保引擎就绪后再配置语言参数。
     */
    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 强制锁定发音语言为中文，防止系统语言环境异常导致发音引擎崩溃或乱码
                tts?.language = Locale.CHINESE
            }
        }
    }

    /**
     * 发起硬件级语音播报指令
     *
     * @param text  待播报的纯文本（注意：传入前需由业务层确保已剔除 Markdown 等非语义符号）
     * @param flush 核心队列控制阀：
     * - true  (QUEUE_FLUSH): 强制插队。立刻掐断当前扬声器正在播放的声音，清空历史任务队列，并最高优级朗读本次文本。
     * 常用于用户点击“停止/切换”或流式大模型吐出第一句话时。
     * - false (QUEUE_ADD)  : 乖乖排队。不打断当前正在播放的声音，将本次文本静默追加到系统播放列表末尾。
     * 常用于大模型持续吐出第二、第三句话时的无缝声学衔接。
     */
    override fun speak(text: String, flush: Boolean) {
        // 根据业务层传入的 flush 指令，动态映射为 Android 底层的队列枚举常量
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD

        // 触发底层发声（最后两个参数分别代表附加参数与唯一标识 ID，此处采用默认配置）
        tts?.speak(text, queueMode, null, null)
    }

    /**
     * 强行终止当前发音引擎的所有工作
     * * 业务场景：通常在页面销毁 (onDestroy) 或用户主动要求闭嘴时调用，立即释放硬件独占状态。
     */
    override fun stop() {
        tts?.stop()
    }

    /**
     * 探测当前声学硬件的工作状态
     *
     * @return Boolean 返回 true 代表扬声器正在输出该引擎提供的语音流；返回 false 代表引擎处于空闲挂起状态。
     */
    override fun isSpeaking(): Boolean {
        // 安全调用机制：若 tts 对象尚未初始化完毕，默认返回 false
        return tts?.isSpeaking == true
    }
}