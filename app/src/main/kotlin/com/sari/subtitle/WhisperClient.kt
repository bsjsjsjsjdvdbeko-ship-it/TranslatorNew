package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Groq Speech-to-Text client. Returns segment timestamps when the API provides them. */
class WhisperClient(private val apiKey: String, private val http: OkHttpClient = defaultClient()) : SttClient {
    override suspend fun transcribe(audioFile: File, language: String, prompt: String): TranscriptResult =
        transcribeDetailed(audioFile, language, prompt)

    suspend fun transcribeDetailed(audioFile: File, language: String = "ar", prompt: String = ""): TranscriptResult = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح Groq API من الإعدادات." }
        val wav = SttDiagnostics.inspectWav(audioFile)
        Log.d(TAG, "Groq request file=${audioFile.name} size=${wav.sizeBytes} wavValid=${wav.valid} sampleRate=${wav.sampleRate} channels=${wav.channels} bits=${wav.bitsPerSample} dataBytes=${wav.dataBytes}")
        require(wav.valid) { "ملف WAV غير صالح أو لا يحتوي بيانات صوتية: ${wav.problem ?: "تحقق من الالتقاط"}." }
        val model = if (prompt.startsWith("[HIGH_ACCURACY]")) "whisper-large-v3" else "whisper-large-v3-turbo"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .apply { if (prompt.isNotBlank()) addFormDataPart("prompt", prompt.take(3_000)) }
            .addFormDataPart("timestamp_granularities[]", "segment")
            .apply { if (language.isNotBlank() && language != "auto") addFormDataPart("language", language) }
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        retry(request) { raw -> parseResult(raw) }
    }

    private fun parseResult(raw: String): TranscriptResult {
        val json = JSONObject(raw)
        val text = json.optString("text").trim()
        if (text.isBlank()) error("لم يُرجع Groq نصاً صوتياً.")
        val segments = mutableListOf<TranscriptSegment>()
        val array = json.optJSONArray("segments")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optDouble("start", Double.NaN)
                val end = item.optDouble("end", Double.NaN)
                val segmentText = item.optString("text").trim()
                if (segmentText.isNotBlank() && start.isFinite() && end.isFinite() && end >= start) {
                    segments += TranscriptSegment(start * 1000.0, end * 1000.0, segmentText)
                }
            }
        }
        return TranscriptResult(text, segments)
    }

    private suspend fun <T> retry(request: Request, parse: (String) -> T): T {
        var last: Exception? = null
        repeat(3) { i ->
            try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val failure = SttHttpException(response.code, raw, "Groq")
                        Log.e(TAG, "Groq HTTP ${response.code}: ${raw.take(400)}")
                        if (response.code !in listOf(408, 429) && response.code !in 500..599) throw failure
                        throw failure
                    }
                    Log.d(TAG, "Groq HTTP ${response.code}; responseBytes=${raw.length}")
                    return parse(raw)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                Log.e(TAG, "Groq attempt ${i + 1}/3 failed: ${SttDiagnostics.messageFor(e)}", e)
                val httpError = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SttHttpException>().firstOrNull()
                if (httpError != null && httpError.statusCode !in listOf(408, 429) && httpError.statusCode !in 500..599) {
                    throw IllegalStateException(SttDiagnostics.messageFor(e), e)
                }
                if (i < 2) delay(500L * (i + 1))
            }
        }
        val failure = last ?: IllegalStateException("Unknown Groq error")
        throw IllegalStateException(SttDiagnostics.messageFor(failure), failure)
    }

    companion object {
        private const val TAG = "SariTranslate"
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
