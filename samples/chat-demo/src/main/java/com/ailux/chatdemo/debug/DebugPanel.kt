package com.ailux.chatdemo.debug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ailux.chatdemo.AppLanguage
import com.ailux.chatdemo.AppLocaleManager
import com.ailux.chatdemo.BuildConfig
import com.ailux.chatdemo.ProviderMode
import com.ailux.chatdemo.Strings

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DebugPanel(
    config: DebugConfig,
    onConfigChange: (DebugConfig) -> Unit,
    onDismiss: () -> Unit,
    onRebuildClient: () -> Unit,
    // ── Diagnostics hooks (B2-2, DEBUG-only) ──
    privacyVerbose: Boolean = false,
    onPrivacyVerboseChange: (Boolean) -> Unit = {},
    onCopyLastTaskDiagnostic: () -> Unit = {},
    onCopySessionDiagnostic: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Collect language state so the panel recomposes immediately on switch
    val currentLanguage by AppLocaleManager.language.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ═══════════════════════════════════════════════
            // Sticky title bar — does NOT scroll with content
            // ═══════════════════════════════════════════════
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = Strings.panelTitle,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = Strings.panelSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Rebuild button in title bar
                Surface(
                    modifier = Modifier.clickable { onRebuildClient() },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = Strings.rebuild,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))

            // ═══════════════════════════════════════════════
            // Scrollable content area
            // ═══════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                // ─── Language switch (first item) ───
                FlowChipGroup(
                    label = Strings.language,
                    options = AppLanguage.entries.map { it.displayName },
                    selected = currentLanguage.displayName,
                    onSelect = { name ->
                        val lang = AppLanguage.entries.first { it.displayName == name }
                        AppLocaleManager.switchTo(lang)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // ═══════════════════════════════════════════════
                // Section: Request-level (instant)
                // ═══════════════════════════════════════════════
                SectionHeader(title = Strings.requestLevel, subtitle = Strings.requestLevelDesc)

                // Model
                LabeledTextField(
                    label = Strings.model,
                    value = config.model,
                    onValueChange = { onConfigChange(config.copy(model = it)) },
                    placeholder = Strings.modelPlaceholder,
                )

                // Provider routing
                LabeledTextField(
                    label = Strings.providerRouting,
                    value = config.provider,
                    onValueChange = { onConfigChange(config.copy(provider = it)) },
                    placeholder = Strings.providerRoutingPlaceholder,
                )

                // Context mode
                FlowChipGroup(
                    label = Strings.contextMode,
                    options = listOf("server", "client"),
                    selected = config.contextMode,
                    onSelect = { onConfigChange(config.copy(contextMode = it)) },
                )

                // Preset accounts (FlowRow for wrapping)
                FlowChipGroup(
                    label = Strings.account,
                    options = PresetAccount.entries.map { it.localizedLabel() },
                    selected = config.presetAccount.localizedLabel(),
                    onSelect = { label ->
                        val account = PresetAccount.entries.first { it.localizedLabel() == label }
                        onConfigChange(config.copy(presetAccount = account))
                    },
                )

                // Session ID
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LabeledTextField(
                        label = Strings.sessionId,
                        value = config.sessionId,
                        onValueChange = { onConfigChange(config.copy(sessionId = it)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ActionChip(
                        text = Strings.newSession,
                        onClick = {
                            onConfigChange(config.copy(sessionId = java.util.UUID.randomUUID().toString()))
                        },
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stop sequences (v0.2.4)
                LabeledTextField(
                    label = Strings.stopSequences,
                    value = config.stopSequences.joinToString(","),
                    onValueChange = { raw ->
                        val stops = if (raw.isBlank()) emptyList()
                        else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        onConfigChange(config.copy(stopSequences = stops))
                    },
                    placeholder = Strings.stopPlaceholder,
                )

                // Custom overrides JSON (v0.2.4)
                LabeledTextField(
                    label = Strings.customOverrides,
                    value = config.customOverridesJson,
                    onValueChange = { onConfigChange(config.copy(customOverridesJson = it)) },
                    placeholder = "{\"seed\": 42, \"response_format\": {\"type\": \"json_object\"}}",
                    maxLines = 4,
                    fontFamily = FontFamily.Monospace,
                )

                // Attach test image (v0.2.4 multimodal)
                SwitchRow(
                    label = Strings.attachTestImage,
                    checked = config.attachTestImage,
                    onCheckedChange = { onConfigChange(config.copy(attachTestImage = it)) },
                )

                // Streaming mode toggle (v0.2.6)
                SwitchRow(
                    label = "Streaming mode",
                    checked = config.useStreaming,
                    onCheckedChange = { onConfigChange(config.copy(useStreaming = it)) },
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // ═══════════════════════════════════════════════
                // Section: Client-level (requires rebuild)
                // ═══════════════════════════════════════════════
                SectionHeader(
                    title = Strings.clientLevel,
                    subtitle = Strings.clientLevelDesc,
                )

                // Provider mode (2-line chips: name + description)
                ProviderModeChipGroup(
                    label = Strings.providerMode,
                    selected = config.providerMode,
                    onSelect = { onConfigChange(config.copy(providerMode = it)) },
                )

                // Stall detection (v0.2.3)
                SwitchRow(
                    label = Strings.stallDetection,
                    checked = config.stallDetectionEnabled,
                    onCheckedChange = { onConfigChange(config.copy(stallDetectionEnabled = it)) },
                )

                AnimatedVisibility(visible = config.stallDetectionEnabled) {
                    LabeledTextField(
                        label = Strings.stallThreshold,
                        value = config.stallIdleThresholdMs.toString(),
                        onValueChange = { raw ->
                            raw.toLongOrNull()?.let { onConfigChange(config.copy(stallIdleThresholdMs = it)) }
                        },
                    )
                }

                // Concurrency policy (v0.2.3)
                FlowChipGroup(
                    label = Strings.concurrencyPolicy,
                    options = listOf("CANCEL_PREVIOUS", "REJECT", "ENQUEUE"),
                    selected = config.concurrencyPolicy,
                    onSelect = { onConfigChange(config.copy(concurrencyPolicy = it)) },
                )

                // ═══════════════════════════════════════════════
                // Section: Diagnostics (B2-2) — DEBUG builds only
                // ═══════════════════════════════════════════════
                if (BuildConfig.DEBUG) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    SectionHeader(
                        title = Strings.diagnostics,
                        subtitle = Strings.diagnosticsDesc,
                    )

                    // Copy last-task diagnostic
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clickable { onCopyLastTaskDiagnostic() },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = Strings.copyLastTaskDiagnostic,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }

                    // Copy session diagnostic (recent 5 tasks)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clickable { onCopySessionDiagnostic() },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = Strings.copySessionDiagnostic,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }

                    // Privacy verbose toggle (rebuild required)
                    SwitchRow(
                        label = Strings.privacyVerbose,
                        checked = privacyVerbose,
                        onCheckedChange = onPrivacyVerboseChange,
                    )
                    Text(
                        text = Strings.privacyVerboseDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom rebuild button
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRebuildClient() },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = Strings.rebuildClient,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
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

/**
 * Chip group using FlowRow — wraps to next line when horizontal space runs out.
 * Replaces the old Row-based ChipRow that could overflow.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChipGroup(
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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

/**
 * Provider mode chip group with 2-line display (name + description).
 * Uses FlowRow for wrapping on narrow screens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderModeChipGroup(
    label: String,
    selected: ProviderMode,
    onSelect: (ProviderMode) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderMode.entries.forEach { mode ->
                val isSelected = mode == selected
                val (title, desc) = mode.localizedTitleAndDesc()
                Surface(
                    modifier = Modifier.clickable { onSelect(mode) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            },
                        )
                    }
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

// ──────────────────────────────────────────
// Extension helpers for localized labels
// ──────────────────────────────────────────

private fun PresetAccount.localizedLabel(): String = when (this) {
    PresetAccount.FREE -> Strings.freeTier
    PresetAccount.PRO -> Strings.proTier
    PresetAccount.ADMIN -> Strings.adminTier
}

private fun ProviderMode.localizedTitleAndDesc(): Pair<String, String> = when (this) {
    ProviderMode.MOCK -> Strings.mockProvider to Strings.mockProviderDesc
    ProviderMode.BACKEND_PROXY -> Strings.backendProxy to Strings.backendProxyDesc
}
