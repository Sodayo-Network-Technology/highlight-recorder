package com.highlightrecorder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.highlightrecorder.upload.UploadSettings
import com.highlightrecorder.upload.db.UploadTaskEntity

/** 上传队列:任务状态一览(管理层可一眼看出谁没传),支持手动重试与清除记录。 */
@Composable
fun UploadScreen(viewModel: UploadViewModel, onBack: () -> Unit) {
    val tasks by viewModel.tasks.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val pair by viewModel.pairState.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("上传队列", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Text(
                when {
                    !settings.enabled -> "云端上传未启用,可在设置页开启;保存的录像仅留在本机。"
                    !settings.serverUrlValid -> "服务器地址未配置或无效(仅允许 https 公网地址)。"
                    settings.token.isBlank() && !pair.paired ->
                        "尚未配置凭据:请在设置页完成设备配对(推荐)或填写开发期令牌。"
                    else -> "保存成功后自动上传到 Sodayo SH Drive(${
                        if (pair.paired) "配对目录" else settings.targetFolder
                    })。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (tasks.isEmpty()) {
            item {
                Text(
                    "暂无上传任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }

        items(tasks, key = { it.clientUploadId }) { task ->
            TaskCard(task, onRetry = { viewModel.retry(task.clientUploadId) }, onRemove = { viewModel.remove(task.clientUploadId) })
        }

        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun TaskCard(task: UploadTaskEntity, onRetry: () -> Unit, onRemove: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val (label, color) = when (task.state) {
                    UploadTaskEntity.STATE_DONE -> "已上传" to MaterialTheme.colorScheme.primary
                    UploadTaskEntity.STATE_FAILED -> "失败" to MaterialTheme.colorScheme.error
                    UploadTaskEntity.STATE_UPLOADING -> "上传中" to MaterialTheme.colorScheme.tertiary
                    UploadTaskEntity.STATE_RETRY_WAIT -> "等待重试" to MaterialTheme.colorScheme.tertiary
                    else -> "排队中" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(label, color = color, style = MaterialTheme.typography.labelLarge)
            }

            val progress = if (task.sizeBytes > 0) {
                "${task.uploadedBytes * 100 / task.sizeBytes}%"
            } else {
                "-"
            }
            val detail = buildString {
                append(formatBytes(task.sizeBytes))
                if (task.state == UploadTaskEntity.STATE_UPLOADING || task.state == UploadTaskEntity.STATE_RETRY_WAIT) {
                    append(" · 已传 $progress")
                    task.totalParts?.let { append("(${task.uploadedParts}/$it 片)") }
                }
                append(" · ${formatTime(task.recordedAt)}")
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            task.lastError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.state in listOf(
                        UploadTaskEntity.STATE_FAILED,
                        UploadTaskEntity.STATE_RETRY_WAIT,
                        UploadTaskEntity.STATE_QUEUED,
                    )
                ) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                TextButton(onClick = onRemove) { Text("清除记录") }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= GB -> "%.1f GB".format(bytes / GB.toFloat())
    bytes >= MB -> "%.1f MB".format(bytes / MB.toFloat())
    bytes >= KB -> "%.0f KB".format(bytes / KB.toFloat())
    else -> "$bytes B"
}

private const val KB = 1L shl 10
private const val MB = 1L shl 20
private const val GB = 1L shl 30

private fun formatTime(epochMs: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(epochMs))
