package dev.sesyazi.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.progressindicator.LinearProgressIndicator
import dev.sesyazi.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val audioPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let(viewModel::acceptAudio)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right,
                bottom = systemBars.bottom,
            )
            insets
        }

        bindActions()
        observeState()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun bindActions() = with(binding) {
        downloadModelButton.setOnClickListener { viewModel.downloadModel() }
        chooseAudioButton.setOnClickListener { audioPicker.launch(arrayOf("audio/*", "application/ogg")) }
        transcribeButton.setOnClickListener { viewModel.transcribe() }
        errorText.setOnClickListener { viewModel.clearError() }
        copyButton.setOnClickListener { copyTranscript(viewModel.state.value.transcript) }
        shareButton.setOnClickListener { shareTranscript(viewModel.state.value.transcript) }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: MainUiState) = with(binding) {
        modelStatusText.text = when (state.modelState) {
            ModelState.CHECKING -> getString(R.string.progress_model_check)
            ModelState.MISSING -> getString(R.string.model_missing)
            ModelState.DOWNLOADING -> "Model indiriliyor · ${state.progressPercent ?: 0}%"
            ModelState.READY -> getString(R.string.model_ready)
        }
        downloadModelButton.isVisible = state.modelState == ModelState.MISSING ||
            state.modelState == ModelState.DOWNLOADING
        downloadModelButton.isEnabled = !state.isBusy && state.modelState == ModelState.MISSING

        chooseAudioButton.isEnabled = !state.isBusy
        audioCard.isVisible = state.audioName != null
        audioNameText.text = state.audioName.orEmpty()
        transcribeButton.isEnabled = !state.isBusy &&
            state.modelState == ModelState.READY &&
            state.audioName != null

        val showProgress = state.isBusy
        progressBar.isVisible = showProgress
        progressText.isVisible = showProgress
        configureProgress(progressBar, state.progressPercent)
        progressText.text = progressLabel(state)

        errorText.isVisible = state.errorMessage != null
        errorText.text = state.errorMessage.orEmpty()

        resultCard.isVisible = state.transcript.isNotBlank()
        transcriptText.text = state.transcript
    }

    private fun configureProgress(progress: LinearProgressIndicator, percent: Int?) {
        progress.isIndeterminate = percent == null
        if (percent != null) progress.setProgressCompat(percent, true)
    }

    private fun progressLabel(state: MainUiState): String {
        val base = when {
            state.modelState == ModelState.DOWNLOADING ->
                state.progressDetail ?: "Model indiriliyor"
            state.operation == Operation.STAGING_AUDIO -> getString(R.string.progress_preparing)
            state.operation == Operation.DECODING_AUDIO -> getString(R.string.progress_decoding)
            state.operation == Operation.TRANSCRIBING -> getString(R.string.progress_transcribing)
            else -> "İşleniyor…"
        }
        val percent = state.progressPercent
        return if (percent != null) "$base · $percent%" else base
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri != null) viewModel.acceptAudio(uri)
    }

    private fun copyTranscript(transcript: String) {
        if (transcript.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.result_title), transcript))
        Toast.makeText(this, R.string.copy_success, Toast.LENGTH_SHORT).show()
    }

    private fun shareTranscript(transcript: String) {
        if (transcript.isBlank()) return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_transcript)))
    }
}
