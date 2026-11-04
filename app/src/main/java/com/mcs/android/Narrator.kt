package com.mcs.android

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/** Free Android system text-to-speech, NOT cloned voice. Never claims to clone a user's voice. */
class Narrator(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { code ->
        ready = code == TextToSpeech.SUCCESS
    }
    private val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir

    fun available(): Boolean = ready

    fun synthesize(text: String, onResult: (Result<File>) -> Unit) {
        if (!ready) { onResult(Result.failure(IllegalStateException("Android TTS engine is still initializing"))); return }
        if (text.isBlank()) { onResult(Result.failure(IllegalArgumentException("Narration script is empty"))); return }
        val file = File(dir, "MCS_narration_${System.currentTimeMillis()}.wav")
        val utteranceId = "MCS_${System.currentTimeMillis()}"
        tts.language = Locale.ENGLISH
        tts.setSpeechRate(0.95f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(done: String?) { onResult(Result.success(file)) }
            override fun onError(failed: String?) { onResult(Result.failure(RuntimeException("Android TTS failed"))) }
        })
        val rc = tts.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (rc != TextToSpeech.SUCCESS) onResult(Result.failure(RuntimeException("TTS engine rejected request")))
    }

    fun close() = tts.shutdown()
}
