package com.mcs.android

import org.junit.Assert.*
import org.junit.Test

class EditorLogicTest {
    @Test fun srtRoundtripAndTiming() {
        val data = "1\n00:00:01,200 --> 00:00:03,500\nMCS captions\n"
        val cues = EditorLogic.parseSrt(data)
        assertEquals(1, cues.size)
        assertEquals("MCS captions", EditorLogic.activeCaption(cues, 2200L))
        assertEquals(cues, EditorLogic.parseSrt(EditorLogic.toSrt(cues)))
    }

    @Test fun longScriptNoLoss() {
        val input = (1..2500).joinToString(" ") { "word$it" }
        val scenes = EditorLogic.splitScript(input)
        assertTrue(scenes.size <= 30)
        assertEquals(input, scenes.joinToString(" ") { it.text })
    }

    @Test fun fitAndFill() {
        assertEquals(0.5f, EditorLogic.frameScale(1080,1920,1080,960,false))
        assertEquals(1.0f, EditorLogic.frameScale(1080,1920,1080,960,true))
    }
}
