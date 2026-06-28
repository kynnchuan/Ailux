package com.ailux.chatdemo.download

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.ailux.chatdemo.AppLanguage
import com.ailux.chatdemo.AppLocaleManager

/**
 * Composable panel for model download UI.
 *
 * Shows different states: idle (download prompt), downloading (progress),
 * ready (model available), or error (with retry/switch-source guidance).
 *
 * @param viewModel The [ModelDownloadViewModel] managing download state.
 * @param onModelReady Called when model is downloaded and verified, with the path.
 * @param onPickFile Called when user wants to use SAF file picker instead.
 */
@Composable
fun ModelDownloadPanel(
    viewModel: ModelDownloadViewModel,
    onModelReady: (String) -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = s("端侧模型", "On-Device Model"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = s(
                    "Qwen2-1.5B-Instruct (INT8, ~1.7GB)，首次使用需联网下载，之后全程离线。",
                    "Qwen2-1.5B-Instruct (INT8, ~1.7GB). First download required, then fully offline."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            when (val s = state) {
                is DownloadUiState.Idle -> IdleContent(
                    source = s.source,
                    onStartDownload = { viewModel.startDownload() },
                    onSwitchSource = { viewModel.switchSource(it) },
                    onPickFile = onPickFile,
                )
                is DownloadUiState.Downloading -> DownloadingContent(
                    progressFraction = s.progressFraction,
                    statusText = s.statusText,
                    onCancel = { viewModel.cancelDownload() },
                )
                is DownloadUiState.Ready -> {
                    ReadyContent(
                        modelPath = s.modelPath,
                        onLoad = { onModelReady(s.modelPath) },
                        onDelete = { viewModel.deleteModel() },
                    )
                }
                is DownloadUiState.Error -> ErrorContent(
                    message = s.message,
                    suggestion = s.suggestion,
                    canRetry = s.canRetry,
                    currentSource = s.currentSource,
                    onRetry = { viewModel.retry() },
                    onSwitchSource = { viewModel.switchSource(it) },
                    onPickFile = onPickFile,
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    source: ModelDownloader.MirrorSource,
    onStartDownload: () -> Unit,
    onSwitchSource: (ModelDownloader.MirrorSource) -> Unit,
    onPickFile: () -> Unit,
) {
    Column {
        // Source indicator
        Text(
            text = s("下载源: ", "Source: ") + source.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onStartDownload,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(s("下载模型", "Download"))
            }

            OutlinedButton(onClick = onPickFile) {
                Text(s("选择文件", "Pick File"))
            }
        }

        // Switch source link
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
private fun DownloadingContent(
    progressFraction: Float,
    statusText: String,
    onCancel: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = s("取消", "Cancel"),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "${(progressFraction * 100).toInt()}%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ReadyContent(
    modelPath: String,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = s("模型已就绪", "Model Ready"),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = modelPath.substringAfterLast("/"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onLoad) {
                Text(s("加载模型", "Load Model"))
            }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(s("删除", "Delete"))
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    suggestion: String,
    canRetry: Boolean,
    currentSource: ModelDownloader.MirrorSource,
    onRetry: () -> Unit,
    onSwitchSource: (ModelDownloader.MirrorSource) -> Unit,
    onPickFile: () -> Unit,
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

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canRetry) {
                Button(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(s("重试", "Retry"))
                }
            }

            val otherSource = ModelDownloader.MirrorSource.entries.first { it != currentSource }
            OutlinedButton(onClick = { onSwitchSource(otherSource) }) {
                Text(s("换源", "Switch Source"))
            }

            TextButton(onClick = onPickFile) {
                Text(s("选择文件", "Pick File"))
            }
        }
    }
}

/** Locale-aware string helper (reuses AppLocaleManager). */
private fun s(zh: String, en: String): String =
    if (AppLocaleManager.language.value == AppLanguage.CHINESE) zh else en
