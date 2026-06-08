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

/**
 * 智能交互全链路调度中枢 (ChatViewModel)
 * * 架构设计目标：
 * 1. 单向数据流 (UDF) 核心：作为 Single Source of Truth (唯一真相源)，为 UI 暴露无副作用的 StateFlow，彻底解耦视图与逻辑。
 * 2. 策略模式 (Strategy Pattern)：通过依赖注入挂载“云端”与“端侧”双擎，实现异构算力架构的热切换与隔离。
 * 3. 柔性降级 (Graceful Degradation)：在端侧硬件受限时（如缺乏视觉算子），动态剥离多模态数据，防范底层 JNI 或 C++ 引擎的 OOM/空指针崩溃。
 * 4. 高频节流 (Throttling)：协调大模型极速流式传输 (SSE) 与本地 SQLite I/O 之间的速率差，合并刷新频率，阻断 UI 过度重组引起的卡顿。
 */
class ChatViewModel(
    private val repository: ChatRepository,
    // 依赖注入：云端多模态推理节点（处理海量参数与图像张量）
    private val remoteEngine: LLMEngine,
    // 依赖注入：离线端侧轻量推理节点（保护隐私，MediaPipe 驱动，纯文本）
    private val localEngine: LLMEngine,
    private val ttsManager: TTSManager,
    private val sttManager: STTManager
) {
    // 隔离子协程异常的主视图作用域，采用 SupervisorJob 防止局部挂起函数崩溃（如网络超时）导致全局 View 销毁
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 核心流：从数据库监听全量消息快照，通过 map 操作符聚合成对话轮次 (ConversationTurn) 供 UI 消费
    val conversationTurns: StateFlow<List<ConversationTurn>> = repository.getAllMessagesFlow()
        .map { messages ->
            val turns = mutableListOf<ConversationTurn>()
            // 按组 ID 将一问多答进行关联聚合
            val grouped = messages.groupBy { it.groupId }
            for ((groupId, groupMessages) in grouped) {
                val userMsg = groupMessages.find { it.isUser } ?: continue
                val aiMsgs = groupMessages.filter { !it.isUser }
                // 确保 AI 回复按时间戳有序排列（支持同一问题的多次重新生成记录）
                val sortedAiMsgs = aiMsgs.sortedBy { it.timestamp }
                turns.add(ConversationTurn(groupId, userMsg, sortedAiMsgs, sortedAiMsgs.size - 1))
            }
            // 确保对话流的时间线正确
            turns.sortedBy { it.userMessage.timestamp }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 用户输入框的响应式状态缓存
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()//转换成只读对外暴露

    // 历史回答版本显示覆盖表（用户手动左右切换查看不同的 AI 生成版本）
    private val _displayIndexOverrides = MutableStateFlow<Map<String, Int>>(emptyMap())
    val displayIndexOverrides = _displayIndexOverrides.asStateFlow()

    // 全局自动语音播报状态旗标
    private val _isAutoSpeakEnabled = MutableStateFlow(false)
    val isAutoSpeakEnabled = _isAutoSpeakEnabled.asStateFlow()

    // 麦克风物理硬件占用旗标
    private val _isListening = MutableStateFlow(false)
    val isListening = _isListening.asStateFlow()

    // 🌟 端云环境隔离状态旗标：True 表示阻断公网请求，完全由本地 MediaPipe/GPU 提供离线算力
    private val _isLocalModelEnabled = MutableStateFlow(false)
    val isLocalModelEnabled = _isLocalModelEnabled.asStateFlow()

    /**
     * 更新输入框挂载文本
     */
    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
    }

    /**
     * 触发底层算力引擎切换事件 (Cloud vs On-Device)
     * 在 UI 线程触发，即时生效于下一次 executeEngineInference 调度。
     */
    fun toggleEngineMode() {
        _isLocalModelEnabled.value = !_isLocalModelEnabled.value
    }

    /**
     * 开启声音采集
     */
    fun startVoiceInput() {

        if (_isListening.value) return
        _isListening.value = true
        onInputTextChanged("正在聆听...")

        sttManager.startListening(
            onResult = { text, isFinal ->
                // STT 增量文本回显
                onInputTextChanged(text)
                if (isFinal) _isListening.value = false
            },
            onError = { errorMsg ->
                // 异常释放焦点
                _isListening.value = false
                onInputTextChanged("[$errorMsg]")
            }
        )
    }

    /**
     * 释放麦克风焦点，并将最终捕获的声学文本推入调度总线
     */
    fun stopAndSendVoiceInput() {
        if (!_isListening.value) return
        sttManager.stopListening()
        _isListening.value = false

        val finalMessage = _inputText.value
        // 语义防呆：防止误将 STT 内部状态词汇提交给大模型
        if (finalMessage.isNotBlank() && finalMessage != "正在聆听..." && !finalMessage.startsWith("[")) {
            sendMessage(finalMessage)
        } else {
            onInputTextChanged("")
        }
    }

    /**
     * 发送消息
     */
    fun sendMessage(text: String, images: List<ByteArray> = emptyList()) {
        val hasImages = images.isNotEmpty()

        // 强阻塞脏消息输入
        if (text.isBlank() && !hasImages) return
        if (text == "正在聆听...") return

        onInputTextChanged("")
        // 强制打断录音状态（防御用户在录音期间通过其他途径触发发送）
        if (_isListening.value) {
            _isListening.value = false
            sttManager.stopListening()
        }

        // 切换至 IO 线程池：规避数据库高频写入引发的 Android Main 线程掉帧 (Jank)
        viewModelScope.launch(Dispatchers.IO) {
            val now = Clock.System.now().toEpochMilliseconds()
            val groupId = "group_$now"

            // UI 占位解析算法：为无图、单图、多图环境配置差异化文案载体，提供用户层面的视觉确认
            val messageContent = if (hasImages) {
                if (images.size > 1) "[图片x${images.size}] $text".trim() else "[图片] $text".trim()
            } else {
                text
            }

            val userMsg = Message(
                id = "user_$now",
                groupId = groupId,
                content = messageContent,
                isUser = true,
                timestamp = now
            )
            repository.insertMessage(userMsg)

            executeEngineInference(groupId, text, images)
        }
    }

    /**
     * 重新发起当前对话树的推理请求
     */
    fun regenerateResponse(groupId: String, prompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            executeEngineInference(groupId, prompt)
        }
    }

    /**
     * 切换全局 TTS 自动播报机制
     */
    fun toggleAutoSpeak() {
        _isAutoSpeakEnabled.value = !_isAutoSpeakEnabled.value
        if (!_isAutoSpeakEnabled.value) {
            ttsManager.stop()
        }
    }

    private var currentSpeakingMessageId: String? = null

    /**
     * 用户手动触发对单条消息的 TTS 播报
     */
    fun onSpeakMessageClicked(messageId: String, text: String) {
        if (currentSpeakingMessageId == messageId && ttsManager.isSpeaking()) {
            ttsManager.stop()
            currentSpeakingMessageId = null
        } else {
            // 洗清 Markdown 特殊标记，防止 TTS 引擎产生异响
            val cleanText = text.replace(Regex("[#*`-]"), "")
            ttsManager.speak(cleanText, flush = true)
            currentSpeakingMessageId = messageId
        }
    }

    /**
     * 处理用户水平滑动查看重试版本的回调
     */
    fun changeDisplayIndex(groupId: String, newIndex: Int) {
        _displayIndexOverrides.update { it.toMutableMap().apply { put(groupId, newIndex) } }
    }

    /**
     * 处理点赞/踩等交互
     */
    fun onInteractionClicked(messageId: String, currentStatus: Int, targetStatus: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val finalStatus = if (currentStatus == targetStatus) 0 else targetStatus
            repository.updateInteraction(messageId, finalStatus)
        }
    }

    /**
     * 核心推理机状态调度器 (State Machine Inference Scheduler)
     * * 深度职责：
     * 1. 根据 _isLocalModelEnabled 动态寻址云端或端侧算力节点。
     * 2. 执行多模态数据的架构级防御拦截。
     * 3. 维护 SSE 流式打字机的节流写入 (Throttling) 机制，保护 SQLite 连接池。
     * 4. 执行边收边播的 TTS 滑动窗分片逻辑。
     */
    private suspend fun executeEngineInference(groupId: String, prompt: String, images: List<ByteArray> = emptyList()) {
        val now = Clock.System.now().toEpochMilliseconds() + 1
        val aiMsgId = "ai_$now"
        val isLocalActive = _isLocalModelEnabled.value

        // 根据引擎环境动态构建占位缓冲文案
        val initialStatusText = if (isLocalActive) "思考中..." else "思考中..."
        val initialAiMsg = Message(
            id = aiMsgId,
            groupId = groupId,
            content = initialStatusText,
            isUser = false,
            timestamp = now,
            followUpQuestions = emptyList()
        )
        repository.insertMessage(initialAiMsg)

        // 生成新回答时，清除该组的版本强显覆写，默认展示最新流
        _displayIndexOverrides.update { it.toMutableMap().apply { remove(groupId) } }
        ttsManager.stop()

        var currentText = ""
        var displayContent = ""
        var dynamicFollowUps = emptyList<String>()

        // 数据库高频操作节流参数
        var lastUpdateTime = 0L
        val throttleIntervalMs = 150L

        // 流式分片播报断句游标
        var lastSpokenIndex = 0
        val punctuationRegex = Regex("[。！？，；\n!?]")


        // 端侧大模型下若存在图像数据，则进行安全截断
        val safeImages = if (isLocalActive && images.isNotEmpty()) {
            // 提供强视觉反馈：向流式回写气泡内注入环境隔离通告，保证系统白盒可解释性
            currentText += "*(系统提示：当前工作在完全离线的端侧模式，引擎仅对文本进行逻辑推理)*\n\n"
            emptyList<ByteArray>() // 安全截断：将输送链路彻底置空
        } else {
            images
        }

        // 获取路由策略对应的活跃物理/虚拟引擎
        val activeEngine = if (isLocalActive) localEngine else remoteEngine

        try {
            // 以受保护形态喂送最终的参数流，并挂起等待协程抛出的字符串碎片
            activeEngine.generateResponse(prompt, safeImages).collect { partialResult ->
                currentText += partialResult
                val separator = "---追问---"

                // 极简 AST 解析引擎：分离主干正文与后缀的追问预判胶囊
                if (currentText.contains(separator)) {
                    val parts = currentText.split(separator)
                    displayContent = parts[0].trim()

                    if (parts.size > 1) {
                        val followUpsText = parts[1]
                        dynamicFollowUps = followUpsText.lines()
                            .filter { it.isNotBlank() }
                            // 正则清洗掉 AI 可能生成的 Markdown List 符号，提取纯净追问词汇
                            .map { line ->
                                line.replace(Regex("^[-*\\d.]+\\s*"), "").trim().removeSurrounding("[", "]").trim()
                            }
                            .filter { it.isNotEmpty() && it.length > 2 }
                            .take(3) // 强约束溢出：最多接纳 3 个追问，维护 UI 排版
                    }
                } else {
                    displayContent = currentText
                }

                // 自动播报处理模块：实时滑动窗口寻找标点符号进行语调分片播报
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

                // SQLite 写入数据节流
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
        } catch (e: Exception) {

            displayContent = "[底层算力节点建立中断或显存不足: ${e.message}]"
        }

        // 推理状态机终结，彻底持久化完整无缺损的数据实体并提交至 SQLite
        val finalMsg = repository.getMessageById(aiMsgId) ?: initialAiMsg
        repository.insertMessage(finalMsg.copy(
            content = displayContent,
            followUpQuestions = dynamicFollowUps
        ))


        /*
            兜底播报：将剩余未遇到标点符号的尾部文本进行播报
            displayContent：当前显示的文本
            lastSpokenIndex：已经读过的长度
         */
        if (_isAutoSpeakEnabled.value && displayContent.length > lastSpokenIndex) {
            val remainingText = displayContent.substring(lastSpokenIndex).replace(Regex("[#*`-]"), "")
            if (remainingText.isNotBlank()) {
                ttsManager.speak(remainingText, flush = (lastSpokenIndex == 0))
            }
        }
    }
}