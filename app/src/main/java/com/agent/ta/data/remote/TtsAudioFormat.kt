package com.agent.ta.data.remote

data class TtsAudioResult(
    val bytes: ByteArray,
    val format: String
)

object TtsAudioFormat {
    fun resolve(declaredFormat: String?, bytes: ByteArray): String? {
        val detected = detect(bytes)
        val declared = normalize(declaredFormat)
        return when {
            detected != null -> detected
            declared != null && bytes.isEmpty() -> declared
            else -> null
        }
    }

    private fun normalize(format: String?): String? = when (format?.trim()?.lowercase()) {
        "wav", "wave", "audio/wav", "audio/x-wav" -> "wav"
        "mp3", "mpeg", "audio/mpeg", "audio/mp3" -> "mp3"
        else -> null
    }

    private fun detect(bytes: ByteArray): String? {
        if (bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
        ) return "wav"
        if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals("ID3".toByteArray())) return "mp3"
        if (bytes.size >= 2 && bytes[0].toInt() and 0xFF == 0xFF && bytes[1].toInt() and 0xE0 == 0xE0) return "mp3"
        return null
    }
}
