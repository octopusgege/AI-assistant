package com.development.ai_assistant.domain.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.development.ai_assistant.database.AppDatabase
import com.development.ai_assistant.domain.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(private val db: AppDatabase) {

    // 获取包含所有历史记录的流
    fun getAllMessagesFlow(): Flow<List<Message>> {
        return db.appDatabaseQueries
            .selectAll()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { entityList ->
                // 将数据库实体转换为 UI 需要的模型
                entityList.map { entity ->
                    Message(
                        id = entity.id,
                        content = entity.content,
                        isUser = entity.isUser == 1L
                    )
                }
            }
    }

    // 插入新消息
    fun insertMessage(message: Message) {
        db.appDatabaseQueries.insertMessage(
            id = message.id,
            content = message.content,
            isUser = if (message.isUser) 1L else 0L,
            timestamp = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
            // timestamp = System.currentTimeMillis()
        )
    }

    // 获取时间戳
    private fun getCurrentTimeMillis(): Long {
        return kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
    }
}