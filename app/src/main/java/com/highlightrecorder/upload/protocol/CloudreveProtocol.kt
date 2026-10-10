package com.highlightrecorder.upload.protocol

/**
 * Cloudreve V4 端点与字段定义 —— 集中在此一处。
 *
 * 契约来源:Sodayo Drive 侧交接单(2026-10-11,基于 feature/device-auth-v2 真实代码):
 * - 创建会话:PUT  /api/v4/file/upload
 * - 上传分片:POST /api/v4/file/upload/{sid}/{idx}(末片自动 Complete)
 * - 目录列表:GET  /api/v4/file?uri=…(files[].id 即 OA 关联所用 hash_id)
 * - 建子目录:POST /api/v4/file/create
 * 仍有出入时以 Fork 路由为准,联调中只改本文件。
 */
object CloudreveEndpoints {

    /** 创建上传会话(PUT,交接单 §1.2)。 */
    const val CREATE_SESSION = "/api/v4/file/upload"

    /** 上传分片(POST;本机存储走 Cloudreve 自身 API,末片后服务端自动完成)。 */
    fun uploadChunk(sessionId: String, index: Int) =
        "/api/v4/file/upload/$sessionId/$index"

    /** 目录列表(GET,uri 参数编码后附上;files[].id = hash_id)。 */
    const val LIST_FILES = "/api/v4/file"

    /** 对象存储类策略的显式完成回调(本机/从机/又拍云在最后分片后自动完成,无需调用)。 */
    fun completeS3(sessionId: String) = "/api/v4/file/upload/$sessionId/complete"

    /** 创建目录(目标取证目录不存在时的补救)。 */
    const val CREATE_DIRECTORY = "/api/v4/directory"
}

/** 上传会话(由创建会话响应宽容解析;字段名做多种拼写兼容)。 */
class UploadSession(
    val sessionId: String,
    val chunkSize: Long,
    val uploadUrls: List<String>,
    val credential: String?,
    val policyType: String?,
    val relay: Boolean?,
)

/** 分片发送方式,由会话字段推断(不得写死同一套上传目的地)。 */
sealed interface ChunkPlan {

    /** 本机存储:POST Cloudreve 自身分片 API;认证优先会话 credential,缺省回退调用方凭据(设备 Token)。 */
    data class RelayApi(val sessionId: String, val chunkSize: Long, val credential: String?) : ChunkPlan

    /** 从机中转:POST upload_urls[index],附 ?chunk=序号 与会话 credential。 */
    data class RelayUrl(val urls: List<String>, val credential: String) : ChunkPlan

    /** 对象存储预签名:PUT upload_urls[index],不带认证头(URL 已签名)。 */
    data class Presigned(val urls: List<String>) : ChunkPlan
}

fun planFor(session: UploadSession): ChunkPlan = when {
    session.uploadUrls.isEmpty() ->
        ChunkPlan.RelayApi(session.sessionId, session.chunkSize, session.credential)
    session.credential != null ->
        ChunkPlan.RelayUrl(session.uploadUrls, session.credential)
    else ->
        ChunkPlan.Presigned(session.uploadUrls)
}

/** 远端文件条目(目录列表返回;id 即 hash_id,供 OA 取证关联)。 */
data class RemoteFile(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
)

/**
 * 是否需要显式完成回调:对象存储类(S3/OSS/COS/KS3/OBS/BOS)需要;
 * 本机/从机在最后分片后自动完成(交接单:末片自动 Complete)。以 Fork 实际策略为准。
 */
fun needsExplicitComplete(session: UploadSession): Boolean =
    session.policyType?.lowercase()?.let { t ->
        listOf("s3", "oss", "cos", "ks3", "obs", "bos").any { t.contains(it) }
    } ?: false
