package com.ailux.chatdemo.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ailux.chatdemo.AppLanguage
import com.ailux.chatdemo.AppLocaleManager

/**
 * Model available in the download catalog.
 */
data class AvailableModel(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeLabel: String,
    val isDownloaded: Boolean = false,
    val modelPath: String? = null,
)

/**
 * BottomSheet-based model management UI.
 *
 * Shows:
 * - Title bar with source switcher
 * - List of available models (downloaded status, download/load actions)
 * - Download progress inline when active
 * - Pick file option
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadDialog(
    viewModel: ModelDownloadViewModel,
    onModelReady: (String) -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            if (state !is DownloadUiState.Downloading) {
                onDismiss()
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // ── Title bar ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = s("端侧模型管理", "On-Device Models"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(4.dp))

            // Source switcher inline
            val currentSource = when (val s = state) {
                is DownloadUiState.Idle -> s.source
                is DownloadUiState.Downloading -> s.source
                is DownloadUiState.Error -> s.currentSource
                else -> ModelDownloader.MirrorSource.HF_MIRROR
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Text(
                    text = s("下载源: ", "Source: ") + currentSource.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                val otherSource = ModelDownloader.MirrorSource.entries.first { it != currentSource }
                TextButton(
                    onClick = { viewModel.switchSource(otherSource) },
                    enabled = state !is DownloadUiState.Downloading,
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = s("切换到 ${otherSource.label}", "Switch to ${otherSource.label}"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ── Model list ──
            // Available models catalog
            val availableModels = listOf(
                AvailableModel(
                    id = "qwen2-1.5b",
                    displayName = "Qwen2-1.5B-Instruct",
                    description = "INT8 量化，适合端侧推理",
                    sizeLabel = "~1.7 GB",
                    isDownloaded = state is DownloadUiState.Ready,
                    modelPath = (state as? DownloadUiState.Ready)?.modelPath,
                ),
            )

            availableModels.forEach { model ->
                ModelListItem(
                    model = model,
                    downloadState = state,
                    onDownload = { viewModel.startDownload() },
                    onLoad = { model.modelPath?.let(onModelReady) },
                    onCancel = { viewModel.cancelDownload() },
                    onRetry = { viewModel.retry() },
                    onDelete = { viewModel.deleteModel() },
                )
                Spacer(Modifier.height(8.dp))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Pick file option
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPickFile),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = s("从设备选择模型文件", "Pick model file from device"),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = s("支持 .litertlm 格式", "Supports .litertlm format"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelListItem(
    model: AvailableModel,
    downloadState: DownloadUiState,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Model info header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${model.description} · ${model.sizeLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (model.isDownloaded) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // State-dependent actions
            when (downloadState) {
                is DownloadUiState.Idle -> {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s("开始下载", "Download"))
                    }
                }
                is DownloadUiState.Downloading -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { downloadState.progressFraction },
                            modifier = Modifier.fillMaxWidth(),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = downloadState.statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${(downloadState.progressFraction * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(s("取消下载", "Cancel"))
                        }
                    }
                }
                is DownloadUiState.Ready -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onLoad,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(s("加载模型", "Load Model"))
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(s("删除", "Delete"))
                        }
                    }
                }
                is DownloadUiState.Error -> {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = downloadState.suggestion,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (downloadState.canRetry) {
                                Button(onClick = onRetry) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(s("重试", "Retry"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Locale-aware string helper. */
private fun s(zh: String, en: String): String =
    if (AppLocaleManager.language.value == AppLanguage.CHINESE) zh else en
