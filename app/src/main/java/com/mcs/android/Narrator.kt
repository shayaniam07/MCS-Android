package com.mcs.android

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Free built-in Android TTS. Does not pretend to provide cloned voices. */
object VoiceChunker {
    /** Avoids Android TTS single-utterance text-size limits. */
    fun split(text: String, maxChars: Int = 2800): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        val out = StringBuilder()
        for (word in words) {
            if (word.length > maxChars) {
                if (out.isNotEmpty()) { chunks.add(out.toString()); out.clear() }
                chunks.addAll(word.chunked(maxChars))
            } else {
                if (out.isNotEmpty() && out.length + 1 + word.length > maxChars) {
                    chunks.add(out.toString())
                    out.clear()
                }
                if (out.isNotEmpty()) out.append(' ')
                out.append(word)
            }
        }
        if (out.isNotEmpty()) chunks.add(out.toString())
        return chunks
    }
}

/** Join WAV files by copying PCM data, not by concatenating multiple WAV headers. */
internal object WavJoiner {
    private data class Part(val file: File, val format: ByteArray, val offset: Long, val bytes: Long)
    private fun uint32(raf: RandomAccessFile): Long {
        val a = raf.readUnsignedByte()
        val b = raf.readUnsignedByte()
        val c = raf.readUnsignedByte()
        val d = raf.readUnsignedByte()
        return a.toLong() or (b.toLong() shl 8) or (c.toLong() shl 16) or (d.toLong() shl 24)
    }
    private fun write32(stream: FileOutputStream, value: Long) {
        for (shift in 0..3) stream.write(((value shr (shift * 8)) and 255).toInt())
    }
    private fun part(file: File): Part = RandomAccessFile(file, "r").use { raf ->
        val signature = ByteArray(12)
        raf.readFully(signature)
        require(String(signature, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(signature, 8, 4, Charsets.US_ASCII) == "WAVE") { "TTS engine did not output WAV" }
        var fmt: ByteArray? = null
        var dataOffset = -1L
        var dataSize = -1L
        while (raf.filePointer + 8 <= raf.length()) {
            val id = ByteArray(4).also { raf.readFully(it) }
            val size = uint32(raf)
            val offset = raf.filePointer
            require(size <= raf.length() - offset) { "Invalid WAV chunk" }
            when (String(id, Charsets.US_ASCII)) {
                "fmt " -> {
                    require(size in 16..1024) { "Invalid WAV format" }
                    fmt = ByteArray(size.toInt()).also { raf.readFully(it) }
                }
                "data" -> {
                    dataOffset = offset
                    dataSize = size
                }
            }
            raf.seek(offset + size + (size % 2))
        }
        require(fmt != null && dataOffset >= 0 && dataSize >= 0) { "WAV format or audio data missing" }
        Part(file, fmt!!, dataOffset, dataSize)
    }
    fun join(files: List<File>, dest: File) {
        require(files.isNotEmpty()) { "No narration chunks" }
        val parts = files.map { part(it) }
        val format = parts.first().format
        require(parts.all { it.format.contentEquals(format) }) { "TTS chunk audio formats differ" }
        val total = parts.sumOf { it.bytes }
        require(total <= 0x7FFFFFFFL) { "Narration exceeds WAV size limit" }
        val padding = format.size % 2
        FileOutputStream(dest).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            write32(output, 4L + 8L + format.size + padding + 8L + total + total % 2)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            write32(output, format.size.toLong())
            output.write(format)
            if (padding != 0) output.write(0)
            output.write("data".toByteArray(Charsets.US_ASCII))
            write32(output, total)
            val buffer = ByteArray(64 * 1024)
            for (p in parts) RandomAccessFile(p.file, "r").use { raf ->
                raf.seek(p.offset)
                var remaining = p.bytes
                while (remaining > 0) {
                    val n = raf.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (n <= 0) error("Truncated TTS WAV chunk")
                    output.write(buffer, 0, n)
                    remaining -= n
                }
            }
            if (total % 2L != 0L) output.write(0)
        }
    }
}

class Narrator(context: Context) {
    @Volatile private var ready = false
    private val handler = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { code ->
        ready = code == TextToSpeech.SUCCESS
    }
    private val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
    private val generating = AtomicBoolean(false)

    fun available(): Boolean = ready

    fun synthesize(
        text: String,
        languageTag: String = "en-US",
        rate: Float = 0.95f,
        pitch: Float = 1f,
        onResult: (Result<File>) -> Unit
    ) {
        if (!ready) { onResult(Result.failure(IllegalStateException("Android TTS is not ready"))); return }
        if (!generating.compareAndSet(false, true)) {
            onResult(Result.failure(IllegalStateException("Another narration is generating"))); return
        }
        val pieces = VoiceChunker.split(text)
        if (pieces.isEmpty()) {
            generating.set(false)
            onResult(Result.failure(IllegalArgumentException("Narration text is empty")))
            return
        }
        val resultFile = File(dir, "MCS_narration_" + System.currentTimeMillis() + ".wav")
        val prefix = "MCS_" + System.nanoTime()
        val parts = pieces.indices.map { i -> File(dir, prefix + "_" + i + ".wav") }
        val finished = AtomicBoolean(false)
        fun finish(result: Result<File>) {
            if (finished.compareAndSet(false, true)) {
                generating.set(false)
                parts.forEach { it.delete() }
                if (result.isFailure) resultFile.delete()
                onResult(result)
            }
        }
        val voiceStatus = tts.setLanguage(Locale.forLanguageTag(languageTag))
        if (voiceStatus == TextToSpeech.LANG_NOT_SUPPORTED || voiceStatus == TextToSpeech.LANG_MISSING_DATA) {
            finish(Result.failure(IllegalArgumentException("This device has no installed TTS voice for " + languageTag)))
            return
        }
        tts.setSpeechRate(rate.coerceIn(0.5f, 1.5f))
        tts.setPitch(pitch.coerceIn(0.6f, 1.5f))
        fun synthPart(index: Int) {
            if (finished.get()) return
            val id = prefix + "_" + index
            val status = tts.synthesizeToFile(pieces[index], Bundle(), parts[index], id)
            if (status != TextToSpeech.SUCCESS)
                finish(Result.failure(IllegalStateException("Android voice engine rejected narration part " + (index + 1))))
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (!utteranceId.orEmpty().startsWith(prefix) || finished.get()) return
                val next = utteranceId!!.removePrefix(prefix + "_").toIntOrNull()?.plus(1) ?: return
                if (next < pieces.size) {
                    handler.post { synthPart(next) }
                } else {
                    Thread {
                        val result = runCatching { WavJoiner.join(parts, resultFile); resultFile }
                        handler.post { finish(result) }
                    }.start()
                }
            }
            override fun onError(utteranceId: String?) {
                handler.post { finish(Result.failure(IllegalStateException("Android TTS failed to synthesize narration"))) }
            }
        })
        synthPart(0)
    }

    fun close() = tts.shutdown()
}
