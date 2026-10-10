package com.highlightrecorder.upload.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设备配对凭据存储:sdkt_ Token 经 Android Keystore AES-GCM 加密后落 SharedPreferences,
 * 密钥不出安全硬件(对接交接单 §3:Token 不进 URL、撤销/过期即失效)。
 */
class DeviceTokenStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("device_pairing", Context.MODE_PRIVATE)

    /** 解密失败/未配对返回 null(调用方视为未配对)。 */
    fun token(): String? = runCatching {
        val ct = prefs.getString(KEY_TOKEN_CT, null) ?: return null
        val raw = Base64.decode(ct, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, GCM_IV_LEN))
        String(cipher.doFinal(raw, GCM_IV_LEN, raw.size - GCM_IV_LEN))
    }.getOrNull()

    fun save(token: String, deviceId: Long, ingestUri: String?, expiresAtMs: Long) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.iv + cipher.doFinal(token.toByteArray())
        prefs.edit()
            .putString(KEY_TOKEN_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putLong(KEY_DEVICE_ID, deviceId)
            .putString(KEY_INGEST_URI, ingestUri)
            .putLong(KEY_EXPIRES_AT, expiresAtMs)
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    fun isPaired(): Boolean = token() != null

    fun deviceId(): Long = prefs.getLong(KEY_DEVICE_ID, -1L)

    /** 配对授予的上传目录(如 cloudreve://my/移动端取证),空则回退设置页目录。 */
    fun ingestUri(): String? = prefs.getString(KEY_INGEST_URI, null)?.takeIf { it.isNotBlank() }

    fun expiresAtMs(): Long = prefs.getLong(KEY_EXPIRES_AT, 0L)

    fun pairedAtMs(): Long = prefs.getLong(KEY_PAIRED_AT, 0L)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "sodayo_device_token"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12
        private const val KEY_TOKEN_CT = "token_ct"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_INGEST_URI = "ingest_uri"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_PAIRED_AT = "paired_at"
    }
}
