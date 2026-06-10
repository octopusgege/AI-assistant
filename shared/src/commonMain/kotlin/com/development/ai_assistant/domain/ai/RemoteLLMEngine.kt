package com.development.ai_assistant.domain.ai

import com.development.ai_assistant.config.LLMConfig
import com.development.ai_assistant.utils.Base64Util
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
 * 远端大模型多模态网络推理引擎 (Remote Multi-modal Engine Implementation)
 * * 职责：
 * 1. 负责组装符合 OpenAI Vision 规范的复杂多模态 JSON 请求体。
 * 2. 建立并维护 HTTP 长连接，解析 Server-Sent Events (SSE) 流式返回的增量数据。
 * 3. 拦截并静默处理局部的网络或序列化异常，保障流式传输的健壮性。
 */
class RemoteLLMEngine(
    private val httpClient: HttpClient
) : LLMEngine {


    private val apiUrl = LLMConfig.API_URL
    private val apiKey = LLMConfig.API_KEY
    private val modelName = LLMConfig.MODEL_NAME

    // 初始化宽松的 JSON 解析器
    private val jsonParser = Json { ignoreUnknownKeys = true }

    override fun generateResponse(prompt: String, images: List<ByteArray>): Flow<String> = flow {

        // 定义 System Prompt，强约束大模型的行为边界与输出格式
        val systemInstruction = """
            你是一个具备视觉能力的智能助手，请用简洁准确的语言回答用户的问题。如果用户上传了图片，请优先对其进行深度分析。
            【重要指令】在回答正文结束后，请务必根据当前上下文，给出3个用户可能感兴趣的追问问题。追问的问题控制在20个字以内。
            请严格按照以下格式输出追问部分，务必以"---追问---"作为分隔符：            
            ---追问---
            - [追问问题1]
            - [追问问题2]
            - [追问问题3]
        """.trimIndent()

        /* * 动态构建多模态 Content 结构树
         * 根据 OpenAI 视觉模型协议，若存在图片，content 必须为 JSON 数组；若无图片，可简化为字符串或单元素数组。
         */
        val userContentElement = buildJsonArray {
            // 节点 1：压入文本指令。若文本为空且存在图片，则提供兜底 Prompt 以触发视觉解析。
            add(buildJsonObject {
                put("type", "text")
                put("text", prompt.ifBlank { "请仔细分析并描述提供的图片内容" })

            })

            // 节点 2：遍历压入所有的图像数据
            if (images.isNotEmpty()) {
                images.forEach { imageBytes ->
                    // 执行 CPU 密集型的 Base64 编码操作
                    val base64Image = Base64Util.encode(imageBytes)
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            // 拼装 Base64 Data URI Scheme 格式头部
                            put("url", "data:image/jpeg;base64,$base64Image")
                        })
                    })
                }
            }
        }

        // 构建最终的 POST 请求报文体
        val requestBody = buildJsonObject {
            put("model", modelName)
            put("stream", true)
            put("enable_search", true)//联网
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemInstruction)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userContentElement)
                })
            })
        }.toString()

        try {
            // 发起 Ktor 协程异步网络请求，并保持长连接 (preparePost)
            httpClient.preparePost(apiUrl) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { response ->
                // 获取底层的字节读取管道
                val channel: ByteReadChannel = response.bodyAsChannel()

                // 持续挂起并监听流，直到服务端发送 EOF (End of File) 关闭连接
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: continue

                    // SSE (Server-Sent Events) 协议规范：有效数据以 "data: " 开头
                    if (line.startsWith("data: ") && !line.contains("[DONE]")) {
                        val jsonString = line.removePrefix("data: ").trim()
                        if (jsonString.isNotEmpty()) {
                            try {
                                // 深度寻址解析 JSON，提取增量的字符 (delta.content)
                                val jsonObject = jsonParser.parseToJsonElement(jsonString).jsonObject
                                val choices = jsonObject["choices"]?.jsonArray
                                val delta = choices?.get(0)?.jsonObject?.get("delta")?.jsonObject
                                val content = delta?.get("content")?.jsonPrimitive?.content ?: ""

                                // 将解析出的有效字符碎片推送到上层的 Flow 收集器中
                                if (content.isNotEmpty()) {
                                    emit(content)
                                }
                            } catch (e: Exception) {
                                // 容错处理：忽略局部残缺 JSON 导致的解析异常，维持整体数据流不中断
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 捕获网络超时、断网等致命异常，并通过数据流向上层 UI 投递友好的错误提示
            emit("\n[网络通信异常，视觉数据链路未能成功建立，请检查网络连接]")
        }
    }
}