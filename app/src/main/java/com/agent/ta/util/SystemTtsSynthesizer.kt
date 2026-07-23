package com.agent.ta.util

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class SystemTtsSynthesizer(private val context: Context) {

    suspend fun synthesize(text: String): File? = suspendCancellableCoroutine { continuation ->
        val outputFile = File(context.cacheDir, "system_voice_${UUID.randomUUID()}.wav")
        var textToSpeech: TextToSpeech? = null
        var finished = false

        fun finish(result: File?) {
            if (finished) return
            finished = true
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            if (continuation.isActive) continuation.resume(result)
        }

        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                finish(null)
                return@TextToSpeech
            }

            val tts = textToSpeech ?: run {
                finish(null)
                return@TextToSpeech
            }
            val languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                finish(null)
                return@TextToSpeech
            }

            val utteranceId = UUID.randomUUID().toString()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    finish(outputFile.takeIf { it.exists() && it.length() > 0 })
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    finish(null)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    finish(null)
                }
            })

            val result = tts.synthesizeToFile(
                text,
                Bundle(),
                outputFile,
                utteranceId
            )
            if (result != TextToSpeech.SUCCESS) finish(null)
        }

        continuation.invokeOnCancellation {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            outputFile.delete()
        }
    }
}
