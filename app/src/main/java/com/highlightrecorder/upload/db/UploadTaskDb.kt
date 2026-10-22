package com.highlightrecorder.upload.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 上传任务(Room 持久化,数据库是队列的唯一事实来源;
 * 进程被杀/设备重启后据此重建,对接协议 §6)。
 */
@Entity(tableName = "upload_tasks")
data class UploadTaskEntity(
    /** 幂等键:任务创建时生成 UUID,重试永不变化(对接协议 §2.4)。 */
    @PrimaryKey val clientUploadId: String,
    /** 已保存文件的 MediaStore/file Uri。 */
    val contentUri: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** 本地流式计算,上传前补齐;服务端复核(联调确认其复核行为)。 */
    val sha256: String? = null,
    val recordedAt: Long,
    val durationMs: Long? = null,
    /** QUEUED / UPLOADING / RETRY_WAIT / DONE / FAILED。 */
    val state: String = STATE_QUEUED,
    val cloudreveSessionId: String? = null,
    val uploadedBytes: Long = 0,
    val uploadedParts: Int = 0,
    val totalParts: Int? = null,
    /** 上传最终成功后由响应解析写入(可能为 null,联调冻结 file_id 协议后补齐)。 */
    val serverFileId: String? = null,
    val retryCount: Int = 0,
    val nextRetryAt: Long? = null,
    val lastError: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val STATE_QUEUED = "QUEUED"
        const val STATE_UPLOADING = "UPLOADING"
        const val STATE_RETRY_WAIT = "RETRY_WAIT"
        const val STATE_DONE = "DONE"
        const val STATE_FAILED = "FAILED"

        /** 确认不充分(2026-10-22 交接 §C.3-5):列表可能截断,无法断言文件存在与否;
         *  不自动重传,保留任务与原件,等待人工在 UI 决定重试。 */
        const val STATE_UNRESOLVED = "UNRESOLVED"
    }
}

@Dao
interface UploadTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: UploadTaskEntity)

    @Update
    suspend fun update(task: UploadTaskEntity)

    @Query("SELECT * FROM upload_tasks WHERE clientUploadId = :id")
    suspend fun byId(id: String): UploadTaskEntity?

    @Query("SELECT * FROM upload_tasks WHERE state IN ('QUEUED','RETRY_WAIT') ORDER BY createdAt ASC")
    suspend fun pending(): List<UploadTaskEntity>

    @Query("SELECT * FROM upload_tasks ORDER BY createdAt DESC LIMIT 200")
    fun observeAll(): Flow<List<UploadTaskEntity>>

    @Query("UPDATE upload_tasks SET state = 'QUEUED', uploadedBytes = 0, uploadedParts = 0, lastError = NULL, nextRetryAt = NULL WHERE clientUploadId = :id")
    suspend fun resetForRetry(id: String)

    @Query("UPDATE upload_tasks SET state = 'QUEUED' WHERE state = 'UPLOADING'")
    suspend fun resetStuck()

    @Query("DELETE FROM upload_tasks WHERE clientUploadId = :id")
    suspend fun delete(id: String)

    /** 同名异物换名重传:更新 upload_id(主键)与文件名,事务内删旧插新(契约 §7 有界递增)。 */
    @Transaction
    suspend fun renameIdentity(oldId: String, updated: UploadTaskEntity) {
        delete(oldId)
        insert(updated)
    }
}

@Database(entities = [UploadTaskEntity::class], version = 1, exportSchema = false)
abstract class UploadDatabase : RoomDatabase() {

    abstract fun dao(): UploadTaskDao

    companion object {
        @Volatile
        private var instance: UploadDatabase? = null

        fun get(context: Context): UploadDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                UploadDatabase::class.java,
                "upload_tasks.db",
            ).build().also { instance = it }
        }
    }
}
