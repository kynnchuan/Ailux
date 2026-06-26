package com.ailux.chatdemo

import android.net.Uri
import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ailux.chatdemo.download.DownloadUiState
import com.ailux.chatdemo.download.ModelDownloadPanel
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
                val downloadViewModel: ModelDownloadViewModel = viewModel()

                // Use `key(generation)` to force ViewModel recreation whenever the
                // client is rebuilt. Using a monotonic counter (not providerMode.name)
                // prevents Compose from reusing a cached ViewModel that holds a
                // released client — e.g. Mock→Backend→Mock would reuse the first
                // Mock ViewModel if keyed by name alone.
                key(generation) {
                    val chatViewModel: ChatViewModel = viewModel(
                        factory = ChatViewModel.Factory(
                            client = ChatClientManager.ailuxClient,
                            application = application,
                        ),
                        key = "chat-$generation", // unique key per rebuild
                    )
                    // Determine whether to show the download panel:
                    // Show it in LOCAL_RUNTIME mode always (for model management),
                    // or when user just switched to Local but model isn't ready yet.
                    val downloadState by downloadViewModel.uiState.collectAsState()
                    val showDownloadPanel = providerMode == ProviderMode.LOCAL_RUNTIME ||
                        (downloadState is DownloadUiState.Downloading)

                    ChatScreen(
                        viewModel = chatViewModel,
                        isConfigured = true,
                        providerModeLabel = providerMode.label,
                        currentMode = providerMode,
                        onSwitchProvider = { newMode ->
                            if (newMode == ProviderMode.LOCAL_RUNTIME) {
                                // Check if model is already available (downloaded or SAF-picked)
                                val modelReady = downloadViewModel.isModelReady()
                                val hasPath = ChatClientManager.modelPath.value != null
                                if (modelReady) {
                                    ChatClientManager.setModelPath(downloadViewModel.getModelPath())
                                    ChatClientManager.switchProvider(ProviderMode.LOCAL_RUNTIME)
                                } else if (hasPath) {
                                    ChatClientManager.switchProvider(ProviderMode.LOCAL_RUNTIME)
                                } else {
                                    // No model available yet — show download panel
                                    // Stay on current provider (Mock fallback), panel guides user
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Please download or select a model first",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } else {
                                ChatClientManager.switchProvider(newMode)
                            }
                        },
                        downloadPanel = if (showDownloadPanel || !downloadViewModel.isModelReady()) {
                            {
                                ModelDownloadPanel(
                                    viewModel = downloadViewModel,
                                    onModelReady = { path ->
                                        ChatClientManager.setModelPath(path)
                                        ChatClientManager.switchProvider(ProviderMode.LOCAL_RUNTIME)
                                    },
                                    onPickFile = { pickModelFile() },
                                )
                            }
                        } else null,
                    )
                }
            }
        }
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
