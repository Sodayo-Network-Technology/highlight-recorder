package com.highlightrecorder.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-B（GPT 6961327400）：重试时间语义统一与用户文案验收。 */
class ErrorPolicyP0bTest {

    @Test
    fun `auth message leads with action and states next-app-open resume`() {
        val msg = authSuspectUserMessage("401 unauthorized")
        // 行动指引在前（两行截断也不丢关键操作）
        assertTrue(msg.startsWith("设备可能被撤销或账号被冻结"))
        assertTrue(msg.contains("设置 → 重新配对"))
        // 如实描述恢复机制：重新配对成功后立即自动续传(queue-flow-hardening:
        // pair 成功回调触发 pending 重新入队)；下次打开 App 也会兜底恢复
        assertTrue(msg.contains("重新配对成功后会自动继续上传"))
        assertTrue(msg.contains("下次打开 App"))
        // 技术诊断附尾
        assertTrue(msg.endsWith("（401 unauthorized）"))
    }

    @Test
    fun `subtitle distinguishes four queue semantics`() {
        // 系统计划重试（Result.retry 路径，nextRetryAt 非空）：次数进度、无具体时刻
        assertEquals("自动重试中", queueStateSubtitle("RETRY_WAIT", 123L, 0))
        assertEquals("自动重试中 · 已重试 3 次", queueStateSubtitle("RETRY_WAIT", 123L, 3))
        // 认证暂停（AUTH_SUSPECT：nextRetryAt=null，无系统重试）
        assertEquals("认证已暂停", queueStateSubtitle("RETRY_WAIT", null, 2))
        // 需人工确认（弱确认不充分）
        assertEquals("需点重试确认", queueStateSubtitle("UNRESOLVED", null, 1))
        // 其余（QUEUED/UPLOADING/DONE/FAILED）无副标题
        assertEquals("", queueStateSubtitle("QUEUED", null, 0))
        assertEquals("", queueStateSubtitle("UPLOADING", null, 0))
        assertEquals("", queueStateSubtitle("DONE", null, 0))
    }
}
