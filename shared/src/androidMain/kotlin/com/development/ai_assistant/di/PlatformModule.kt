package com.development.ai_assistant.di

import com.development.ai_assistant.database.DriverFactory
import com.development.ai_assistant.utils.AndroidSTTManager
import com.development.ai_assistant.utils.AndroidTTSManager
import com.development.ai_assistant.utils.STTManager
import com.development.ai_assistant.utils.TTSManager
import com.development.ai_assistant.domain.ai.LLMEngine
import com.development.ai_assistant.domain.ai.LocalLlamaEngine
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual fun platformModule(): Module = module {

    single { DriverFactory(androidContext()) }
    single<TTSManager> { AndroidTTSManager(androidContext()) }
    single<STTManager> { AndroidSTTManager(androidContext()) }


    single<LLMEngine>(named("localEngine")) {
        LocalLlamaEngine(androidContext())
    }
}