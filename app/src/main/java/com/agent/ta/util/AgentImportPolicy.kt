package com.agent.ta.util

import java.io.File

object AgentImportPolicy {
    const val MAX_ARCHIVE_BYTES = 100L * 1024 * 1024
    const val MAX_ENTRY_COUNT = 100
    const val MAX_ENTRY_BYTES = 20L * 1024 * 1024
    const val MAX_TOTAL_EXTRACTED_BYTES = 80L * 1024 * 1024

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "ogg", "flac", "m4a", "aac")

    fun isAllowedPath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
        val normalized = path.replace('\\', '/')
        if (normalized.split('/').any { it == ".." || it.isBlank() }) return false
        return when {
            normalized == "agent.json" -> true
            normalized in setOf("relationship.json", "memory.json", "recent_chats.json") -> true
            normalized.startsWith("avatars/") -> extension(normalized) in IMAGE_EXTENSIONS
            normalized.startsWith("voice/") -> extension(normalized) in AUDIO_EXTENSIONS
            else -> false
        }
    }

    fun isContained(baseDir: File, candidate: File): Boolean {
        val base = baseDir.canonicalFile
        val file = candidate.canonicalFile
        return file == base || file.path.startsWith(base.path + File.separator)
    }

    fun hasSupportedSignature(path: String, bytes: ByteArray): Boolean {
        return when (extension(path)) {
            "json" -> bytes.isNotEmpty() && (bytes.first().toInt().toChar() == '{' || bytes.first().toInt().toChar() == '[')
            "png" -> bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            "jpg", "jpeg" -> bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            "webp" -> bytes.size >= 12 && bytes.startsWith("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
            "gif" -> bytes.size >= 6 && (bytes.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) || bytes.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray()))
            "bmp" -> bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()
            "heic", "heif", "avif" -> isIsoBmffImage(bytes)
            "wav" -> bytes.size >= 12 && bytes.startsWith("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
            "mp3" -> bytes.startsWith("ID3".toByteArray()) || (bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xE0 == 0xE0)
            "ogg" -> bytes.size >= 4 && bytes.startsWith("OggS".toByteArray())
            "flac" -> bytes.size >= 4 && bytes.startsWith("fLaC".toByteArray())
            "m4a", "aac" -> isIsoBmffAudio(bytes) || (bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xF0 == 0xF0)
            else -> false
        }
    }

    /**
     * 根据文件头识别真实图片格式，返回规范扩展名（小写）。
     * 无法识别时返回 null。
     */
    fun detectImageExtension(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        return when {
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) -> "jpg"
            bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "png"
            bytes.size >= 12 &&
                bytes.startsWith("RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "webp"
            bytes.size >= 6 &&
                (bytes.copyOfRange(0, 6).contentEquals("GIF87a".toByteArray()) ||
                    bytes.copyOfRange(0, 6).contentEquals("GIF89a".toByteArray())) -> "gif"
            bytes.size >= 2 &&
                bytes[0] == 'B'.code.toByte() &&
                bytes[1] == 'M'.code.toByte() -> "bmp"
            isIsoBmffImage(bytes) -> {
                when (detectIsoBrand(bytes)) {
                    "heic", "heix", "mif1", "hevc", "hevs" -> "heic"
                    "avif", "avis" -> "avif"
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * 根据真实文件头规范化头像路径。
     * - 扩展名与真实格式一致时保持原路径。
     * - 扩展名不一致但能识别真实格式时，返回修正后的路径。
     * - 无法识别真实格式时返回 null，由调用方决定是否拒绝。
     */
    fun normalizedAvatarPath(path: String, bytes: ByteArray): String? {
        val detected = detectImageExtension(bytes) ?: return null
        val currentExt = extension(path)
        if (currentExt == detected || (currentExt == "jpeg" && detected == "jpg") ||
            (currentExt == "heif" && detected == "heic")
        ) {
            return if (currentExt == "jpeg") {
                path.substringBeforeLast('.') + ".jpg"
            } else {
                path
            }
        }
        val dotIndex = path.lastIndexOf('.')
        val base = if (dotIndex > 0) path.substring(0, dotIndex) else path
        return "$base.$detected"
    }

    /**
     * 根据文件头识别真实音频格式，返回规范扩展名（小写）。
     * 无法识别时返回 null。
     */
    fun detectAudioExtension(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        return when {
            bytes.size >= 12 &&
                bytes.startsWith("RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray()) -> "wav"
            bytes.startsWith("ID3".toByteArray()) -> "mp3"
            bytes.size >= 2 &&
                bytes[0].toInt() and 0xFF == 0xFF &&
                bytes[1].toInt() and 0xE0 == 0xE0 -> "mp3"
            bytes.startsWith("OggS".toByteArray()) -> "ogg"
            bytes.startsWith("fLaC".toByteArray()) -> "flac"
            isIsoBmffAudio(bytes) -> "m4a"
            else -> null
        }
    }

    /**
     * 根据真实文件头规范化音频路径。
     * - 扩展名与真实格式一致时保持原路径。
     * - 扩展名不一致但能识别真实格式时，返回修正后的路径。
     * - 无法识别真实格式时返回 null，由调用方决定是否拒绝。
     */
    fun normalizedVoicePath(path: String, bytes: ByteArray): String? {
        val detected = detectAudioExtension(bytes) ?: return null
        val currentExt = extension(path)
        if (currentExt == detected) return path
        val dotIndex = path.lastIndexOf('.')
        val base = if (dotIndex > 0) path.substring(0, dotIndex) else path
        return "$base.$detected"
    }

    private fun isIsoBmffImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        // ISO BMFF: bytes 4-7 为 'ftyp'
        if (!bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) return false
        val brand = detectIsoBrand(bytes) ?: return false
        return brand in setOf("heic", "heix", "mif1", "hevc", "hevs", "avif", "avis")
    }

    private fun isIsoBmffAudio(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (!bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) return false
        val brand = detectIsoBrand(bytes) ?: return false
        return brand in setOf("m4a ", "m4v ", "isom", "mp42", "dash")
    }

    private fun detectIsoBrand(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        return String(bytes, 8, 4, Charsets.US_ASCII).lowercase()
    }

    private fun extension(path: String): String = path.substringAfterLast('.', "").lowercase()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)
}
