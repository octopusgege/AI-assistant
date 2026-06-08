package com.development.ai_assistant.utils

/**
 * 跨端语音识别接口（语音转文字）
 */
interface STTManager {
    /**
     * 开始监听麦克风
     * @param onResult 识别结果回调。String 为识别出的文字；Boolean 为 true 代表最终结果，false 代表用户还在说话时的中间结果。
     * @param onError 错误信息回调。
     */
    fun startListening(onResult: (String, Boolean) -> Unit, onError: (String) -> Unit)

    /**
     * 停止监听并释放麦克风资源
     */
    fun stopListening()
}