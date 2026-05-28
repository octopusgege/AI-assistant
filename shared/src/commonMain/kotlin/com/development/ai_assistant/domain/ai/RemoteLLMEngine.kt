package com.development.ai_assistant.domain.ai

import com.development.ai_assistant.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 远端大模型网络推理引擎
 * 负责建立长连接并解析 Server-Sent Events (SSE) 流式响应数据。
 * 使用 kotlinx.serialization 安全构建请求体，防止特殊字符引发 JSON 解析异常。
 */
class RemoteLLMEngine(
    private val httpClient: HttpClient
) : LLMEngine {

    private val apiUrl = AppConfig.apiUrl
    private val apiKey = AppConfig.apiKey
    private val modelName = AppConfig.modelName

    private val jsonParser = Json { ignoreUnknownKeys = true }

    override fun generateResponse(prompt: String): Flow<String> = flow {

        val systemInstruction = """
            你是一个智能助手，请用简洁准确的语言回答用户的问题。
            【重要指令】在回答正文结束后，请务必根据当前上下文，给出2个用户可能感兴趣的追问问题。
            请严格按照以下格式输出追问部分，务必以"---追问---"作为分隔符：            
            ---追问---
            - [追问问题1]
            - [追问问题2]
        """.trimIndent()

        val requestBody = buildJsonObject {
            put("model", modelName)
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemInstruction)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString()

        try {
            httpClient.preparePost(apiUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { response ->
                val channel: ByteReadChannel = response.bodyAsChannel()

                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: continue

                    if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                        val jsonString = line.removePrefix("data: ").trim()
                        if (jsonString.isNotEmpty()) {
                            try {
                                val jsonObject = jsonParser.parseToJsonElement(jsonString).jsonObject
                                val choices = jsonObject["choices"]?.jsonArray
                                val delta = choices?.get(0)?.jsonObject?.get("delta")?.jsonObject
                                val content = delta?.get("content")?.jsonPrimitive?.content ?: ""

                                if (content.isNotEmpty()) {
                                    emit(content)
                                }
                            } catch (e: Exception) {

                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit("\n[网络通信异常，请检查本地配置文件或设备网络状态]")
        }
    }
}