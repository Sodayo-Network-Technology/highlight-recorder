package com.highlightrecorder.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.highlightrecorder.upload.db.UploadTaskEntity
import com.highlightrecorder.upload.protocol.CloudreveV4Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 上传执行器:读取 Room 任务 → 哈希 → Cloudreve 三步协议 → 状态回写。
 *
 * 错误处置遵循对接协议 §7;单并发由 [Deps.uploadMutex] 保证(§7 建议并发 1)。
 * PAUSE_FOR_REAUTH 返回 failure 结束重试,等用户重新配置后由「上传队列页」或
 * 下次 App 启动的 reconcile 重新入队。
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val deps = Deps.get(app)
        val id = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val task = deps.repo.byId(id) ?: return Result.failure()
        if (task.state == UploadTaskEntity.STATE_DONE) return Result.success()

        if (runAttemptCount >= ErrorPolicy.MAX_ATTEMPTS) {
            deps.repo.markFailed(id, "重试 ${runAttemptCount} 次后放弃,请手动重试")
            return Result.failure()
        }

        val settings = deps.settingsRepo.snapshot()
        if (!settings.configured) {
            deps.repo.markRetryWait(id, runAttemptCount + 1, null, "上传未配置或凭据缺失,请到设置页完成配置")
            return Result.failure()
        }
        val base = BaseUrlValidator.validate(settings.serverUrl) ?: run {
            deps.repo.markFailed(id, "服务器地址无效(仅允许 https 公网地址)")
            return Result.failure()
        }

        runCatching { setForeground(buildForegroundInfo(task.fileName)) }

        deps.repo.markUploading(id)
        return try {
            deps.uploadMutex.withLock {
                executeUpload(deps, task, settings, base)
            }
        } catch (e: UploadException) {
            val attempt = runAttemptCount + 1
            when (e.disposition) {
                RetryDisposition.PERMANENT -> {
                    deps.repo.markFailed(id, e.message)
                    Result.failure()
                }
                RetryDisposition.PAUSE_FOR_REAUTH -> {
                    deps.repo.markRetryWait(id, attempt, null, "需要重新认证: ${e.message}")
                    Result.failure()
                }
                RetryDisposition.RETRY -> {
                    val delayMs = ErrorPolicy.retryDelayMillis(attempt, e.retryAfterSeconds)
                    deps.repo.markRetryWait(id, attempt, System.currentTimeMillis() + delayMs, e.message)
                    Result.retry()
                }
            }
        } catch (t: Throwable) {
            // 未预期异常(磁盘/流错误等)按可重试处理,退避后继续
            val attempt = runAttemptCount + 1
            val delayMs = ErrorPolicy.retryDelayMillis(attempt)
            deps.repo.markRetryWait(id, attempt, System.currentTimeMillis() + delayMs, "异常: ${t.message}")
            Result.retry()
        }
    }

    private suspend fun executeUpload(
        deps: Dep,
        task: UploadTaskEntity,
        settings: UploadSettings,
        base: okhttp3.HttpUrl,
    ): Result {
        val id = task.clientUploadId
        val source = ClipChunkSource(applicationContext.contentResolver, Uri.parse(task.contentUri), task.sizeBytes)

        var current = task
        if (current.sha256 == null) {
            val sha = withContext(Dispatchers.IO) { sha256Of(source, current.sizeBytes) }
            current = deps.repo.setSha256(id, sha) ?: return Result.failure()
        }

        // 凭据用本次任务的设置快照;设置变更后下一次重试自动生效
        val credentials = StaticTokenCredentialProvider(settings.token)
        val uploader = CloudreveV4Uploader(base, credentials)
        val fileId = uploader.upload(
            fileName = current.fileName,
            mimeType = current.mimeType,
            sizeBytes = current.sizeBytes,
            sha256 = current.sha256,
            clientUploadId = current.clientUploadId,
            targetFolder = settings.targetFolder,
            source = source,
            onProgress = { bytes, parts, total ->
                deps.repo.updateProgress(id, bytes, parts, total)
            },
        )
        deps.repo.markDone(id, fileId)
        if (settings.deleteAfterUpload) {
            runCatching {
                applicationContext.contentResolver.delete(Uri.parse(current.contentUri), null, null)
            }
        }
        return Result.success()
    }

    private fun buildForegroundInfo(fileName: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "云端上传", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("正在上传取证录像")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 34) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    companion object {
        const val KEY_TASK_ID = "clientUploadId"
        private const val CHANNEL_ID = "upload"
        private const val NOTIF_ID = 2001
    }
}
