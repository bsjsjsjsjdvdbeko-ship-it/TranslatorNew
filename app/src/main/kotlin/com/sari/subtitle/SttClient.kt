package com.sari.subtitle

import java.io.File

data class WordTimestamp(val word: String, val start: Double, val end: Double)
data class Utterance(val text: String, val start: Double, val end: Double)
data class SttResult(
    val text: String,
    val language: String? = null,
    val words: List<WordTimestamp> = emptyList(),
    val utterances: List<Utterance> = emptyList(),
    val confidence: Double? = null,
    val success: Boolean = true,
    val error: String? = null
)

interface SttClient {
    suspend fun transcribe(file: File, language: String = "auto", contextPrompt: String = ""): SttResult
}
