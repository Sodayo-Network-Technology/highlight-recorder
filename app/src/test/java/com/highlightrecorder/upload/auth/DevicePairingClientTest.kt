package com.highlightrecorder.upload.auth

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DevicePairingClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = DevicePairingClient(
        baseUrl = server.url("/"),
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build(),
    )

    @Test
    fun `兑换配对码解析sdkt凭据与ingest目录`() = runTest {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"code":0,"data":{"device_token":"sdkt_abc123","device_id":3,"expires_in":7776000,"scope":"ingest","ingest_uri":"cloudreve://my/移动端取证"}}"""),
        )
        server.start()

        val now = System.currentTimeMillis()
        val r = client().exchange("a1b2c3d4e5", "HUAWEI P60")

        assertEquals("sdkt_abc123", r.token)
        assertEquals(3L, r.deviceId)
        assertEquals("cloudreve://my/移动端取证", r.ingestUri)
        assertEquals("ingest", r.scope)
        assert(r.expiresAtMs in now..(now + 91L * 24 * 3600 * 1000))

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v4/sodayo/device/token", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"pair_code\":\"a1b2c3d4e5\""))
        assertTrue(body.contains("\"device_name\""))
    }

    @Test
    fun `业务码非0透传服务端msg`() = runTest {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setBody("""{"code":40001,"msg":"配对码无效或已过期"}"""),
        )
        server.start()

        try {
            client().exchange("0000000000", "P60")
            throw AssertionError("应当抛出 PairingException")
        } catch (e: PairingException) {
            assertTrue(e.message!!.contains("配对码无效"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空配对码直接拒绝`() = runTest {
        server.start()
        client().exchange(" ", "P60")
    }
}
