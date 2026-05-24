package com.development.ai_assistant // 根据你的实际包名

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.development.ai_assistant.App
import com.development.ai_assistant.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 将 ApplicationContext 注入
        initKoin(module {
            single<android.content.Context> { this@MainActivity.applicationContext }
        })

        setContent {
            App()
        }
    }
}