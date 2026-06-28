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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ailux.chatdemo.download.DownloadUiState
import com.ailux.chatdemo.download.ModelDownloadDialog
import com.ailux.chatdemo.download.ModelDownloadService
import com.ailux.chatdemo.download.ModelDownloadViewModel
import com.ailux.chatdemo.ui.theme.AiluxTheme
import java.io.File

/**
 * Demo main Activity: hosts the [ChatScreen] composable.
 *
 * Observes the [ChatClientManager.providerMode] and rebuilds the ViewModel
 * when the user switches between MockProvider, BackendProxy, and LocalRuntime.
 */
class MainActivity : ComponentActivity() {

    /**
     * SAF file picker for selecting on-device .litertlm model files.
     * On selection, the model is copied to internal storage (persistent access)
     * and the path is passed to [ChatClientManager].
     */
    private val modelFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Copy the model to internal storage to avoid relying on SAF URI
        // persistence (which requires takePersistableUriPermission and is
        // fragile for native file-path-based engines).
        val destFile = File(filesDir, "models/local-model.litertlm")
        destFile.parentFile?.mkdirs()
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            ChatClientManager.setModelPath(destFile.absolutePath)
            ChatClientManager.switchProvider(ProviderMode.LOCAL_RUNTIME)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load model: ${e.message}", Toast.LENGTH_LONG).show()
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

        // Ensure client is initialized (safe to call multiple times)
        ChatClientManager.initialize(context = applicationContext)

        setContent {
            AiluxTheme {
                val providerMode by ChatClientManager.providerMode.collectAsState()
                val generation by ChatClientManager.generation.collectAsState()

                // Model download ViewModel — survives config changes, shared across
                // provider mode switches. Only relevant in LOCAL_RUNTIME mode.
                val dlViewModel: ModelDownloadViewModel = viewModel()

                // Store reference for service binding
                remember(dlViewModel) {
                    downloadViewModel = dlViewModel
                    // Bind to service if already running
                    bindToDownloadService()
                    true
                }

                // Dialog visibility state
                var showDownloadDialog by remember { mutableStateOf(false) }

                // Auto-show dialog when switching to LOCAL_RUNTIME without a model
                val downloadState by dlViewModel.uiState.collectAsState()

                // Use `key(generation)` to force ViewModel recreation whenever the
                // client is rebuilt.
                key(generation) {
                    val chatViewModel: ChatViewModel = viewModel(
                        factory = ChatViewModel.Factory(
                            client = ChatClientManager.ailuxClient,
                            application = application,
                        ),
                        key = "chat-$generation", // unique key per rebuild
                    )

                    ChatScreen(
                        viewModel = chatViewModel,
                        isConfigured = true,
                        providerModeLabel = providerMode.label,
                        currentMode = providerMode,
                        onSwitchProvider = { newMode ->
                            if (newMode == ProviderMode.LOCAL_RUNTIME) {
                                // Check if model is already available
                                val modelReady = dlViewModel.isModelReady()
                                val hasPath = ChatClientManager.modelPath.value != null
                                if (modelReady) {
                                    ChatClientManager.setModelPath(dlViewModel.getModelPath())
                                    ChatClientManager.switchProvider(ProviderMode.LOCAL_RUNTIME)
                                } else if (hasPath) {
                                    ChatClientManager.switchProvider(ProviderMode.LOCAL_RUNTIME)
                                } else {
                                    // No model — show download dialog
                                    showDownloadDialog = true
                                }
                            } else {
                                ChatClientManager.switchProvider(newMode)
                            }
                        },
                        onOpenModelManager = {
                            showDownloadDialog = true
                        },
                        // No longer pass inline download panel
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

/**
 * Fallback screen displayed when the SDK has not been configured.
 * (Retained for backwards compatibility, though no longer actively used
 * since both modes are always available.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnconfiguredScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Ailux Demo",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Configuration missing",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Please add the following to local.properties:\n\n" +
                            "ailux.baseUrl=https://your-api.com\n" +
                            "ailux.apiKey=your-api-key",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
