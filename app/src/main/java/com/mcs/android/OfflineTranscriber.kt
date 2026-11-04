package com.mcs.android

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Vosk English small model: downloaded only when the user requests automatic captions. */
class OfflineTranscriber(private val context: Context) {
    private val modelName = "vosk-model-small-en-us-0.15"
    private val url = "https://alphacephei.com/vosk/models/" + modelName + ".zip"
    private val expectedSha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    private val modelsDir = File(context.filesDir, "vosk_models")
    private val modelDir get() = File(modelsDir, modelName)

    fun hasModel(): Boolean = File(modelDir, "am/final.mdl").isFile

    fun ensureModel(progress: (String) -> Unit): File {
        if (hasModel()) return modelDir
        modelsDir.mkdirs()
        val archive = File(modelsDir, "model_download.part")
        val unpack = File(modelsDir, "unpacking_" + System.currentTimeMillis())
        try {
            progress("Downloading free English model (~40 MB), one time only…")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 45_000
                instanceFollowRedirects = true
            }
            val sha = MessageDigest.getInstance("SHA-256")
            try {
                require(connection.responseCode in 200..299) { "Model download failed HTTP " + connection.responseCode }
                connection.inputStream.use { source ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var received = 0L
                        var last = 0L
                        while (true) {
                            val n = source.read(buffer)
                            if (n == -1) break
                            received += n
                            require(received <= 85_000_000) { "Model download unexpectedly large" }
                            output.write(buffer, 0, n)
                            sha.update(buffer, 0, n)
                            if (received - last >= 5_000_000) {
                                last = received
                                progress("Downloaded " + (received / 1_000_000) + " MB…")
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val hash = sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash == expectedSha256) { "Model checksum mismatch; download was rejected" }
            progress("Extracting speech model…")
            unpack.mkdirs()
            var extracted = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val dest = File(unpack, entry.name)
                    val root = unpack.canonicalPath + File.separator
                    require(dest.canonicalPath.startsWith(root)) { "Unsafe zip entry" }
                    if (entry.isDirectory) dest.mkdirs() else {
                        dest.parentFile?.mkdirs()
                        FileOutputStream(dest).use { output ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                extracted += n
                                require(extracted <= 600_000_000) { "Model expanded too large" }
                                output.write(buf, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val sourceFolder = File(unpack, modelName)
            require(File(sourceFolder, "am/final.mdl").isFile) { "Model files missing after extraction" }
            if (modelDir.exists()) modelDir.deleteRecursively()
            require(sourceFolder.renameTo(modelDir)) { "Could not place downloaded model" }
            progress("English speech model installed")
            return modelDir
        } finally {
            archive.delete()
            unpack.deleteRecursively()
        }
    }

    private data class Word(val start: Long, val end: Long, val text: String)

    /** Accurate word boundaries depend on the model and audio; captions remain editable. */
    fun transcribeVideo(uri: Uri, progress: (String) -> Unit): List<CaptionCue> {
        require(hasModel()) { "Download the English speech model first" }
        progress("Loading offline speech model…")
        val model = Model(modelDir.absolutePath)
        try {
            val words = mutableListOf<Word>()
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var recognizer: Recognizer? = null
            try {
                extractor.setDataSource(context, uri, null)
                val track = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: error("Imported video has no audio track")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio codec")
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()
                var decoderInDone = false
                var decoderOutDone = false
                val info = MediaCodec.BufferInfo()
                var inputRate = 16_000
                var channels = 1
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var phase = 0L
                var frames = 0L
                fun collect(json: String) {
                    val array = JSONObject(json).optJSONArray("result") ?: return
                    for (j in 0 until array.length()) {
                        val obj = array.optJSONObject(j) ?: continue
                        val word = obj.optString("word").trim()
                        val startMs = (obj.optDouble("start") * 1000).toLong()
                        val endMs = (obj.optDouble("end") * 1000).toLong()
                        if (word.isNotBlank() && endMs > startMs) words.add(Word(startMs, endMs, word))
                    }
                }
                var passes = 0
                while (!decoderOutDone && passes++ < 3_000_000) {
                    if (!decoderInDone) {
                        val n = codec.dequeueInputBuffer(12_000)
                        if (n >= 0) {
                            val b = codec.getInputBuffer(n) ?: error("Audio input buffer missing")
                            b.clear()
                            val bytes = extractor.readSampleData(b, 0)
                            if (bytes < 0) {
                                codec.queueInputBuffer(n, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decoderInDone = true
                            } else {
                                codec.queueInputBuffer(n, 0, bytes, extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                        }
                    }
                    val n = codec.dequeueOutputBuffer(info, 12_000)
                    if (n == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (n >= 0 && recognizer == null)) {
                        val outputFormat = codec.outputFormat
                        inputRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT ||
                            encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Unsupported decoded PCM format" }
                        require(inputRate in 8000..192000) { "Unsupported audio sample rate" }
                        recognizer = Recognizer(model, 16000f).also { it.setWords(true) }
                    }
                    if (n >= 0) {
                        val buffer = codec.getOutputBuffer(n)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val raw = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frameBytes = bytesPerSample * channels
                            val output = java.io.ByteArrayOutputStream()
                            while (raw.remaining() >= frameBytes) {
                                var mono = 0f
                                for (c in 0 until channels) {
                                    mono += if (bytesPerSample == 4) raw.float.coerceIn(-1f, 1f)
                                        else raw.short.toFloat() / 32768f
                                }
                                mono = (mono / channels).coerceIn(-1f, 1f)
                                val pcm = (mono * 32767f).toInt()
                                phase += 16000
                                while (phase >= inputRate) {
                                    phase -= inputRate
                                    output.write(pcm and 255)
                                    output.write((pcm shr 8) and 255)
                                }
                                frames++
                            }
                            val data = output.toByteArray()
                            if (data.isNotEmpty() && recognizer!!.acceptWaveForm(data, data.size))
                                collect(recognizer!!.result)
                            if (frames % (inputRate * 6L) < 1024)
                                progress("Transcribing audio: " + (frames / inputRate) + "s processed…")
                        }
                        decoderOutDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(n, false)
                    }
                }
                recognizer?.let { collect(it.finalResult) }
                require(words.isNotEmpty()) { "No recognizable English speech found in this video" }
                progress("Creating editable captions from " + words.size + " recognized words…")
                return groupWords(words)
            } finally {
                runCatching { recognizer?.close() }
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                extractor.release()
            }
        } finally {
            model.close()
        }
    }

    private fun groupWords(words: List<Word>): List<CaptionCue> {
        val out = mutableListOf<CaptionCue>()
        var group = mutableListOf<Word>()
        fun flush() {
            if (group.isNotEmpty()) {
                out.add(CaptionCue(group.first().start, group.last().end,
                    group.joinToString(" ") { it.text }))
                group = mutableListOf()
            }
        }
        for (word in words) {
            if (group.isNotEmpty() && (group.size >= 7 || word.start - group.last().end > 850)) flush()
            group.add(word)
        }
        flush()
        return out
    }
}
