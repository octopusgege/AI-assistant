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
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 聊天界面视图模型
 * 负责协调用户输入、本地数据库的持久化调度以及与底层推理引擎的流式数据交互。
 * 采用 MVI 架构中的单向数据流原则更新 UI 状态。
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val engine: LLMEngine
) {
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 会话轮次状态流
     * 从本地 SQLite 数据库订阅数据源，按 groupId 聚合提问与回复，并基于时间戳进行全局排序
     */
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

    /**
     * 更新输入框文本状态
     */
    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
    }

    /**
     * 派发用户消息
     * 调度至 IO 线程以避免数据库写入阻塞主线程，同时生成基于毫秒级时间戳的唯一标识
     */
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

    /**
     * 触发多版本回复重新生成逻辑
     */
    fun regenerateResponse(groupId: String, prompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            executeEngineInference(groupId, prompt)
        }
    }

    /**
     * 执行底层引擎推理过程
     * 创建占位消息后，收集引擎流式分发的数据，通过内存累加方式同步覆盖写入数据库
     */
    private suspend fun executeEngineInference(groupId: String, prompt: String) {
        val now = Clock.System.now().toEpochMilliseconds() + 1
        val aiMsgId = "ai_$now"
        val initialFollowUps = listOf("你能详细解释一下吗？", "这个方案有什么优缺点？")

        val initialAiMsg = Message(
            id = aiMsgId,
            groupId = groupId,
            content = "正在思考...",
            isUser = false,
            timestamp = now,
            followUpQuestions = initialFollowUps
        )
        repository.insertMessage(initialAiMsg)

       _displayIndexOverrides.update { it.toMutableMap().apply { remove(groupId) } }

        var currentText = ""
        engine.generateResponse(prompt).collect { partialResult ->
            currentText += partialResult
            val currentMsg = repository.getMessageById(aiMsgId) ?: initialAiMsg
            repository.insertMessage(currentMsg.copy(content = currentText))
        }
    }

    /**
     * 覆盖默认视图索引
     * 用户点 "<" ">" 切换 AI 回复版本时调用
     */
    fun changeDisplayIndex(groupId: String, newIndex: Int) {
        _displayIndexOverrides.update { it.toMutableMap().apply { put(groupId, newIndex) } }
    }

    /**
     * 处理交互事件（点赞/点踩）
     * 状态机支持反向取消逻辑，状态变更通过 IO 线程同步至数据库
     */
    fun onInteractionClicked(messageId: String, currentStatus: Int, targetStatus: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val finalStatus = if (currentStatus == targetStatus) 0 else targetStatus
            repository.updateInteraction(messageId, finalStatus)
        }
    }
}