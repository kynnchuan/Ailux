package com.ailux.chatdemo.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ailux.chatdemo.BuildConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel managing model download state for the demo.
 *
 * Encapsulates [ModelDownloader] and exposes a simple [DownloadUiState] for the
 * Compose UI layer to observe. Handles retry, source switching, and cancellation.
 */
class ModelDownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val downloader = ModelDownloader(application).apply {
        // Inject HF token from BuildConfig (sourced from local.properties: hf.token)
        val token = BuildConfig.HF_TOKEN
        if (token.isNotEmpty()) {
            hfToken = token
        }
    }

    private var downloadJob: Job? = null

    /** Current mirror source selection. Must be initialized BEFORE _uiState. */
    private var currentSource = ModelDownloader.MirrorSource.HF_MIRROR

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<DownloadUiState> = _uiState.asStateFlow()

    private fun buildInitialState(): DownloadUiState {
        return try {
            if (downloader.isModelAvailable()) {
                DownloadUiState.Ready(downloader.getModelPath())
            } else {
                DownloadUiState.Idle(currentSource)
            }
        } catch (e: Exception) {
            // Defensive: avoid crashing ViewModel construction if file I/O fails
            android.util.Log.w("ModelDownloadVM", "buildInitialState failed, defaulting to Idle", e)
            DownloadUiState.Idle(currentSource)
        }
    }

    /** Whether a valid HF token is configured. UI can warn users if false. */
    val hasHfToken: Boolean = !downloader.hfToken.isNullOrEmpty()

    /** Check if model is already downloaded and ready. */
    fun isModelReady(): Boolean = downloader.isModelAvailable()

    /** Get model path (only valid when [isModelReady] is true). */
    fun getModelPath(): String = downloader.getModelPath()

    /** Bound service reference for notification updates. */
    private var downloadService: ModelDownloadService? = null

    /** Bind to the download service for notification updates. */
    fun bindService(service: ModelDownloadService) {
        downloadService = service
    }

    /** Start or resume download with the current source. */
    fun startDownload() {
        if (downloadJob?.isActive == true) return

        // Start foreground notification service
        ModelDownloadService.start(getApplication())

        downloadJob = viewModelScope.launch {
            downloader.download(currentSource).collect { progress ->
                _uiState.value = when (progress) {
                    is DownloadProgress.Started -> DownloadUiState.Downloading(
                        source = progress.source,
                        progressFraction = 0f,
                        statusText = "Connecting to ${progress.source.label}...",
                    )
                    is DownloadProgress.InProgress -> {
                        val percent = (progress.progressFraction * 100).toInt()
                        val statusText = "${progress.downloadedMb} / ${progress.totalMb} MB"
                        // Update notification
                        downloadService?.updateProgress(percent, statusText)
                        DownloadUiState.Downloading(
                            source = currentSource,
                            progressFraction = progress.progressFraction,
                            statusText = statusText,
                        )
                    }
                    is DownloadProgress.Verifying -> {
                        downloadService?.updateProgress(100, "Verifying...")
                        DownloadUiState.Downloading(
                            source = currentSource,
                            progressFraction = 1f,
                            statusText = "Verifying SHA-256...",
                        )
                    }
                    is DownloadProgress.Completed -> {
                        downloadService?.notifyComplete()
                        DownloadUiState.Ready(progress.modelPath)
                    }
                    is DownloadProgress.Failed -> {
                        downloadService?.notifyFailed(
                            progress.error.message ?: "Unknown error"
                        )
                        DownloadUiState.Error(
                            message = progress.error.message ?: "Unknown error",
                            suggestion = progress.suggestion,
                            canRetry = progress.canRetry,
                            currentSource = currentSource,
                        )
                    }
                }
            }
        }
    }

    /** Cancel an in-progress download. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadService?.notifyCancelled()
        _uiState.value = DownloadUiState.Idle(currentSource)
    }

    /** Switch to a different mirror source and reset state. */
    fun switchSource(source: ModelDownloader.MirrorSource) {
        cancelDownload()
        currentSource = source
        _uiState.value = DownloadUiState.Idle(source)
    }

    /** Retry download with the same or different source. */
    fun retry() {
        startDownload()
    }

    /** Delete the downloaded model and reset to idle. */
    fun deleteModel() {
        viewModelScope.launch {
            downloader.deleteModel()
            _uiState.value = DownloadUiState.Idle(currentSource)
        }
    }
}

/**
 * UI state for the model download screen section.
 */
sealed class DownloadUiState {
    /** No model downloaded, ready to start. */
    data class Idle(val source: ModelDownloader.MirrorSource) : DownloadUiState()

    /** Download in progress. */
    data class Downloading(
        val source: ModelDownloader.MirrorSource,
        val progressFraction: Float,
        val statusText: String,
    ) : DownloadUiState()

    /** Model downloaded and verified, ready to use. */
    data class Ready(val modelPath: String) : DownloadUiState()

    /** Download failed with actionable guidance. */
    data class Error(
        val message: String,
        val suggestion: String,
        val canRetry: Boolean,
        val currentSource: ModelDownloader.MirrorSource,
    ) : DownloadUiState()
}
