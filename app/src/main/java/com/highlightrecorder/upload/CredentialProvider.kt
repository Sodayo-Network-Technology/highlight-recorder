package com.highlightrecorder.upload

/**
 * 上传凭据抽象:把「认证方式」与上传协议(CloudreveV4Uploader)解耦。
 *
 * 正式方案是 Drive 侧设备授权(浏览器配对 → 可撤销 Device Token → Keystore 保存),
 * 对接协议 §3,当前状态 DESIGN 未实现;该接口即为将来接入预留的插槽。
 */
interface CredentialProvider {

    /** 是否已具备可用凭据。 */
    fun isConfigured(): Boolean

    /** 附加到 API 请求的认证头。 */
    fun authHeaders(): Map<String, String>

    /** 收到 401 时尝试续期;返回 true 表示已刷新,调用方可重试一次。 */
    suspend fun refresh(): Boolean = false
}

/**
 * 开发/联调期凭据:设置页配置的访问令牌(来自隔离测试实例的测试账号)。
 * ⚠️ 临时方案:正式分发版必须替换为设备授权实现,不得把长期令牌作为最终凭据下发。
 */
class StaticTokenCredentialProvider(
    private val tokenProvider: () -> String,
) : CredentialProvider {

    constructor(token: String) : this({ token })

    override fun isConfigured(): Boolean = tokenProvider().isNotBlank()

    override fun authHeaders(): Map<String, String> {
        val t = tokenProvider()
        return if (t.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $t")
    }
}
