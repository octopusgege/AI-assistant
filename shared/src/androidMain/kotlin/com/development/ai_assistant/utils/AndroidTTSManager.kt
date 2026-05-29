package com.development.ai_assistant.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class AndroidTTSManager(context: Context) : TTSManager {
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
            }
        }
    }

    override fun speak(text: String, flush: Boolean) {
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, queueMode, null, null)
    }

    override fun stop() {
        tts?.stop()
    }

    // 返回当前是否正在朗读
    override fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }
}