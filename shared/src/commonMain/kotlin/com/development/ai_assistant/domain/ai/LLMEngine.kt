package com.development.ai_assistant.domain.ai

import kotlinx.coroutines.flow.Flow

/**
 * 核心大模型推理引擎抽象规约 (Engine Interface)
 * * 采用依赖倒置原则 (DIP)，将业务层与具体的网络实现隔离。
 */
interface LLMEngine {

    /**
     * 发起多模态流式响应请求 (Multi-modal Streaming Request)
     *
     * @param prompt 用户输入的纯文本指令。
     * @param images 待分析的图像二进制字节流集合。默认值为空列表，向下兼容纯文本对话场景。
     * 集合内的每张图片都将被转换为 Base64 并行输入给视觉大模型。
     * @return 包含流式响应文本的 [Flow] 数据流，上层通过 collect() 持续收集大模型吐出的字符碎片。
     */
    fun generateResponse(
        prompt: String,
        images: List<ByteArray> = emptyList()
    ): Flow<String>
}