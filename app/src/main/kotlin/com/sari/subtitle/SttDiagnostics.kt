package com.sari.subtitle

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** Safe diagnostics for STT requests. Never logs API key values. */
internal object SttDiagnostics {
    private const val TAG = "SariTranslate"

    data class WavInfo(
        val valid: Boolean,
        val sizeBytes: Long,
        val sampleRate: Int? = null,
        val channels: Int? = null,
        val bitsPerSample: Int? = null,
        val dataBytes: Long? = null,
        val problem: String? = null
    )

    fun inspectWav(file: File): WavInfo {
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (!file.exists()) return WavInfo(false, length, problem = "file does not exist")
        if (length <= 44L) return WavInfo(false, length, problem = "WAV is empty or header-only")
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val riff = ByteArray(4); raf.readFully(riff)
                raf.skipBytes(4)
                val wave = ByteArray(4); raf.readFully(wave)
                if (String(riff, Charsets.US_ASCII) != "RIFF" || String(wave, Charsets.US_ASCII) != "WAVE") {
                    return WavInfo(false, length, problem = "missing RIFF/WAVE signature")
                }
                raf.seek(20)
                val audioFormat = readU16LE(raf)
                val channels = readU16LE(raf)
                val sampleRate = readU32LE(raf).toInt()
                val byteRate = readU32LE(raf)
                val blockAlign = readU16LE(raf)
                val bits = readU16LE(raf)
                raf.seek(40)
                val dataBytes = readU32LE(raf)
                val expectedByteRate = sampleRate.toLong() * channels * (bits / 8)
                val expectedBlockAlign = channels * (bits / 8)
                val expectedFileLength = 44L + dataBytes
                var anyNonZeroSample = false
                val bytesToScan = minOf(dataBytes, length - 44L)
                raf.seek(44)
                var remaining = bytesToScan
                val scan = ByteArray(8192)
                while (remaining > 0 && !anyNonZeroSample) {
                    val n = raf.read(scan, 0, minOf(scan.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    for (i in 0 until n) if (scan[i].toInt() != 0) { anyNonZeroSample = true; break }
                    remaining -= n
                }
                val valid = audioFormat == 1 && channels == 1 && sampleRate in 8_000..192_000 && bits == 16 &&
                    byteRate == expectedByteRate && blockAlign == expectedBlockAlign && dataBytes > 0 &&
                    dataBytes % expectedBlockAlign.coerceAtLeast(1) == 0L && expectedFileLength == length && anyNonZeroSample
                val problem = when {
                    audioFormat != 1 -> "WAV encoding is not PCM"
                    channels != 1 -> "expected mono WAV but found $channels channels"
                    bits != 16 -> "expected 16-bit PCM"
                    dataBytes <= 0 || length <= 44L -> "WAV has no audio payload"
                    expectedFileLength != length -> "WAV data length does not match file length"
                    dataBytes % expectedBlockAlign.coerceAtLeast(1) != 0L -> "WAV payload is not aligned to complete PCM samples"
                    !anyNonZeroSample -> "WAV audio payload is all zero"
                    else -> "invalid PCM WAV header"
                }
                WavInfo(valid, length, sampleRate, channels, bits, dataBytes, if (valid) null else problem)
            }
        } catch (e: Exception) {
            WavInfo(false, length, problem = "WAV header read failed: ${e.javaClass.simpleName}")
        }
    }

    fun logWav(file: File, source: String) {
        val wav = inspectWav(file)
        Log.d(TAG, "$source WAV exists=${file.exists()} size=${wav.sizeBytes} valid=${wav.valid} sampleRate=${wav.sampleRate} channels=${wav.channels} bits=${wav.bitsPerSample} dataBytes=${wav.dataBytes} problem=${wav.problem}")
    }

    fun messageFor(error: Throwable): String {
        val chain = generateSequence(error) { it.cause }.toList()
        val http = chain.filterIsInstance<SttHttpException>().firstOrNull()
        if (http != null) {
            val detail = safeServerDetail(http.responseBody).take(240).replace(Regex("[\\r\\n\\t]+"), " ")
            val mapped = when (http.statusCode) {
                401 -> "مفتاح API غير صحيح أو غير مصرح به (HTTP 401)"
                403 -> "رفض المزود الطلب (HTTP 403؛ تحقق من صلاحيات الحساب أو الحجب الشبكي)"
                404 -> "نقطة الخدمة أو النموذج غير متاح (HTTP 404)"
                408 -> "انتهت مهلة الطلب (HTTP 408)"
                413 -> "حجم الملف الصوتي أكبر من المسموح (HTTP 413)"
                415 -> "صيغة الملف الصوتي غير مدعومة (HTTP 415)"
                422 -> "المزود رفض بيانات الصوت أو إعدادات اللغة (HTTP 422)"
                429 -> "تجاوزت حد الطلبات أو الحصة لدى المزود (HTTP 429)"
                in 500..599 -> "تعطل مؤقت في خادم المزود (HTTP ${http.statusCode})"
                else -> "رفض المزود الطلب (HTTP ${http.statusCode})"
            }
            return if (detail.isBlank()) mapped else "$mapped: $detail"
        }
        if (chain.any { it is SocketTimeoutException }) return "انتهت مهلة الاتصال بخدمة تحويل الصوت. جرّب شبكة أخرى أو أعد المحاولة."
        if (chain.any { it is UnknownHostException }) return "تعذر العثور على خادم المزود (DNS). تحقق من VPN أو DNS والشبكة."
        if (chain.any { it is ConnectException }) return "رفض الاتصال بخادم المزود. تحقق من الشبكة أو VPN."
        if (chain.any { it is SSLException }) return "فشل اتصال HTTPS الآمن. تحقق من وقت الجهاز أو VPN أو اعتراض الاتصال."
        val message = error.message?.takeIf { it.isNotBlank() }
        return message ?: "حدث خطأ غير معروف أثناء تحويل الصوت (${error.javaClass.simpleName})."
    }

    fun safeServerDetail(body: String): String {
        if (body.isBlank()) return ""
        return body
            .replace(Regex("""(?i)(authorization\s*[:=]\s*(?:bearer|token)\s+)[^\s"']+""")) { it.groupValues[1] + "[REDACTED]" }
            .replace(Regex("""(?i)(api[_-]?key|token|secret)("?\s*[:=]\s*"?)[^\s,}"]+""")) { it.groupValues[1] + it.groupValues[2] + "[REDACTED]" }
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(400)
    }

    fun safeMessage(message: String?): String = safeServerDetail(message.orEmpty()).ifBlank { "no detail" }

    fun isRetryableHttp(statusCode: Int): Boolean = statusCode == 408 || statusCode == 429 || statusCode in 500..599

    fun retryAfterMillis(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return it.coerceIn(0L, 86_400L) * 1_000L }
        return runCatching {
            val whenRetry = java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (whenRetry - System.currentTimeMillis()).coerceAtLeast(0L)
        }.getOrNull()
    }

    private fun readU16LE(raf: RandomAccessFile): Int {
        val a = raf.read(); val b = raf.read()
        if (a < 0 || b < 0) throw java.io.EOFException()
        return a or (b shl 8)
    }

    private fun readU32LE(raf: RandomAccessFile): Long {
        val a = readU16LE(raf).toLong()
        val b = readU16LE(raf).toLong()
        return a or (b shl 16)
    }
}

internal class SttHttpException(
    val statusCode: Int,
    val responseBody: String,
    private val provider: String,
    val retryAfterMs: Long? = null
) : java.io.IOException("$provider HTTP $statusCode: ${responseBody.take(240)}")
