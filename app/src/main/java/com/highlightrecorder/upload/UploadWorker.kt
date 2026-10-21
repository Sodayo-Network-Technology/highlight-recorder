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
import com.highlightrecorder.upload.auth.SodayoDeviceCredentialProvider
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
        val paired = deps.tokenStore.isPaired()
        if (!settings.enabled || !settings.serverUrlValid || (!paired && settings.token.isBlank())) {
            deps.repo.markRetryWait(id, runAttemptCount + 1, null, "上传未配置或未配对,请到设置页完成配置/配对")
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
                executeUpload(deps, task, settings, base, paired)
            }
        } catch (e: UploadException) {
            val attempt = runAttemptCount + 1
            when (e.disposition) {
                RetryDisposition.PERMANENT -> {
                    deps.repo.markFailed(id, diagnose(e))
                    Result.failure()
                }
                // 契约 §6:40020/401=撤销/冻结/权限回收(服务端不可区分)——暂停任务+
                // 【保留 Token】+保留原件;解冻/权限恢复后同 Token 自动恢复。
                // 仅用户在设置页明确「解除配对」才清除凭据,此处绝不清理。
                RetryDisposition.AUTH_SUSPECT -> {
                    deps.repo.markRetryWait(
                        id, attempt, null,
                        "AUTH_SUSPECT(认证被拒,凭据已保留;若为冻结/权限回收,恢复后自动续传): ${diagnose(e)}",
                    )
                    Result.failure()
                }
                // 契约 §7:40004 同名冲突/40011 会话无效——弱确认流程,绝不直接判成功
                RetryDisposition.WEAK_CONFIRM -> {
                    handleWeakConfirm(deps, task, settings, base, paired, attempt)
                }
                RetryDisposition.RETRY -> {
                    val delayMs = ErrorPolicy.retryDelayMillis(attempt, e.retryAfterSeconds)
                    deps.repo.markRetryWait(id, attempt, System.currentTimeMillis() + delayMs, diagnose(e))
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

    /** correlation_id 已并入 UploadException.message,直接透传。 */
    private fun diagnose(e: UploadException): String = e.message ?: "未知上传错误"

    /**
     * 40004/40011 弱确认(契约 §7):查列表核对 name+size。
     * - 候选存在且 size 相符 → 弱确认结案(content-verify: name+size;【不删本地原件】);
     * - 同名异物 → 文件名递增 -2/-3(≤3)换 uri 重传;
     * - 无候选 → 同 upload_id 重建会话重传(服务端无残留,安全)。
     */
    private suspend fun handleWeakConfirm(
        deps: Dep,
        task: UploadTaskEntity,
        settings: UploadSettings,
        base: okhttp3.HttpUrl,
        paired: Boolean,
        attempt: Int,
    ): Result {
        val id = task.clientUploadId
        val credentials: CredentialProvider = if (paired) {
            SodayoDeviceCredentialProvider(deps.tokenStore)
        } else {
            StaticTokenCredentialProvider(settings.token)
        }
        val folder = if (paired) (deps.tokenStore.ingestUri() ?: settings.targetFolder) else settings.targetFolder
        val files = CloudreveV4Uploader(base, credentials).let { up ->
            runCatching { up.listFiles(folder) }.getOrElse { emptyList() }
        }
        val candidate = files.firstOrNull { it.name == task.fileName }

        return when {
            // 弱确认结案:size 相符即视为同一内容(name+size 弱证明,不构成内容完整性证明)
            candidate != null && candidate.sizeBytes == task.sizeBytes -> {
                deps.repo.markDone(id, candidate.id)
                Result.success()
            }
            // 同名异物:另一内容占了该文件名 → 换 -2/-3 后缀(≤3)重传
            candidate != null -> {
                val suffix = maxOf(2, UploadIds.currentSuffix(task.fileName) + 1)
                val newName = UploadIds.fileNameWithSuffix(task.fileName, suffix)
                if (newName == null) {
                    deps.repo.markFailed(id, "同名异物且重命名次数用尽(>3),请手动处理: ${task.fileName}")
                    Result.failure()
                } else {
                    val newId = "$id-${suffix}"
                    deps.repo.renameIdentity(id, newId, newName)
                    UploadScheduler.enqueue(applicationContext, newId, settings.wifiOnly)
                    Result.success()
                }
            }
            // 无候选:会话无效/过期未合并,服务端无残留文件——同 upload_id 重建会话重传(契约 §7)
            else -> {
                val delayMs = ErrorPolicy.retryDelayMillis(attempt)
                deps.repo.markRetryWait(id, attempt, System.currentTimeMillis() + delayMs, "弱确认未过(目录无同名文件),同 upload_id 重建会话重传")
                Result.retry()
            }
        }
    }

    private suspend fun executeUpload(
        deps: Dep,
        task: UploadTaskEntity,
        settings: UploadSettings,
        base: okhttp3.HttpUrl,
        paired: Boolean,
    ): Result {
        val id = task.clientUploadId
        val source = ClipChunkSource(applicationContext.contentResolver, Uri.parse(task.contentUri), task.sizeBytes)

        var current = task
        if (current.sha256 == null) {
            val sha = withContext(Dispatchers.IO) { sha256Of(source, current.sizeBytes) }
            current = deps.repo.setSha256(id, sha) ?: return Result.failure()
        }

        // 凭据:设备 Token 优先,未配对回退开发期静态令牌
        val credentials: CredentialProvider = if (paired) {
            SodayoDeviceCredentialProvider(deps.tokenStore)
        } else {
            StaticTokenCredentialProvider(settings.token)
        }
        // 上传目录:配对授予的 ingest_uri 优先(交接单:目标锁定 cloudreve://my/移动端取证)
        val folder = if (paired) (deps.tokenStore.ingestUri() ?: settings.targetFolder) else settings.targetFolder
        val uploader = CloudreveV4Uploader(base, credentials)
        val fileId = uploader.upload(
            fileName = current.fileName,
            mimeType = current.mimeType,
            sizeBytes = current.sizeBytes,
            sha256 = current.sha256,
            clientUploadId = current.clientUploadId,
            targetFolder = folder,
            source = source,
            lastModifiedMs = current.recordedAt,
            onProgress = { bytes, parts, total ->
                deps.repo.updateProgress(id, bytes, parts, total)
            },
        )
        // hash_id 解析:上传本身不返回 file_id,按文件名从取证目录列表获取(契约 §4.3)
        val serverFileId = runCatching {
            uploader.resolveFileId(folder, current.fileName)
        }.getOrNull()
        deps.repo.markDone(id, serverFileId ?: fileId)
        // 仅直接成功(未走弱确认)且用户显式开启时才删本地;弱确认路径永不删除(契约 §7/E3)
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
