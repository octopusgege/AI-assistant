package com.development.ai_assistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.SlideTransition
import com.development.ai_assistant.di.initKoin
import com.development.ai_assistant.ui.screens.ChatScreen

@Composable
fun App() {


    MaterialTheme {
        // 直接将 ChatScreen 作为根页面
        Navigator(ChatScreen()) { navigator ->
            SlideTransition(navigator)
        }
    }
}