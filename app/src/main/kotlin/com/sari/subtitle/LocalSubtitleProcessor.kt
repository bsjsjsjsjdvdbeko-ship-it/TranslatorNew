package com.sari.subtitle

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** Offline-first preparation pipeline: decode media audio, transcribe five-minute chunks, translate timed segments, export SRT. */
class LocalSubtitleProcessor(private val context: Context) {
    suspend fun prepare(uri: Uri, onProgress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val settings = SettingsRepository(context).load()
        val work = File(context.cacheDir, "subtitle_work").apply { mkdirs() }
        val wav = File(work, "source_${System.currentTimeMillis()}.wav")
        val chunks = mutableListOf<File>()
        try {
            onProgress("استخراج الصوت من الملف…")
            val mediaAudioOffsetMs = MediaAudioDecoder(context).decodeToWav(uri, wav) { bytes -> onProgress("استخراج الصوت… ${(bytes / 1024 / 1024)} MB") }
            val dataBytes = (wav.length() - 44).coerceAtLeast(0)
            val bytesPerSecond = 16000L * 2L
            val chunkBytes = 5L * 60L * bytesPerSecond
            var offset = 0L
            var index = 0
            while (offset < dataBytes) {
                val length = minOf(chunkBytes, dataBytes - offset)
                val part = File(work, "chunk_${index}.wav")
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(0); out.write(ByteArray(44))
                    RandomAccessFile(wav, "r").use { input ->
                        input.seek(44 + offset)
                        val buffer = ByteArray(64 * 1024)
                        var remaining = length
                        while (remaining > 0) {
                            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            out.write(buffer, 0, n); remaining -= n
                        }
                    }
                    writeWavHeader(out, length)
                }
                chunks += part
                offset += length; index++
            }
            require(chunks.isNotEmpty()) { "لم يتم العثور على صوت قابل للمعالجة." }
            val cues = mutableListOf<SubtitleCue>()
            val translator = createTranslator(settings)
            var baseMs = mediaAudioOffsetMs
            chunks.forEachIndexed { chunkIndex, part ->
                onProgress("التعرّف على الكلام: الجزء ${chunkIndex + 1} من ${chunks.size}…")
                val duration = ((part.length() - 44).coerceAtLeast(0) / 32L)
                val result = transcribe(part, settings)
                if (result.text.isNotBlank() && result.segments.isEmpty()) {
                    error("مزود التعرّف أعاد النص بلا توقيتات مقاطع. لن أنشئ ترجمة تقريبية؛ اختر Groq Whisper أو Deepgram Nova-3 أو مزوداً يعيد توقيتات.")
                }
                val segments = result.segments
                segments.forEach { segment ->
                    val original = segment.text.trim()
                    if (original.isBlank()) return@forEach
                    onProgress("ترجمة الجزء ${chunkIndex + 1}/${chunks.size}…")
                    val quality = when (settings.translationQuality) {
                        SettingsRepository.QUALITY_FAST -> TranslationQuality.FAST
                        SettingsRepository.QUALITY_BALANCED -> TranslationQuality.BALANCED
                        else -> TranslationQuality.HUMAN
                    }
                    val source = settings.sourceLanguage.ifBlank { "auto" }
                    val target = settings.targetLanguage.ifBlank { "ar" }
                    val translated = if (settings.provider == SettingsRepository.PROVIDER_OMNIROUTE) {
                        OmniRouteClient(settings.omniRouteBaseUrl, settings.omniRouteKey).translate(
                            original, source, target, settings.omniRouteTranslationModel, TranslationContext(), quality
                        )
                    } else translator.translateWithContext(original, source, target, TranslationContext(), quality).trim()
                    if (translated.isNotBlank()) {
                        val start = baseMs + segment.startMs.toLong().coerceAtLeast(0L)
                        val end = baseMs + segment.endMs.toLong().coerceAtLeast(segment.startMs.toLong() + 500L)
                        cues += SubtitleCue(original, translated, start, end.coerceAtLeast(start + 700L))
                    }
                }
                baseMs += duration
            }
            require(cues.isNotEmpty()) { "لم ينتج عن المعالجة أي مقاطع ترجمة. تحقق من الصوت ومفتاح STT." }
            val name = "SariSubtitle_${System.currentTimeMillis()}"
            onProgress("حفظ ملف الترجمة…")
            SrtExporter.export(context, cues.sortedBy { it.startElapsedMs }, name)
        } finally {
            wav.delete(); chunks.forEach { it.delete() }
        }
    }

    private suspend fun transcribe(file: File, settings: AppSettings): TranscriptResult {
        val prompt = "[HIGH_ACCURACY]"
        if (settings.provider == SettingsRepository.PROVIDER_OMNIROUTE) {
            return OmniRouteClient(settings.omniRouteBaseUrl, settings.omniRouteKey).transcribe(
                file, settings.omniRouteSttModels.split(',').map { it.trim() }, settings.speechLanguage, prompt
            )
        }
        return when (SttProviderPolicy.normalize(settings.sttProvider)) {
            "deepgram-nova3" -> DeepgramClient(settings.deepgramKey, model = "nova-3").transcribe(file, settings.speechLanguage, prompt)
            "deepgram-nova2" -> DeepgramClient(settings.deepgramKey, model = "nova-2").transcribe(file, settings.speechLanguage, prompt)
            "groq" -> WhisperClient(settings.speechKey).transcribeDetailed(file, settings.speechLanguage, prompt)
            "vosk" -> {
                val root = File(context.filesDir, "vosk-models")
                val model = root.listFiles()?.firstOrNull { it.isDirectory && (it.name.startsWith("vosk-model-ar-mgb2") || File(it, "am").exists()) }
                    ?: error("نموذج Vosk غير موجود. حمّله من إعدادات STT أو اختر مزوداً سحابياً.")
                VoskClient(model).transcribe(file, settings.speechLanguage, prompt)
            }
            else -> error("مزود STT غير معروف: ${settings.sttProvider}")
        }
    }

    private fun createTranslator(s: AppSettings): TranslationClient {
        val provider = when (s.provider) {
            SettingsRepository.PROVIDER_GROQ -> TranslationProvider.GROQ
            SettingsRepository.PROVIDER_GOOGLE -> TranslationProvider.GOOGLE
            SettingsRepository.PROVIDER_OPENAI -> TranslationProvider.OPENAI
            SettingsRepository.PROVIDER_OMNIROUTE -> TranslationProvider.OMNIROUTE
            else -> TranslationProvider.MYMEMORY
        }
        val key = when (provider) {
            TranslationProvider.GROQ -> s.speechKey
            TranslationProvider.OPENAI -> s.openAiKey.ifBlank { s.translationKey }
            TranslationProvider.OMNIROUTE -> s.omniRouteKey
            else -> s.translationKey
        }
        return TranslationClient(provider, key, s.myMemoryEmail)
    }

    private fun writeWavHeader(file: RandomAccessFile, size: Long) {
        file.seek(0); file.writeBytes("RIFF"); file.writeIntLE((36 + size).toInt()); file.writeBytes("WAVE")
        file.writeBytes("fmt "); file.writeIntLE(16); file.writeShortLE(1); file.writeShortLE(1)
        file.writeIntLE(16000); file.writeIntLE(32000); file.writeShortLE(2); file.writeShortLE(16)
        file.writeBytes("data"); file.writeIntLE(size.toInt())
    }
    private fun RandomAccessFile.writeIntLE(v: Int) { write(v and 255); write((v shr 8) and 255); write((v shr 16) and 255); write((v shr 24) and 255) }
    private fun RandomAccessFile.writeShortLE(v: Int) { write(v and 255); write((v shr 8) and 255) }
}
