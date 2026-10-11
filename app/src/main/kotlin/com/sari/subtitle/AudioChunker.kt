package com.sari.subtitle

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import kotlin.math.max

/**
 * Collects 16 kHz mono PCM16 samples and writes complete three-second WAV chunks.
 * Instances are intended to be owned by the capture worker thread.
 */
class AudioChunker(context: Context, private val sampleRate: Int = 16_000) {
    private val chunkSamples = sampleRate * CHUNK_MS.toInt() / 1000
    private val pending = ArrayDeque<File>()
    private val buffer = ShortArray(chunkSamples)
    private var count = 0
    private val outputDir = File(context.cacheDir, "audio_chunks").apply { mkdirs() }

    @Synchronized
    fun addSamples(samples: ShortArray, count: Int) {
        val safeCount = count.coerceIn(0, samples.size)
        var offset = 0
        while (offset < safeCount) {
            val copied = minOf(chunkSamples - this.count, safeCount - offset)
            System.arraycopy(samples, offset, buffer, this.count, copied)
            this.count += copied
            offset += copied
            if (this.count == chunkSamples) {
                val chunkFile = File(outputDir, "chunk_${System.nanoTime()}.wav")
                writeWav(chunkFile, buffer, this.count)
                var energy = 0.0
                for (i in 0 until this.count) {
                    val sample = buffer[i].toDouble()
                    energy += sample * sample
                }
                val rms = kotlin.math.sqrt(energy / this.count)
                if (rms >= 700.0) pending.addLast(chunkFile) else chunkFile.delete()
                this.count = 0
            }
        }
    }

    @Synchronized fun hasChunkReady(): Boolean = pending.isNotEmpty()

    @Synchronized
    fun getNextChunk(): File {
        check(pending.isNotEmpty()) { "No complete audio chunk is ready." }
        return pending.removeFirst()
    }

    private fun writeWav(file: File, samples: ShortArray, sampleCount: Int) {
        val dataBytes = sampleCount * 2
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 36 + dataBytes)
            out.write("WAVE".toByteArray(Charsets.US_ASCII))
            out.write("fmt ".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, 16)
            writeShortLE(out, 1)
            writeShortLE(out, 1)
            writeIntLE(out, sampleRate)
            writeIntLE(out, sampleRate * 2)
            writeShortLE(out, 2)
            writeShortLE(out, 16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeIntLE(out, dataBytes)
            for (i in 0 until sampleCount) writeShortLE(out, samples[i].toInt())
        }
    }

    private fun writeIntLE(out: FileOutputStream, value: Int) {
        out.write(value and 0xff); out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff); out.write((value ushr 24) and 0xff)
    }
    private fun writeShortLE(out: FileOutputStream, value: Int) {
        out.write(value and 0xff); out.write((value ushr 8) and 0xff)
    }

    companion object {
        const val CHUNK_MS = 3_000L
        const val OVERLAP_MS = 500L
        const val STEP_MS = CHUNK_MS - OVERLAP_MS
        fun chunkCount(durationMs: Long): Int = if (durationMs <= 0) 0 else max(1, kotlin.math.ceil((durationMs - OVERLAP_MS).toDouble() / STEP_MS).toInt())
        fun startMs(index: Int): Long = index * STEP_MS
    }
}

class TranscriptDeduplicator(private val similarityThreshold: Double = 0.82) {
    private var previous = ""
    @Synchronized fun shouldTranslate(candidate: String): Boolean {
        val cleaned = candidate.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (cleaned.isBlank()) return false
        val old = previous
        if (old.isNotBlank() && similarity(old, cleaned) >= similarityThreshold) return false
        previous = cleaned
        return true
    }
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val aa = a.split(" ").filter { it.isNotBlank() }.toSet()
        val bb = b.split(" ").filter { it.isNotBlank() }.toSet()
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return 2.0 * aa.intersect(bb).size / (aa.size + bb.size)
    }
}
