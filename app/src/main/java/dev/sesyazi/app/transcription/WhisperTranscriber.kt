package dev.sesyazi.app.transcription

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.sesyazi.app.audio.AudioPreprocessor
import dev.sesyazi.app.audio.DecodedAudio
import dev.sesyazi.app.model.InstalledSpeechModel
import java.io.Closeable
import kotlin.math.ceil

open class TranscriptionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class ModelInitializationException(message: String, cause: Throwable? = null) :
    TranscriptionException(message, cause)

class WhisperTranscriber(private val model: InstalledSpeechModel) : Closeable {
    private val recognizer: OfflineRecognizer
    private val vad: Vad

    init {
        val requiredFiles = listOf(model.encoder, model.decoder, model.tokens, model.vad)
        if (requiredFiles.any { !it.isFile }) {
            throw ModelInitializationException("Konuşma modeli eksik. Modeli yeniden indir.")
        }

        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 4)
        val modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = model.encoder.absolutePath,
                decoder = model.decoder.absolutePath,
                language = "tr",
                task = "transcribe",
                tailPaddings = 800,
            ),
            numThreads = threads,
            debug = false,
            provider = "cpu",
            modelType = "whisper",
            tokens = model.tokens.absolutePath,
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

        vad = try {
            Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = model.vad.absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.4f,
                        minSpeechDuration = 0.3f,
                        windowSize = VAD_WINDOW_SIZE,
                        maxSpeechDuration = 27f,
                    ),
                    sampleRate = TARGET_SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                ),
            )
        } catch (error: Throwable) {
            recognizer.release()
            throw ModelInitializationException("Konuşma algılama modeli cihazda başlatılamadı.", error)
        }
    }

    fun transcribe(audio: DecodedAudio, onProgress: (Int) -> Unit): String {
        require(audio.sampleRate == TARGET_SAMPLE_RATE)
        if (audio.samples.isEmpty()) {
            throw TranscriptionException("Ses kaydı boş görünüyor.")
        }

        val normalized = AudioPreprocessor.normalize(audio.samples)
        val speechChunks = detectSpeech(normalized)
        val chunks = speechChunks.ifEmpty { fixedChunks(normalized) }

        var transcript = ""
        chunks.forEachIndexed { index, chunk ->
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
            onProgress(((index + 1) * 100 / chunks.size).coerceIn(0, 100))
        }

        if (transcript.isBlank()) {
            throw TranscriptionException(
                "Bu kayıtta anlaşılır bir Türkçe konuşma algılanamadı.",
            )
        }
        return transcript.trim()
    }

    private fun detectSpeech(samples: FloatArray): List<FloatArray> {
        vad.reset()
        val chunks = mutableListOf<FloatArray>()
        var offset = 0
        while (offset < samples.size) {
            val window = FloatArray(VAD_WINDOW_SIZE)
            samples.copyInto(
                destination = window,
                destinationOffset = 0,
                startIndex = offset,
                endIndex = minOf(offset + VAD_WINDOW_SIZE, samples.size),
            )
            vad.acceptWaveform(window)
            drainVad(chunks)
            offset += VAD_WINDOW_SIZE
        }
        vad.flush()
        drainVad(chunks)
        return chunks
    }

    private fun drainVad(chunks: MutableList<FloatArray>) {
        while (!vad.empty()) {
            val speech = vad.front().samples
            vad.pop()
            if (speech.size >= MIN_SPEECH_SAMPLES) {
                chunks += AudioPreprocessor.padWithSilence(speech, SPEECH_PADDING_SAMPLES)
            }
        }
    }

    private fun fixedChunks(samples: FloatArray): List<FloatArray> {
        val chunkSize = CHUNK_SECONDS * TARGET_SAMPLE_RATE
        if (samples.size <= chunkSize) return listOf(samples)

        val overlapSize = OVERLAP_MILLISECONDS * TARGET_SAMPLE_RATE / 1_000
        val stride = chunkSize - overlapSize
        val chunkCount = 1 + ceil((samples.size - chunkSize).toDouble() / stride).toInt()
        return buildList(chunkCount) {
            var start = 0
            while (start < samples.size) {
                val end = minOf(start + chunkSize, samples.size)
                add(samples.copyOfRange(start, end))
                if (end == samples.size) break
                start += stride
            }
        }
    }

    override fun close() {
        vad.release()
        recognizer.release()
    }

    private companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        const val VAD_WINDOW_SIZE = 512
        const val MIN_SPEECH_SAMPLES = TARGET_SAMPLE_RATE / 4
        const val SPEECH_PADDING_SAMPLES = TARGET_SAMPLE_RATE * 150 / 1_000
        const val CHUNK_SECONDS = 28
        const val OVERLAP_MILLISECONDS = 750
    }
}
