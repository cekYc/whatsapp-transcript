package dev.sesyazi.app.transcription

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import dev.sesyazi.app.audio.DecodedAudio
import dev.sesyazi.app.model.ModelManager
import java.io.Closeable
import java.io.File
import kotlin.math.ceil

open class TranscriptionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class ModelInitializationException(message: String, cause: Throwable? = null) :
    TranscriptionException(message, cause)

class WhisperTranscriber(modelDirectory: File) : Closeable {
    private val recognizer: OfflineRecognizer

    init {
        val encoder = File(modelDirectory, ModelManager.ENCODER_FILE)
        val decoder = File(modelDirectory, ModelManager.DECODER_FILE)
        val tokens = File(modelDirectory, ModelManager.TOKENS_FILE)
        if (!encoder.isFile || !decoder.isFile || !tokens.isFile) {
            throw ModelInitializationException("Konuşma modeli eksik. Modeli yeniden indir.")
        }

        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
        val modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                language = "tr",
                task = "transcribe",
                tailPaddings = 800,
            ),
            numThreads = threads,
            debug = false,
            provider = "cpu",
            modelType = "whisper",
            tokens = tokens.absolutePath,
        )
        recognizer = try {
            OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = TARGET_SAMPLE_RATE,
                        featureDim = 80,
                        dither = 0f,
                    ),
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search",
                ),
            )
        } catch (error: Throwable) {
            throw ModelInitializationException("Konuşma modeli cihazda başlatılamadı.", error)
        }
    }

    fun transcribe(audio: DecodedAudio, onProgress: (Int) -> Unit): String {
        require(audio.sampleRate == TARGET_SAMPLE_RATE)
        val samples = audio.samples
        val chunkSize = CHUNK_SECONDS * TARGET_SAMPLE_RATE
        val overlapSize = OVERLAP_MILLISECONDS * TARGET_SAMPLE_RATE / 1_000
        val stride = chunkSize - overlapSize
        val chunkCount = if (samples.size <= chunkSize) {
            1
        } else {
            1 + ceil((samples.size - chunkSize).toDouble() / stride).toInt()
        }

        var transcript = ""
        var chunkIndex = 0
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + chunkSize, samples.size)
            val chunk = samples.copyOfRange(start, end)
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(chunk, TARGET_SAMPLE_RATE)
                recognizer.decode(stream)
                val part = recognizer.getResult(stream).text.trim()
                transcript = TranscriptMerger.merge(transcript, part)
            } catch (error: Throwable) {
                throw TranscriptionException("Sesin bir bölümü metne çevrilemedi.", error)
            } finally {
                stream.release()
            }

            chunkIndex++
            onProgress((chunkIndex * 100 / chunkCount).coerceIn(0, 100))
            if (end == samples.size) break
            start += stride
        }

        if (transcript.isBlank()) {
            throw TranscriptionException(
                "Bu kayıtta anlaşılır bir Türkçe konuşma algılanamadı.",
            )
        }
        return transcript.trim()
    }

    override fun close() {
        recognizer.release()
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val CHUNK_SECONDS = 28
        const val OVERLAP_MILLISECONDS = 750
    }
}
