package com.highlightrecorder.upload.protocol

import com.highlightrecorder.upload.ChunkSource
import com.highlightrecorder.upload.RetryDisposition
import com.highlightrecorder.upload.StaticTokenCredentialProvider
import com.highlightrecorder.upload.UploadException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class CloudreveV4UploaderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun uploader(token: String = "user-token") = CloudreveV4Uploader(
        baseUrl = server.url("/"),
        credentials = StaticTokenCredentialProvider(token),
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build(),
    )

    private fun sourceOf(data: ByteArray) = ChunkSource { offset, length ->
        ByteArrayInputStream(data.copyOfRange(offset.toInt(), (offset + length).toInt()))
    }

    private fun ok(body: String) = MockResponse()
        .addHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `本机存储策略 分片走会话API并解析file_id`() = runTest {
        val data = ByteArray(20) { it.toByte() }
        server.enqueue(ok("""{"code":0,"data":{"session_id":"s1","chunk_size":8,"upload_urls":[],"credential":"sess-cred","storage_policy":{"policy_type":"local","relay":false}}}"""))
        server.enqueue(ok("""{"code":0,"data":{}}"""))
        server.enqueue(ok("""{"code":0,"data":{}}"""))
        server.enqueue(ok("""{"code":0,"data":{"file":{"id":"file-123"}}}"""))
        server.start()

        var progress = 0
        val fileId = uploader().upload(
            fileName = "replay.mp4", mimeType = "video/mp4", sizeBytes = 20L,
            sha256 = "abc", clientUploadId = "cid-1", targetFolder = "/手机取证",
            source = sourceOf(data),
            onProgress = { _, parts, total -> progress++; assertEquals(3, total); assertEquals(parts, progress) },
        )

        assertEquals("file-123", fileId)
        assertEquals(3, progress)

        val create = server.takeRequest()
        assertEquals("PUT", create.method)
        assertEquals("/api/v4/file/upload", create.path)
        assertEquals("Bearer user-token", create.getHeader("Authorization"))
        assertEquals("cid-1", create.getHeader("X-Client-Upload-Id"))
        val createBody = create.body.readUtf8()
        assert(createBody.contains("\"uri\":\"/手机取证/replay.mp4\""))
        assert(createBody.contains("\"client_upload_id\":\"cid-1\""))
        assert(createBody.contains("\"source\":\"android_replay\""))

        val chunk0 = server.takeRequest()
        assertEquals("POST", chunk0.method)
        assertEquals("/api/v4/file/upload/s1/0", chunk0.path)
        assertEquals("Bearer sess-cred", chunk0.getHeader("Authorization"))
        assertEquals(8L, chunk0.body.size)
        assertEquals(0, chunk0.body.readByteArray()[0].toInt())

        val chunk1 = server.takeRequest()
        assertEquals("/api/v4/file/upload/s1/1", chunk1.path)
        assertEquals(8L, chunk1.body.size)

        val chunk2 = server.takeRequest()
        assertEquals("/api/v4/file/upload/s1/2", chunk2.path)
        assertEquals(4L, chunk2.body.size)
    }

    @Test
    fun `设备Token模式 无会话credential时分片回退用户凭据`() = runTest {
        val data = ByteArray(4) { it.toByte() }
        server.enqueue(ok("""{"code":0,"data":{"session_id":"sd1","chunk_size":4,"storage_policy":{"policy_type":"local"}}}"""))
        server.enqueue(ok("""{"code":0,"data":{"file":{"id":"fid-9"}}}"""))
        server.start()

        val fileId = uploader("sdkt_abc").upload(
            fileName = "a.mp4", mimeType = "video/mp4", sizeBytes = 4L,
            sha256 = null, clientUploadId = "c-d", targetFolder = "/手机取证",
            source = sourceOf(data),
        )
        assertEquals("fid-9", fileId)

        val create = server.takeRequest()
        assertEquals("PUT", create.method)
        assertEquals("Bearer sdkt_abc", create.getHeader("Authorization"))
        val chunk = server.takeRequest()
        assertEquals("POST", chunk.method)
        assertEquals("/api/v4/file/upload/sd1/0", chunk.path)
        assertEquals("设备 Token 分片必须携带认证头", "Bearer sdkt_abc", chunk.getHeader("Authorization"))
    }

    @Test
    fun `列表解析并按文件名解析hash_id`() = runTest {
        server.enqueue(ok("""{"code":0,"data":{"files":[{"id":"h-1","name":"replay_a.mp4","size":10},{"id":"h-2","name":"replay_b.mp4","size":5}]}}"""))
        server.enqueue(ok("""{"code":0,"data":{"files":[{"id":"h-1","name":"replay_a.mp4","size":10}]}}"""))
        server.start()
        val up = uploader()

        val id = up.resolveFileId("/手机取证", "replay_b.mp4", attempts = 1)
        assertEquals("h-2", id)
        val listReq = server.takeRequest()
        assertEquals("GET", listReq.method)
        // 交接 §C.3:弱确认列表必须显式 page_size=1000(默认仅 ~100 条)
        assert("page_size=1000" in listReq.path!!)

        val missing = up.resolveFileId("/手机取证", "missing.mp4", attempts = 1)
        assertNull(missing)
    }

    @Test
    fun `列表结果达页大小上限视为可能截断`() = runTest {
        server.enqueue(ok("""{"code":0,"data":{"files":[{"id":"h-1","name":"a.mp4","size":1},{"id":"h-2","name":"b.mp4","size":1}]}}"""))
        server.enqueue(ok("""{"code":0,"data":{"files":[{"id":"h-1","name":"a.mp4","size":1}]}}"""))
        server.start()
        val up = uploader()

        // pageSize=2 返回 2 条 → 截断不可证伪
        val full = up.listFiles("/手机取证", pageSize = 2)
        assertEquals(2, full.files.size)
        assert(full.possiblyTruncated)
        assert(server.takeRequest().path!!.contains("page_size=2"))

        // pageSize=2 返回 1 条 → 可证明完整
        val partial = up.listFiles("/手机取证", pageSize = 2)
        assertEquals(1, partial.files.size)
        assert(!partial.possiblyTruncated)
    }

    @Test
    fun `业务码404也触发目录补救回退`() = runTest {
        val data = ByteArray(4) { it.toByte() }
        // 形态:HTTP 200 + JSON code=404(目录不存在),而非 HTTP 404
        server.enqueue(ok("""{"code":404,"msg":"object not found"}"""))
        server.enqueue(ok("""{"code":0,"data":{}}""")) // 建目录响应
        server.enqueue(ok("""{"code":0,"data":{"session_id":"s-b404","chunk_size":4,"storage_policy":{"policy_type":"local"}}}"""))
        server.enqueue(ok("""{"code":0,"data":{"file":{"id":"f-b404"}}}"""))
        server.start()

        val fileId = uploader().upload(
            "a.mp4", "video/mp4", 4L, null, "cid-b404", "/被删目录",
            sourceOf(data),
        )
        assertEquals("f-b404", fileId)
        val first = server.takeRequest()
        assert(first.body.readUtf8().contains("/被删目录/a.mp4"))
        val retry = server.takeRequest() // 建目录
        assertEquals("/api/v4/directory", retry.path)
        val second = server.takeRequest()
        assert(second.body.readUtf8().contains("/被删目录/a.mp4"))
    }

    @Test
    fun `对象存储策略 走预签名URL且需显式完成`() = runTest {
        val data = ByteArray(10) { it.toByte() }
        server.enqueue(ok("""{"code":0,"data":{"session_id":"s2","chunk_size":8,"upload_urls":["PRESIGNED_0","PRESIGNED_1"],"storage_policy":{"policy_type":"s3"}}}"""))
        server.enqueue(ok("""{}"""))
        server.enqueue(ok("""{}"""))
        server.enqueue(ok("""{"code":0,"data":{"file_id":"fid-9"}}"""))
        server.start()

        val fileId = uploader().upload(
            fileName = "a.mp4", mimeType = "video/mp4", sizeBytes = 10L,
            sha256 = null, clientUploadId = "cid-2", targetFolder = "",
            source = sourceOf(data),
        )

        assertEquals("fid-9", fileId)

        val create2 = server.takeRequest()
        assertEquals("PUT", create2.method)
        assertEquals("/api/v4/file/upload", create2.path)
        assertNull("建会话走用户凭据", create2.getHeader("Authorization")?.let { if (it == "Bearer user-token") null else it })

        val p0 = server.takeRequest()
        assertEquals("PUT", p0.method)
        assertEquals("/PRESIGNED_0", p0.path)
        assertEquals(8L, p0.body.size)
        assertNull("预签名URL不得附带认证头", p0.getHeader("Authorization"))

        val p1 = server.takeRequest()
        assertEquals("/PRESIGNED_1", p1.path)
        assertEquals(2L, p1.body.size)

        val complete = server.takeRequest()
        assertEquals("POST", complete.method)
        assertEquals("/api/v4/file/upload/s2/complete", complete.path)
    }

    @Test
    fun `HTTP401判定AUTH_SUSPECT且不清凭据`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        try {
            uploader().upload("a.mp4", "video/mp4", 1L, null, "c", "", sourceOf(ByteArray(1)))
            throw AssertionError("应当抛出 UploadException")
        } catch (e: UploadException) {
            assertEquals(RetryDisposition.AUTH_SUSPECT, e.disposition)
        }
    }

    @Test
    fun `HTTP401加40020载荷判定AUTH_SUSPECT并捕获correlation`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"code":40020,"msg":"device token invalid, expired or revoked","correlation_id":"corr-1"}"""),
        )
        server.start()
        try {
            uploader().upload("a.mp4", "video/mp4", 1L, null, "c", "", sourceOf(ByteArray(1)))
            throw AssertionError("应当抛出 UploadException")
        } catch (e: UploadException) {
            assertEquals(RetryDisposition.AUTH_SUSPECT, e.disposition)
            assertEquals(40020, e.businessCode)
            assertEquals("corr-1", e.correlationId)
            assert(e.message!!.contains("corr-1"))
        }
    }

    @Test
    fun `同名冲突40004进入弱确认流程`() = runTest {
        server.enqueue(
            ok("""{"code":40004,"msg":"Object existed","correlation_id":"corr-2"}"""),
        )
        server.start()
        try {
            uploader().upload("a.mp4", "video/mp4", 1L, null, "c", "", sourceOf(ByteArray(1)))
            throw AssertionError("应当抛出 UploadException")
        } catch (e: UploadException) {
            assertEquals(RetryDisposition.WEAK_CONFIRM, e.disposition)
            assertEquals(40004, e.businessCode)
            assertEquals("corr-2", e.correlationId)
        }
    }

    @Test
    fun `403已知msg为请求错误_未知403安全拒绝并携带诊断`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"code":403,"msg":"limited to ingest folder"}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(403)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"code":403,"msg":"weird rejection","correlation_id":"corr-3"}"""),
        )
        server.start()
        // 已知 msg:请求错误(客户端 bug),PERMANENT,不触发 AUTH_SUSPECT
        try {
            uploader().upload("a.mp4", "video/mp4", 1L, null, "c", "", sourceOf(ByteArray(1)))
            throw AssertionError("应当抛出 UploadException")
        } catch (e: UploadException) {
            assertEquals(RetryDisposition.PERMANENT, e.disposition)
            assert(e.message!!.contains("limited to ingest"))
        }
        // 未知 msg:安全拒绝,PERMANENT + correlation_id 诊断
        try {
            uploader().upload("a.mp4", "video/mp4", 1L, null, "c", "", sourceOf(ByteArray(1)))
            throw AssertionError("应当抛出 UploadException")
        } catch (e: UploadException) {
            assertEquals(RetryDisposition.PERMANENT, e.disposition)
            assertEquals("corr-3", e.correlationId)
        }
    }

    @Test
    fun `建会话携带last_modified毫秒`() = runTest {
        server.enqueue(
            ok("""{"code":0,"data":{"session_id":"s-lm","chunk_size":4,"storage_policy":{"policy_type":"local"}}}"""),
        )
        server.enqueue(ok("""{"code":0,"data":{"file":{"id":"f-lm"}}}"""))
        server.start()
        uploader().upload(
            "a.mp4", "video/mp4", 4L, null, "c-lm", "", sourceOf(ByteArray(4)),
            lastModifiedMs = 1791800000000L,
        )
        val create = server.takeRequest()
        assert(create.body.readUtf8().contains("\"last_modified\":1791800000000"))
    }

    @Test
    fun `429解析RetryAfter`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "120"))
        server.start()
        try {
            uploader().upload("a.mp4", "video/mp4", 1L, null, "c", "", sourceOf(ByteArray(1)))
            throw AssertionError("应当抛出 UploadException")
        } catch (e: UploadException) {
            assertEquals(RetryDisposition.RETRY, e.disposition)
            assertEquals(120L, e.retryAfterSeconds)
        }
    }

    @Test
    fun `业务码非0视为永久失败并透传msg`() = runTest {
        server.enqueue(ok("""{"code":1009,"msg":"空间容量不足"}"""))
        server.start()
        try {
            uploader().upload("a.mp4", "video/mp4", 1L, null, "c", "", sourceOf(ByteArray(1)))
            throw AssertionError("应当抛出 UploadException")
        } catch (e: UploadException) {
            assertEquals(RetryDisposition.PERMANENT, e.disposition)
            assert(e.message!!.contains("容量不足"))
        }
    }

    @Test
    fun `目标目录404时回退根目录`() = runTest {
        val data = ByteArray(4) { it.toByte() }
        server.enqueue(MockResponse().setResponseCode(404)) // ① 带目录建会话失败
        server.enqueue(ok("""{"code":0,"data":{}}"""))      // ② 建目录响应
        server.enqueue(MockResponse().setResponseCode(404)) // ③ 带目录重试仍失败
        server.enqueue(ok("""{"code":0,"data":{"session_id":"s3","chunk_size":4,"upload_urls":[],"credential":"c","storage_policy":{"policy_type":"local"}}}""")) // ④ 回退根目录成功
        server.enqueue(ok("""{"code":0,"data":{"file":{"id":"f1"}}}""")) // ⑤ 分片响应
        server.start()

        val fileId = uploader().upload(
            "a.mp4", "video/mp4", 4L, null, "cid-3", "/不存在目录",
            sourceOf(data),
        )
        assertEquals("f1", fileId)
        val first = server.takeRequest()
        assert(first.body.readUtf8().contains("\"uri\":\"/不存在目录/a.mp4\""))
        server.takeRequest() // 建目录请求
        val retry = server.takeRequest()
        assert(retry.body.readUtf8().contains("\"uri\":\"/不存在目录/a.mp4\""))
        val fallback = server.takeRequest()
        assert(fallback.body.readUtf8().contains("\"uri\":\"/a.mp4\""))
    }
}
