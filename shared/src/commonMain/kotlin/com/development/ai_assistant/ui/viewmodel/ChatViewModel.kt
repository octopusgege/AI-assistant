package com.development.ai_assistant.ui.viewmodel

import com.development.ai_assistant.database.AppDatabase
import com.development.ai_assistant.domain.model.Message
import com.development.ai_assistant.domain.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.random.Random

class ChatViewModel(private val repository: ChatRepository) {
    private val viewModelScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 👉 状态1：直接从数据库读取 Flow 历史记录
    val messages: StateFlow<List<Message>> = repository.getAllMessagesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()

    // 这个变量是为了拿到注入的 DB 对象进行打字机的更新，
    // 实际项目中可以放到 Repository 中，为了快速连调，我们在 Koin 里 get() 注入时可以一并传入。
    // 但更简便的做法是，在 Repository 中加一个 updateMessage 方法。
    fun onInputTextChanged(newText: String) {
        _inputText.value = newText
    }

    fun sendMessage() {
        val text = _inputText.value
        if (text.isBlank()) return

        _inputText.value = ""

        val userMsgId = "user_${Random.nextInt()}"
        val userMsg = Message(id = userMsgId, content = text, isUser = true)

        // 👉 将用户消息存入数据库
        repository.insertMessage(userMsg)

        val aiMsgId = "ai_${Random.nextInt()}"
        val initialAiMsg = Message(id = aiMsgId, content = "", isUser = false)

        // 👉 将 AI 初始空白消息存入数据库
        repository.insertMessage(initialAiMsg)

        viewModelScope.launch {
            val mockResponse = "我已经收到你的消息：“${text}”。现在这些数据全部都保存在本地的 SQLite 数据库中了，你可以尝试杀掉 App 再次打开，我会一直记得！"
            var currentText = ""

            mockResponse.forEach { char ->
                delay(50)
                currentText += char
                // 👉 每次追加字符，都更新数据库中该条消息的内容 (UI 列表会自动响应 Flow 更新)
                // 注意：这里简单调用了 insertMessage 达到 Replace 效果更新内容。
                repository.insertMessage(initialAiMsg.copy(content = currentText))
            }
        }
    }
}