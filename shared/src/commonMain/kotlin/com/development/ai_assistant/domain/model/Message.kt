package com.development.ai_assistant.domain.model

data class Message(
    val id: String,
    val groupId: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = 0L, //记录产生时间
    val interactionStatus: Int = 0,
    val followUpQuestions: List<String> = emptyList()
)

data class ConversationTurn(
    val groupId: String,
    val userMessage: Message,
    val aiMessages: List<Message>,
    val currentDisplayIndex: Int
)