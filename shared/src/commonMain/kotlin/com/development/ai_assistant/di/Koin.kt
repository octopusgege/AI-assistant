package com.development.ai_assistant.di

import com.development.ai_assistant.database.AppDatabase
import com.development.ai_assistant.database.DriverFactory
import com.development.ai_assistant.domain.repository.ChatRepository
import com.development.ai_assistant.ui.viewmodel.ChatViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

//
expect fun platformModule(): Module

val sharedModule = module {
    // 注入数据库实例
    single { AppDatabase(get<DriverFactory>().createDriver()) }
    // 注入 Repository
    single { ChatRepository(get()) }
    // 注入 ViewModel
    factory { ChatViewModel(get()) }
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