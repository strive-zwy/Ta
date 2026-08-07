package com.agent.ta.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentImportPolicyTest {
    @Test
    fun `accepts supported package paths`() {
        assertTrue(AgentImportPolicy.isAllowedPath("agent.json"))
        assertTrue(AgentImportPolicy.isAllowedPath("avatars/a.webp"))
        assertTrue(AgentImportPolicy.isAllowedPath("avatars/a.gif"))
        assertTrue(AgentImportPolicy.isAllowedPath("avatars/a.bmp"))
        assertTrue(AgentImportPolicy.isAllowedPath("avatars/a.heic"))
        assertTrue(AgentImportPolicy.isAllowedPath("avatars/a.avif"))
        assertTrue(AgentImportPolicy.isAllowedPath("voice/neutral.wav"))
        assertTrue(AgentImportPolicy.isAllowedPath("voice/neutral.mp3"))
        assertTrue(AgentImportPolicy.isAllowedPath("relationship.json"))
        assertTrue(AgentImportPolicy.isAllowedPath("memory.json"))
        assertTrue(AgentImportPolicy.isAllowedPath("recent_chats.json"))
    }

    @Test
    fun `rejects traversal absolute and unsupported paths`() {
        assertFalse(AgentImportPolicy.isAllowedPath("../agent.json"))
        assertFalse(AgentImportPolicy.isAllowedPath("/agent.json"))
        assertFalse(AgentImportPolicy.isAllowedPath("avatars/a.exe"))
        assertFalse(AgentImportPolicy.isAllowedPath("secret.txt"))
    }

    @Test
    fun `validates file signatures`() {
        assertTrue(AgentImportPolicy.hasSupportedSignature("avatars/a.png", pngHeader()))
        assertTrue(AgentImportPolicy.hasSupportedSignature("voice/a.wav", wavHeader()))
        assertTrue(AgentImportPolicy.hasSupportedSignature("voice/a.mp3", byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())))
        assertFalse(AgentImportPolicy.hasSupportedSignature("voice/a.wav", byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `detects supported image formats from file headers`() {
        assertEquals("jpg", AgentImportPolicy.detectImageExtension(jpegHeader()))
        assertEquals("png", AgentImportPolicy.detectImageExtension(pngHeader()))
        assertEquals("webp", AgentImportPolicy.detectImageExtension(webpHeader()))
        assertEquals("gif", AgentImportPolicy.detectImageExtension("GIF89a".toByteArray()))
        assertEquals("bmp", AgentImportPolicy.detectImageExtension("BM000000".toByteArray()))
        assertEquals("heic", AgentImportPolicy.detectImageExtension(isoImageHeader("heic")))
        assertEquals("avif", AgentImportPolicy.detectImageExtension(isoImageHeader("avif")))
        assertNull(AgentImportPolicy.detectImageExtension(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `normalizes avatar path using detected image format`() {
        assertEquals(
            "avatars/avatar_1.png",
            AgentImportPolicy.normalizedAvatarPath("avatars/avatar_1.jpg", pngHeader())
        )
        assertEquals(
            "avatars/avatar_1.webp",
            AgentImportPolicy.normalizedAvatarPath("avatars/avatar_1.png", webpHeader())
        )
        assertEquals(
            "avatars/avatar_1.jpg",
            AgentImportPolicy.normalizedAvatarPath("avatars/avatar_1.jpeg", jpegHeader())
        )
        assertNull(
            AgentImportPolicy.normalizedAvatarPath("avatars/avatar_1.jpg", byteArrayOf(1, 2, 3))
        )
    }

    @Test
    fun `detects supported audio formats from file headers`() {
        assertEquals("wav", AgentImportPolicy.detectAudioExtension(wavHeader()))
        assertEquals("mp3", AgentImportPolicy.detectAudioExtension(mp3Id3Header()))
        assertEquals("mp3", AgentImportPolicy.detectAudioExtension(mp3FrameHeader()))
        assertEquals("ogg", AgentImportPolicy.detectAudioExtension(oggHeader()))
        assertEquals("flac", AgentImportPolicy.detectAudioExtension(flacHeader()))
        assertEquals("m4a", AgentImportPolicy.detectAudioExtension(m4aHeader()))
        assertNull(AgentImportPolicy.detectAudioExtension(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `normalizes voice path using detected audio format`() {
        assertEquals(
            "voice/sample.mp3",
            AgentImportPolicy.normalizedVoicePath("voice/sample.wav", mp3Id3Header())
        )
        assertEquals(
            "voice/sample.ogg",
            AgentImportPolicy.normalizedVoicePath("voice/sample.wav", oggHeader())
        )
        assertEquals(
            "voice/sample.wav",
            AgentImportPolicy.normalizedVoicePath("voice/sample.wav", wavHeader())
        )
        assertNull(
            AgentImportPolicy.normalizedVoicePath("voice/sample.wav", byteArrayOf(1, 2, 3))
        )
    }

    private fun jpegHeader() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private fun pngHeader() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun webpHeader() = "RIFF0000WEBPVP8 ".toByteArray()
    private fun isoImageHeader(brand: String) = byteArrayOf(0, 0, 0, 24) + "ftyp$brand".toByteArray()
    private fun wavHeader() = "RIFF0000WAVEfmt ".toByteArray()
    private fun mp3Id3Header() = "ID3\u0003\u0000".toByteArray()
    private fun mp3FrameHeader() = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
    private fun oggHeader() = "OggS\u0000\u0002".toByteArray()
    private fun flacHeader() = "fLaC\u0000".toByteArray()
    private fun m4aHeader() = byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray()
}
