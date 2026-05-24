package com.development.ai_assistant

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform