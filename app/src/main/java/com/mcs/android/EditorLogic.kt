package com.mcs.android

import kotlin.math.max
import kotlin.math.min

/** Platform independent editor logic; compiled and tested with regular Kotlin/JVM. */
data class CaptionCue(val startMs: Long, val endMs: Long, val text: String) {
    fun visibleAt(ms: Long) = startMs <= ms && ms < endMs
}

data class ScriptScene(val number: Int, val text: String, val estimatedMs: Long)

object EditorLogic {
    private val timestamp = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{1,3})")
    private val timePair = Regex("\\s*(\\S+)\\s*-->\\s*(\\S+)\\s*")

    fun parseSrt(raw: String): List<CaptionCue> = raw.replace("\r\n", "\n")
        .replace("\r", "\n")
        .trim().split(Regex("\\n\\s*\\n"))
        .mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
            if (timeLineIndex < 0) return@mapNotNull null
            val pair = timePair.matchEntire(lines[timeLineIndex]) ?: return@mapNotNull null
            val start = parseTime(pair.groupValues[1]) ?: return@mapNotNull null
            val end = parseTime(pair.groupValues[2]) ?: return@mapNotNull null
            val caption = lines.drop(timeLineIndex + 1).joinToString(" ").trim()
            if (caption.isBlank() || end <= start) null else CaptionCue(start, end, caption)
        }.sortedBy { it.startMs }

    fun parseTime(s: String): Long? {
        val m = timestamp.matchEntire(s) ?: return null
        val (h, mm, ss, ms) = m.destructured
        return ((h.toLong() * 3600 + mm.toLong() * 60 + ss.toLong()) * 1000 + ms.padEnd(3, '0').toLong())
    }

    fun formatTime(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        val h = t / 3600000
        val m = t / 60000 % 60
        val s = t / 1000 % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, t % 1000)
    }

    fun toSrt(cues: List<CaptionCue>): String = cues.sortedBy { it.startMs }
        .mapIndexed { i, cue -> "${i + 1}\n${formatTime(cue.startMs)} --> ${formatTime(cue.endMs)}\n${cue.text}\n" }
        .joinToString("\n")

    fun activeCaption(cues: List<CaptionCue>, atMs: Long): String = cues.firstOrNull { it.visibleAt(atMs) }?.text ?: ""

    /** Keep all script words. Never silently discard text or cap at 30 by deletion. */
    fun splitScript(script: String, maxScenes: Int = 30, wpm: Int = 145): List<ScriptScene> {
        val words = script.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val scenes = min(maxScenes.coerceAtLeast(1), max(1, (words.size + 94) / 95))
        val batchSize = (words.size + scenes - 1) / scenes
        return words.chunked(batchSize).mapIndexed { index, chunk ->
            ScriptScene(index + 1, chunk.joinToString(" "), (chunk.size * 60000L) / wpm.coerceAtLeast(1))
        }
    }

    /** Source-aspect-preserving video box inside an output frame (Fit) or filling it (Fill). */
    fun frameScale(sourceW: Int, sourceH: Int, targetW: Int, targetH: Int, fill: Boolean): Float {
        require(sourceW > 0 && sourceH > 0 && targetW > 0 && targetH > 0)
        val x = targetW.toFloat() / sourceW
        val y = targetH.toFloat() / sourceH
        return if (fill) max(x, y) else min(x, y)
    }

    fun clampNormalized(p: Float): Float = p.coerceIn(0.04f, 0.96f)
}
