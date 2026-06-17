package com.ailux.chatdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.ailux.chatdemo.ui.theme.AiluxTheme

/**
 * Demo main Activity: hosts the [ChatScreen] composable.
 *
 * Observes the [ChatClientManager.providerMode] and rebuilds the ViewModel
 * when the user switches between MockProvider and BackendProxy at runtime.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ensure client is initialized (safe to call multiple times)
        ChatClientManager.initialize()

        setContent {
            AiluxTheme {
                val providerMode by ChatClientManager.providerMode.collectAsState()
                val generation by ChatClientManager.generation.collectAsState()

                // Use `key(generation)` to force ViewModel recreation whenever the
                // client is rebuilt. Using a monotonic counter (not providerMode.name)
                // prevents Compose from reusing a cached ViewModel that holds a
                // released client — e.g. Mock→Backend→Mock would reuse the first
                // Mock ViewModel if keyed by name alone.
                key(generation) {
                    val chatViewModel: ChatViewModel = viewModel(
                        factory = ChatViewModel.Factory(ChatClientManager.ailuxClient),
                        key = "chat-$generation", // unique key per rebuild
                    )
                    ChatScreen(
                        viewModel = chatViewModel,
                        isConfigured = true,
                        providerModeLabel = providerMode.label,
                        currentMode = providerMode,
                        onSwitchProvider = { newMode ->
                            ChatClientManager.switchProvider(newMode)
                        },
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
