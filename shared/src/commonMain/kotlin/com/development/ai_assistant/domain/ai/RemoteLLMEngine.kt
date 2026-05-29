package com.development.ai_assistant.domain.ai

// 👉 1. 导入全新的配置类
import com.development.ai_assistant.config.LLMConfig

// 👉 2. Ktor 核心扩展函数导入（缺一不可）
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

// 👉 3. 协程与 JSON 序列化导入
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
 */
class RemoteLLMEngine(
    private val httpClient: HttpClient
) : LLMEngine {

    // 直接读取我们刚刚手动创建的 LLMConfig
    private val apiUrl = LLMConfig.API_URL
    private val apiKey = LLMConfig.API_KEY
    private val modelName = LLMConfig.MODEL_NAME

    private val jsonParser = Json { ignoreUnknownKeys = true }

    override fun generateResponse(prompt: String): Flow<String> = flow {

        val systemInstruction = """
            你是一个智能助手，请用简洁准确的语言回答用户的问题。
            【重要指令】在回答正文结束后，请务必根据当前上下文，给出3个用户可能感兴趣的追问问题。追问的问题控制在20个字以内。
            请严格按照以下格式输出追问部分，务必以"---追问---"作为分隔符：            
            ---追问---
            - [追问问题1]
            - [追问问题2]
            - [追问问题3]
        """.trimIndent()

        val requestBody = buildJsonObject {
            put("model", modelName)
            put("stream", true)
            // 开启大模型的联网检索能力
            put("enable_search", true)
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
                                // 维持流持续性，忽略局部反序列化错误
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit("\n[网络通信异常，请检查设备网络状态]")
        }
    }
}