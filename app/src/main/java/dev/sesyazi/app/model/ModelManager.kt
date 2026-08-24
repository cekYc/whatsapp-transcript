package dev.sesyazi.app.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class ModelTier(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    FAST("tiny", "Hızlı", "Daha hızlı, temel doğruluk"),
    BALANCED("base", "Dengeli", "Önerilen varsayılan"),
    ACCURATE("small", "Yüksek", "Daha doğru, daha yavaş"),
}

data class InstalledSpeechModel(
    val tier: ModelTier,
    val encoder: File,
    val decoder: File,
    val tokens: File,
    val vad: File,
)

data class ModelDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val currentFile: String,
) {
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else
            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

class ModelDownloadException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class ModelManager(context: Context) {
    private val modelRoot = File(context.filesDir, MODEL_ROOT)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun selectedTier(): ModelTier {
        val stored = preferences.getString(SELECTED_TIER, null)
        return ModelTier.entries.firstOrNull { it.storageKey == stored } ?: ModelTier.BALANCED
    }

    fun selectTier(tier: ModelTier) {
        preferences.edit().putString(SELECTED_TIER, tier.storageKey).apply()
    }

    fun downloadSizeBytes(tier: ModelTier): Long = modelSpec(tier).allFiles.sumOf { it.size }

    fun downloadSizeMegabytes(tier: ModelTier): Int =
        ((downloadSizeBytes(tier) + 999_999L) / 1_000_000L).toInt()

    fun installedModel(tier: ModelTier): InstalledSpeechModel {
        val spec = modelSpec(tier)
        val directory = modelDirectory(spec)
        return InstalledSpeechModel(
            tier = tier,
            encoder = File(directory, spec.encoderFileName),
            decoder = File(directory, spec.decoderFileName),
            tokens = File(directory, spec.tokensFileName),
            vad = File(commonDirectory(), VAD_FILE.fileName),
        )
    }

    fun invalidate(tier: ModelTier) {
        readyMarker(modelSpec(tier)).delete()
    }

    fun isReady(tier: ModelTier): Boolean {
        val spec = modelSpec(tier)
        if (!readyMarker(spec).isFile) return false
        return spec.allFiles.all { fileSpec ->
            val file = destination(spec, fileSpec)
            file.isFile && file.length() == fileSpec.size
        }
    }

    fun ensureDownloaded(
        tier: ModelTier,
        onProgress: (ModelDownloadProgress) -> Unit,
    ) {
        val spec = modelSpec(tier)
        modelDirectory(spec).mkdirs()
        commonDirectory().mkdirs()
        readyMarker(spec).delete()

        val totalSize = downloadSizeBytes(tier)
        var completedBytes = 0L
        spec.allFiles.forEach { fileSpec ->
            val target = destination(spec, fileSpec)
            if (isValid(target, fileSpec)) {
                completedBytes += fileSpec.size
                onProgress(ModelDownloadProgress(completedBytes, totalSize, fileSpec.label))
                return@forEach
            }

            target.delete()
            download(fileSpec, target, completedBytes, totalSize, onProgress)
            if (!isValid(target, fileSpec)) {
                target.delete()
                throw ModelDownloadException(
                    "İndirilen ${fileSpec.label} dosyasının bütünlük kontrolü başarısız oldu.",
                )
            }
            completedBytes += fileSpec.size
        }

        readyMarker(spec).writeText(MODEL_MANIFEST_VERSION, Charsets.UTF_8)
        onProgress(ModelDownloadProgress(totalSize, totalSize, "Model hazır"))
    }

    private fun download(
        spec: ModelFileSpec,
        destination: File,
        completedBytes: Long,
        totalBytes: Long,
        onProgress: (ModelDownloadProgress) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.part")
        partial.delete()

        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SesYazi-Android/$MODEL_MANIFEST_VERSION")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw ModelDownloadException(
                    "Model sunucusu ${spec.label} için HTTP $status döndürdü.",
                )
            }

            connection.inputStream.buffered().use { input ->
                FileOutputStream(partial).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    var fileBytes = 0L
                    var lastReported = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        fileBytes += count
                        if (fileBytes - lastReported >= PROGRESS_GRANULARITY) {
                            onProgress(
                                ModelDownloadProgress(
                                    downloadedBytes = completedBytes + fileBytes,
                                    totalBytes = totalBytes,
                                    currentFile = spec.label,
                                ),
                            )
                            lastReported = fileBytes
                        }
                    }
                }
            }

            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
        } catch (error: ModelDownloadException) {
            partial.delete()
            throw error
        } catch (error: IOException) {
            partial.delete()
            throw ModelDownloadException(
                "${spec.label} indirilemedi. İnternet bağlantını kontrol edip tekrar dene.",
                error,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun isValid(file: File, spec: ModelFileSpec): Boolean {
        if (!file.isFile || file.length() != spec.size) return false
        return sha256(file).equals(spec.sha256, ignoreCase = true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun modelSpec(tier: ModelTier): SpeechModelSpec = MODEL_SPECS.getValue(tier)
    private fun modelDirectory(spec: SpeechModelSpec) = File(modelRoot, spec.directoryName)
    private fun commonDirectory() = File(modelRoot, COMMON_DIRECTORY)
    private fun readyMarker(spec: SpeechModelSpec) = File(modelDirectory(spec), READY_MARKER)

    private fun destination(model: SpeechModelSpec, file: ModelFileSpec): File =
        if (file.common) File(commonDirectory(), file.fileName)
        else File(modelDirectory(model), file.fileName)

    private data class ModelFileSpec(
        val fileName: String,
        val label: String,
        val size: Long,
        val sha256: String,
        val url: String,
        val common: Boolean = false,
    )

    private data class SpeechModelSpec(
        val directoryName: String,
        val encoderFileName: String,
        val decoderFileName: String,
        val tokensFileName: String,
        val files: List<ModelFileSpec>,
    ) {
        val allFiles: List<ModelFileSpec>
            get() = files + VAD_FILE
    }

    companion object {
        private const val MODEL_MANIFEST_VERSION = "speech-models-v2"
        private const val MODEL_ROOT = "models"
        private const val COMMON_DIRECTORY = "common"
        private const val READY_MARKER = ".ready-v2"
        private const val PREFERENCES = "speech_model_preferences"
        private const val SELECTED_TIER = "selected_tier"
        private const val PROGRESS_GRANULARITY = 512L * 1024L
        private const val HF_ROOT = "https://huggingface.co/csukuangfj"

        private const val TOKENS_HASH =
            "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"

        private val VAD_FILE = ModelFileSpec(
            fileName = "silero_vad.onnx",
            label = "Sessizlik algılama modeli",
            size = 643_854L,
            sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            common = true,
        )

        private val MODEL_SPECS = mapOf(
            ModelTier.FAST to whisperSpec(
                modelName = "tiny",
                directoryName = "whisper-tiny-multilingual-int8",
                encoderSize = 12_937_772L,
                encoderHash = "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
                decoderSize = 89_855_401L,
                decoderHash = "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
            ),
            ModelTier.BALANCED to whisperSpec(
                modelName = "base",
                directoryName = "whisper-base-multilingual-int8",
                encoderSize = 29_120_534L,
                encoderHash = "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11",
                decoderSize = 130_672_026L,
                decoderHash = "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d",
            ),
            ModelTier.ACCURATE to whisperSpec(
                modelName = "small",
                directoryName = "whisper-small-multilingual-int8",
                encoderSize = 112_442_483L,
                encoderHash = "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
                decoderSize = 262_226_114L,
                decoderHash = "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
            ),
        )

        private fun whisperSpec(
            modelName: String,
            directoryName: String,
            encoderSize: Long,
            encoderHash: String,
            decoderSize: Long,
            decoderHash: String,
        ): SpeechModelSpec {
            val repository = "$HF_ROOT/sherpa-onnx-whisper-$modelName/resolve/main"
            val encoder = "$modelName-encoder.int8.onnx"
            val decoder = "$modelName-decoder.int8.onnx"
            val tokens = "$modelName-tokens.txt"
            return SpeechModelSpec(
                directoryName = directoryName,
                encoderFileName = encoder,
                decoderFileName = decoder,
                tokensFileName = tokens,
                files = listOf(
                    ModelFileSpec(
                        fileName = encoder,
                        label = "${modelName.replaceFirstChar { it.uppercase() }} ses kodlayıcı",
                        size = encoderSize,
                        sha256 = encoderHash,
                        url = "$repository/$encoder?download=true",
                    ),
                    ModelFileSpec(
                        fileName = decoder,
                        label = "${modelName.replaceFirstChar { it.uppercase() }} dil modeli",
                        size = decoderSize,
                        sha256 = decoderHash,
                        url = "$repository/$decoder?download=true",
                    ),
                    ModelFileSpec(
                        fileName = tokens,
                        label = "Türkçe sözlük",
                        size = 816_730L,
                        sha256 = TOKENS_HASH,
                        url = "$repository/$tokens?download=true",
                    ),
                ),
            )
        }
    }
}
