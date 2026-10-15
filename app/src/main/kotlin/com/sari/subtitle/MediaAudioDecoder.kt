package com.sari.subtitle

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

/** Decodes the audio track of a user-selected media URI to mono 16 kHz PCM WAV. */
class MediaAudioDecoder(private val context: Context) {
    fun decodeToWav(uri: Uri, output: File, onProgress: (Long) -> Unit = {}): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                extractor.setDataSource(uri.toString())
            } else {
                extractor.setDataSource(context, uri, null)
            }
            var audioTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) { audioTrack = i; audioFormat = format; break }
            }
            require(audioTrack >= 0) { "لم أجد مساراً صوتياً داخل الملف المحدد." }
            val selectedAudioFormat = audioFormat ?: error("لم أجد تنسيقاً لمسار الصوت.")
            extractor.selectTrack(audioTrack)
            val mime = selectedAudioFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(selectedAudioFormat, null, null, 0)
            codec.start()
            output.parentFile?.mkdirs()
            var firstPresentationTimeUs = -1L
            RandomAccessFile(output, "rw").use { wav ->
                wav.setLength(0)
                wav.write(ByteArray(44))
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var outRate = selectedAudioFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44100)
                var outChannels = selectedAudioFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
                var bytesWritten = 0L
                var lastProgress = 0L
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buffer = codec.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            outRate = f.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, outRate)
                            outChannels = f.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, outChannels)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outIndex)
                            if (buffer != null && info.size > 0) {
                                if (firstPresentationTimeUs < 0L) firstPresentationTimeUs = info.presentationTimeUs
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val pcm = ByteArray(info.size); buffer.get(pcm)
                                val normalized = toMono16k(pcm, outRate, outChannels)
                                wav.seek(wav.length()); wav.write(normalized); bytesWritten += normalized.size
                                if (bytesWritten - lastProgress > 1_600_000) { onProgress(bytesWritten); lastProgress = bytesWritten }
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
                require(bytesWritten > 0) { "تعذر استخراج صوت قابل للقراءة من الملف." }
                writeWavHeader(wav, bytesWritten)
            }
            return (firstPresentationTimeUs.coerceAtLeast(0L) / 1000L)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
        }
    }

    private fun toMono16k(bytes: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val frameBytes = (channels.coerceAtLeast(1) * 2)
        val frames = bytes.size / frameBytes
        if (frames == 0) return ByteArray(0)
        val step = sampleRate.coerceAtLeast(1).toDouble() / 16000.0
        val outFrames = kotlin.math.ceil(frames / step).toInt()
        val out = ByteArray(outFrames * 2)
        var o = 0
        var pos = 0.0
        repeat(outFrames) {
            val frame = pos.toInt().coerceAtMost(frames - 1)
            var sum = 0
            for (ch in 0 until channels.coerceAtLeast(1)) {
                val p = frame * frameBytes + ch * 2
                sum += ((bytes[p].toInt() and 0xff) or (bytes[p + 1].toInt() shl 8)).toShort().toInt()
            }
            val sample = (sum / channels.coerceAtLeast(1)).coerceIn(-32768, 32767)
            out[o++] = (sample and 0xff).toByte(); out[o++] = ((sample shr 8) and 0xff).toByte()
            pos += step
        }
        return out
    }

    private fun writeWavHeader(file: RandomAccessFile, dataSize: Long) {
        file.seek(0)
        file.writeBytes("RIFF"); file.writeIntLE((36 + dataSize).toInt()); file.writeBytes("WAVE")
        file.writeBytes("fmt "); file.writeIntLE(16); file.writeShortLE(1); file.writeShortLE(1)
        file.writeIntLE(16000); file.writeIntLE(32000); file.writeShortLE(2); file.writeShortLE(16)
        file.writeBytes("data"); file.writeIntLE(dataSize.toInt())
    }

    private fun RandomAccessFile.writeIntLE(v: Int) { write(v and 255); write((v shr 8) and 255); write((v shr 16) and 255); write((v shr 24) and 255) }
    private fun RandomAccessFile.writeShortLE(v: Int) { write(v and 255); write((v shr 8) and 255) }
    private fun MediaFormat.getIntegerOr(key: String, fallback: Int) = runCatching { getInteger(key) }.getOrDefault(fallback)
}
