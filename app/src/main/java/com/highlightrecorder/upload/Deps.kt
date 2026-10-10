package com.highlightrecorder.upload

import android.content.Context
import com.highlightrecorder.upload.auth.DeviceTokenStore
import kotlinx.coroutines.sync.Mutex

class Dep(
    val settingsRepo: UploadSettingsRepository,
    val repo: UploadTaskRepository,
    val tokenStore: DeviceTokenStore,
    /** 单上传并发闸(对接协议 §7 建议默认 1)。 */
    val uploadMutex: Mutex = Mutex(),
)

/** 进程级单例(与项目现有 SettingsHolder 风格一致,不引入 DI 框架)。 */
object Deps {

    @Volatile
    private var ref: Dep? = null

    fun get(context: Context): Dep = ref ?: synchronized(this) {
        ref ?: create(context.applicationContext).also { ref = it }
    }

    private fun create(app: Context): Dep = Dep(
        settingsRepo = UploadSettingsRepository(app),
        repo = UploadTaskRepository(
            com.highlightrecorder.upload.db.UploadDatabase.get(app).dao(),
        ),
        tokenStore = DeviceTokenStore(app),
    )
}
