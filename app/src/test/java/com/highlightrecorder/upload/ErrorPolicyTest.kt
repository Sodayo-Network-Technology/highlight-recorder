package com.highlightrecorder.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPolicyTest {

    private fun d(code: Int?, phase: ErrorPolicy.Phase): RetryDisposition =
        ErrorPolicy.dispositionFor(code, phase)

    private fun b(code: Int?): RetryDisposition = ErrorPolicy.dispositionForBusiness(code)

    @Test
    fun `HTTP状态码映射符合契约v2_2`() {
        assertEquals(RetryDisposition.AUTH_SUSPECT, d(401, ErrorPolicy.Phase.CHUNK))
        assertEquals(RetryDisposition.PERMANENT, d(403, ErrorPolicy.Phase.CHUNK)) // 403 一律不清凭据,分类看 msg
        assertEquals(RetryDisposition.RETRY, d(409, ErrorPolicy.Phase.CHUNK))
        assertEquals(RetryDisposition.PERMANENT, d(413, ErrorPolicy.Phase.CHUNK))
        assertEquals(RetryDisposition.RETRY, d(429, ErrorPolicy.Phase.CHUNK))
        assertEquals(RetryDisposition.RETRY, d(500, ErrorPolicy.Phase.CHUNK))
        assertEquals(RetryDisposition.RETRY, d(503, ErrorPolicy.Phase.COMPLETE))
        assertEquals(RetryDisposition.PERMANENT, d(400, ErrorPolicy.Phase.CHUNK))
        assertEquals(RetryDisposition.RETRY, d(null, ErrorPolicy.Phase.CHUNK)) // 网络异常
    }

    @Test
    fun `404按阶段区分`() {
        // 建会话/列表 404:端点或目录不匹配 → 永久;分片/完成 404:会话过期 → 重试换新会话
        assertEquals(RetryDisposition.PERMANENT, d(404, ErrorPolicy.Phase.CREATE))
        assertEquals(RetryDisposition.PERMANENT, d(404, ErrorPolicy.Phase.LIST))
        assertEquals(RetryDisposition.RETRY, d(404, ErrorPolicy.Phase.CHUNK))
        assertEquals(RetryDisposition.RETRY, d(410, ErrorPolicy.Phase.COMPLETE))
    }

    @Test
    fun `业务码路由符合契约7`() {
        // 40020/401 → AUTH_SUSPECT(保留 Token,绝不自动清除)
        assertEquals(RetryDisposition.AUTH_SUSPECT, b(ErrorPolicy.CODE_AUTH))
        assertEquals(RetryDisposition.AUTH_SUSPECT, b(401))
        // 40004/40011 → 弱确认流程(绝不直接判成功)
        assertEquals(RetryDisposition.WEAK_CONFIRM, b(ErrorPolicy.CODE_NAME_CONFLICT))
        assertEquals(RetryDisposition.WEAK_CONFIRM, b(ErrorPolicy.CODE_SESSION_INVALID))
        // 40051 容量/40013 分片长度 → 永久
        assertEquals(RetryDisposition.PERMANENT, b(ErrorPolicy.CODE_CAPACITY))
        assertEquals(RetryDisposition.PERMANENT, b(ErrorPolicy.CODE_BAD_SLICE))
        // 未知业务码 → 永久
        assertEquals(RetryDisposition.PERMANENT, b(40001))
        assertEquals(RetryDisposition.PERMANENT, b(null))
    }

    @Test
    fun `sdkt_403消息分类`() {
        assertTrue(ErrorPolicy.isKnownRequestError403("limited to ingest folder"))
        assertTrue(ErrorPolicy.isKnownRequestError403("device not bound to this session"))
        assertFalse(ErrorPolicy.isKnownRequestError403("some unknown rejection"))
        assertFalse(ErrorPolicy.isKnownRequestError403(null))
    }

    @Test
    fun `RetryAfter优先`() {
        assertEquals(5000L, ErrorPolicy.retryDelayMillis(0, retryAfterSeconds = 5))
    }

    @Test
    fun `指数退避1s到60s`() {
        val low = ErrorPolicy.retryDelayMillis(0)
        assertTrue("首次退避应在1-2秒,实际 $low", low in 1_000L..1_999L)
        val high = ErrorPolicy.retryDelayMillis(20)
        assertTrue("退避封顶60秒,实际 $high", high <= 60_000L)
    }

    @Test
    fun `弱确认决策真值表(交接2026-10-22_C3)`() {
        // 同名 + size 相符 → 弱确认结案
        assertEquals(WeakConfirmDecision.CONFIRMED, WeakConfirm.decide(true, true, false))
        assertEquals(WeakConfirmDecision.CONFIRMED, WeakConfirm.decide(true, true, true))
        // 同名异物 → 换名重传
        assertEquals(WeakConfirmDecision.RENAME_AND_RETRY, WeakConfirm.decide(true, false, false))
        assertEquals(WeakConfirmDecision.RENAME_AND_RETRY, WeakConfirm.decide(true, false, true))
        // 无候选 + 列表完整 → 同 upload_id 安全重传
        assertEquals(WeakConfirmDecision.SAFE_REUPLOAD, WeakConfirm.decide(false, false, false))
        // 无候选 + 列表可能截断 → 确认不充分,绝不自动重传
        assertEquals(WeakConfirmDecision.INSUFFICIENT_LIST, WeakConfirm.decide(false, false, true))
    }
}
