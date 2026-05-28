package com.development.ai_assistant.domain.ai

import kotlinx.coroutines.flow.Flow

// 跨端的大模型接口
interface LLMEngine {
    // 接收用户的 prompt，返回一个流式吐字的 Flow 数据流
    fun generateResponse(prompt: String): Flow<String>
}