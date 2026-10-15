package com.sari.subtitle

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Executes an STT request with retries only for transient HTTP/network failures. */
internal object SttRequestExecutor {
    private const val TAG = "SariTranslate"
    private const val MAX_ATTEMPTS = 3

    suspend fun execute(provider: String, client: OkHttpClient, request: Request): String {
        var last: Throwable? = null
        for (index in 0 until MAX_ATTEMPTS) {
            val attempt = index + 1
            val started = System.nanoTime()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                    if (response.isSuccessful) {
                        Log.i(TAG, "$provider STT result=success HTTP=${response.code} attempt=$attempt elapsedMs=$elapsedMs responseChars=${body.length}")
                        return body
                    }
                    val error = SttHttpException(
                        statusCode = response.code,
                        responseBody = SttDiagnostics.safeServerDetail(body),
                        provider = provider,
                        retryAfterMs = SttDiagnostics.retryAfterMillis(response.header("Retry-After"))
                    )
                    Log.w(TAG, "$provider STT result=http_error HTTP=${response.code} attempt=$attempt elapsedMs=$elapsedMs detail=${SttDiagnostics.safeServerDetail(body).take(180)}")
                    val retryable = SttDiagnostics.isRetryableHttp(response.code)
                    if (!retryable || attempt == MAX_ATTEMPTS) throw error
                    last = error
                    val waitMs = if (response.code == 429 && error.retryAfterMs != null) error.retryAfterMs.coerceIn(250L, 5_000L)
                        else 400L * attempt
                    delay(waitMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SttHttpException) {
                throw e
            } catch (e: IOException) {
                last = e
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                Log.w(TAG, "$provider STT result=network_error attempt=$attempt elapsedMs=$elapsedMs exception=${e.javaClass.simpleName} message=${SttDiagnostics.safeMessage(e.message)}")
                if (attempt == MAX_ATTEMPTS) break
                delay(400L * attempt)
            }
        }
        val cause = last ?: IOException("STT request failed without a response")
        throw IOException("$provider: ${SttDiagnostics.messageFor(cause)}", cause)
    }
}
