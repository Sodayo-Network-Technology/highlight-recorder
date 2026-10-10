package com.highlightrecorder.upload.protocol

/**
 * Cloudreve V4 端点与字段定义 —— 集中在此一处。
 *
 * ⚠️ TO_VERIFY:按上游 V4 官方文档/前端参考实现编写;最终契约必须以 Sodayo Fork
 * 实际路由核对后冻结(Drive 侧回复 §2-#2/#4/#5:Session 过期与 Complete 语义、
 * 分片走 Relay 还是预签名、folder/file ID 规则均待核验)。联调时只改本文件。
 */
object CloudreveEndpoints {

    /** 创建上传会话。 */
    const val CREATE_SESSION = "/api/v4/file/upload"

    /** 本机存储分片(走 Cloudreve 自身 API,需带会话 credential)。 */
    fun uploadChunkApi(sessionId: String, index: Int, chunkSize: Long) =
        "/api/v4/file/upload/$sessionId/$index?chunk_size=$chunkSize"

    /** 对象存储类策略的显式完成回调(本机/从机/又拍云在最后分片后自动完成,无需调用)。 */
    fun completeS3(sessionId: String) = "/api/v4/file/upload/$sessionId/complete"

    /** 创建目录(目标取证目录不存在时的补救,TO_VERIFY)。 */
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

/** 分片发送方式,由会话字段推断(对接协议 §4:不得写死同一套上传目的地)。 */
sealed interface ChunkPlan {

    /** 本机存储:PUT Cloudreve 自身分片 API,附会话 credential。 */
    data class RelayApi(val sessionId: String, val chunkSize: Long, val credential: String?) : ChunkPlan

    /** 从机中转:PUT upload_urls[index],附 ?chunk=序号 与 credential。 */
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

/**
 * 是否需要显式完成回调:对象存储类(S3/OSS/COS/KS3/OBS/BOS)需要;
 * 本机/从机/又拍云在最后分片后自动完成。TO_VERIFY:以 Fork 实际策略类型枚举为准。
 */
fun needsExplicitComplete(session: UploadSession): Boolean =
    session.policyType?.lowercase()?.let { t ->
        listOf("s3", "oss", "cos", "ks3", "obs", "bos").any { t.contains(it) }
    } ?: false
