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
                // upload_id 确定性生成(契约 §7):UUIDv5(设备ID+SHA256+采集日期),
                // 同任务重试必须复用——故在入库前先流式算哈希(1-3s,IO 线程)
                val source = com.highlightrecorder.upload.ClipChunkSource(
                    app.contentResolver, clipUri, sizeBytes,
                )
                val sha256 = runCatching { sha256Of(source, sizeBytes) }.getOrNull()
                val uploadId = if (sha256 != null) {
                    UploadIds.stableUploadId(deps.tokenStore.deviceId(), sha256, now)
                } else {
                    UUID.randomUUID().toString() // 哈希失败兜底:随机 id,Worker 内补算 sha256
                }
                val entity = UploadTaskEntity(
                    clientUploadId = uploadId,
                    contentUri = clipUri.toString(),
                    // 上传文件名含 upload_id 短码(契约 §5 建议形态,唯一性由 upload_id 保证)
                    fileName = "replay-" +
                        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(java.util.Date(now)) + "-" + uploadId.substring(0, 8) + ".mp4",
                    mimeType = "video/mp4",
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
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

    /** App 启动时恢复队列:清理僵死 UPLOADING,把未完成任务重新入队。 */
    fun reconcile(context: Context) {
        scope.launch {
            runCatching {
                val app = context.applicationContext
                val deps = Deps.get(app)
                deps.repo.resetStuck()
                enqueuePending(app)
            }.onFailure { FileLogger.log(TAG, "reconcile failed", it) }
        }
    }

    /**
     * 配对成功后立即恢复队列:把认证暂停(RETRY_WAIT+nextRetryAt=null)/排队中的
     * 任务按当前设置重新入队,不再等下次 App 启动的 reconcile(重新配对是用户
     * 当场完成的动作,当场恢复才兑现"配对成功后自动续传"的文案承诺)。
     */
    fun resumeAfterAuthRestored(context: Context) {
        scope.launch {
            runCatching { enqueuePending(context.applicationContext) }
                .onFailure { FileLogger.log(TAG, "resumeAfterAuthRestored failed", it) }
        }
    }

    /**
     * wifiOnly 切换后按新网络约束对全部 pending 任务重新入队:WorkManager 的
     * constraint 在入队时固定(UploadScheduler),DataStore 变更不会传播到已排队的
     * work。pending 只含 QUEUED/RETRY_WAIT(REPLACE 策略不会打断进行中的 UPLOADING)。
     */
    fun onWifiOnlyChanged(context: Context, wifiOnly: Boolean) {
        scope.launch {
            runCatching { enqueuePending(context.applicationContext, wifiOnly) }
                .onFailure { FileLogger.log(TAG, "onWifiOnlyChanged failed", it) }
        }
    }

    /**
     * 按判据 [UploadSettings.canAutoQueue] 把全部 pending 任务重新入队。
     * 旧实现用 settings.configured(静态令牌判据)做守卫,纯配对设备
     * (生产正路,静态令牌为空)被判为未配置,任务连启动恢复也会被跳过。
     */
    private suspend fun enqueuePending(app: Context, wifiOnly: Boolean? = null) {
        val deps = Deps.get(app)
        val settings = deps.settingsRepo.snapshot()
        val paired = deps.tokenStore.isPaired()
        if (!settings.canAutoQueue(paired)) return
        deps.repo.pending().forEach {
            UploadScheduler.enqueue(app, it.clientUploadId, wifiOnly ?: settings.wifiOnly)
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
