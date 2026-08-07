package com.agent.ta.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsAudioFormatTest {

    @Test
    fun `normalizes supported response formats`() {
        assertEquals("wav", TtsAudioFormat.resolve("WAVE", byteArrayOf()))
        assertEquals("mp3", TtsAudioFormat.resolve("audio/mpeg", byteArrayOf()))
    }

    @Test
    fun `detects wav from riff wave header`() {
        val bytes = "RIFF0000WAVEfmt ".toByteArray()

        assertEquals("wav", TtsAudioFormat.resolve(null, bytes))
    }

    @Test
    fun `detects mp3 from id3 and frame headers`() {
        assertEquals("mp3", TtsAudioFormat.resolve(null, byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())))
        assertEquals("mp3", TtsAudioFormat.resolve(null, byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x00)))
    }

    @Test
    fun `rejects unknown format and bytes`() {
        assertNull(TtsAudioFormat.resolve("pcm", byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `rejects declared format when file header disagrees`() {
        assertNull(TtsAudioFormat.resolve("wav", byteArrayOf(1, 2, 3, 4)))
    }
}
