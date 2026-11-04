package com.mcs.android

import kotlin.math.min

/** Editable source-media segments. Times always refer to the imported source, not output timestamps. */
data class ClipSegment(
    val startMs: Long,
    val endMs: Long,
    val label: String,
    val selected: Boolean = true
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

object ClipLogic {
    fun fixed(durationMs: Long, seconds: Int): List<ClipSegment> {
        if (durationMs <= 0 || seconds < 1) return emptyList()
        val step = seconds * 1000L
        val result = mutableListOf<ClipSegment>()
        var cursor = 0L
        while (cursor < durationMs && result.size < 250) {
            val end = min(durationMs, cursor + step)
            if (end - cursor >= 1000L)
                result.add(ClipSegment(cursor, end, "Short " + (result.size + 1)))
            cursor = end
        }
        return result
    }

    /** Group imported/transcribed cues into useful shorts with boundaries at speech gaps. */
    fun fromCaptions(cues: List<CaptionCue>, durationMs: Long, maxSeconds: Int = 60): List<ClipSegment> {
        if (durationMs <= 0 || cues.isEmpty()) return emptyList()
        val limit = maxSeconds.coerceIn(15, 180) * 1000L
        val valid = cues.filter { it.endMs > it.startMs && it.startMs < durationMs }
            .sortedBy { it.startMs }
        if (valid.isEmpty()) return emptyList()
        val out = mutableListOf<ClipSegment>()
        var start = (valid.first().startMs - 350L).coerceAtLeast(0L)
        var end = start
        for (cue in valid) {
            val cueEnd = min(durationMs, cue.endMs + 350L)
            if (cueEnd - start > limit && end - start >= 3000L) {
                out.add(ClipSegment(start, end, "Speech clip " + (out.size + 1)))
                start = (cue.startMs - 350L).coerceAtLeast(0L)
                end = start
            }
            end = maxOf(end, cueEnd).coerceAtMost(start + limit)
            if (out.size >= 249) break
        }
        if (end - start >= 1000L) out.add(ClipSegment(start, end, "Speech clip " + (out.size + 1)))
        return out
    }

    fun split(segments: List<ClipSegment>, index: Int, atMs: Long): List<ClipSegment> {
        if (index !in segments.indices) return segments
        val target = segments[index]
        if (atMs <= target.startMs + 500 || atMs >= target.endMs - 500) return segments
        return segments.take(index) +
            listOf(target.copy(endMs = atMs, label = target.label + " A"),
                target.copy(startMs = atMs, label = target.label + " B")) +
            segments.drop(index + 1)
    }

    /** Approximate captions from a supplied script, never claim speech recognition/alignment. */
    fun captionsFromScript(script: String, startMs: Long, endMs: Long, wordsPerCue: Int = 7): List<CaptionCue> {
        val words = script.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || endMs <= startMs) return emptyList()
        val groups = words.chunked(wordsPerCue.coerceIn(2, 16))
        return groups.mapIndexed { index, group ->
            val offset = groups.take(index).sumOf { it.size }
            val a = startMs + (endMs - startMs) * offset / words.size
            val b = startMs + (endMs - startMs) * (offset + group.size) / words.size
            CaptionCue(a, maxOf(a + 1, b), group.joinToString(" "))
        }
    }
}
