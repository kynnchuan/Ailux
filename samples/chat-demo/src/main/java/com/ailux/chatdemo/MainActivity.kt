package com.ailux.chatdemo

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ailux.chatdemo.download.DownloadUiState
import com.ailux.chatdemo.download.ModelDownloadDialog
import com.ailux.chatdemo.download.ModelDownloadService
import com.ailux.chatdemo.download.ModelDownloadViewModel
import com.ailux.chatdemo.ui.theme.AiluxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Demo main Activity: hosts the redesigned [ChatScreen] with drawer + chips.
 *
 * Observes the [ChatClientManager.providerMode] and rebuilds the ViewModel
 * when the user switches between MockProvider, BackendProxy, and LocalRuntime.
 */
class MainActivity : ComponentActivity() {

    /**
     * Loading state for SAF model copy operation.
     * Observed by Compose to show a full-screen loading overlay.
     */
    private val _isLoadingModel = mutableStateOf(false)

    /**
     * SAF file picker for selecting on-device .litertlm model files.
     * The copy operation runs on IO dispatcher to avoid blocking the main thread.
     */
    private val modelFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        _isLoadingModel.value = true
        lifecycleScope.launch {
            try {
                val destFile = withContext(Dispatchers.IO) {
                    val dest = File(filesDir, "models/local-model.litertlm")
                    dest.parentFile?.mkdirs()
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    }
                    dest
                }
                ChatClientManager.setModelPath(destFile.absolutePath)
                ChatClientManager.switchProvider(ProviderMode.LOCAL_RUNTIME)
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Failed to load model: ${e.message}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                _isLoadingModel.value = false
            }
        }
    }

    /** Launch the SAF file picker for .litertlm model selection. */
    fun pickModelFile() {
        modelFilePicker.launch(arrayOf("application/octet-stream"))
    }

    /** Service connection for download notifications. */
    private var downloadService: ModelDownloadService? = null
    private var downloadViewModel: ModelDownloadViewModel? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as ModelDownloadService.LocalBinder).getService()
            downloadService = service
            downloadViewModel?.bindService(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            downloadService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ChatClientManager.initialize(context = applicationContext)

        setContent {
            AiluxTheme {
                val providerMode by ChatClientManager.providerMode.collectAsState()
                val generation by ChatClientManager.generation.collectAsState()

                val dlViewModel: ModelDownloadViewModel = viewModel()

                remember(dlViewModel) {
                    downloadViewModel = dlViewModel
                    bindToDownloadService()
                    true
                }

                var showDownloadDialog by remember { mutableStateOf(false) }
                val downloadState by dlViewModel.uiState.collectAsState()

                key(generation) {
                    val chatViewModel: ChatViewModel = viewModel(
                        factory = ChatViewModel.Factory(
                            client = ChatClientManager.ailuxClient,
                            application = application,
                        ),
                        key = "chat-$generation",
                    )

                    ChatScreen(
                        viewModel = chatViewModel,
                        isConfigured = true,
                        providerModeLabel = providerMode.label,
                        currentMode = providerMode,
                        onSwitchProvider = { newMode ->
                            if (newMode == ProviderMode.LOCAL_RUNTIME) {
                                val modelReady = dlViewModel.isModelReady()
                                val hasPath = ChatClientManager.modelPath.value != null
                                if (modelReady) {
                                    ChatClientManager.setModelPath(dlViewModel.getModelPath())
                                    ChatClientManager.switchProvider(ProviderMode.LOCAL_RUNTIME)
                                } else if (hasPath) {
                                    ChatClientManager.switchProvider(ProviderMode.LOCAL_RUNTIME)
                                } else {
                                    showDownloadDialog = true
                                }
                            } else {
                                ChatClientManager.switchProvider(newMode)
                            }
                        },
                        onOpenModelManager = {
                            showDownloadDialog = true
                        },
                        onNewConversation = {
                            chatViewModel.newConversation()
                        },
                        downloadPanel = null,
                    )
                }

                // Download dialog (floating above everything)
                if (showDownloadDialog || downloadState is DownloadUiState.Downloading) {
                    ModelDownloadDialog(
                        viewModel = dlViewModel,
                        onModelReady = { path ->
                            ChatClientManager.setModelPath(path)
                            ChatClientManager.switchProvider(ProviderMode.LOCAL_RUNTIME)
                            showDownloadDialog = false
                        },
                        onPickFile = {
                            showDownloadDialog = false
                            pickModelFile()
                        },
                        onDismiss = {
                            showDownloadDialog = false
                        },
                    )
                }

                // Full-screen loading overlay for SAF model copy
                val isLoading by _isLoadingModel
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Loading model...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun bindToDownloadService() {
        val intent = Intent(this, ModelDownloadService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        try {
            unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
            // Service was not bound
        }
        super.onDestroy()
    }
}
