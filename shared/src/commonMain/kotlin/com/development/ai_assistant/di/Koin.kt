package com.development.ai_assistant.di

import com.development.ai_assistant.database.AppDatabase
import com.development.ai_assistant.database.DriverFactory
import com.development.ai_assistant.domain.repository.ChatRepository
import com.development.ai_assistant.ui.viewmodel.ChatViewModel
// 👉 确保导入了这个包
import com.development.ai_assistant.domain.ai.LLMEngine
import com.development.ai_assistant.domain.ai.MockLLMEngine
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

expect fun platformModule(): Module

val sharedModule = module {
    single { AppDatabase(get<DriverFactory>().createDriver()) }
    single { ChatRepository(get()) }
    single<LLMEngine> { MockLLMEngine() }

    // 两个 get() 分别拿到 ChatRepository 和 MockLLMEngine
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