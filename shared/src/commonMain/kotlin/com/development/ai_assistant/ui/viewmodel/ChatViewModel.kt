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
 * 实现了基于时间窗口的节流机制（Throttling），保障富文本渲染时的 UI 帧率。
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

    /**
     * 引擎推理与流式控制
     * 拦截 SSE 响应流，分离正文与追问标签，并应用节流阀避免过度的重绘请求阻塞主线程。
     */
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
        var displayContent = ""
        var dynamicFollowUps = emptyList<String>()

        // 渲染节流控制变量
        var lastUpdateTime = 0L
        val throttleIntervalMs = 150L

        engine.generateResponse(prompt).collect { partialResult ->
            currentText += partialResult
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
                        .filter { it.isNotEmpty()}
                        // 取前 3 个追问
                        .take(3)
                }
            } else {
                displayContent = currentText
            }

            val currentTime = Clock.System.now().toEpochMilliseconds()

            // 节流阀逻辑：仅在距离上次更新超过指定时间窗口时，才放行数据库写入与 UI 状态更新
            if (currentTime - lastUpdateTime >= throttleIntervalMs) {
                val currentMsg = repository.getMessageById(aiMsgId) ?: initialAiMsg
                repository.insertMessage(currentMsg.copy(
                    content = displayContent,
                    followUpQuestions = dynamicFollowUps
                ))
                lastUpdateTime = currentTime
            }
        }

        // 流式下发结束后，强制执行一次全量覆写，确保不会遗漏时间窗口内的最后尾部字符
        val finalMsg = repository.getMessageById(aiMsgId) ?: initialAiMsg
        repository.insertMessage(finalMsg.copy(
            content = displayContent,
            followUpQuestions = dynamicFollowUps
        ))
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