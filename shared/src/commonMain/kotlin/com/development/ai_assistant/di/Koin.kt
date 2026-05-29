package com.development.ai_assistant.di

import com.development.ai_assistant.database.AppDatabase
import com.development.ai_assistant.database.DriverFactory
import com.development.ai_assistant.domain.ai.LLMEngine
import com.development.ai_assistant.domain.ai.RemoteLLMEngine
import com.development.ai_assistant.domain.repository.ChatRepository
import com.development.ai_assistant.ui.viewmodel.ChatViewModel
import com.development.ai_assistant.utils.TTSManager
import io.ktor.client.HttpClient
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * 平台层依赖注入模块契约协议
 */
expect fun platformModule(): Module

/**
 * 公共层依赖注入图谱 (Shared Module)
 * 统一管理跨端业务组件、持久化层及网络层的实例生命周期
 */
val sharedModule = module {

    single { AppDatabase(get<DriverFactory>().createDriver()) }

    single { ChatRepository(get<AppDatabase>()) }

    single { HttpClient() }

    single<LLMEngine> { RemoteLLMEngine(get<HttpClient>()) }

    //
    factory {
        ChatViewModel(
            repository = get<ChatRepository>(),
            engine = get<LLMEngine>(),
            ttsManager = get<TTSManager>()
        )
    }
}

/**
 * 依赖注入容器初始化入口
 * * @param appModule 平台层特有模块（例如 Android 端的 Application Context 模块）
 */
fun initKoin(appModule: Module? = null) {
    startKoin {
        if (appModule != null) {
            modules(appModule, platformModule(), sharedModule)
        } else {
            modules(platformModule(), sharedModule)
        }
    }
}