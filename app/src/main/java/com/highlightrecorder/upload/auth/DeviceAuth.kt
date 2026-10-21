package com.highlightrecorder.upload.auth

import com.highlightrecorder.upload.CredentialProvider
import com.highlightrecorder.upload.ErrorPolicy
import com.highlightrecorder.upload.UploadException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 设备凭据(交接单 §3):sdkt_ Token 仅作 Bearer 头,无在线轮换——
 * 90 天过期或被撤销后由 401 → PAUSE_FOR_REAUTH 触发清除并提示重新配对。
 */
class SodayoDeviceCredentialProvider(
    private val store: DeviceTokenStore,
) : CredentialProvider {

    override fun isConfigured(): Boolean = store.token() != null

    override fun authHeaders(): Map<String, String> {
        val t = store.token() ?: return emptyMap()
        return mapOf("Authorization" to "Bearer $t")
    }
}

/** 配对结果:Token 已由调用方写入 [DeviceTokenStore]。 */
data class PairingResult(
    val token: String,
    val deviceId: Long,
    val expiresAtMs: Long,
    val ingestUri: String?,
    val scope: String?,
)

/** 异常类型标记:401/403 等鉴权类失败,UI 直接展示 message。 */
class PairingException(message: String) : Exception(message)

/**
 * 设备配对客户端(交接单 §1.1):员工在网页端发起配对得到 10 位配对码(5 分钟有效),
 * APK 用配对码 + 设备名兑换 sdkt_ Token(90 天)。
 */
class DevicePairingClient(
    baseUrl: HttpUrl,
    client: OkHttpClient? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    constructor(baseUrl: String, client: OkHttpClient? = null) :
        this(baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("baseUrl 无效"), client, Json { ignoreUnknownKeys = true })

    private val base = baseUrl
    private val http = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun exchange(pairCode: String, deviceName: String): PairingResult {
        require(pairCode.isNotBlank()) { "配对码不能为空" }
        require(deviceName.isNotBlank()) { "设备名不能为空" }
        val body = "{\"pair_code\":${jsonStr(pairCode)},\"device_name\":${jsonStr(deviceName)}}"
        val request = Request.Builder()
            .url(endpoint("/api/v4/sodayo/device/token"))
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        http.newCall(request).await().use { resp ->
            // body 只读一次;非 2xx 也防御性解析 envelope(B5:错误体可能是 JSON 业务码)
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val env = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                val msg = env?.let { o -> (o["msg"] as? JsonPrimitive)?.contentOrNull }
                val corr = env?.let { o -> (o["correlation_id"] as? JsonPrimitive)?.contentOrNull }
                throw PairingException(
                    buildString {
                        append("HTTP ${resp.code}")
                        msg?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                        corr?.let { append(" (correlation_id=$it)") }
                    },
                )
            }
            val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: throw PairingException("响应不是合法 JSON")
            val code = obj["code"]?.jsonPrimitive?.intOrNull ?: 0
            if (code != 0) {
                throw PairingException(obj["msg"]?.jsonPrimitive?.contentOrNull ?: "配对被拒绝(code=$code)")
            }
            val data = obj["data"] as? JsonObject ?: throw PairingException("响应缺少 data")
            val token = data.string("device_token")
                ?: throw PairingException("响应缺少 device_token")
            val expiresIn = data.long("expires_in") ?: (90L * 24 * 3600)
            return PairingResult(
                token = token,
                deviceId = data.long("device_id") ?: -1L,
                expiresAtMs = System.currentTimeMillis() + expiresIn * 1000,
                ingestUri = data.string("ingest_uri"),
                scope = data.string("scope"),
            )
        }
    }

    private fun endpoint(path: String): HttpUrl =
        base.resolve(path) ?: throw PairingException("非法端点: $path")

    private fun JsonObject.string(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.long(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.longOrNull }

    private fun jsonStr(s: String): String = JsonPrimitive(s).toString()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }
        })
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
