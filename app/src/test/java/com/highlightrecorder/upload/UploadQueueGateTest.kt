package com.highlightrecorder.upload

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传队列自动调度闸(纯 JVM):判定"是否允许为 pending 任务自动重新入队"。
 *
 * 回归背景(queue-flow-hardening):
 *  - UploadCoordinator.reconcile 旧判据 settings.configured 含 token.isNotBlank()
 *    (开发期静态令牌)。纯配对设备(生产正路,静态令牌为空)被判为"未配置",
 *    认证暂停(RETRY_WAIT+nextRetryAt=null)的任务连下次 App 启动的 reconcile 也不恢复;
 *  - 配对成功回调(pair)此前不触发任何恢复,任务只能逐个手动重试。
 * 新判据必须与 onClipSaved/UploadWorker 一致:
 * enabled + 服务器地址合法 + (已配对 || 有静态令牌)。
 */
class UploadQueueGateTest {

    private fun settings(
        enabled: Boolean = true,
        serverUrl: String = "https://drive.example.com",
        token: String = "",
    ) = UploadSettings(enabled = enabled, serverUrl = serverUrl, token = token)

    @Test
    fun `paired device without static token can auto-resume`() {
        // 核心回归:纯配对设备(无静态令牌)——旧 configured=false 会吞掉整个恢复路径
        assertTrue(settings().canAutoQueue(paired = true))
    }

    @Test
    fun `static-token device without pairing can auto-resume`() {
        // 开发/联调期静态令牌路径保持可用
        assertTrue(settings(token = "dev-token").canAutoQueue(paired = false))
    }

    @Test
    fun `no credentials at all blocks auto-resume`() {
        assertFalse(settings().canAutoQueue(paired = false))
    }

    @Test
    fun `disabled upload blocks auto-resume`() {
        assertFalse(settings(enabled = false).canAutoQueue(paired = true))
    }

    @Test
    fun `invalid server url blocks auto-resume`() {
        // 坏输入/空地址在所有 variant 都拒绝(与 Debug 私网旁路无关)
        assertFalse(settings(serverUrl = "").canAutoQueue(paired = true))
        assertFalse(settings(serverUrl = "not a url").canAutoQueue(paired = true))
    }
}
