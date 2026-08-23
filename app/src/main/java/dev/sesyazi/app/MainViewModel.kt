package dev.sesyazi.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sesyazi.app.audio.AudioDecodeException
import dev.sesyazi.app.audio.AudioDecoder
import dev.sesyazi.app.audio.SharedAudioStore
import dev.sesyazi.app.model.ModelDownloadException
import dev.sesyazi.app.model.ModelManager
import dev.sesyazi.app.transcription.ModelInitializationException
import dev.sesyazi.app.transcription.TranscriptionException
import dev.sesyazi.app.transcription.WhisperTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ModelState {
    CHECKING,
    MISSING,
    DOWNLOADING,
    READY,
}

enum class Operation {
    STAGING_AUDIO,
    DECODING_AUDIO,
    TRANSCRIBING,
}

data class MainUiState(
    val modelState: ModelState = ModelState.CHECKING,
    val isBusy: Boolean = false,
    val operation: Operation? = null,
    val progressPercent: Int? = null,
    val progressDetail: String? = null,
    val audioName: String? = null,
    val transcript: String = "",
    val errorMessage: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelManager = ModelManager(application)
    private val audioStore = SharedAudioStore(application)
    private val audioDecoder = AudioDecoder()

    private val mutableState = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    private var stagedAudioFile: File? = null

    init {
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) { modelManager.isReady() }
            mutableState.update {
                it.copy(modelState = if (ready) ModelState.READY else ModelState.MISSING)
            }
        }
    }

    fun acceptAudio(uri: Uri) {
        if (mutableState.value.isBusy) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isBusy = true,
                    operation = Operation.STAGING_AUDIO,
                    progressPercent = null,
                    progressDetail = null,
                    transcript = "",
                    errorMessage = null,
                )
            }
            try {
                val staged = withContext(Dispatchers.IO) { audioStore.stage(uri) }
                stagedAudioFile = staged.file
                mutableState.update {
                    it.copy(
                        isBusy = false,
                        operation = null,
                        audioName = staged.displayName,
                    )
                }
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        isBusy = false,
                        operation = null,
                        audioName = null,
                        errorMessage = friendlyMessage(error),
                    )
                }
            }
        }
    }

    fun downloadModel() {
        if (mutableState.value.isBusy || mutableState.value.modelState == ModelState.READY) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    modelState = ModelState.DOWNLOADING,
                    isBusy = true,
                    operation = null,
                    progressPercent = 0,
                    progressDetail = "İndirme başlatılıyor",
                    errorMessage = null,
                )
            }
            try {
                withContext(Dispatchers.IO) {
                    modelManager.ensureDownloaded { progress ->
                        mutableState.update {
                            it.copy(
                                progressPercent = progress.percent,
                                progressDetail = progress.currentFile,
                            )
                        }
                    }
                }
                mutableState.update {
                    it.copy(
                        modelState = ModelState.READY,
                        isBusy = false,
                        progressPercent = null,
                        progressDetail = null,
                    )
                }
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        modelState = ModelState.MISSING,
                        isBusy = false,
                        progressPercent = null,
                        progressDetail = null,
                        errorMessage = friendlyMessage(error),
                    )
                }
            }
        }
    }

    fun transcribe() {
        val audioFile = stagedAudioFile ?: return
        if (mutableState.value.isBusy || mutableState.value.modelState != ModelState.READY) return

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isBusy = true,
                    operation = Operation.DECODING_AUDIO,
                    progressPercent = null,
                    progressDetail = null,
                    transcript = "",
                    errorMessage = null,
                )
            }
            try {
                val decoded = withContext(Dispatchers.IO) { audioDecoder.decode(audioFile) }
                mutableState.update {
                    it.copy(
                        operation = Operation.TRANSCRIBING,
                        progressPercent = 0,
                        progressDetail = "${decoded.durationSeconds.toInt().coerceAtLeast(1)} sn ses",
                    )
                }

                val transcript = withContext(Dispatchers.Default) {
                    WhisperTranscriber(modelManager.modelDirectory()).use { transcriber ->
                        transcriber.transcribe(decoded) { percent ->
                            mutableState.update { it.copy(progressPercent = percent) }
                        }
                    }
                }

                withContext(Dispatchers.IO) { audioStore.clear() }
                stagedAudioFile = null
                mutableState.update {
                    it.copy(
                        isBusy = false,
                        operation = null,
                        progressPercent = null,
                        progressDetail = null,
                        audioName = null,
                        transcript = transcript,
                    )
                }
            } catch (error: Throwable) {
                if (error is ModelInitializationException) {
                    modelManager.invalidate()
                }
                mutableState.update {
                    it.copy(
                        modelState = if (error is ModelInitializationException) {
                            ModelState.MISSING
                        } else {
                            it.modelState
                        },
                        isBusy = false,
                        operation = null,
                        progressPercent = null,
                        progressDetail = null,
                        errorMessage = friendlyMessage(error),
                    )
                }
            }
        }
    }

    fun clearError() {
        mutableState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        audioStore.clear()
        super.onCleared()
    }

    private fun friendlyMessage(error: Throwable): String = when (error) {
        is AudioDecodeException,
        is ModelDownloadException,
        is TranscriptionException,
        -> error.message ?: getApplication<Application>().getString(R.string.generic_error)

        is OutOfMemoryError -> "Bu ses cihazın belleği için çok uzun. Daha kısa bir mesaj dene."
        else -> getApplication<Application>().getString(R.string.generic_error)
    }
}
