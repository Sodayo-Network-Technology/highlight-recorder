package com.highlightrecorder.upload

/**
 * 上传失败处置(契约 v2.2 FINAL §1 三维度决策表 + §7 重试规则)。
 */
enum class RetryDisposition {
    /** 可重试:网络异常、5xx、429、会话过期重建等,由 WorkManager 退避重试。 */
    RETRY,

    /**
     * 认证疑似失效(HTTP 401 / 业务码 40020·401):撤销/冻结/权限回收服务端不可区分。
     * 动作=暂停任务+【保留 Token】+保留本地视频+提示用户;解冻/权限恢复后同 Token 自动恢复。
     * 仅用户在 UI 明确「重新配对/解除配对」时才清除 Token(契约 §6,rev3 勘误 7)。
     */
    AUTH_SUSPECT,

    /** 永久失败:403(请求错误或未知拒绝,均不清凭据)、40051 容量不足、40013 分片长度、其他 4xx。 */
    PERMANENT,

    /**
     * 弱确认流程(业务码 40004 同名冲突 / 40011 会话无效):
     * 绝不直接判成功——查列表核对 name+size;弱确认结案【不删本地原件】;
     * 未确认且会话过期→同 upload_id 重建;同名异物→upload_id-2 递增(≤3)。
     */
    WEAK_CONFIRM,
}

/** 协议层与编排层统一的上传异常。 */
class UploadException(
    /** HTTP 状态码;纯业务码错误(HTTP 200+code≠0)为 null。 */
    val httpCode: Int?,
    val disposition: RetryDisposition,
    message: String,
    cause: Throwable? = null,
    /** 429 响应的 Retry-After(秒)。 */
    val retryAfterSeconds: Long? = null,
    /** JSON envelope 业务码(code≠0 时)。 */
    val businessCode: Int? = null,
    /** 服务端排障用 correlation_id(仅错误响应携带),随任务 lastError 展示供回报。 */
    val correlationId: String? = null,
) : Exception(message, cause)

object ErrorPolicy {

    /** 单任务最大尝试次数,超过挂起队列(契约 §7「连续 10 次挂起」)。 */
    const val MAX_ATTEMPTS = 10

    enum class Phase { CREATE, CHUNK, COMPLETE, LIST }

    // 契约 §7 业务码
    const val CODE_AUTH = 40020
    const val CODE_NAME_CONFLICT = 40004
    const val CODE_SESSION_INVALID = 40011
    const val CODE_CAPACITY = 40051
    const val CODE_BAD_SLICE = 40013

    /** JSON envelope 业务码 → 处置(契约 §7 表)。 */
    fun dispositionForBusiness(code: Int?): RetryDisposition = when (code) {
        null -> RetryDisposition.PERMANENT
        401, CODE_AUTH -> RetryDisposition.AUTH_SUSPECT
        CODE_NAME_CONFLICT, CODE_SESSION_INVALID -> RetryDisposition.WEAK_CONFIRM
        CODE_CAPACITY, CODE_BAD_SLICE -> RetryDisposition.PERMANENT
        else -> RetryDisposition.PERMANENT
    }

    /**
     * HTTP 状态码 → 处置(无 envelope 业务码时的兜底;有业务码优先走 [dispositionForBusiness])。
     * 404/410 在建会话/列表阶段=端点或目录不匹配(永久);分片/完成阶段=会话过期(重试)。
     */
    fun dispositionFor(httpCode: Int?, phase: Phase): RetryDisposition = when {
        httpCode == null -> RetryDisposition.RETRY
        httpCode == 401 -> RetryDisposition.AUTH_SUSPECT
        httpCode == 403 -> RetryDisposition.PERMANENT // 403 一律不清凭据;msg 分类见 [isKnownRequestError403]
        httpCode == 404 || httpCode == 410 ->
            if (phase == Phase.CREATE || phase == Phase.LIST) RetryDisposition.PERMANENT else RetryDisposition.RETRY
        httpCode == 409 -> RetryDisposition.RETRY
        httpCode == 413 -> RetryDisposition.PERMANENT
        httpCode == 429 -> RetryDisposition.RETRY
        httpCode in 500..599 -> RetryDisposition.RETRY
        else -> RetryDisposition.PERMANENT
    }

    /**
     * sdkt_ 403 分类(契约 §1):msg 含已知模式=客户端请求错误(路径越界/会话绑定不符,修 bug);
     * 其余=未知拒绝(服务端无稳定区分字段,安全拒绝+诊断)。两者均不触发 AUTH_SUSPECT。
     */
    fun isKnownRequestError403(msg: String?): Boolean =
        msg != null && (
            msg.contains("limited to ingest", ignoreCase = true) ||
                msg.contains("not bound", ignoreCase = true)
            )

    /** 指数退避 1s→60s + 抖动(契约 §7);Retry-After 优先。 */
    fun retryDelayMillis(attempt: Int, retryAfterSeconds: Long? = null): Long {
        retryAfterSeconds?.let { return it.coerceAtLeast(1) * 1000L }
        val backoff = 1000L shl attempt.coerceIn(0, 6) // 1s → 64s
        val jitter = (0L..999L).random()
        return (backoff + jitter).coerceAtMost(60_000L)
    }
}
