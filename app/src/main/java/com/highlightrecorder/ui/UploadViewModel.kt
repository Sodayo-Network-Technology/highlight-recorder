package com.highlightrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.highlightrecorder.upload.Deps
import com.highlightrecorder.upload.UploadScheduler
import com.highlightrecorder.upload.UploadSettings
import com.highlightrecorder.upload.db.UploadTaskEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UploadViewModel(app: Application) : AndroidViewModel(app) {

    private val deps = Deps.get(app)

    val tasks: StateFlow<List<UploadTaskEntity>> = deps.repo.observeTasks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val settings: StateFlow<UploadSettings> = deps.settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Lazily, UploadSettings())

    fun updateSettings(transform: (UploadSettings) -> UploadSettings) {
        viewModelScope.launch { deps.settingsRepo.update(transform) }
    }

    /** 手动重试:重置状态后重新入队(服务端幂等键不变)。 */
    fun retry(id: String) {
        viewModelScope.launch {
            deps.repo.resetForRetry(id)
            UploadScheduler.enqueue(getApplication(), id, deps.settingsRepo.snapshot().wifiOnly)
        }
    }

    /** 清除任务记录(只删记录,不动本地文件)。 */
    fun remove(id: String) {
        viewModelScope.launch { deps.repo.delete(id) }
    }

    companion object {
        fun stateLabel(state: String): String = when (state) {
            UploadTaskEntity.STATE_QUEUED -> "排队中"
            UploadTaskEntity.STATE_UPLOADING -> "上传中"
            UploadTaskEntity.STATE_RETRY_WAIT -> "等待重试"
            UploadTaskEntity.STATE_DONE -> "已上传"
            else -> "失败"
        }
    }
}
