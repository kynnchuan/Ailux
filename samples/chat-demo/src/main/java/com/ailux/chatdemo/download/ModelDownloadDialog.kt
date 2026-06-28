package com.ailux.chatdemo.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ailux.chatdemo.AppLanguage
import com.ailux.chatdemo.AppLocaleManager

/**
 * Dialog-based model download UI.
 *
 * Replaces the inline panel approach with a proper AlertDialog that floats
 * above the chat content. Combined with [ModelDownloadService] for background
 * progress notifications.
 *
 * @param viewModel The [ModelDownloadViewModel] managing download state.
 * @param onModelReady Called when model is downloaded and verified.
 * @param onPickFile Called when user wants to use SAF file picker instead.
 * @param onDismiss Called when user closes the dialog.
 */
@Composable
fun ModelDownloadDialog(
    viewModel: ModelDownloadViewModel,
    onModelReady: (String) -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    AlertDialog(
        onDismissRequest = {
            // Allow dismiss only when not actively downloading
            if (state !is DownloadUiState.Downloading) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = state !is DownloadUiState.Downloading,
            dismissOnClickOutside = state !is DownloadUiState.Downloading,
        ),
        icon = {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        },
        title = {
            Text(
                text = s("端侧模型管理", "On-Device Model"),
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = s(
                        "Qwen2-1.5B-Instruct (INT8, ~1.7GB)\n首次使用需联网下载，之后全程离线运行。",
                        "Qwen2-1.5B-Instruct (INT8, ~1.7GB)\nFirst download required, then fully offline."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                when (val s = state) {
                    is DownloadUiState.Idle -> IdleDialogContent(
                        source = s.source,
                        onSwitchSource = { viewModel.switchSource(it) },
                    )
                    is DownloadUiState.Downloading -> DownloadingDialogContent(
                        progressFraction = s.progressFraction,
                        statusText = s.statusText,
                    )
                    is DownloadUiState.Ready -> ReadyDialogContent(
                        modelPath = s.modelPath,
                    )
                    is DownloadUiState.Error -> ErrorDialogContent(
                        message = s.message,
                        suggestion = s.suggestion,
                    )
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is DownloadUiState.Idle -> {
                    Button(onClick = { viewModel.startDownload() }) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(s("开始下载", "Download"))
                    }
                }
                is DownloadUiState.Downloading -> {
                    OutlinedButton(
                        onClick = { viewModel.cancelDownload() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(s("取消下载", "Cancel"))
                    }
                }
                is DownloadUiState.Ready -> {
                    Button(onClick = { onModelReady(s.modelPath) }) {
                        Text(s("加载模型", "Load Model"))
                    }
                }
                is DownloadUiState.Error -> {
                    if (s.canRetry) {
                        Button(onClick = { viewModel.retry() }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s("重试", "Retry"))
                        }
                    }
                }
            }
        },
        dismissButton = {
            when (val s = state) {
                is DownloadUiState.Idle -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onPickFile) {
                            Text(s("选择文件", "Pick File"))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(s("取消", "Cancel"))
                        }
                    }
                }
                is DownloadUiState.Downloading -> {
                    // No dismiss during download
                }
                is DownloadUiState.Ready -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { viewModel.deleteModel() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(s("删除模型", "Delete"))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(s("关闭", "Close"))
                        }
                    }
                }
                is DownloadUiState.Error -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val otherSource = ModelDownloader.MirrorSource.entries.first { it != s.currentSource }
                        TextButton(onClick = { viewModel.switchSource(otherSource) }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s("换源", "Switch"))
                        }
                        TextButton(onClick = onPickFile) {
                            Text(s("选择文件", "Pick File"))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(s("关闭", "Close"))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun IdleDialogContent(
    source: ModelDownloader.MirrorSource,
    onSwitchSource: (ModelDownloader.MirrorSource) -> Unit,
) {
    Column {
        Text(
            text = s("当前下载源: ", "Current source: ") + source.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val otherSource = ModelDownloader.MirrorSource.entries.first { it != source }
        TextButton(
            onClick = { onSwitchSource(otherSource) },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                text = s("切换到 ${otherSource.label}", "Switch to ${otherSource.label}"),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun DownloadingDialogContent(
    progressFraction: Float,
    statusText: String,
) {
    Column {
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${(progressFraction * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = s(
                "下载过程中可最小化此对话框，进度将在通知栏显示。",
                "You can minimize this dialog. Progress is shown in notifications."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ReadyDialogContent(modelPath: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = s("模型已就绪", "Model Ready"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = modelPath.substringAfterLast("/"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ErrorDialogContent(
    message: String,
    suggestion: String,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = s("下载失败", "Download Failed"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        ) {
            Text(
                text = suggestion,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

/** Locale-aware string helper. */
private fun s(zh: String, en: String): String =
    if (AppLocaleManager.language.value == AppLanguage.CHINESE) zh else en
