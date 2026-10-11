package com.sari.subtitle

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioChunker(
    private val context: Context,
    private val onChunkReady: ((File) -> Unit)? = null
) {

    companion object {
        private const val TAG = "SariAudioChunker"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_COUNT = 1
        const val BYTES_PER_SAMPLE = 2
        const val CHUNK_DURATION_SECONDS = 10
        const val OVERLAP_DURATION_SECONDS = 1.5
        private const val VAD_RMS_THRESHOLD = 0.008
        private const val MIN_SPEECH_RATIO = 0.10
        private const val MAX_BUFFER_SECONDS = 30
    }

    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val audioBuffer = ByteArrayOutputStream()
    private val chunkBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_DURATION_SECONDS
    private val overlapBytes = (SAMPLE_RATE * BYTES_PER_SAMPLE * OVERLAP_DURATION_SECONDS).toInt()
    private val maxBufferBytes = SAMPLE_RATE * BYTES_PER_SAMPLE * MAX_BUFFER_SECONDS

    init {
        try {
            require(CHUNK_DURATION_SECONDS >= 10) { "Groq audio chunks must be at least 10 seconds" }
            require(overlapBytes < chunkBytes) { "Overlap must be shorter than the chunk" }
            Log.d(TAG, "Initialized: sampleRate=$SAMPLE_RATE, chunkSeconds=$CHUNK_DURATION_SECONDS, overlapSeconds=$OVERLAP_DURATION_SECONDS")
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            throw e
        }
    }

    fun appendAudioData(data: ByteArray) {
        if (closed.get()) {
            Log.w(TAG, "Ignoring audio because chunker is closed")
            return
        }
        if (data.isEmpty()) return
        synchronized(lock) {
            try {
                if (closed.get()) return
                val validLength = data.size - (data.size % 2)
                if (validLength <= 0) return
                audioBuffer.write(data, 0, validLength)
                Log.d(TAG, "Audio received: $validLength bytes; buffer=${audioBuffer.size()}")
                while (audioBuffer.size() >= chunkBytes) {
                    val allBytes = audioBuffer.toByteArray()
                    val chunk = allBytes.copyOfRange(0, chunkBytes)
                    val remainingStart = chunkBytes - overlapBytes
                    val remaining = allBytes.copyOfRange(remainingStart, allBytes.size)
                    audioBuffer.reset()
                    audioBuffer.write(remaining)
                    processChunk(chunk)
                }
                if (audioBuffer.size() > maxBufferBytes) {
                    Log.w(TAG, "Audio buffer exceeded limit; trimming oldest data")
                    val bytes = audioBuffer.toByteArray()
                    val keepFrom = bytes.size - maxBufferBytes
                    audioBuffer.reset()
                    audioBuffer.write(bytes, keepFrom + (keepFrom % 2), bytes.size - keepFrom - (keepFrom % 2))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to append audio data", e)
            }
        }
    }

    private fun processChunk(pcm: ByteArray) {
        try {
            if (pcm.isEmpty()) return
            val vadResult = detectSpeech(pcm)
            Log.d(TAG, "VAD: rms=${vadResult.rms}, speechRatio=${vadResult.speechRatio}, speech=${vadResult.hasSpeech}")
            if (!vadResult.hasSpeech) {
                Log.d(TAG, "Silent chunk skipped")
                return
            }
            val wavFile = writeWavFile(pcm)
            Log.d(TAG, "WAV created: ${wavFile.name}, size=${wavFile.length()}")
            try {
                onChunkReady?.invoke(wavFile)
            } catch (e: Exception) {
                Log.e(TAG, "Chunk callback failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chunk processing failed", e)
        }
    }

    private data class VadResult(val rms: Double, val speechRatio: Double, val hasSpeech: Boolean)

    private fun detectSpeech(pcm: ByteArray): VadResult {
        try {
            val bytesPerFrame = SAMPLE_RATE / 50 * BYTES_PER_SAMPLE
            var frameCount = 0
            var speechFrames = 0
            var totalSquare = 0.0
            var offset = 0
            while (offset + bytesPerFrame <= pcm.size) {
                var frameSquare = 0.0
                var samples = 0
                var i = offset
                while (i + 1 < offset + bytesPerFrame) {
                    val low = pcm[i].toInt() and 0xFF
                    val high = pcm[i + 1].toInt()
                    val sample = ((high shl 8) or low).toShort().toInt()
                    val normalized = sample / 32768.0
                    frameSquare += normalized * normalized
                    totalSquare += normalized * normalized
                    samples++
                    i += 2
                }
                if (samples > 0) {
                    val frameRms = sqrt(frameSquare / samples)
                    if (frameRms >= VAD_RMS_THRESHOLD) speechFrames++
                    frameCount++
                }
                offset += bytesPerFrame
            }
            if (frameCount == 0) return VadResult(0.0, 0.0, false)
            val overallRms = sqrt(totalSquare / (frameCount * (bytesPerFrame / BYTES_PER_SAMPLE)))
            val speechRatio = speechFrames.toDouble() / frameCount.toDouble()
            return VadResult(overallRms, speechRatio, overallRms >= VAD_RMS_THRESHOLD && speechRatio >= MIN_SPEECH_RATIO)
        } catch (e: Exception) {
            Log.e(TAG, "VAD failed", e)
            return VadResult(0.0, 0.0, false)
        }
    }

    private fun writeWavFile(pcm: ByteArray): File {
        val directory = File(context.cacheDir, "audio_chunks")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create audio chunk directory")
        }
        val wavFile = File.createTempFile("sari_chunk_", ".wav", directory)
        try {
            FileOutputStream(wavFile).use { output ->
                writeWavHeader(output, pcm.size)
                output.write(pcm)
                output.flush()
            }
            return wavFile
        } catch (e: Exception) {
            try { wavFile.delete() } catch (_: Exception) {}
            throw e
        }
    }

    private fun writeWavHeader(output: FileOutputStream, pcmLength: Int) {
        val byteRate = SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE
        val blockAlign = CHANNEL_COUNT * BYTES_PER_SAMPLE
        val totalLength = 36 + pcmLength
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeIntLE(output, totalLength)
        output.write("WAVE".toByteArray(Charsets.US_ASCII))
        output.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeIntLE(output, 16)
        writeShortLE(output, 1)
        writeShortLE(output, CHANNEL_COUNT)
        writeIntLE(output, SAMPLE_RATE)
        writeIntLE(output, byteRate)
        writeShortLE(output, blockAlign)
        writeShortLE(output, 16)
        output.write("data".toByteArray(Charsets.US_ASCII))
        writeIntLE(output, pcmLength)
    }

    private fun writeIntLE(output: FileOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
        output.write((value shr 16) and 0xFF)
        output.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(output: FileOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value shr 8) and 0xFF)
    }

    fun reset() {
        synchronized(lock) {
            try {
                audioBuffer.reset()
                Log.d(TAG, "Audio buffer reset")
            } catch (e: Exception) {
                Log.e(TAG, "Reset failed", e)
            }
        }
    }

    fun close() {
        try {
            closed.set(true)
            synchronized(lock) { audioBuffer.reset() }
            Log.d(TAG, "AudioChunker closed")
        } catch (e: Exception) {
            Log.e(TAG, "Close failed", e)
        }
    }
}
