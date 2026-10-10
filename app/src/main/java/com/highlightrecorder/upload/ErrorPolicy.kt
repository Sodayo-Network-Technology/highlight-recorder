package com.highlightrecorder.upload

/** 上传失败后的处置决定(对接协议 §7 错误码语义)。 */
enum class RetryDisposition {
    /** 可重试:网络异常、5xx、429、会话过期等,由 WorkManager 退避重试。 */
    RETRY,

    /** 需要人工重新认证(401 且续期失败),暂停队列等待用户处理。 */
    PAUSE_FOR_REAUTH,

    /** 永久失败(403 授权不足/设备被撤销、413 超限、配额不足、业务拒绝),不重试。 */
    PERMANENT,
}

/** 协议层与编排层统一的上传异常。 */
class UploadException(
    /** HTTP 状态码;非 HTTP 错误(网络断开、业务码)为 null。 */
    val httpCode: Int?,
    val disposition: RetryDisposition,
    message: String,
    cause: Throwable? = null,
    /** 429 响应的 Retry-After(秒),重试调度时优先尊重。 */
    val retryAfterSeconds: Long? = null,
) : Exception(message, cause)

object ErrorPolicy {

    /** 单任务最大尝试次数,超过转 FAILED(WorkManager 侧同时兜底)。 */
    const val MAX_ATTEMPTS = 10

    enum class Phase { CREATE, CHUNK, COMPLETE }

    /**
     * HTTP 状态码 → 处置决定。
     * 404/410 在建会话阶段通常是端点不匹配(永久);在分片/完成阶段通常是会话过期(换新会话重试)。
     */
    fun dispositionFor(httpCode: Int?, phase: Phase): RetryDisposition = when {
        httpCode == null -> RetryDisposition.RETRY
        httpCode == 401 -> RetryDisposition.PAUSE_FOR_REAUTH
        httpCode == 403 -> RetryDisposition.PERMANENT
        httpCode == 404 || httpCode == 410 ->
            if (phase == Phase.CREATE) RetryDisposition.PERMANENT else RetryDisposition.RETRY
        httpCode == 409 -> RetryDisposition.RETRY
        httpCode == 413 -> RetryDisposition.PERMANENT
        httpCode == 429 -> RetryDisposition.RETRY
        httpCode in 500..599 -> RetryDisposition.RETRY
        else -> RetryDisposition.PERMANENT
    }

    /** 指数退避 + 抖动;Retry-After 优先且不封顶到下限以下。 */
    fun retryDelayMillis(attempt: Int, retryAfterSeconds: Long? = null): Long {
        retryAfterSeconds?.let { return it.coerceAtLeast(1) * 1000L }
        val backoff = 30_000L shl attempt.coerceIn(0, 6) // 30s → 32min
        val jitter = (0L..10_000L).random()
        return (backoff + jitter).coerceAtMost(30 * 60_000L)
    }
}
