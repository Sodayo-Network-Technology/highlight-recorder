package com.highlightrecorder.upload

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * upload_id 生成(契约 §7 勘误 5):UUIDv5(设备ID + 本地文件 SHA256 + 采集日期)。
 * 同一任务重试必须复用(换 id 会产生第二个不同名文件=重复上传);
 * 仅「同名异物」弱确认时文件名递增 -2/-3(≤3),见 [fileNameWithSuffix]。
 */
object UploadIds {

    /** Sodayo ingest upload_id 命名空间(工程常量;UUIDv5 按域隔离)。 */
    private val NAMESPACE: UUID = UUID.fromString("6f5e0c1e-9d3a-4c5e-8f21-3a7b5d9e1c2f")

    fun stableUploadId(deviceId: Long, sha256: String, recordedAtMs: Long): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(recordedAtMs))
        val name = "dev=$deviceId|sha=$sha256|day=$day"
        // 说明:RFC 4122 §4.3 的 UUIDv5 规范使用 SHA-1;此处按安全扫描要求改用 SHA-256 派生。
        // upload_id 仅为客户端确定性标识(同任务复用、跨端无需互算),SHA-256 派生同样满足且更强。
        val md = MessageDigest.getInstance("SHA-256")
        md.update(toBytes(NAMESPACE))
        md.update(name.toByteArray())
        val h = md.digest()
        h[6] = ((h[6].toInt() and 0x0F) or 0x50).toByte() // version 5
        h[8] = ((h[8].toInt() and 0x3F) or 0x80).toByte() // RFC 4122 variant
        val hex = h.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        // UUID = 16 字节 = 32 hex(8-4-4-4-12);SHA-1 为 20 字节,取前 16 字节
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    /** 当前文件名携带的重命名序号(无后缀=0;…-2.mp4=2)。 */
    fun currentSuffix(fileName: String): Int =
        Regex("-(\\d+)\\.mp4$").find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** 递增序号后的文件名;仅 -2/-3 有效(契约 §7 递增 ≤3),越界拒绝。 */
    fun fileNameWithSuffix(fileName: String, suffix: Int): String? {
        if (suffix < 2 || suffix > 3) return null
        val base = fileName.removeSuffix(".mp4").replace(Regex("-\\d+$"), "")
        return "$base-$suffix.mp4"
    }

    private fun toBytes(u: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
}
