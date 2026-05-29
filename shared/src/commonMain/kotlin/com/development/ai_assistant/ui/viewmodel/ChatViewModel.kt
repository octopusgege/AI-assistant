@file:OptIn(ExperimentalTime::class)
package com.development.ai_assistant.ui.viewmodel

import com.development.ai_assistant.domain.ai.LLMEngine
import com.development.ai_assistant.domain.model.ConversationTurn
import com.development.ai_assistant.domain.model.Message
import com.development.ai_assistant.domain.repository.ChatRepository
import com.development.ai_assistant.utils.TTSManager
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
 * 聊天会话视图模型
 *
 * 核心职责：
 * 1. 维护会话列表的 UI 状态流 (StateFlow)。
 * 2. 调度用户输入与本地数据库持久化操作。
 * 3. 驱动远端大模型引擎进行流式推理，并拦截处理特殊格式（如追问建议）。
 * 4. 结合时间节流阀保障 UI 渲染性能，并通过标点切片算法实现流式语音播报 (Streaming TTS)。
 */
class ChatViewModel(
    private val repository: ChatRepository,
    private val engine: LLMEngine,
    private val ttsManager: TTSManager
) {
    /**
     * 协程作用域：绑定主线程并使用 SupervisorJob，确保子协程失败不会取消整个 ViewModel 的生命周期。
     */
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 当前正在通过 TTS 播放的消息 ID。
     * 用于判断点击喇叭按钮时是执行“停止”还是“播放新消息”逻辑。
     */
    private var currentSpeakingMessageId: String? = null

    /**
     * 全局自动语音播报开关的状态流。
     * 当开启时，AI 回复生成过程中会自动进行流式语音合成与播放。
     */
    private val _isAutoSpeakEnabled = MutableStateFlow(false)
    val isAutoSpeakEnabled = _isAutoSpeakEnabled.asStateFlow()

    /**
     * 用户输入框文本内容的状态流。
     */
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    private val _displayIndexOverrides = MutableStateFlow<Map<String, Int>>(emptyMap())
    val displayIndexOverrides = _displayIndexOverrides.asStateFlow()

    /**
     * 会话轮次状态流
     * 从本地持久层拉取所有消息，并按 groupId 进行分组排序，构建对话轮次 (ConversationTurn)。
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




    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
    }

    /**
     * 发送用户消息并触发模型推理
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
     * 针对当前会话组重新生成大模型回复
     */
    fun regenerateResponse(groupId: String, prompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            executeEngineInference(groupId, prompt)
        }
    }

    /**
     * 切换全局自动语音播报状态
     */
    fun toggleAutoSpeak() {
        _isAutoSpeakEnabled.value = !_isAutoSpeakEnabled.value
        if (!_isAutoSpeakEnabled.value) {
            ttsManager.stop()
        }
    }


    /**
     * 处理气泡下方喇叭按钮的点击事件（播放/停止切换）
     */
    fun onSpeakMessageClicked(messageId: String, text: String) {
        // 如果点击的是同一条消息，并且当前系统正在发声，则执行停止操作
        if (currentSpeakingMessageId == messageId && ttsManager.isSpeaking()) {
            ttsManager.stop()
            currentSpeakingMessageId = null
        } else {
            // 否则（点击了新消息，或者当前没在发声），打断之前声音并开始朗读当前消息
            val cleanText = text.replace(Regex("[#*`-]"), "")
            ttsManager.speak(cleanText, flush = true)
            currentSpeakingMessageId = messageId
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

    /**
     * 核心推理引擎与流式调度控制
     * * 业务流程：
     * 1. 初始化预占位消息结构并写入数据库。
     * 2. 监听底层网络引擎的 SSE 字符流下发。
     * 3. 报文解析：分离模型正文与追问区块。
     * 4. 语音切片：识别断句标点，动态向 TTS 引擎投递播放任务。
     * 5. 渲染节流：基于系统时钟实施脏数据合并写入，避免主线程过度重绘。
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

        // 推理流程启动前，强制终止遗留的语音播报任务
        ttsManager.stop()

        var currentText = ""
        var displayContent = ""
        var dynamicFollowUps = emptyList<String>()

        // 渲染更新控制变量：阈值设为 150 毫秒
        var lastUpdateTime = 0L
        val throttleIntervalMs = 150L

        // 流式语音切片控制变量
        var lastSpokenIndex = 0
        // 基于常规中文/英文断句符号进行文本分片，保障语义连贯性
        val punctuationRegex = Regex("[。！？，；\n!?]")

        engine.generateResponse(prompt).collect { partialResult ->
            currentText += partialResult
            val separator = "---追问---"

            // 模块一：富文本报文解析与剥离
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
                        .filter { it.isNotEmpty() && it.length > 2 }
                        .take(3)
                }
            } else {
                displayContent = currentText
            }

            // 模块二：流式语音播报任务分发
            if (_isAutoSpeakEnabled.value && displayContent.length > lastSpokenIndex) {
                val unSpokenText = displayContent.substring(lastSpokenIndex)
                val match = punctuationRegex.findAll(unSpokenText).lastOrNull()

                if (match != null) {
                    val splitIndex = match.range.last + 1
                    val textToSpeakChunk = unSpokenText.substring(0, splitIndex)

                    // 剥除可能影响 TTS 引擎发音逻辑的 Markdown 特殊修饰符
                    val cleanText = textToSpeakChunk.replace(Regex("[#*`-]"), "")

                    // 首次投递采取刷新机制中断原有队列，后续段落采取追加机制
                    ttsManager.speak(cleanText, flush = (lastSpokenIndex == 0))
                    lastSpokenIndex += splitIndex
                }
            }

            // UI 状态重绘与持久层 IO 节流保护
            val currentTime = Clock.System.now().toEpochMilliseconds()
            if (currentTime - lastUpdateTime >= throttleIntervalMs) {
                val currentMsg = repository.getMessageById(aiMsgId) ?: initialAiMsg
                repository.insertMessage(currentMsg.copy(
                    content = displayContent,
                    followUpQuestions = dynamicFollowUps
                ))
                lastUpdateTime = currentTime
            }
        }

        // 模块四：数据流结束后的边缘用例兜底处理
        val finalMsg = repository.getMessageById(aiMsgId) ?: initialAiMsg
        repository.insertMessage(finalMsg.copy(
            content = displayContent,
            followUpQuestions = dynamicFollowUps
        ))

        // 播报残留的末尾非规范短句（无结尾标点的文本残片）
        if (_isAutoSpeakEnabled.value && displayContent.length > lastSpokenIndex) {
            val remainingText = displayContent.substring(lastSpokenIndex).replace(Regex("[#*`-]"), "")
            if (remainingText.isNotBlank()) {
                ttsManager.speak(remainingText, flush = (lastSpokenIndex == 0))
            }
        }
    }
}