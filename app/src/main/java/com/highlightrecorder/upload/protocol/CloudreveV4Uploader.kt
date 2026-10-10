package com.highlightrecorder.upload.protocol

import com.highlightrecorder.upload.ChunkSource
import com.highlightrecorder.upload.CredentialProvider
import com.highlightrecorder.upload.ErrorPolicy
import com.highlightrecorder.upload.ErrorPolicy.Phase
import com.highlightrecorder.upload.RetryDisposition
import com.highlightrecorder.upload.UploadException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

/**
 * Cloudreve V4 原生上传协议客户端:创建会话 → 按存储策略分片 → (对象存储)显式完成。
 *
 * 纯 JVM 实现(不含 Android 依赖),配合 MockWebServer 可完整单测。
 * 响应字段宽容解析(多种拼写兼容),服务端业务码≠0 视为永久失败并携带原始 msg。
 */
class CloudreveV4Uploader(
    baseUrl: HttpUrl,
    private val credentials: CredentialProvider,
    client: OkHttpClient? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    constructor(baseUrl: String, credentials: CredentialProvider, client: OkHttpClient? = null) :
        this(baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("baseUrl 无效: $baseUrl"), credentials, client)

    private val base = baseUrl
    private val http = (client ?: OkHttpClient()).newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * 执行完整上传,返回能从最后一步响应解析到的 file_id(解析不到为 null,
     * file_id 的确切返回时机/字段 TO_VERIFY,见 Drive 侧回复 §2-#5)。
     */
    suspend fun upload(
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        sha256: String?,
        clientUploadId: String,
        targetFolder: String,
        source: ChunkSource,
        onProgress: suspend (bytes: Long, parts: Int, totalParts: Int) -> Unit = { _, _, _ -> },
    ): String? {
        require(sizeBytes > 0) { "空文件不可上传" }
        val folder = targetFolder.trim().trimEnd('/')
        val session = createSessionWithFallback(
            folder, fileName, sizeBytes, mimeType, sha256, clientUploadId,
        )

        val plan = planFor(session)
        val totalParts = totalParts(session.chunkSize, sizeBytes)
        var uploaded = 0L
        var lastData: JsonElement? = null
        for (index in 0 until totalParts) {
            val offset = index * session.chunkSize
            val length = minOf(session.chunkSize, sizeBytes - offset).toInt()
            lastData = when (plan) {
                is ChunkPlan.RelayApi -> putChunk(
                    url(CloudreveEndpoints.uploadChunkApi(plan.sessionId, index, plan.chunkSize)),
                    source, offset, length,
                    headers = plan.credential?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap(),
                )
                is ChunkPlan.RelayUrl -> putChunk(
                    url(withChunkQuery(plan.urls, index)),
                    source, offset, length,
                    headers = mapOf("Authorization" to "Bearer ${plan.credential}"),
                )
                is ChunkPlan.Presigned -> putChunk(
                    url(presignedUrl(plan.urls, index)),
                    source, offset, length, headers = emptyMap(),
                )
            }
            uploaded += length
            onProgress(uploaded, index + 1, totalParts)
        }
        if (needsExplicitComplete(session)) {
            lastData = completeS3(session.sessionId)
        }
        return extractFileId(lastData)
    }

    // ---- 三步协议 ----

    /** 建会话;目标目录 404 时先补救目录,仍失败则回退根目录重试一次。 */
    private suspend fun createSessionWithFallback(
        folder: String,
        fileName: String,
        sizeBytes: Long,
        mimeType: String,
        sha256: String?,
        clientUploadId: String,
    ): UploadSession =
        try {
            createSession(folder, fileName, sizeBytes, mimeType, sha256, clientUploadId)
        } catch (e: UploadException) {
            if (e.httpCode == 404) {
                runCatching { createDirectory(folder) }
                try {
                    createSession(folder, fileName, sizeBytes, mimeType, sha256, clientUploadId)
                } catch (e2: UploadException) {
                    if (e2.httpCode == 404 && folder.isNotEmpty()) {
                        createSession("", fileName, sizeBytes, mimeType, sha256, clientUploadId)
                    } else {
                        throw e2
                    }
                }
            } else {
                throw e
            }
        }

    private suspend fun createSession(
        folder: String,
        fileName: String,
        sizeBytes: Long,
        mimeType: String,
        sha256: String?,
        clientUploadId: String,
    ): UploadSession {
        val uri = if (folder.isEmpty()) "/$fileName" else "$folder/$fileName"
        val body = buildString {
            append('{')
            append("\"uri\":${jsonString(uri)},")
            append("\"size\":$sizeBytes,")
            append("\"name\":${jsonString(fileName)},")
            append("\"mime_type\":${jsonString(mimeType)},")
            if (sha256 != null) append("\"sha256\":${jsonString(sha256)},")
            // 扩展字段:为将来 Sodayo Ingest Adapter 的幂等/来源语义预留,服务端可忽略
            append("\"client_upload_id\":${jsonString(clientUploadId)},")
            append("\"source\":\"android_replay\"}")
        }
        val request = Request.Builder()
            .url(url(CloudreveEndpoints.CREATE_SESSION))
            .header("X-Client-Upload-Id", clientUploadId)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val (_, text) = send(request, Phase.CREATE)
        return parseSession(envelopeData(text, Phase.CREATE))
    }

    private suspend fun putChunk(
        url: HttpUrl,
        source: ChunkSource,
        offset: Long,
        length: Int,
        headers: Map<String, String>,
    ): JsonElement? {
        val request = Request.Builder()
            .url(url)
            .put(chunkBody(source, offset, length))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val (_, text) = send(request, Phase.CHUNK, useCredentialHeaders = false)
        return envelopeData(text, Phase.CHUNK)
    }

    private suspend fun completeS3(sessionId: String): JsonElement? {
        val request = Request.Builder()
            .url(url(CloudreveEndpoints.completeS3(sessionId)))
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val (_, text) = send(request, Phase.COMPLETE)
        return envelopeData(text, Phase.COMPLETE)
    }

    private suspend fun createDirectory(folder: String) {
        val body = """{"uri":${jsonString(folder)}}"""
        val request = Request.Builder()
            .url(url(CloudreveEndpoints.CREATE_DIRECTORY))
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        send(request, Phase.CREATE, allowCredentialRefresh = false)
    }

    // ---- 请求执行与响应解析 ----

    private suspend fun send(
        request: Request,
        phase: Phase,
        useCredentialHeaders: Boolean = true,
        allowCredentialRefresh: Boolean = true,
    ): Pair<Int, String> {
        val signed = request.newBuilder().apply {
            if (useCredentialHeaders) credentials.authHeaders().forEach { (k, v) -> header(k, v) }
        }.build()
        http.newCall(signed).await().use { resp ->
            if (resp.code == 401 && allowCredentialRefresh && credentials.refresh()) {
                return send(request, phase, useCredentialHeaders, allowCredentialRefresh = false)
            }
            if (!resp.isSuccessful) {
                throw UploadException(
                    httpCode = resp.code,
                    disposition = ErrorPolicy.dispositionFor(resp.code, phase),
                    message = "HTTP ${resp.code} [$phase]${resp.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}",
                    retryAfterSeconds = if (resp.code == 429) {
                        resp.header("Retry-After")?.toLongOrNull()
                    } else {
                        null
                    },
                )
            }
            return resp.code to (resp.body?.string().orEmpty())
        }
    }

    /** Cloudreve 统一响应包裹 {code,data,msg}:业务码≠0 视为永久失败并透传 msg。 */
    private fun envelopeData(text: String, phase: Phase): JsonElement? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: throw UploadException(null, RetryDisposition.PERMANENT, "[$phase] 响应不是合法 JSON")
        val obj = root as? JsonObject
            ?: throw UploadException(null, RetryDisposition.PERMANENT, "[$phase] 响应结构异常")
        val code = obj["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code != 0) {
            throw UploadException(
                null, RetryDisposition.PERMANENT,
                "服务端拒绝(code=$code): ${obj["msg"]?.jsonPrimitive?.contentOrNull ?: "未知错误"}",
            )
        }
        return obj["data"]
    }

    private fun parseSession(data: JsonElement?): UploadSession {
        val obj = (data as? JsonObject)?.let { d -> (d["upload_session"] as? JsonObject) ?: d }
            ?: throw UploadException(null, RetryDisposition.PERMANENT, "创建会话响应缺少 data")
        val sessionId = obj.string("session_id", "sessionId", "id")
            ?: throw UploadException(null, RetryDisposition.PERMANENT, "会话响应缺少 session_id")
        val policy = obj["storage_policy"] as? JsonObject
        return UploadSession(
            sessionId = sessionId,
            chunkSize = obj.long("chunk_size", "chunkSize") ?: DEFAULT_CHUNK_SIZE,
            uploadUrls = (obj["upload_urls"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: emptyList(),
            credential = obj.string("credential", "credentials"),
            policyType = policy?.string("policy_type", "policyType", "type"),
            relay = policy?.string("relay")?.toBooleanStrictOrNull(),
        )
    }

    private fun extractFileId(data: JsonElement?): String? {
        val obj = data as? JsonObject ?: return null
        (obj["file"] as? JsonObject)?.let { f ->
            f.string("id", "file_id")?.let { return it }
        }
        return obj.string("file_id", "fileId", "id")
    }

    // ---- 工具 ----

    private fun url(path: String): HttpUrl =
        base.resolve(path) ?: throw UploadException(null, RetryDisposition.PERMANENT, "非法端点: $path")

    /** 从机中转:分片 URL 追加 ?chunk=序号(兼容 URL 已带 query 的情况)。 */
    private fun withChunkQuery(urls: List<String>, index: Int): String {
        val u = urls.getOrNull(minOf(index, urls.size - 1))
            ?: throw UploadException(null, RetryDisposition.PERMANENT, "分片 URL 缺失(index=$index)")
        return u.toHttpUrlOrNull()
            ?.newBuilder()?.addQueryParameter("chunk", index.toString())?.build()?.toString()
            ?: "$u?chunk=$index"
    }

    private fun presignedUrl(urls: List<String>, index: Int): String =
        urls.getOrNull(index)
            ?: throw UploadException(null, RetryDisposition.PERMANENT, "预签名 URL 缺失(index=$index)")

    private fun totalParts(chunkSize: Long, sizeBytes: Long): Int =
        ceil(sizeBytes.toDouble() / chunkSize.coerceAtLeast(1)).toInt().coerceAtLeast(1)

    private fun chunkBody(source: ChunkSource, offset: Long, length: Int): RequestBody =
        object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
            override fun contentLength() = length.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                source.open(offset, length).use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                    }
                }
            }
        }

    private fun jsonString(s: String): String = JsonPrimitive(s).toString()

    private fun JsonObject.string(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.long(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.longOrNull }

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
        /** 会话未返回 chunk_size 时的兜底分片大小(8MB)。 */
        const val DEFAULT_CHUNK_SIZE = 8L * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
