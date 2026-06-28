package com.ailux.chatdemo.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ailux.chatdemo.ProviderMode
import com.ailux.chatdemo.Strings

/**
 * Chip row displayed above the input field.
 * Shows current provider mode and model name as tappable chips.
 * Tapping opens a BottomSheet for selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelProviderChipRow(
    currentMode: ProviderMode,
    currentModelName: String?,
    onProviderSelected: (ProviderMode) -> Unit,
    onModelChipClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showProviderSheet by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Provider chip
        ProviderChip(
            mode = currentMode,
            onClick = { showProviderSheet = true },
        )

        // Model chip (only meaningful for LOCAL_RUNTIME and BACKEND_PROXY)
        if (currentMode == ProviderMode.LOCAL_RUNTIME || currentMode == ProviderMode.BACKEND_PROXY) {
            ModelChip(
                modelName = currentModelName,
                mode = currentMode,
                onClick = onModelChipClick,
            )
        }
    }

    // Provider selection BottomSheet
    if (showProviderSheet) {
        ProviderSelectionSheet(
            currentMode = currentMode,
            onSelect = { mode ->
                showProviderSheet = false
                onProviderSelected(mode)
            },
            onDismiss = { showProviderSheet = false },
        )
    }
}

@Composable
private fun ProviderChip(
    mode: ProviderMode,
    onClick: () -> Unit,
) {
    val (containerColor, contentColor, icon) = when (mode) {
        ProviderMode.MOCK -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Filled.Science,
        )
        ProviderMode.BACKEND_PROXY -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Filled.Cloud,
        )
        ProviderMode.LOCAL_RUNTIME -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Filled.Memory,
        )
    }

    val label = when (mode) {
        ProviderMode.MOCK -> Strings.chipMock
        ProviderMode.BACKEND_PROXY -> Strings.chipBackend
        ProviderMode.LOCAL_RUNTIME -> Strings.chipLocal
    }

    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}

@Composable
private fun ModelChip(
    modelName: String?,
    mode: ProviderMode,
    onClick: () -> Unit,
) {
    val displayName = modelName ?: Strings.chipSelectModel
    val containerColor = when (mode) {
        ProviderMode.LOCAL_RUNTIME -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (mode) {
        ProviderMode.LOCAL_RUNTIME -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "\u25BE", // ▾
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.6f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSelectionSheet(
    currentMode: ProviderMode,
    onSelect: (ProviderMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = Strings.sheetSelectProvider,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            ProviderMode.entries.forEach { mode ->
                ProviderOptionRow(
                    mode = mode,
                    isSelected = mode == currentMode,
                    onClick = { onSelect(mode) },
                )
            }

            Spacer(modifier = Modifier.padding(bottom = 32.dp))
        }
    }
}

@Composable
private fun ProviderOptionRow(
    mode: ProviderMode,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val icon: ImageVector
    val title: String
    val description: String

    when (mode) {
        ProviderMode.MOCK -> {
            icon = Icons.Filled.Science
            title = Strings.mockProvider
            description = Strings.mockProviderDesc
        }
        ProviderMode.BACKEND_PROXY -> {
            icon = Icons.Filled.Cloud
            title = Strings.backendProxy
            description = Strings.backendProxyDesc
        }
        ProviderMode.LOCAL_RUNTIME -> {
            icon = Icons.Filled.Memory
            title = Strings.localRuntime
            description = Strings.localRuntimeDesc
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
