package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiClient(
    private val apiKey: String,
    private val http: OkHttpClient = defaultClient()
) {
    suspend fun translate(text: String, source: String, target: String, context: TranslationContext): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "أدخل مفتاح OpenAI API في الإعدادات." }
        val system = buildString {
            append("أنت مترجم محترف. ترجم النص إلى العربية بأسلوب طبيعي وواضح. حافظ على الأسماء والمصطلحات. لا تُضف معلومات غير موجودة. اجعل الترجمة مختصرة ومناسبة للترجمة المرئية. ")
            append("لغة المصدر: ").append(if (source.isBlank()) "auto" else source).append(". الهدف: ").append(target).append(". ")
            append("لا تشرح ولا تضع ملاحظات؛ أعد الترجمة فقط.\n")
            append(contextText(context))
        }
        val payload = JSONObject()
            .put("model", "gpt-4o-mini")
            .put("temperature", 0.3)
            .put("max_tokens", 500)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", text)))
            .toString()
        val request = Request.Builder().url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        var last: Exception? = null
        repeat(3) { i ->
            try {
                return@withContext http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("OpenAI HTTP ${response.code}: ${raw.take(180)}")
                    val result = JSONObject(raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                    if (result.isBlank()) error("OpenAI أعاد ترجمة فارغة.")
                    result
                }
            } catch (e: Exception) {
                last = e
                Log.d(TAG, "OpenAI attempt ${i + 1} failed: ${e.message}")
                if (i < 2) delay(500L * (i + 1))
            }
        }
        throw IllegalStateException("فشلت ترجمة OpenAI بعد 3 محاولات.", last)
    }

    suspend fun testKey(): Boolean = try {
        if (apiKey.isBlank()) false else http.newCall(Request.Builder().url("https://api.openai.com/v1/models/gpt-4o-mini").header("Authorization", "Bearer $apiKey").get().build()).execute().use { it.isSuccessful }
    } catch (e: Exception) { Log.d(TAG, "OpenAI key test failed: ${e.message}"); false }

    private fun contextText(context: TranslationContext): String = buildString {
        if (context.previousTranslations.isNotEmpty()) {
            append("السياق السابق للاختيار اللغوي فقط:\n")
            context.previousTranslations.takeLast(6).forEach { append("SRC: ${it.source}\nTGT: ${it.translation}\n") }
        }
        if (context.glossary.isNotEmpty()) append("القاموس الإلزامي:\n${context.glossary.entries.joinToString("\n") { "${it.key} = ${it.value}" }}\n")
        if (context.characterNotes.isNotBlank()) append("ملاحظات الأسماء/المتحدثين:\n${context.characterNotes}\n")
    }

    companion object {
        private const val TAG = "SariOpenAI"
        fun defaultClient() = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    }
}
