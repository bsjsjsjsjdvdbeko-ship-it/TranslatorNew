package com.sari.subtitle

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import java.io.FileOutputStream

class SttDiagnosticsTest {
    @Test fun retryPolicyRetriesOnlyTransientStatuses() {
        assertFalse(SttDiagnostics.isRetryableHttp(400))
        assertFalse(SttDiagnostics.isRetryableHttp(401))
        assertFalse(SttDiagnostics.isRetryableHttp(403))
        assertFalse(SttDiagnostics.isRetryableHttp(422))
        assertTrue(SttDiagnostics.isRetryableHttp(429))
        assertTrue(SttDiagnostics.isRetryableHttp(500))
    }

    @Test fun retryAfterSupportsSeconds() {
        kotlin.test.assertEquals(2_000L, SttDiagnostics.retryAfterMillis("2"))
    }

    @Test fun httpErrorsAreClassifiedAndDoNotLeakSecrets() {
        val message = SttDiagnostics.messageFor(SttHttpException(401, "{\"error\":\"invalid_api_key\",\"api_key\":\"sk-secret-value\"}", "Groq"))
        assertContains(message, "HTTP 401")
        assertFalse(message.contains("sk-secret-value"))
    }

    @Test fun wavInspectorRejectsHeaderOnlyAndAllZeroAudio() {
        val headerOnly = File.createTempFile("sari-header", ".wav")
        val silent = File.createTempFile("sari-silent", ".wav")
        try {
            writeWav(headerOnly, ByteArray(0))
            writeWav(silent, ByteArray(320) { 0 })
            assertFalse(SttDiagnostics.inspectWav(headerOnly).valid)
            assertFalse(SttDiagnostics.inspectWav(silent).valid)
            assertContains(SttDiagnostics.inspectWav(silent).problem.orEmpty(), "zero")
        } finally { headerOnly.delete(); silent.delete() }
    }

    @Test fun wavInspectorAcceptsNonSilentMonoPcm16() {
        val file = File.createTempFile("sari-valid", ".wav")
        try {
            writeWav(file, ByteArray(320).also { it[0] = 1 })
            val info = SttDiagnostics.inspectWav(file)
            assertTrue(info.valid, info.problem ?: "invalid")
            assertTrue(info.sizeBytes > 44)
            assertTrue(info.dataBytes == 320L)
        } finally { file.delete() }
    }

    private fun writeWav(file: File, data: ByteArray) {
        FileOutputStream(file).use { out ->
            fun intLE(v: Int) { out.write(v and 255); out.write((v ushr 8) and 255); out.write((v ushr 16) and 255); out.write((v ushr 24) and 255) }
            fun shortLE(v: Int) { out.write(v and 255); out.write((v ushr 8) and 255) }
            out.write("RIFF".toByteArray()); intLE(36 + data.size)
            out.write("WAVEfmt ".toByteArray()); intLE(16); shortLE(1); shortLE(1)
            intLE(16_000); intLE(32_000); shortLE(2); shortLE(16)
            out.write("data".toByteArray()); intLE(data.size); out.write(data)
        }
    }
}
