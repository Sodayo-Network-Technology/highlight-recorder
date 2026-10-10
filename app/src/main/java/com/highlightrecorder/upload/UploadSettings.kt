package com.highlightrecorder.upload

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.uploadDataStore by preferencesDataStore(name = "upload_settings")

/**
 * 云端上传配置(独立于录制设置的 DataStore 文件)。
 * 服务器地址必须通过 [BaseUrlValidator](仅 https 公网地址,对接协议 §9)。
 */
data class UploadSettings(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    /** 开发/联调期静态令牌;正式版由设备授权(Device Token)取代。 */
    val token: String = "",
    val wifiOnly: Boolean = true,
    /** 上传成功后删除本地原件;默认保留双保险(计划书 §2.4)。 */
    val deleteAfterUpload: Boolean = false,
    /** Drive 内的目标取证目录。 */
    val targetFolder: String = "/手机取证",
) {
    val serverUrlValid: Boolean get() = BaseUrlValidator.validate(serverUrl) != null

    /** 三要素齐备才允许自动上传。 */
    val configured: Boolean get() = enabled && serverUrlValid && token.isNotBlank()
}

class UploadSettingsRepository(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("upload_enabled")
        val SERVER_URL = stringPreferencesKey("upload_server_url")
        val TOKEN = stringPreferencesKey("upload_token")
        val WIFI_ONLY = booleanPreferencesKey("upload_wifi_only")
        val DELETE_AFTER = booleanPreferencesKey("upload_delete_after")
        val TARGET_FOLDER = stringPreferencesKey("upload_target_folder")
    }

    private fun fromPrefs(p: androidx.datastore.preferences.core.Preferences) = UploadSettings(
        enabled = p[Keys.ENABLED] ?: false,
        serverUrl = p[Keys.SERVER_URL] ?: "",
        token = p[Keys.TOKEN] ?: "",
        wifiOnly = p[Keys.WIFI_ONLY] ?: true,
        deleteAfterUpload = p[Keys.DELETE_AFTER] ?: false,
        targetFolder = p[Keys.TARGET_FOLDER] ?: "/手机取证",
    )

    val settings: Flow<UploadSettings> = context.uploadDataStore.data.map { fromPrefs(it) }

    suspend fun snapshot(): UploadSettings = settings.first()

    suspend fun update(transform: (UploadSettings) -> UploadSettings) {
        context.uploadDataStore.edit { p ->
            val next = transform(fromPrefs(p))
            p[Keys.ENABLED] = next.enabled
            p[Keys.SERVER_URL] = next.serverUrl.trim()
            p[Keys.TOKEN] = next.token.trim()
            p[Keys.WIFI_ONLY] = next.wifiOnly
            p[Keys.DELETE_AFTER] = next.deleteAfterUpload
            p[Keys.TARGET_FOLDER] = next.targetFolder.trim()
        }
    }
}
