@file:OptIn(ExperimentalTime::class)
package com.development.ai_assistant.domain.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.development.ai_assistant.database.AppDatabase
import com.development.ai_assistant.domain.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 聊天记录数据库
 * 负责封装 SQLDelight 的底层数据库操作，并向表现层暴露响应式的数据流
 */
class ChatRepository(private val db: AppDatabase) {

    /**
     * 监听全量消息流
     */
    fun getAllMessagesFlow(): Flow<List<Message>> {
        return db.appDatabaseQueries.selectAll().asFlow().mapToList(Dispatchers.IO).map { entityList ->
            entityList.map { entity ->
                Message(
                    id = entity.id,
                    groupId = entity.groupId,
                    content = entity.content,
                    isUser = entity.isUser == 1L,
                    timestamp = entity.timestamp,
                    interactionStatus = entity.interactionStatus.toInt(),
                    followUpQuestions = entity.followUpQuestions?.split("||")?.filter { it.isNotBlank() } ?: emptyList()
                )
            }
        }
    }

    /**
     * 插入或更新单条消息
     */
    fun insertMessage(message: Message) {
        val finalTimestamp = if (message.timestamp == 0L) {
            Clock.System.now().toEpochMilliseconds()
        } else {
            message.timestamp
        }

        db.appDatabaseQueries.insertMessage(
            id = message.id,
            groupId = message.groupId,
            content = message.content,
            isUser = if (message.isUser) 1L else 0L,
            timestamp = finalTimestamp,
            interactionStatus = message.interactionStatus.toLong(),
            followUpQuestions = message.followUpQuestions.joinToString("||")
        )
    }

    /**
     * 更新单条消息的交互状态（点赞/点踩）
     */
    fun updateInteraction(messageId: String, status: Int) {
        db.appDatabaseQueries.updateInteractionStatus(status.toLong(), messageId)
    }

    /**
     * 根据主键查询单条消息
     */
    fun getMessageById(id: String): Message? {
        val entity = db.appDatabaseQueries.selectById(id).executeAsOneOrNull() ?: return null
        return Message(
            id = entity.id,
            groupId = entity.groupId,
            content = entity.content,
            isUser = entity.isUser == 1L,
            timestamp = entity.timestamp,
            interactionStatus = entity.interactionStatus.toInt(),
            followUpQuestions = entity.followUpQuestions?.split("||")?.filter { it.isNotBlank() } ?: emptyList()
        )
    }
}