package com.mcs.android

import org.junit.Assert.*
import org.junit.Test

class ClipLogicTest {
    @Test fun fixedClipsCoverVideo() {
        val clips = ClipLogic.fixed(91_000, 30)
        assertEquals(4, clips.size)
        assertEquals(0L, clips.first().startMs)
        assertEquals(91_000L, clips.last().endMs)
    }
    @Test fun splitClipKeepsBounds() {
        val clips = ClipLogic.split(listOf(ClipSegment(0, 60_000, "One")), 0, 20_000)
        assertEquals(2, clips.size)
        assertEquals(20_000L, clips[0].endMs)
        assertEquals(20_000L, clips[1].startMs)
    }
    @Test fun captionsFromScriptPreserveAllWords() {
        val text = (1..71).joinToString(" ") { "word" + it }
        val cues = ClipLogic.captionsFromScript(text, 0, 71_000, 6)
        assertEquals(text, cues.joinToString(" ") { it.text })
        assertEquals(71_000L, cues.last().endMs)
    }
    @Test fun transcriptCutsRemainWithinVideo() {
        val cues = listOf(CaptionCue(1000, 4000, "hello"), CaptionCue(10_000, 15_000, "world"))
        val clips = ClipLogic.fromCaptions(cues, 20_000)
        assertTrue(clips.isNotEmpty())
        assertTrue(clips.all { it.startMs >= 0 && it.endMs <= 20_000 })
    }
}
