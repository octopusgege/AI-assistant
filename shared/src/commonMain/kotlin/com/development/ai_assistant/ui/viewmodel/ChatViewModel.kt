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

    fun sendMessage(text: String) {
        if (text.isBlank() || text == "正在聆听...") return
        onInputTextChanged("")
        // 确保发送时关闭可能仍在运行的语音引擎
        if (_isListening.value) {
            _isListening.value = false
            sttManager.stopListening()
        }

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

        ttsManager.stop()

        var currentText = ""
        var displayContent = ""
        var dynamicFollowUps = emptyList<String>()

        var lastUpdateTime = 0L
        val throttleIntervalMs = 150L
        var lastSpokenIndex = 0
        val punctuationRegex = Regex("[。！？，；\n!?]")

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
                        .filter { it.isNotEmpty() && it.length > 2 }
                        .take(3)
                }
            } else {
                displayContent = currentText
            }

            if (_isAutoSpeakEnabled.value && displayContent.length > lastSpokenIndex) {
                val unSpokenText = displayContent.substring(lastSpokenIndex)
                val match = punctuationRegex.findAll(unSpokenText).lastOrNull()

                if (match != null) {
                    val splitIndex = match.range.last + 1
                    val textToSpeakChunk = unSpokenText.substring(0, splitIndex)
                    val cleanText = textToSpeakChunk.replace(Regex("[#*`-]"), "")
                    ttsManager.speak(cleanText, flush = (lastSpokenIndex == 0))
                    lastSpokenIndex += splitIndex
                }
            }

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

        val finalMsg = repository.getMessageById(aiMsgId) ?: initialAiMsg
        repository.insertMessage(finalMsg.copy(
            content = displayContent,
            followUpQuestions = dynamicFollowUps
        ))

        if (_isAutoSpeakEnabled.value && displayContent.length > lastSpokenIndex) {
            val remainingText = displayContent.substring(lastSpokenIndex).replace(Regex("[#*`-]"), "")
            if (remainingText.isNotBlank()) {
                ttsManager.speak(remainingText, flush = (lastSpokenIndex == 0))
            }
        }
    }
}