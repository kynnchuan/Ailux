package com.ailux.demo

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ailux.core.model.LLMTaskState
import com.ailux.demo.model.ChatMessage

/**
 * Main chat screen composable.
 *
 * @param viewModel The [ChatViewModel] instance.
 * @param isConfigured Whether the SDK has been configured correctly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    isConfigured: Boolean = true,
    providerModeLabel: String = "MockProvider · Offline demo mode",
) {
    val messages by viewModel.messages.collectAsState()
    val taskState by viewModel.state.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom whenever the message list grows OR the
    // currently-streaming assistant message appends new tokens (content /
    // reasoningContent length changes). Without keying on the tail length,
    // streaming would only scroll once when the message is first inserted.
    val lastMessage = messages.lastOrNull()
    val tailContentLength = lastMessage?.content?.length ?: 0
    val tailReasoningLength = lastMessage?.reasoningContent?.length ?: 0
    LaunchedEffect(messages.size, tailContentLength, tailReasoningLength) {
        if (messages.isEmpty()) return@LaunchedEffect
        val targetIndex = listState.layoutInfo.totalItemsCount - 1
        if (targetIndex < 0) return@LaunchedEffect
        // Use non-animated scroll while streaming so we stay pinned to the
        // bottom even when tokens arrive faster than the scroll animation.
        if (lastMessage?.isStreaming == true) {
            listState.scrollToItem(targetIndex)
        } else {
            listState.animateScrollToItem(targetIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Ailux Demo",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = providerModeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding(),
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
                // Top spacing
                item { Spacer(modifier = Modifier.height(8.dp)) }

                item {
                    ProviderModeHint(providerModeLabel = providerModeLabel)
                }

                if (!isConfigured) {
                    item {
                        ConfigurationHint()
                    }
                } else if (messages.isEmpty()) {
                    item {
                        EmptyStateHint()
                    }
                }

                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }

                // Bottom spacing
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // Status indicator bar
            StatusBar(taskState = taskState)

            // Persistent quick prompt bar — always visible above the input box
            // so users can re-trigger sample prompts after the empty state
            // disappears. Disabled while a request is in flight.
            if (isConfigured) {
                QuickPromptBar(
                    onPromptSelected = { inputText = it },
                    enabled = taskState !is LLMTaskState.Connecting &&
                        taskState !is LLMTaskState.Streaming,
                )
            }

            // Bottom input bar
            InputBar(
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

// ──────────────────────────────────────────
// Sub-components
// ──────────────────────────────────────────

@Composable
private fun ProviderModeHint(providerModeLabel: String) {
    val isMockMode = providerModeLabel.startsWith("MockProvider")
    val containerColor = if (isMockMode) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
    }
    val description = if (isMockMode) {
        "No API key, no backend, no network requests — perfect for local demos and tests. Usage values are local estimates."
    } else {
        "Requests are forwarded through your Backend Proxy. Keep model API keys on the server side; never check real keys into the repository."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = providerModeLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
                // ── Reasoning section (only for assistant messages with reasoningContent) ──
                if (!isUser && (message.reasoningContent.isNotEmpty() || message.isReasoning)) {
                    ReasoningSection(
                        reasoningContent = message.reasoningContent,
                        isReasoning = message.isReasoning,
                    )

                    // Divider between reasoning and main content
                    if (message.content.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            thickness = 0.5.dp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── Main content area ──
                if (message.content.isNotEmpty() || (isUser) || (!message.isReasoning && message.reasoningContent.isEmpty())) {
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

                        // Blinking cursor while the main content is being streamed
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

/**
 * Collapsible reasoning display section.
 *
 * - Collapsed by default; only the title and a short preview are shown.
 * - Tap to expand or collapse the full reasoning content.
 * - Auto-expands while reasoning is in progress and shows a blinking cursor.
 */
@Composable
private fun ReasoningSection(
    reasoningContent: String,
    isReasoning: Boolean,
) {
    // Force-expand while reasoning is in progress; otherwise collapsed by default.
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
        // Title row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isReasoning) "💭 Thinking..." else "💭 Reasoning",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (showExpanded) "Collapse ▲" else "Expand ▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )
        }

        if (showExpanded) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = reasoningContent.ifEmpty { " " },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = FontStyle.Italic,
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                // Blinking cursor while reasoning is in progress
                if (isReasoning) {
                    Spacer(modifier = Modifier.width(2.dp))
                    BlinkingCursor()
                }
            }
        } else if (reasoningContent.isNotEmpty()) {
            // Show a short preview when collapsed
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = reasoningContent,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = FontStyle.Italic,
                ),
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
                        text = "Connecting...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is LLMTaskState.Streaming -> {
                    Text(
                        text = "Generating (${taskState.tokenCount} tokens)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                is LLMTaskState.Failed -> {
                    Text(
                        text = "❌ ${taskState.error.message}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is LLMTaskState.Cancelling -> {
                    Text(
                        text = "Cancelling...",
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
private fun InputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean,
    enabled: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
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
                    Text(
                        text = if (enabled) "Type a message..." else "Please configure local.properties first",
                    )
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = false,
                maxLines = 4,
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
                    contentDescription = if (isGenerating) "Cancel" else "Send",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun EmptyStateHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "🤖",
                style = MaterialTheme.typography.displayMedium,
            )
            Text(
                text = "Hi! I'm the Ailux Demo.\nTry the quick prompts above the input box.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Weather / Model match keyword rules; \"Hi\" hits the fallback. Tapping a chip fills the input box — feel free to edit before sending.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * Quick prompt bar that stays visible above the input box for the lifetime of
 * the screen, so users can re-trigger sample prompts even after the empty
 * state hint disappears. The chips are horizontally scrollable to keep the
 * row compact on narrow devices.
 */
@Composable
private fun QuickPromptBar(
    onPromptSelected: (String) -> Unit,
    enabled: Boolean,
) {
    val prompts = remember {
        listOf(
            "How's the weather today?",
            "What model are you?",
            "Hi",
        )
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            prompts.forEach { prompt ->
                DemoPromptChip(
                    text = prompt,
                    enabled = enabled,
                    onClick = { onPromptSelected(prompt) },
                )
            }
        }
    }
}

@Composable
private fun DemoPromptChip(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val containerAlpha = if (enabled) 1f else 0.5f
    Surface(
        modifier = Modifier
            .alpha(containerAlpha)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ConfigurationHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
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
                    text = "⚙️ Configuration missing",
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
