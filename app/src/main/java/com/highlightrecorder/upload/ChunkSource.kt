package com.highlightrecorder.upload

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** 按范围读取待上传文件的抽象:协议层不感知 ContentResolver,便于单元测试。 */
fun interface ChunkSource {

    /** 打开从 [offset] 起最多 [length] 字节的读取流。 */
    fun open(offset: Long, length: Int): InputStream
}

/** MediaStore Uri 与 API 26-28 的 file:// 双兼容数据源。 */
class ClipChunkSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    val sizeBytes: Long,
) : ChunkSource {

    override fun open(offset: Long, length: Int): InputStream {
        val raw = if (uri.scheme == "file") {
            File(uri.path ?: throw IOException("非法文件路径: $uri")).inputStream()
        } else {
            resolver.openInputStream(uri) ?: throw IOException("无法打开录像文件: $uri")
        }
        return CappedStream(raw, offset, length)
    }
}

/** 跳到 [offset] 并把读取量限制在 [remaining] 字节内的流包装。 */
private class CappedStream(
    private val raw: InputStream,
    offset: Long,
    private val remaining: Int,
) : InputStream() {

    private var left = remaining

    init {
        var toSkip = offset
        while (toSkip > 0) {
            val n = raw.skip(toSkip)
            if (n > 0) {
                toSkip -= n
            } else {
                if (raw.read() < 0) break else toSkip -= 1
            }
        }
    }

    override fun read(): Int {
        if (left <= 0) return -1
        val r = raw.read()
        if (r >= 0) left--
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val r = raw.read(b, off, minOf(len, left))
        if (r > 0) left -= r
        return r
    }

    override fun close() = raw.close()
}

/** 流式 SHA-256,不整文件载入内存(60–150MB 片段约 1–3 秒)。 */
fun sha256Of(source: ChunkSource, sizeBytes: Long, readChunk: Int = 1 shl 16): String {
    val md = MessageDigest.getInstance("SHA-256")
    var offset = 0L
    while (offset < sizeBytes) {
        val len = minOf(readChunk.toLong(), sizeBytes - offset).toInt()
        source.open(offset, len).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        offset += len
    }
    return md.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
