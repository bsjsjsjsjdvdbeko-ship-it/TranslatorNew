package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class DeepgramClient(
    private val apiKey: String,
    private val http: OkHttpClient = defaultClient()
) : SttClient {
    override suspend fun transcribe(file: File, language: String, contextPrompt: String): SttResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext SttResult("", success = false, error = "أدخل مفتاح Deepgram API.")
        if (!file.exists() || file.length() == 0L) return@withContext SttResult("", success = false, error = "الملف الصوتي فارغ.")
        val url = "https://api.deepgram.com/v1/listen".toHttpUrl().newBuilder()
            .addQueryParameter("model", "nova-2")
            .addQueryParameter("language", if (language.isBlank() || language == "auto") "ar" else language)
            .addQueryParameter("smart_format", "true")
            .addQueryParameter("punctuate", "true")
            .addQueryParameter("utterances", "true")
            .addQueryParameter("diarize_model", "latest")
            .apply {
                contextPrompt.split(',', '\n', ';').map { it.trim() }.filter { it.isNotBlank() }.take(20).forEach { addQueryParameter("keywords", it) }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .header("Content-Type", "audio/wav")
            .post(file.asRequestBody("audio/wav".toMediaType()))
            .build()
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("Deepgram HTTP ${response.code}: ${raw.take(180)}")
                    return@withContext parse(raw)
                }
            } catch (e: Exception) {
                last = e
                Log.d(TAG, "Deepgram attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < 2) delay(500L * (1L shl attempt))
            }
        }
        SttResult("", success = false, error = "فشل Deepgram بعد 3 محاولات: ${last?.message ?: "unknown"}")
    }

    private fun parse(raw: String): SttResult {
        return try {
            val json = JSONObject(raw)
            val results = json.optJSONObject("results") ?: return SttResult("", success = false, error = "استجابة Deepgram غير صالحة.")
            val channels = results.optJSONArray("channels") ?: return SttResult("", success = false, error = "لم تُرجع Deepgram قنوات صوتية.")
            val alt = channels.optJSONObject(0)?.optJSONArray("alternatives")?.optJSONObject(0)
                ?: return SttResult("", success = false, error = "لم تُرجع Deepgram transcript.")
            val text = alt.optString("transcript").trim()
            val confidence = if (alt.has("confidence")) alt.optDouble("confidence") else null
            val words = mutableListOf<WordTimestamp>()
            alt.optJSONArray("words")?.let { a ->
                for (i in 0 until a.length()) {
                    val w = a.optJSONObject(i) ?: continue
                    val word = w.optString("punctuated_word", w.optString("word")).trim()
                    val start = w.optDouble("start", Double.NaN)
                    val end = w.optDouble("end", Double.NaN)
                    if (word.isNotBlank() && start.isFinite() && end.isFinite()) words += WordTimestamp(word, start, end)
                }
            }
            val utterances = mutableListOf<Utterance>()
            results.optJSONArray("utterances")?.let { a ->
                for (i in 0 until a.length()) {
                    val u = a.optJSONObject(i) ?: continue
                    val t = u.optString("transcript").trim()
                    val start = u.optDouble("start", Double.NaN)
                    val end = u.optDouble("end", Double.NaN)
                    if (t.isNotBlank() && start.isFinite() && end.isFinite()) utterances += Utterance(t, start, end)
                }
            }
            SttResult(text, language = "ar", words = words, utterances = utterances, confidence = confidence)
        } catch (e: Exception) {
            SttResult("", success = false, error = "تعذر تحليل استجابة Deepgram: ${e.message}")
        }
    }

    suspend fun testKey(): Boolean {
        if (apiKey.isBlank()) return false
        return try {
            val request = Request.Builder()
                .url("https://api.deepgram.com/v1/projects")
                .header("Authorization", "Token $apiKey")
                .get().build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.d(TAG, "Deepgram key test failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "SariDeepgram"
        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }
}
