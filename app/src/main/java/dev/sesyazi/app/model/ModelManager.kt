package dev.sesyazi.app.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
    private val modelDirectory = File(context.filesDir, MODEL_DIRECTORY)
    private val readyMarker = File(modelDirectory, READY_MARKER)

    fun modelDirectory(): File = modelDirectory

    fun invalidate() {
        readyMarker.delete()
    }

    fun isReady(): Boolean {
        if (!readyMarker.isFile) return false
        return MODEL_FILES.all { spec ->
            val file = File(modelDirectory, spec.fileName)
            file.isFile && file.length() == spec.size
        }
    }

    fun ensureDownloaded(onProgress: (ModelDownloadProgress) -> Unit) {
        modelDirectory.mkdirs()
        readyMarker.delete()

        var completedBytes = 0L
        MODEL_FILES.forEach { spec ->
            val destination = File(modelDirectory, spec.fileName)
            if (isValid(destination, spec)) {
                completedBytes += spec.size
                onProgress(
                    ModelDownloadProgress(completedBytes, TOTAL_SIZE, spec.label),
                )
                return@forEach
            }

            destination.delete()
            download(spec, destination, completedBytes, onProgress)
            if (!isValid(destination, spec)) {
                destination.delete()
                throw ModelDownloadException(
                    "İndirilen ${spec.label} dosyasının bütünlük kontrolü başarısız oldu.",
                )
            }
            completedBytes += spec.size
        }

        readyMarker.writeText(MODEL_VERSION, Charsets.UTF_8)
        onProgress(ModelDownloadProgress(TOTAL_SIZE, TOTAL_SIZE, "Model hazır"))
    }

    private fun download(
        spec: ModelFile,
        destination: File,
        completedBytes: Long,
        onProgress: (ModelDownloadProgress) -> Unit,
    ) {
        val partial = File(modelDirectory, "${spec.fileName}.part")
        partial.delete()

        val connection = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SesYazi-Android/$MODEL_VERSION")
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
                                    totalBytes = TOTAL_SIZE,
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

    private fun isValid(file: File, spec: ModelFile): Boolean {
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

    private data class ModelFile(
        val fileName: String,
        val label: String,
        val size: Long,
        val sha256: String,
        val url: String,
    )

    companion object {
        const val ENCODER_FILE = "tiny-encoder.int8.onnx"
        const val DECODER_FILE = "tiny-decoder.int8.onnx"
        const val TOKENS_FILE = "tiny-tokens.txt"

        private const val MODEL_VERSION = "whisper-tiny-multilingual-int8-v1"
        private const val MODEL_DIRECTORY = "models/whisper-tiny-multilingual-int8"
        private const val READY_MARKER = ".ready"
        private const val PROGRESS_GRANULARITY = 512L * 1024L
        private const val BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"

        private val MODEL_FILES = listOf(
            ModelFile(
                fileName = ENCODER_FILE,
                label = "Ses kodlayıcı",
                size = 12_937_772L,
                sha256 = "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434",
                url = "$BASE_URL/$ENCODER_FILE?download=true",
            ),
            ModelFile(
                fileName = DECODER_FILE,
                label = "Dil modeli",
                size = 89_855_401L,
                sha256 = "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925",
                url = "$BASE_URL/$DECODER_FILE?download=true",
            ),
            ModelFile(
                fileName = TOKENS_FILE,
                label = "Türkçe sözlük",
                size = 816_730L,
                sha256 = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
                url = "$BASE_URL/$TOKENS_FILE?download=true",
            ),
        )

        private val TOTAL_SIZE = MODEL_FILES.sumOf { it.size }
    }
}
