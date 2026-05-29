package com.development.ai_assistant.utils

/**
 * 跨端文本转语音 (TTS) 接口
 */
interface TTSManager {
    fun speak(text: String, flush: Boolean = true)
    fun stop()
    // 查询当前底层语音引擎是否正在播报
    fun isSpeaking(): Boolean
}