package com.development.ai_assistant.domain.ai

//  C++每生成一个字，就会调用这个接口的onToken方法
interface TokenCallback {
    fun onToken(token: String)
}

object LlamaBridge {
    init {
        // 加载我们编译好的C++库 libllama_bridge.so
        System.loadLibrary("llama_bridge")
    }

    // external关键字表示这个方法是用C++实现的
    // 加载模型，返回上下文的内存地址
    external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Long

    // 释放模型
    external fun nativeFreeModel(ctxPtr: Long)

    // 流式生成回答，每生成一个字就调用一次callback.onToken
    external fun nativeGenerateStream(ctxPtr: Long, prompt: String, maxTokens: Int, callback: TokenCallback)
}