package com.highlightrecorder.upload

import com.highlightrecorder.upload.db.UploadTaskDao
import com.highlightrecorder.upload.db.UploadTaskEntity
import kotlinx.coroutines.flow.Flow

/** 上传任务仓储:所有状态迁移集中在此,Worker/协调器不直接触碰 DAO。 */
class UploadTaskRepository(private val dao: UploadTaskDao) {

    fun observeTasks(): Flow<List<UploadTaskEntity>> = dao.observeAll()

    suspend fun byId(id: String): UploadTaskEntity? = dao.byId(id)

    suspend fun pending(): List<UploadTaskEntity> = dao.pending()

    suspend fun insert(entity: UploadTaskEntity) = dao.insert(entity)

    suspend fun delete(id: String) = dao.delete(id)

    /** 进程重启后清理僵死 UPLOADING 状态。 */
    suspend fun resetStuck() = dao.resetStuck()

    suspend fun setSha256(id: String, sha256: String): UploadTaskEntity? =
        mutate(id) { it.copy(sha256 = sha256) }

    suspend fun markUploading(id: String): UploadTaskEntity? =
        mutate(id) { it.copy(state = UploadTaskEntity.STATE_UPLOADING, lastError = null) }

    suspend fun updateProgress(id: String, bytes: Long, parts: Int, totalParts: Int): UploadTaskEntity? =
        mutate(id) {
            it.copy(uploadedBytes = bytes, uploadedParts = parts, totalParts = totalParts)
        }

    suspend fun markRetryWait(id: String, attempt: Int, nextRetryAt: Long?, error: String?): UploadTaskEntity? =
        mutate(id) {
            it.copy(
                state = UploadTaskEntity.STATE_RETRY_WAIT,
                retryCount = attempt,
                nextRetryAt = nextRetryAt,
                lastError = error,
            )
        }

    suspend fun markDone(id: String, serverFileId: String?): UploadTaskEntity? =
        mutate(id) {
            it.copy(
                state = UploadTaskEntity.STATE_DONE,
                serverFileId = serverFileId ?: it.serverFileId,
                uploadedBytes = it.sizeBytes,
                lastError = null,
                nextRetryAt = null,
            )
        }

    suspend fun markFailed(id: String, error: String?): UploadTaskEntity? =
        mutate(id) { it.copy(state = UploadTaskEntity.STATE_FAILED, lastError = error) }

    suspend fun resetForRetry(id: String) = dao.resetForRetry(id)

    private suspend fun mutate(id: String, transform: (UploadTaskEntity) -> UploadTaskEntity): UploadTaskEntity? {
        val current = dao.byId(id) ?: return null
        val next = transform(current).copy(updatedAt = System.currentTimeMillis())
        dao.update(next)
        return next
    }
}
