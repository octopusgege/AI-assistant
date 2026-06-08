package com.development.ai_assistant.domain.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地大模型推理引擎实现类
 * * 作用：作为 Kotlin 业务层与底层 C++ (llama.cpp) 之间的桥梁。
 * 负责加载本地 GGUF 模型文件，将用户的提问进行格式包装，并把底层一个一个蹦出来的字通过 Flow 传给界面。
 */
class LocalLlamaEngine(private val context: Context) : LLMEngine {

    // 记录底层 C++ 分配的上下文内存指针，0L 表示尚未加载模型
    private var ctxPtr: Long = 0L

    // 获取手机私有目录下模型文件的绝对路径
    private val modelPath: String
        get() = File(context.getExternalFilesDir(null), "qwen2.5-0.5b-instruct-q4_0.gguf").absolutePath

    /**
     * 按需初始化引擎（懒加载）
     * * 只有在第一次发送消息时才会执行加载，避免 App 刚启动时卡顿。
     * 包含读写文件的耗时操作，必须切换到 IO 线程执行。
     */
    private suspend fun initEngineIfNeeded() = withContext(Dispatchers.IO) {
        if (ctxPtr == 0L) {
            val file = File(modelPath)
            if (!file.exists()) {
                throw IllegalStateException("模型未找到，请检查路径: $modelPath")
            }

            // 调用底层的加载方法，申请 2048 的上下文长度和 4 个 CPU 线程
            ctxPtr = LlamaBridge.nativeLoadModel(modelPath, 2048, 4)
            if (ctxPtr == 0L) {
                throw IllegalStateException("底层 C++ 模型加载失败")
            }
        }
    }

    /**
     * 核心推理方法：生成流式回复
     * * @param prompt 用户的提问内容
     * @param images 本地模式暂不处理图片，该参数忽略
     * @return 返回一个 Flow 数据流，界面只需 collect 即可连续收到生成的文字
     */
    override fun generateResponse(prompt: String, images: List<ByteArray>): Flow<String> = callbackFlow {
        try {
            // 1. 确保底层模型已经准备好
            initEngineIfNeeded()

            // 2. 实例化回调接口，扮演“接球手”的角色
            // 当底层 C++ 算出一个字时，会触发这里的 onToken 方法
            val callback = object : TokenCallback {
                override fun onToken(token: String) {
                    // trySend 是 callbackFlow 提供的发射器，直接把字塞进流里
                    trySend(token)
                }
            }

            // 3. 按照 Qwen 模型的标准 ChatML 格式拼接提示词
            // 只有加上特定的 <|im_start|> 和 <|im_end|> 标记，模型才能分清谁是系统、谁是用户
            val chatMlPrompt = """
                <|im_start|>system
                你是一个AI 助手，用简洁准确的语言回复用户问题。<|im_end|>
                <|im_start|>user
                $prompt<|im_end|>
                <|im_start|>assistant
                
            """.trimIndent()

            // 4. 启动后台协程去执行耗时的生成任务
            launch(Dispatchers.IO) {
                // 将准备好的文本和 callback 交给底层，C++ 开始循环算字
                LlamaBridge.nativeGenerateStream(ctxPtr, chatMlPrompt, 512, callback)

                // 当 C++ 彻底运算完毕退出后，关闭数据流，通知界面“回答结束”
                close()
            }

        } catch (e: Exception) {
            // 如果遇到模型不存在或其他异常，把错误信息当作回答发给界面，防止应用崩溃
            trySend("\n[异常: ${e.message}]")
            close(e)
        }

        // 5. callbackFlow 必须保留的收尾操作
        // 如果界面被销毁导致 Flow 取消，这里可以处理相关的资源清理工作
        awaitClose {
            // 暂不需要在此处释放模型，防止下次问答重新加载缓慢
        }
    }
}