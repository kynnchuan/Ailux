package com.ailux.chatdemo.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ailux.chatdemo.ProviderMode

/**
 * Debug Panel — a runtime configuration ModalBottomSheet for the demo app.
 *
 * Provides controls for all debug parameters defined in [DebugConfig].
 * Split into two sections:
 * - "Request-level" params: take effect immediately on the next send().
 * - "Client-level" params: require a client rebuild (marked with a badge).
 *
 * Entry point: gear icon in the TopAppBar (visible only in DEBUG builds).
 * Per v0.2.2 §14.4.5: pure app-module debug facility, never enters the SDK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugPanel(
    config: DebugConfig,
    onConfigChange: (DebugConfig) -> Unit,
    onDismiss: () -> Unit,
    onRebuildClient: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // Title
            Text(
                text = "Debug Panel",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Runtime configuration for testing v0.2.2~v0.2.4 features",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ═══════════════════════════════════════════════
            // Section: Request-level (instant)
            // ═══════════════════════════════════════════════
            SectionHeader(title = "Request-level", subtitle = "Instant, no rebuild needed")

            // Model
            LabeledTextField(
                label = "Model",
                value = config.model,
                onValueChange = { onConfigChange(config.copy(model = it)) },
                placeholder = "e.g. deepseek-v4-flash, gpt-4o",
            )

            // Provider routing
            LabeledTextField(
                label = "Provider (overrides.provider)",
                value = config.provider,
                onValueChange = { onConfigChange(config.copy(provider = it)) },
                placeholder = "e.g. deepseek, openai",
            )

            // Context mode
            ChipRow(
                label = "Context mode",
                options = listOf("server", "client"),
                selected = config.contextMode,
                onSelect = { onConfigChange(config.copy(contextMode = it)) },
            )

            // Preset accounts
            ChipRow(
                label = "Account",
                options = PresetAccount.entries.map { it.label },
                selected = config.presetAccount.label,
                onSelect = { label ->
                    val account = PresetAccount.entries.first { it.label == label }
                    onConfigChange(config.copy(presetAccount = account))
                },
            )

            // Session ID
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LabeledTextField(
                    label = "Session ID",
                    value = config.sessionId,
                    onValueChange = { onConfigChange(config.copy(sessionId = it)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.width(8.dp))
                ActionChip(
                    text = "New",
                    onClick = {
                        onConfigChange(config.copy(sessionId = java.util.UUID.randomUUID().toString()))
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stop sequences (v0.2.4)
            LabeledTextField(
                label = "Stop sequences (comma-separated)",
                value = config.stopSequences.joinToString(","),
                onValueChange = { raw ->
                    val stops = if (raw.isBlank()) emptyList()
                    else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onConfigChange(config.copy(stopSequences = stops))
                },
                placeholder = "e.g. \\n\\n,END",
            )

            // Custom overrides JSON (v0.2.4)
            LabeledTextField(
                label = "Custom overrides JSON (v0.2.4)",
                value = config.customOverridesJson,
                onValueChange = { onConfigChange(config.copy(customOverridesJson = it)) },
                placeholder = "{\"seed\": 42, \"response_format\": {\"type\": \"json_object\"}}",
                maxLines = 4,
                fontFamily = FontFamily.Monospace,
            )

            // Attach test image (v0.2.4 multimodal)
            SwitchRow(
                label = "Attach test image (v0.2.4 multimodal)",
                checked = config.attachTestImage,
                onCheckedChange = { onConfigChange(config.copy(attachTestImage = it)) },
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // ═══════════════════════════════════════════════
            // Section: Client-level (requires rebuild)
            // ═══════════════════════════════════════════════
            SectionHeader(
                title = "Client-level",
                subtitle = "Requires client rebuild to take effect",
            )

            // Provider mode
            ChipRow(
                label = "Provider mode",
                options = ProviderMode.entries.map { it.label },
                selected = config.providerMode.label,
                onSelect = { label ->
                    val mode = ProviderMode.entries.first { it.label == label }
                    onConfigChange(config.copy(providerMode = mode))
                },
            )

            // Stall detection (v0.2.3)
            SwitchRow(
                label = "Stall detection (v0.2.3)",
                checked = config.stallDetectionEnabled,
                onCheckedChange = { onConfigChange(config.copy(stallDetectionEnabled = it)) },
            )

            AnimatedVisibility(visible = config.stallDetectionEnabled) {
                LabeledTextField(
                    label = "Stall idle threshold (ms)",
                    value = config.stallIdleThresholdMs.toString(),
                    onValueChange = { raw ->
                        raw.toLongOrNull()?.let { onConfigChange(config.copy(stallIdleThresholdMs = it)) }
                    },
                )
            }

            // Concurrency policy (v0.2.3)
            ChipRow(
                label = "Concurrency policy (v0.2.3)",
                options = listOf("CANCEL_PREVIOUS", "REJECT", "ENQUEUE"),
                selected = config.concurrencyPolicy,
                onSelect = { onConfigChange(config.copy(concurrencyPolicy = it)) },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Rebuild button
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRebuildClient() },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = "Rebuild Client",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ──────────────────────────────────────────
// Reusable sub-components
// ──────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    fontFamily: FontFamily? = null,
) {
    Column(modifier = modifier.padding(bottom = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                if (placeholder.isNotEmpty()) {
                    Text(text = placeholder, style = MaterialTheme.typography.bodySmall)
                }
            },
            singleLine = singleLine,
            maxLines = maxLines,
            shape = RoundedCornerShape(8.dp),
            textStyle = MaterialTheme.typography.bodyMedium.let { style ->
                if (fontFamily != null) style.copy(fontFamily = fontFamily) else style
            },
        )
    }
}

@Composable
private fun ChipRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    modifier = Modifier.clickable { onSelect(option) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionChip(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
