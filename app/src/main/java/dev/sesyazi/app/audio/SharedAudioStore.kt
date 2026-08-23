package dev.sesyazi.app.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class StagedAudio(
    val file: File,
    val displayName: String,
)

class SharedAudioStore(private val context: Context) {
    private val stagingDirectory = File(context.cacheDir, "shared_audio")

    fun stage(uri: Uri): StagedAudio {
        clear()
        stagingDirectory.mkdirs()

        val displayName = queryDisplayName(uri) ?: "WhatsApp sesli mesajı"
        val destination = File(stagingDirectory, "incoming_audio")
        var total = 0L

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Paylaşılan ses açılamadı.")
            input.buffered().use { source ->
                FileOutputStream(destination).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_INPUT_BYTES) {
                            throw AudioDecodeException("Paylaşılan ses dosyası 200 MB sınırını aşıyor.")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: AudioDecodeException) {
            destination.delete()
            throw error
        } catch (error: Exception) {
            destination.delete()
            throw AudioDecodeException(
                "WhatsApp sesine erişilemedi. Mesajı yeniden paylaşmayı dene.",
                error,
            )
        }

        if (total == 0L) {
            destination.delete()
            throw AudioDecodeException("Paylaşılan ses dosyası boş.")
        }
        return StagedAudio(destination, displayName)
    }

    fun clear() {
        stagingDirectory.listFiles()?.forEach { file -> file.delete() }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_INPUT_BYTES = 200L * 1024L * 1024L
    }
}
