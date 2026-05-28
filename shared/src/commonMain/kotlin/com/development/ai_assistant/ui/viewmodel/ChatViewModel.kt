@file:OptIn(ExperimentalTime::class)
package com.development.ai_assistant.ui.viewmodel

import com.development.ai_assistant.domain.ai.LLMEngine
import com.development.ai_assistant.domain.model.ConversationTurn
import com.development.ai_assistant.domain.model.Message
import com.development.ai_assistant.domain.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 聊天界面视图模型
 * 负责协调用户输入、本地数据库的持久化调度以及与底层推理引擎的流式数据交互。
 * 具备流式内容实时拦截解析能力，用于分离正文与推荐追问标签，并清洗大模型生成的冗余符号。
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val engine: LLMEngine
) {
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val conversationTurns: StateFlow<List<ConversationTurn>> = repository.getAllMessagesFlow()
        .map { messages ->
            val turns = mutableListOf<ConversationTurn>()
            val grouped = messages.groupBy { it.groupId }
            for ((groupId, groupMessages) in grouped) {
                val userMsg = groupMessages.find { it.isUser } ?: continue
                val aiMsgs = groupMessages.filter { !it.isUser }
                val sortedAiMsgs = aiMsgs.sortedBy { it.timestamp }
                turns.add(ConversationTurn(groupId, userMsg, sortedAiMsgs, sortedAiMsgs.size - 1))
            }
            turns.sortedBy { it.userMessage.timestamp }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    private val _displayIndexOverrides = MutableStateFlow<Map<String, Int>>(emptyMap())
    val displayIndexOverrides = _displayIndexOverrides.asStateFlow()

    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        onInputTextChanged("")

        viewModelScope.launch(Dispatchers.IO) {
            val now = Clock.System.now().toEpochMilliseconds()
            val groupId = "group_$now"

            val userMsg = Message(
                id = "user_$now",
                groupId = groupId,
                content = text,
                isUser = true,
                timestamp = now
            )
            repository.insertMessage(userMsg)

            executeEngineInference(groupId, text)
        }
    }

    fun regenerateResponse(groupId: String, prompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            executeEngineInference(groupId, prompt)
        }
    }

    private suspend fun executeEngineInference(groupId: String, prompt: String) {
        val now = Clock.System.now().toEpochMilliseconds() + 1
        val aiMsgId = "ai_$now"

        val initialAiMsg = Message(
            id = aiMsgId,
            groupId = groupId,
            content = "正在思考...",
            isUser = false,
            timestamp = now,
            followUpQuestions = emptyList()
        )
        repository.insertMessage(initialAiMsg)

        _displayIndexOverrides.update { it.toMutableMap().apply { remove(groupId) } }

        var currentText = ""
        engine.generateResponse(prompt).collect { partialResult ->
            currentText += partialResult

            var displayContent = currentText
            var dynamicFollowUps = emptyList<String>()
            val separator = "---追问---"

            if (currentText.contains(separator)) {
                val parts = currentText.split(separator)
                displayContent = parts[0].trim()

                if (parts.size > 1) {
                    val followUpsText = parts[1]
                    dynamicFollowUps = followUpsText.lines()
                        .filter { it.isNotBlank() }
                        .map { line ->
                            line.replace(Regex("^[-*\\d.]+\\s*"), "")
                                .trim()
                                .removeSurrounding("[", "]")
                                .trim()
                        }
                        .filter { it.isNotEmpty() }
                }
            }

            val currentMsg = repository.getMessageById(aiMsgId) ?: initialAiMsg
            repository.insertMessage(currentMsg.copy(
                content = displayContent,
                followUpQuestions = dynamicFollowUps
            ))
        }
    }

    fun changeDisplayIndex(groupId: String, newIndex: Int) {
        _displayIndexOverrides.update { it.toMutableMap().apply { put(groupId, newIndex) } }
    }

    fun onInteractionClicked(messageId: String, currentStatus: Int, targetStatus: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val finalStatus = if (currentStatus == targetStatus) 0 else targetStatus
            repository.updateInteraction(messageId, finalStatus)
        }
    }
}