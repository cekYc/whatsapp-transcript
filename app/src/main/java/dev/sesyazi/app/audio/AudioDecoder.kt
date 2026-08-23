package dev.sesyazi.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

data class DecodedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationSeconds: Float,
)

class AudioDecodeException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class AudioDecoder {
    fun decode(file: File): DecodedAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null

        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                throw AudioDecodeException("Paylaşılan dosyada okunabilir bir ses kanalı bulunamadı.")
            }

            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: throw AudioDecodeException("Ses dosyasının biçimi belirlenemedi.")

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            var sampleRate = sourceFormat.integerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var channelCount = sourceFormat.integerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = StreamingLinearResampler(sampleRate, TARGET_SAMPLE_RATE, MAX_SAMPLES)

            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw AudioDecodeException("Ses çözücü giriş belleği oluşturamadı.")
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        val newRate = outputFormat.integerOrDefault(
                            MediaFormat.KEY_SAMPLE_RATE,
                            sampleRate,
                        )
                        if (resampler.inputRate != newRate && resampler.hasSamples) {
                            throw AudioDecodeException("Ses örnekleme hızı işlem sırasında değişti.")
                        }
                        sampleRate = newRate
                        channelCount = outputFormat.integerOrDefault(
                            MediaFormat.KEY_CHANNEL_COUNT,
                            channelCount,
                        ).coerceAtLeast(1)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        if (!resampler.hasSamples) {
                            resampler = StreamingLinearResampler(
                                sampleRate,
                                TARGET_SAMPLE_RATE,
                                MAX_SAMPLES,
                            )
                        }
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: throw AudioDecodeException("Ses çözücü çıkış belleği oluşturamadı.")
                            val monoSamples = decodePcm(
                                outputBuffer = outputBuffer,
                                info = info,
                                encoding = pcmEncoding,
                                channels = channelCount,
                            )
                            resampler.append(monoSamples)
                        }

                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            val samples = resampler.finish()
            if (samples.size < TARGET_SAMPLE_RATE / 5) {
                throw AudioDecodeException("Ses mesajı metne çevrilemeyecek kadar kısa veya sessiz.")
            }
            return DecodedAudio(
                samples = samples,
                sampleRate = TARGET_SAMPLE_RATE,
                durationSeconds = samples.size.toFloat() / TARGET_SAMPLE_RATE,
            )
        } catch (error: AudioDecodeException) {
            throw error
        } catch (error: Exception) {
            throw AudioDecodeException(
                "Bu ses biçimi cihaz tarafından çözülemedi. WhatsApp mesajını yeniden paylaşmayı dene.",
                error,
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            extractor.release()
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return index
        }
        return -1
    }

    private fun decodePcm(
        outputBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        encoding: Int,
        channels: Int,
    ): FloatArray {
        val bytes = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            position(info.offset)
            limit(info.offset + info.size)
        }.slice().order(ByteOrder.LITTLE_ENDIAN)

        return when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> downmix16Bit(bytes, channels)
            AudioFormat.ENCODING_PCM_FLOAT -> downmixFloat(bytes, channels)
            AudioFormat.ENCODING_PCM_8BIT -> downmix8Bit(bytes, channels)
            else -> throw AudioDecodeException("Cihazın ürettiği PCM ses biçimi desteklenmiyor ($encoding).")
        }
    }

    private fun downmix16Bit(bytes: ByteBuffer, channels: Int): FloatArray {
        val shorts = bytes.asShortBuffer()
        val frameCount = shorts.remaining() / channels
        return FloatArray(frameCount) { frame ->
            var sum = 0f
            repeat(channels) { channel ->
                sum += shorts.get(frame * channels + channel) / 32768f
            }
            sum / channels
        }
    }

    private fun downmixFloat(bytes: ByteBuffer, channels: Int): FloatArray {
        val floats = bytes.asFloatBuffer()
        val frameCount = floats.remaining() / channels
        return FloatArray(frameCount) { frame ->
            var sum = 0f
            repeat(channels) { channel ->
                sum += floats.get(frame * channels + channel)
            }
            (sum / channels).coerceIn(-1f, 1f)
        }
    }

    private fun downmix8Bit(bytes: ByteBuffer, channels: Int): FloatArray {
        val frameCount = bytes.remaining() / channels
        return FloatArray(frameCount) { frame ->
            var sum = 0f
            repeat(channels) { channel ->
                val unsigned = bytes.get(frame * channels + channel).toInt() and 0xff
                sum += (unsigned - 128) / 128f
            }
            sum / channels
        }
    }

    private fun MediaFormat.integerOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        private const val MAX_AUDIO_MINUTES = 15
        private const val MAX_SAMPLES = TARGET_SAMPLE_RATE * 60 * MAX_AUDIO_MINUTES
        private const val CODEC_TIMEOUT_US = 10_000L
    }
}

internal class StreamingLinearResampler(
    val inputRate: Int,
    private val outputRate: Int,
    maxOutputSamples: Int,
) {
    private val output = FloatArrayBuilder(maxOutputSamples)
    private val step = inputRate.toDouble() / outputRate.toDouble()
    private var nextInputPosition = 0.0
    private var totalInputSamples = 0L
    private var previousSample = 0f

    val hasSamples: Boolean
        get() = totalInputSamples > 0L

    fun append(input: FloatArray) {
        if (input.isEmpty()) return
        require(inputRate > 0 && outputRate > 0)

        if (inputRate == outputRate) {
            output.append(input)
            totalInputSamples += input.size
            previousSample = input.last()
            return
        }

        val chunkStart = totalInputSamples
        val chunkEnd = chunkStart + input.size
        while (true) {
            val low = floor(nextInputPosition).toLong()
            val fraction = nextInputPosition - low
            val high = low + 1L
            val canReadLow = low >= chunkStart - 1L && low < chunkEnd
            val canInterpolate = fraction < EPSILON || high < chunkEnd
            if (!canReadLow || !canInterpolate) break

            val lowValue = sampleAt(low, chunkStart, input)
            val value = if (fraction < EPSILON) {
                lowValue
            } else {
                val highValue = sampleAt(high, chunkStart, input)
                (lowValue + (highValue - lowValue) * fraction).toFloat()
            }
            output.append(value)
            nextInputPosition += step
        }

        totalInputSamples = chunkEnd
        previousSample = input.last()
    }

    fun finish(): FloatArray = output.toArray()

    private fun sampleAt(index: Long, chunkStart: Long, input: FloatArray): Float {
        return if (index == chunkStart - 1L) {
            previousSample
        } else {
            input[(index - chunkStart).toInt()]
        }
    }

    private companion object {
        const val EPSILON = 1e-9
    }
}

private class FloatArrayBuilder(private val maxSize: Int) {
    private var values = FloatArray(16_384)
    private var length = 0

    fun append(value: Float) {
        ensureCapacity(length + 1)
        values[length++] = value
    }

    fun append(newValues: FloatArray) {
        ensureCapacity(length + newValues.size)
        newValues.copyInto(values, destinationOffset = length)
        length += newValues.size
    }

    fun toArray(): FloatArray = values.copyOf(length)

    private fun ensureCapacity(required: Int) {
        if (required > maxSize) {
            throw AudioDecodeException("Ses mesajı 15 dakikalık güvenli işlem sınırını aşıyor.")
        }
        if (required <= values.size) return
        var nextSize = values.size
        while (nextSize < required) {
            nextSize = (nextSize * 2).coerceAtMost(maxSize)
            if (nextSize == values.size) break
        }
        values = values.copyOf(nextSize)
    }
}
