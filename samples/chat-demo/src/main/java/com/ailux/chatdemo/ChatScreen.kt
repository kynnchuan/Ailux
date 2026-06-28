package com.ailux.chatdemo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ailux.core.state.LLMTaskState
import com.ailux.chatdemo.components.ModelProviderChipRow
import com.ailux.chatdemo.debug.DebugPanel
import com.ailux.chatdemo.drawer.ConversationItem
import com.ailux.chatdemo.drawer.DrawerContent
import com.ailux.chatdemo.model.ChatMessage
import kotlinx.coroutines.launch

/**
 * Main chat screen composable — redesigned with:
 * - Left navigation drawer (conversations, models, settings, dev tools)
 * - Clean TopBar (hamburger + title + new chat)
 * - Provider/Model chips above input bar
 * - No inline ProviderModeHint or QuickPromptBar clutter
 *
 * @param viewModel The [ChatViewModel] instance.
 * @param currentMode The current [ProviderMode].
 * @param onSwitchProvider Callback to switch the active provider mode at runtime.
 * @param onOpenModelManager Callback to open model download/selection dialog.
 * @param onNewConversation Callback to start a new conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    isConfigured: Boolean = true,
    providerModeLabel: String = "MockProvider · Offline demo mode",
    currentMode: ProviderMode = ProviderMode.MOCK,
    onSwitchProvider: (ProviderMode) -> Unit = {},
    onOpenModelManager: (() -> Unit)? = null,
    onNewConversation: () -> Unit = {},
    downloadPanel: (@Composable () -> Unit)? = null,
) {
    val messages by viewModel.messages.collectAsState()
    val taskState by viewModel.state.collectAsState()
    val debugConfig by viewModel.debugConfig.collectAsState()
    val privacyVerbose by ChatClientManager.privacyVerbose.collectAsState()
    val currentLanguage by AppLocaleManager.language.collectAsState()
    val modelPath by ChatClientManager.modelPath.collectAsState()
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var showDebugPanel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom
    val lastMessage = messages.lastOrNull()
    val tailContentLength = lastMessage?.content?.length ?: 0
    val tailReasoningLength = lastMessage?.reasoningContent?.length ?: 0
    LaunchedEffect(messages.size, tailContentLength, tailReasoningLength) {
        if (messages.isEmpty()) return@LaunchedEffect
        val targetIndex = listState.layoutInfo.totalItemsCount - 1
        if (targetIndex < 0) return@LaunchedEffect
        if (lastMessage?.isStreaming == true) {
            listState.scrollToItem(targetIndex)
        } else {
            listState.animateScrollToItem(targetIndex)
        }
    }

    // Derive model display name from path
    val modelDisplayName = remember(modelPath) {
        modelPath?.let { path ->
            path.substringAfterLast("/")
                .removeSuffix(".litertlm")
                .replace("_", " ")
        }
    }

    // Conversation list (single session for now, multi-session in P2)
    val conversations = remember(messages) {
        if (messages.isNotEmpty()) {
            listOf(
                ConversationItem(
                    id = "current",
                    title = messages.firstOrNull { it.role == "user" }?.content?.take(30)
                        ?: Strings.newChat,
                    lastMessage = messages.lastOrNull()?.content?.take(50) ?: "",
                    isActive = true,
                )
            )
        } else {
            emptyList()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    conversations = conversations,
                    onNewConversation = {
                        onNewConversation()
                        scope.launch { drawerState.close() }
                    },
                    onSelectConversation = { _ ->
                        // Single session for now; multi-session in P2
                        scope.launch { drawerState.close() }
                    },
                    onDeleteConversation = { _ ->
                        onNewConversation()
                        scope.launch { drawerState.close() }
                    },
                    currentModelName = modelDisplayName,
                    isModelReady = modelPath != null,
                    onOpenModelManager = {
                        scope.launch { drawerState.close() }
                        onOpenModelManager?.invoke()
                    },
                    // Settings — system prompt editable inline
                    systemPrompt = debugConfig.model, // TODO: replace with actual system prompt state
                    onSystemPromptChange = { /* TODO: wire to ChatViewModel.systemInstruction */ },
                    onOpenSamplingSettings = {
                        scope.launch { drawerState.close() }
                        showDebugPanel = true
                    },
                    onOpenContextSettings = {
                        scope.launch { drawerState.close() }
                        showDebugPanel = true
                    },
                    onOpenDevTools = {
                        scope.launch { drawerState.close() }
                        showDebugPanel = true
                    },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Open drawer",
                            )
                        }
                    },
                    title = {
                        Text(
                            text = Strings.appTitle,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    actions = {
                        IconButton(onClick = onNewConversation) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = Strings.newChat,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            contentWindowInsets = WindowInsets(0), // Let us handle insets manually
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                // Message list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(modifier = Modifier.height(8.dp)) }

                    if (messages.isEmpty()) {
                        item {
                            EmptyStateHint()
                        }
                    }

                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                // Status indicator bar
                StatusBar(taskState = taskState)

                // ═══ Bottom input area ═══
                // imePadding ensures this section sticks just above the keyboard.
                // navigationBarsPadding handles the gesture nav bar on edge-to-edge.
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding(),
                    tonalElevation = 3.dp,
                ) {
                    Column {
                        // Chip row: Provider + Model selector
                        ModelProviderChipRow(
                            currentMode = currentMode,
                            currentModelName = modelDisplayName,
                            onProviderSelected = { mode ->
                                onSwitchProvider(mode)
                            },
                            onModelChipClick = {
                                onOpenModelManager?.invoke()
                            },
                        )

                        // Input field + send button
                        InputRow(
                            inputText = inputText,
                            onInputChange = { inputText = it },
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    viewModel.send(inputText.trim())
                                    inputText = ""
                                }
                            },
                            onCancel = { viewModel.cancel() },
                            isGenerating = taskState is LLMTaskState.Connecting || taskState is LLMTaskState.Streaming,
                            enabled = isConfigured,
                        )
                    }
                }
            }
        }
    }

    // ── Debug Panel (retained as ModalBottomSheet for settings) ──
    if (showDebugPanel) {
        DebugPanel(
            config = debugConfig,
            onConfigChange = { viewModel.updateDebugConfig(it) },
            onDismiss = { showDebugPanel = false },
            onRebuildClient = {
                onSwitchProvider(debugConfig.providerMode)
                showDebugPanel = false
            },
            privacyVerbose = privacyVerbose,
            onPrivacyVerboseChange = { ChatClientManager.setPrivacyVerbose(it) },
            onCopyLastTaskDiagnostic = {
                val text = viewModel.lastTaskDiagnosticText()
                if (text.isNullOrBlank()) {
                    Toast.makeText(context, Strings.toastNoDiagnostic, Toast.LENGTH_SHORT).show()
                } else {
                    copyToClipboard(context, "Ailux last-task diagnostic", text)
                    Toast.makeText(context, Strings.toastDiagnosticCopied, Toast.LENGTH_SHORT).show()
                }
            },
            onCopySessionDiagnostic = {
                val text = viewModel.sessionDiagnosticText(includeRecentTasks = 5)
                copyToClipboard(context, "Ailux session diagnostic", text)
                Toast.makeText(context, Strings.toastDiagnosticCopied, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

/**
 * Copies [text] to the system clipboard tagged with [label].
 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

// ──────────────────────────────────────────
// Sub-components
// ──────────────────────────────────────────

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = screenWidth * 0.78f)
                .animateContentSize(),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            tonalElevation = if (isUser) 0.dp else 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                // Reasoning section
                if (!isUser && (message.reasoningContent.isNotEmpty() || message.isReasoning)) {
                    ReasoningSection(
                        reasoningContent = message.reasoningContent,
                        isReasoning = message.isReasoning,
                    )
                    if (message.content.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            thickness = 0.5.dp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Main content
                if (message.content.isNotEmpty() || isUser || (!message.isReasoning && message.reasoningContent.isEmpty())) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = message.content.ifEmpty { " " },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isUser) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (message.isStreaming && !message.isReasoning) {
                            Spacer(modifier = Modifier.width(2.dp))
                            BlinkingCursor()
                        }
                    }
                }

                if (!isUser && message.usageLabel != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message.usageLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningSection(
    reasoningContent: String,
    isReasoning: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val showExpanded = expanded || isReasoning

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .clickable { expanded = !expanded }
            .padding(8.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isReasoning) "Thinking..." else "Reasoning",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (showExpanded) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )
        }

        if (showExpanded) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = reasoningContent.ifEmpty { " " },
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                if (isReasoning) {
                    Spacer(modifier = Modifier.width(2.dp))
                    BlinkingCursor()
                }
            }
        } else if (reasoningContent.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = reasoningContent,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    Box(
        modifier = Modifier
            .size(width = 2.dp, height = 18.dp)
            .alpha(alpha)
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(1.dp),
            ),
    )
}

@Composable
private fun StatusBar(taskState: LLMTaskState) {
    AnimatedVisibility(
        visible = taskState !is LLMTaskState.Idle && taskState !is LLMTaskState.Completed,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            when (taskState) {
                is LLMTaskState.Connecting -> {
                    Text(
                        text = Strings.connecting,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is LLMTaskState.Streaming -> {
                    Text(
                        text = "${Strings.generating} (${taskState.tokenCount} ${Strings.tokens})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is LLMTaskState.Failed -> {
                    Text(
                        text = taskState.error.message ?: "Error",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is LLMTaskState.Cancelling -> {
                    Text(
                        text = Strings.cancelling,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun InputRow(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(text = if (enabled) Strings.typeMessage else "Please configure local.properties first")
            },
            shape = RoundedCornerShape(24.dp),
            singleLine = false,
            maxLines = 6,
            enabled = enabled,
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = if (isGenerating) onCancel else onSend,
            enabled = enabled && (isGenerating || inputText.isNotBlank()),
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    color = if (isGenerating) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    shape = CircleShape,
                ),
        ) {
            Icon(
                imageVector = if (isGenerating) {
                    Icons.Filled.Close
                } else {
                    Icons.AutoMirrored.Filled.Send
                },
                contentDescription = if (isGenerating) Strings.cancel else Strings.send,
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun EmptyStateHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "AI",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = Strings.appTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Ask me anything. Switch models below.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
