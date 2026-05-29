package com.development.ai_assistant.di

import com.development.ai_assistant.database.DriverFactory
import com.development.ai_assistant.utils.AndroidSTTManager
import com.development.ai_assistant.utils.AndroidTTSManager
import com.development.ai_assistant.utils.STTManager
import com.development.ai_assistant.utils.TTSManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {

    single { DriverFactory(androidContext()) }
    // 注册语音播报管理器
    single<TTSManager> { AndroidTTSManager(androidContext()) }
    // 注册语音输入识别管理器
    single<STTManager> { AndroidSTTManager(androidContext()) }
}