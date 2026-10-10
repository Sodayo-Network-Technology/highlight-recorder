package com.highlightrecorder.upload

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.highlightrecorder.data.FileLogger
import com.highlightrecorder.upload.db.UploadTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * 保存成功 → 任务入库 → 入队(顺序固定:持久化先于调度,对接协议 §6;
 * savedEvents Flow 只用于 UI 提示,不作为任务记录)。
 */
object UploadCoordinator {

    private const val TAG = "UploadCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** ClipWriter 写盘成功后调用(IO 线程)。上传未启用/未配置时静默跳过,不影响录制。 */
    fun onClipSaved(context: Context, clipUri: Uri, sizeBytes: Long, durationMs: Long) {
        scope.launch {
            runCatching {
                val app = context.applicationContext
                val deps = Deps.get(app)
                val settings = deps.settingsRepo.snapshot()
                if (!settings.enabled) return@launch
                val paired = deps.tokenStore.isPaired()
                if (!settings.serverUrlValid || (!paired && settings.token.isBlank())) {
                    FileLogger.log(TAG, "上传已启用但服务器未配置/未配对,跳过自动上传")
                    return@launch
                }
                val name = displayName(app, clipUri)
                if (name.isNullOrEmpty()) {
                    FileLogger.log(TAG, "无法解析文件名,跳过自动上传: $clipUri")
                    return@launch
                }
                val now = System.currentTimeMillis()
                val entity = UploadTaskEntity(
                    clientUploadId = UUID.randomUUID().toString(),
                    contentUri = clipUri.toString(),
                    // 文件名策略(交接单 §6):取证目录内唯一,replay_时间_短ID
                    fileName = "replay_" +
                        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(java.util.Date()) + "_" + shortId() + ".mp4",
                    mimeType = "video/mp4",
                    sizeBytes = sizeBytes,
                    recordedAt = now,
                    durationMs = durationMs,
                    createdAt = now,
                    updatedAt = now,
                )
                deps.repo.insert(entity) // 1. 先入库(事务事实来源)
                UploadScheduler.enqueue(app, entity.clientUploadId, settings.wifiOnly) // 2. 后入队
                FileLogger.log(TAG, "已排队上传: ${entity.fileName}")
            }.onFailure { FileLogger.log(TAG, "onClipSaved failed", it) }
        }
    }

    private fun shortId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 8)

    /** App 启动时恢复队列:清理僵死 UPLOADING,把未完成任务重新入队。 */
    fun reconcile(context: Context) {
        scope.launch {
            runCatching {
                val app = context.applicationContext
                val deps = Deps.get(app)
                deps.repo.resetStuck()
                val settings = deps.settingsRepo.snapshot()
                if (!settings.configured) return@launch
                deps.repo.pending().forEach {
                    UploadScheduler.enqueue(app, it.clientUploadId, settings.wifiOnly)
                }
            }.onFailure { FileLogger.log(TAG, "reconcile failed", it) }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "content") {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } else {
            uri.path?.let { File(it).name }
        }
    }.getOrNull()
}
