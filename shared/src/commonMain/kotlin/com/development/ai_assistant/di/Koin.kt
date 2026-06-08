package com.development.ai_assistant.di

import com.development.ai_assistant.database.AppDatabase
import com.development.ai_assistant.database.DriverFactory
import com.development.ai_assistant.domain.ai.LLMEngine
import com.development.ai_assistant.domain.ai.MockLLMEngine
import com.development.ai_assistant.domain.ai.RemoteLLMEngine
import com.development.ai_assistant.domain.repository.ChatRepository
import com.development.ai_assistant.ui.viewmodel.ChatViewModel
import com.development.ai_assistant.utils.STTManager
import com.development.ai_assistant.utils.TTSManager
import io.ktor.client.HttpClient
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

expect fun platformModule(): Module

val sharedModule = module {

    single { AppDatabase(get<DriverFactory>().createDriver()) }
    single { ChatRepository(get<AppDatabase>()) }
    single { HttpClient() }


    single<LLMEngine>(named("remoteEngine")) {
        // 当前为 Mock 模式。需要连真网时，换成 RemoteLLMEngine(get()) 即可
        MockLLMEngine()
    }


    factory {
        ChatViewModel(
            repository = get<ChatRepository>(),
            remoteEngine = get<LLMEngine>(named("remoteEngine")), // 精准拿取云端引擎
            localEngine = get<LLMEngine>(named("localEngine")),   // 精准拿取端侧引擎
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