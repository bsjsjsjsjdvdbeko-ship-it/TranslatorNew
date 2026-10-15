package com.sari.subtitle

/** Canonicalizes persisted STT provider aliases without silently choosing another provider. */
internal object SttProviderPolicy {
    fun normalize(value: String): String = when (value.trim().lowercase()) {
        "groq", "whisper", "groq-whisper", "groq_whisper", "groq whisper" -> "groq"
        "deepgram-nova3", "deepgram-nova-3", "deepgram_nova3", "deepgram nova-3", "deepgram nova3", "nova-3" -> "deepgram-nova3"
        "deepgram", "deepgram-nova2", "deepgram-nova-2", "deepgram_nova2", "nova-2" -> "deepgram-nova2"
        "vosk" -> "vosk"
        else -> value.trim().lowercase()
    }
}
