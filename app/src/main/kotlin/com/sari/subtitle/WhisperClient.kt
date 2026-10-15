package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Groq Speech-to-Text client. Returns segment timestamps when available. */
class WhisperClient(private val apiKey: String, private val http: OkHttpClient = defaultClient()) : SttClient {
    override suspend fun transcribe(audioFile: File, language: String, prompt: String): TranscriptResult =
        transcribeDetailed(audioFile, language, prompt)

    suspend fun transcribeDetailed(audioFile: File, language: String = "ar", prompt: String = ""): TranscriptResult = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        require(key.isNotBlank()) { "أدخل مفتاح Groq API من الإعدادات." }
        require(!key.contains('\r') && !key.contains('\n')) { "مفتاح Groq يحتوي على محارف غير صالحة؛ أعد حفظه من الإعدادات." }
        val wav = SttDiagnostics.inspectWav(audioFile)
        Log.d(TAG, "Groq STT request model=${modelFor(prompt)} file=${audioFile.name} sizeBytes=${wav.sizeBytes} wavValid=${wav.valid} sampleRate=${wav.sampleRate} channels=${wav.channels} bits=${wav.bitsPerSample} dataBytes=${wav.dataBytes} language=${SttLanguagePolicy.forGroq(language) ?: "omitted"}")
        require(wav.valid) { "ملف WAV غير صالح أو لا يحتوي بيانات صوتية: ${wav.problem ?: "تحقق من الالتقاط"}." }
        val model = modelFor(prompt)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .apply { if (prompt.isNotBlank()) addFormDataPart("prompt", prompt.take(3_000)) }
            .addFormDataPart("timestamp_granularities[]", "segment")
            .apply { SttLanguagePolicy.forGroq(language)?.let { addFormDataPart("language", it) } }
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        val raw = SttRequestExecutor.execute("Groq", http, request)
        try {
            val json = JSONObject(raw)
            val text = json.optString("text").trim()
            if (text.isBlank()) throw IllegalStateException("أعاد Groq استجابة ناجحة لكن النص فارغاً.")
            val segments = mutableListOf<TranscriptSegment>()
            val array = json.optJSONArray("segments")
            if (array != null) for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optDouble("start", Double.NaN)
                val end = item.optDouble("end", Double.NaN)
                val segmentText = item.optString("text").trim()
                if (segmentText.isNotBlank() && start.isFinite() && end.isFinite() && end >= start) {
                    segments += TranscriptSegment(start * 1000.0, end * 1000.0, segmentText)
                }
            }
            TranscriptResult(text, segments)
        } catch (e: Exception) {
            Log.e(TAG, "Groq STT result=parse_error responseChars=${raw.length} exception=${e.javaClass.simpleName}", e)
            throw IllegalStateException("نجح اتصال Groq لكن تعذر تحليل استجابة تحويل الصوت. أعد المحاولة أو افحص سجل التشخيص.", e)
        }
    }

    private fun modelFor(prompt: String) = if (prompt.startsWith("[HIGH_ACCURACY]")) "whisper-large-v3" else "whisper-large-v3-turbo"

    companion object {
        private const val TAG = "SariTranslate"
        const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

data class TranscriptResult(val text: String, val segments: List<TranscriptSegment>)
data class TranscriptSegment(val startMs: Double, val endMs: Double, val text: String)
