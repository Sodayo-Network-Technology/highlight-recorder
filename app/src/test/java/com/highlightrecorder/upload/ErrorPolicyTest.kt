package com.highlightrecorder.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPolicyTest {

    private fun d(code: Int?, phase: ErrorPolicy.Phase): RetryDisposition =
        ErrorPolicy.dispositionFor(code, phase)

    @Test
    fun `状态码映射符合对接协议7`() {
        assertEquals(RetryDisposition.PAUSE_FOR_REAUTH, d(401, ErrorPolicy.Phase.CHUNK))
        assertEquals(RetryDisposition.PERMANENT, d(403, ErrorPolicy.Phase.CHUNK))
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
        // 建会话 404:端点不匹配 → 永久;分片/完成 404:会话过期 → 重试换新会话
        assertEquals(RetryDisposition.PERMANENT, d(404, ErrorPolicy.Phase.CREATE))
        assertEquals(RetryDisposition.RETRY, d(404, ErrorPolicy.Phase.CHUNK))
        assertEquals(RetryDisposition.RETRY, d(410, ErrorPolicy.Phase.COMPLETE))
    }

    @Test
    fun `RetryAfter优先`() {
        assertEquals(5000L, ErrorPolicy.retryDelayMillis(0, retryAfterSeconds = 5))
    }

    @Test
    fun `指数退避范围`() {
        val low = ErrorPolicy.retryDelayMillis(0)
        assertTrue("首次退避应在30-45秒,实际 $low", low in 30_000L..45_000L)
        val high = ErrorPolicy.retryDelayMillis(20)
        assertTrue("退避封顶30分钟,实际 $high", high <= 30 * 60_000L)
    }
}
