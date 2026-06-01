@file:OptIn(ExperimentalTime::class)
package com.development.ai_assistant.ui.viewmodel

import com.development.ai_assistant.domain.ai.LLMEngine
import com.development.ai_assistant.domain.model.ConversationTurn
import com.development.ai_assistant.domain.model.Message
import com.development.ai_assistant.domain.repository.ChatRepository
import com.development.ai_assistant.utils.STTManager
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

class ChatViewModel(
    private val repository: ChatRepository,
    private val engine: LLMEngine,
    private val ttsManager: TTSManager,
    private val sttManager: STTManager
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

    private val _isAutoSpeakEnabled = MutableStateFlow(false)
    val isAutoSpeakEnabled = _isAutoSpeakEnabled.asStateFlow()

    // 麦克风录音状态流
    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
    }

    /**
     * 按下麦克风：开始录音
     */
    fun startVoiceInput() {
        if (_isListening.value) return
        _isListening.value = true
        onInputTextChanged("正在聆听...")

        sttManager.startListening(
            onResult = { text, isFinal ->
                // 实时将语音转化为文字显示在输入框或浮层中
                onInputTextChanged(text)
                if (isFinal) {
                    _isListening.value = false
                }
            },
            onError = { errorMsg ->
                _isListening.value = false
                onInputTextChanged("[$errorMsg]")
            }
        )
    }

    /**
     * 松开麦克风：停止录音并立即发送
     */
    fun stopAndSendVoiceInput() {
        if (!_isListening.value) return
        sttManager.stopListening()
        _isListening.value = false

        // 获取刚刚识别出来的文字
        val finalMessage = _inputText.value

        // 过滤掉提示语和错误信息，只有真正的用户语音才发送
        if (finalMessage.isNotBlank() && finalMessage != "正在聆听..." && !finalMessage.startsWith("[")) {
            sendMessage(finalMessage)
        } else {
            // 如果没说话或者识别失败，清空输入框
            onInputTextChanged("")
        }
    }

    /**
     * 向 AI 引擎调度一条用户消息 (Dispatch User Message)
     *
     * 该方法是视图层 (UI) 与领域层 (Domain) 交互的总入口。
     * 负责前置的状态重置、持久化用户消息实体，并异步拉起推理引擎。
     *
     * @param text 输入框内的纯文本内容
     * @param images 待发送的图像字节流集合，默认为空列表
     */
    fun sendMessage(text: String, images: List<ByteArray> = emptyList()) {
        val hasImages = images.isNotEmpty()

        // 边界防御：阻止空消息发送（无字且无图时拦截）
        if (text.isBlank() && !hasImages) return
        // 边界防御：拦截 STT 系统的中间态提示语
        if (text == "正在聆听...") return

        // 重置 UI 输入框状态
        onInputTextChanged("")
        // 防呆设计：如果用户在录音时强制点击发送，需立即切断底层麦克风监听资源
        if (_isListening.value) {
            _isListening.value = false
            sttManager.stopListening()
        }

        // 切换至 IO 线程池执行高耗时（数据库写盘、网络 I/O）任务，避免阻塞主线程 (Main Thread) 引发 UI 卡顿
        viewModelScope.launch(Dispatchers.IO) {
            val now = Clock.System.now().toEpochMilliseconds()
            val groupId = "group_$now"

            // 动态构建展示在用户侧聊天气泡内的文案。如果有图片，则增加 [图片xN] 前缀
            val messageContent = if (hasImages) {
                if (images.size > 1) "[图片x${images.size}] $text".trim() else "[图片] $text".trim()
            } else {
                text
            }

            // 构建用户侧的消息实体并持久化到本地 SQLite 数据库
            val userMsg = Message(
                id = "user_$now",
                groupId = groupId,
                content = messageContent,
                isUser = true,
                timestamp = now
            )
            repository.insertMessage(userMsg)

            // 拉起底层引擎，执行多模态推理流程
            executeEngineInference(groupId, text, images)
        }
    }

    fun regenerateResponse(groupId: String, prompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            executeEngineInference(groupId, prompt)
        }
    }

    fun toggleAutoSpeak() {
        _isAutoSpeakEnabled.value = !_isAutoSpeakEnabled.value
        if (!_isAutoSpeakEnabled.value) {
            ttsManager.stop()
        }
    }

    private var currentSpeakingMessageId: String? = null

    fun onSpeakMessageClicked(messageId: String, text: String) {
        if (currentSpeakingMessageId == messageId && ttsManager.isSpeaking()) {
            ttsManager.stop()
            currentSpeakingMessageId = null
        } else {
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
     * 核心推理调度器 (Core Inference Scheduler)
     *
     * 负责协调底层大模型引擎、TTS 语音播报系统以及本地数据库持久化组件。
     * 采用节流阀机制 (Throttling) 优化高频数据库写入。
     */
    private suspend fun executeEngineInference(groupId: String, prompt: String, images: List<ByteArray> = emptyList()) {
        val now = Clock.System.now().toEpochMilliseconds() + 1
        val aiMsgId = "ai_$now"

        // 构建并插入处于 "Loading" 状态的 AI 占位消息气泡
        val initialAiMsg = Message(
            id = aiMsgId,
            groupId = groupId,
            content = "正在分析多模态数据...",
            isUser = false,
            timestamp = now,
            followUpQuestions = emptyList()
        )
        repository.insertMessage(initialAiMsg)

        // 移除该组的显示索引覆盖，确保总是展示最新生成的回答版本
        _displayIndexOverrides.update { it.toMutableMap().apply { remove(groupId) } }

        // 打断上一轮可能还在进行的 TTS 语音播报
        ttsManager.stop()

        // --- 以下为流式状态收集相关变量 ---
        var currentText = ""
        var displayContent = ""
        var dynamicFollowUps = emptyList<String>()

        // 性能优化：数据库写节流阀，避免高频 I/O 击穿 SQLite 性能上限
        var lastUpdateTime = 0L
        val throttleIntervalMs = 150L

        // TTS 断句追踪游标
        var lastSpokenIndex = 0
        // 正则匹配断句符，用于流式 TTS 的平滑播报
        val punctuationRegex = Regex("[。！？，；\n!?]")

        // 核心：挂起收集大模型引擎吐出的流式字符，传递图文参数
        engine.generateResponse(prompt, images).collect { partialResult ->
            currentText += partialResult
            val separator = "---追问---"

            // 业务逻辑：拦截并分离结构化的追问数据与正文数据
            if (currentText.contains(separator)) {
                val parts = currentText.split(separator)
                displayContent = parts[0].trim()

                if (parts.size > 1) {
                    val followUpsText = parts[1]
                    dynamicFollowUps = followUpsText.lines()
                        .filter { it.isNotBlank() }
                        .map { line ->
                            line.replace(Regex("^[-*\\d.]+\\s*"), "") // 剔除可能存在的 Markdown 列表符
                                .trim()
                                .removeSurrounding("[", "]") // 剥离中括号
                                .trim()
                        }
                        .filter { it.isNotEmpty() && it.length > 2 }
                        .take(3) // 强约束最多截取 3 个追问，防止 UI 溢出
                }
            } else {
                displayContent = currentText
            }

            // 业务逻辑：流式 TTS 动态分片播报
            if (_isAutoSpeakEnabled.value && displayContent.length > lastSpokenIndex) {
                val unSpokenText = displayContent.substring(lastSpokenIndex)
                val match = punctuationRegex.findAll(unSpokenText).lastOrNull()

                if (match != null) {
                    val splitIndex = match.range.last + 1
                    val textToSpeakChunk = unSpokenText.substring(0, splitIndex)
                    // 剔除会引起 TTS 引擎异响的特殊 Markdown 符号
                    val cleanText = textToSpeakChunk.replace(Regex("[#*`-]"), "")
                    ttsManager.speak(cleanText, flush = (lastSpokenIndex == 0))
                    lastSpokenIndex += splitIndex
                }
            }

            // 性能优化：节流式数据库刷新，驱动 UI 层重组 (Recomposition)
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

        // --- 流结束 (Stream Completed) 处理 ---

        // 保证最终完整数据的强制落盘
        val finalMsg = repository.getMessageById(aiMsgId) ?: initialAiMsg
        repository.insertMessage(finalMsg.copy(
            content = displayContent,
            followUpQuestions = dynamicFollowUps
        ))

        // 兜底播报：将剩余未遇到标点符号的尾部文本进行播报
        if (_isAutoSpeakEnabled.value && displayContent.length > lastSpokenIndex) {
            val remainingText = displayContent.substring(lastSpokenIndex).replace(Regex("[#*`-]"), "")
            if (remainingText.isNotBlank()) {
                ttsManager.speak(remainingText, flush = (lastSpokenIndex == 0))
            }
        }
    }
}