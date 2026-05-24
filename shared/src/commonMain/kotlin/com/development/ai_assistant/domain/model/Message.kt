package com.development.ai_assistant.domain.model

// 聊天消息实体类
data class Message(
    val id: String,          // 消息唯一标识
    val content: String,     // 消息文本内容
    val isUser: Boolean      // true 表示用户发送，false 表示 AI 回复
)