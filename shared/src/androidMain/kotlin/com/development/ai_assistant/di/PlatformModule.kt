package com.development.ai_assistant.di

import com.development.ai_assistant.database.DriverFactory
import com.development.ai_assistant.utils.AndroidTTSManager
import com.development.ai_assistant.utils.TTSManager
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android 平台专属依赖注入模块
 * 负责提供所有需要依赖 Android Context 或 Android 特有 API 的实例
 */
actual fun platformModule(): Module = module {

    // 实例化数据库驱动工厂
    single { DriverFactory(androidContext()) }

    // 实例化原生语音播报管理器，并绑定到公共层的 TTSManager 接口上
    single<TTSManager> { AndroidTTSManager(androidContext()) }

}