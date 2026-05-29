package com.development.ai_assistant.config

/**
 * 大模型网络配置中心
 * ⚠️ 安全提示：请确保已将此文件加入 .gitignore，防止 API Key 泄露到云端代码仓库。
 */
object LLMConfig {
    // 必须使用 compatible-mode 兼容模式接口，以适配我们在 RemoteLLMEngine 中编写的标准请求体
    const val API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"

    // 请在此处填入你 local.properties 中的真实 Key，例如 "sk-1f32..."
    const val API_KEY = "sk-91603b0f15c7477ba6b6fc065425e358"

    const val MODEL_NAME = "qwen-max"
}