package com.development.ai_assistant.di

import com.development.ai_assistant.database.DriverFactory
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    // 实例化 DriverFactory
    single { DriverFactory(androidContext()) }
}