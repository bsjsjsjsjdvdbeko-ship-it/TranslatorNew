package com.sari.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class TranslationClient(
    private val provider: TranslationProvider,
    private val apiKey: String,
    private val email: String = "",
    private val http: OkHttpClient = WhisperClient.defaultClient()
) {
    suspend fun translate(text: String, source: String, target: String): String = withContext(Dispatchers.IO) {
        require(text.isNotBlank()) { "لا يوجد نص لترجمته." }
        require(target.isNotBlank()) { "حدد لغة الترجمة الهدف." }
        val request = when (provider) {
            TranslationProvider.GROQ -> {
                require(apiKey.isNotBlank()) { "أدخل مفتاح Groq API في الإعدادات." }
                val system = "Translate the user's text from ${source.ifBlank { "auto-detected language" }} to $target. Return only the translation, without explanation."
                val payload = JSONObject()
                    .put("model", "llama-3.3-70b-versatile")
                    .put("temperature", 0)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", text)))
                    .toString()
                Request.Builder().url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            }
            TranslationProvider.GOOGLE -> {
                require(apiKey.isNotBlank()) { "مفتاح Google Cloud Translation غير موجود." }
                val payload = JSONObject().put("q", text).put("target", target)
                    .apply { if (source != "auto") put("source", source) }.toString()
                Request.Builder()
                    .url("https://translation.googleapis.com/language/translate/v2?key=${URLEncoder.encode(apiKey, "UTF-8")}")
                    .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            }
            TranslationProvider.MYMEMORY -> {
                val s = if (source == "auto") "en" else source
                val q = URLEncoder.encode(text, "UTF-8")
                val pair = URLEncoder.encode("$s|$target", "UTF-8")
                val e = if (email.isBlank()) "" else "&de=" + URLEncoder.encode(email, "UTF-8")
                Request.Builder().url("https://api.mymemory.translated.net/get?q=$q&langpair=$pair$e").get().build()
            }
        }
        var last: Exception? = null
        for (i in 0 until 3) {
            try {
                val result = http.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code}: ${raw.take(180)}")
                    val json = JSONObject(raw)
                    when (provider) {
                        TranslationProvider.GROQ -> json.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()
                        TranslationProvider.GOOGLE -> json.getJSONObject("data").getJSONArray("translations")
                            .getJSONObject(0).getString("translatedText")
                        TranslationProvider.MYMEMORY -> {
                            if (json.optString("responseStatus") != "200")
                                error("خدمة MyMemory لم تقبل الطلب: ${json.optString("responseDetails")}")
                            json.getJSONObject("responseData").getString("translatedText")
                        }
                    }.also { if (it.isBlank()) error("خدمة الترجمة أعادت نصاً فارغاً.") }
                }
                return@withContext result
            } catch (e: Exception) {
                last = e
                if (i < 2) kotlinx.coroutines.delay(500L * (i + 1))
            }
        }
        throw IllegalStateException("فشلت الترجمة بعد 3 محاولات. تحقق من الإنترنت ومفاتيح الخدمة.", last)
    }
}

enum class TranslationProvider { GROQ, GOOGLE, MYMEMORY }
