package com.mcs.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

/** On-device speech-ish energy detector (NOT semantic highlight detection). */
data class CutRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/** Uses the device's decoder; it never uploads media or makes paid API requests. */
class SilenceAnalyzer(private val context: Context) {
    fun findAudibleSections(uri: Uri, thresholdDb: Float = -35f): List<CutRange> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("Video has no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val audible = ArrayList<CutRange>()
            var open = -1L
            var lastLoud = -1L
            var iterations = 0
            while (!outputDone && iterations++ < 200000) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(12000)
                    if (inIndex >= 0) {
                        val bb = codec.getInputBuffer(inIndex)!!
                        bb.clear()
                        val n = extractor.readSampleData(bb, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 12000)
                if (outIndex >= 0) {
                    val pcm = codec.getOutputBuffer(outIndex)
                    if (pcm != null && info.size > 0) {
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        val chunk = pcm.slice().order(ByteOrder.LITTLE_ENDIAN)
                        // Most Android decoders output 16-bit PCM. Float output is explicitly refused.
                        val outFormat = codec.outputFormat
                        val encoding = if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else android.media.AudioFormat.ENCODING_PCM_16BIT
                        if (encoding != android.media.AudioFormat.ENCODING_PCM_16BIT) {
                            throw IllegalArgumentException("Audio decoder returned unsupported PCM encoding: $encoding")
                        }
                        var power = 0.0
                        var count = 0
                        while (chunk.remaining() >= 2) {
                            val x = chunk.short.toDouble() / 32768.0
                            power += x * x
                            count++
                        }
                        if (count > 0) {
                            val rms = sqrt(power / count)
                            val db = (20.0 * kotlin.math.log10(rms.coerceAtLeast(0.000001))).toFloat()
                            val start = info.presentationTimeUs / 1000
                            val sampleMs = (count * 1000L / (sampleRate * channels).coerceAtLeast(1)).coerceAtLeast(10)
                            if (db > thresholdDb) {
                                if (open < 0) open = start
                                lastLoud = start + sampleMs
                            } else if (open >= 0 && start - lastLoud > 350) {
                                audible.add(CutRange((open - 180).coerceAtLeast(0), lastLoud + 180))
                                open = -1L
                            }
                        }
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
            if (open >= 0) audible.add(CutRange((open - 180).coerceAtLeast(0), lastLoud + 180))
            return merge(audible).filter { it.durationMs >= 450L }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    companion object {
        fun merge(input: List<CutRange>, gapMs: Long = 350): List<CutRange> {
            val sorted = input.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
            val output = ArrayList<CutRange>()
            for (range in sorted) {
                val last = output.lastOrNull()
                if (last != null && range.startMs - last.endMs <= gapMs) {
                    output[output.lastIndex] = CutRange(last.startMs, maxOf(last.endMs, range.endMs))
                } else output.add(range)
            }
            return output
        }
    }
}
