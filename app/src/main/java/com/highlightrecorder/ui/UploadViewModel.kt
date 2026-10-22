package com.highlightrecorder.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.highlightrecorder.upload.BaseUrlValidator
import com.highlightrecorder.upload.Deps
import com.highlightrecorder.upload.UploadScheduler
import com.highlightrecorder.upload.UploadSettings
import com.highlightrecorder.upload.auth.DevicePairingClient
import com.highlightrecorder.upload.db.UploadTaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** 设备配对状态(交接单 §3:sdkt_ Token 存 Keystore,90 天有效,无在线轮换)。 */
    data class PairUiState(
        val paired: Boolean = false,
        val deviceId: Long = -1L,
        val expiresAtMs: Long = 0L,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _pairState = MutableStateFlow(PairUiState())
    val pairState: StateFlow<PairUiState> = _pairState

    init {
        refreshPairState()
    }

    fun refreshPairState() {
        val store = deps.tokenStore
        _pairState.value = PairUiState(
            paired = store.isPaired(),
            deviceId = store.deviceId(),
            expiresAtMs = store.expiresAtMs(),
        )
    }

    /** 用网页端生成的配对码兑换设备 Token;成功即保存(Keystore 加密)。
     *  设备名必须与网页端发起配对时的名称一致(契约 §3.2,不符=403)。 */
    fun pair(pairCode: String, deviceName: String) {
        val code = pairCode.trim()
        val name = deviceName.trim()
        if (code.isEmpty() || name.isEmpty() || _pairState.value.busy) return
        viewModelScope.launch {
            _pairState.value = _pairState.value.copy(busy = true, error = null)
            try {
                val settings = deps.settingsRepo.snapshot()
                val base = BaseUrlValidator.validate(settings.serverUrl)
                    ?: throw com.highlightrecorder.upload.auth.PairingException("请先填写有效的服务器地址(https 公网)")
                val client = DevicePairingClient(base)
                val result = client.exchange(code, name)
                deps.tokenStore.save(result.token, result.deviceId, result.ingestUri, result.expiresAtMs)
                refreshPairState()
                _pairState.value = _pairState.value.copy(busy = false)
            } catch (e: Exception) {
                _pairState.value = _pairState.value.copy(busy = false, error = e.message ?: "配对失败")
            }
        }
    }

    /** 解除本机配对(清除本地 Token;服务端撤销请在 Drive 网页端操作)。 */
    fun unpair() {
        deps.tokenStore.clear()
        refreshPairState()
    }

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
            UploadTaskEntity.STATE_UNRESOLVED -> "待人工确认"
            else -> "失败"
        }
    }
}
