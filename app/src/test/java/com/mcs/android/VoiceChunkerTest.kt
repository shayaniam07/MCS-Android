package com.mcs.android
import org.junit.Test
import org.junit.Assert.*

class VoiceChunkerTest {
    @Test fun longNarrationSplitsWithoutDroppingWords() {
        val text = (1..2500).joinToString(" ") { "word" + it }
        val chunks = VoiceChunker.split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 2800 })
        assertEquals(text, chunks.joinToString(" "))
    }
    @Test fun emptyTextHasNoChunks() {
        assertTrue(VoiceChunker.split(" ").isEmpty())
    }
}
