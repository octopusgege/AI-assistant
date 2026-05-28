package com.development.ai_assistant.di

import com.development.ai_assistant.database.AppDatabase
import com.development.ai_assistant.database.DriverFactory
import com.development.ai_assistant.domain.ai.LLMEngine
import com.development.ai_assistant.domain.ai.RemoteLLMEngine
import com.development.ai_assistant.domain.repository.ChatRepository
import com.development.ai_assistant.ui.viewmodel.ChatViewModel
import io.ktor.client.HttpClient
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

expect fun platformModule(): Module

/**
 * 共享层依赖注入图谱
 * 组装持久化层、网络层、领域仓库及模型实例
 */
val sharedModule = module {
    single { AppDatabase(get<DriverFactory>().createDriver()) }
    single { ChatRepository(get()) }

    single { HttpClient() }

    single<LLMEngine> { RemoteLLMEngine(get()) }

    factory { ChatViewModel(get(), get()) }
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