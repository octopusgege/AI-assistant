package com.development.ai_assistant.di

import com.development.ai_assistant.database.AppDatabase
import com.development.ai_assistant.database.DriverFactory
import com.development.ai_assistant.domain.ai.LLMEngine
import com.development.ai_assistant.domain.ai.RemoteLLMEngine
import com.development.ai_assistant.domain.repository.ChatRepository
import com.development.ai_assistant.ui.viewmodel.ChatViewModel
import com.development.ai_assistant.utils.STTManager // 👉 新增导入 STT 接口
import com.development.ai_assistant.utils.TTSManager
import io.ktor.client.HttpClient
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

expect fun platformModule(): Module

val sharedModule = module {
    single { AppDatabase(get<DriverFactory>().createDriver()) }
    single { ChatRepository(get<AppDatabase>()) }
    single { HttpClient() }
    single<LLMEngine> { RemoteLLMEngine(get<HttpClient>()) }

    factory {
        ChatViewModel(
            repository = get<ChatRepository>(),
            engine = get<LLMEngine>(),
            ttsManager = get<TTSManager>(),
            sttManager = get<STTManager>()
        )
    }
}

fun initKoin(appModule: Module? = null) {
    startKoin {
        if (appModule != null) {
            modules(appModule, platformModule(), sharedModule)
        } else {
            modules(platformModule(), sharedModule)
        }
    }
}