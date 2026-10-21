package com.highlightrecorder.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadIdsTest {

    @Test
    fun `UUIDv5 确定性-同输入同输出`() {
        val a = UploadIds.stableUploadId(2, "abc123", 1791800000000L)
        val b = UploadIds.stableUploadId(2, "abc123", 1791800000000L)
        assertEquals(a, b)
        // UUID 形态与版本号
        assertEquals(36, a.length)
        assertEquals('5', a[14])
    }

    @Test
    fun `不同设备或日期产生不同id`() {
        val base = UploadIds.stableUploadId(2, "sha", 1791800000000L)
        val other = UploadIds.stableUploadId(3, "sha", 1791800000000L)
        val otherSha = UploadIds.stableUploadId(2, "sha2", 1791800000000L)
        org.junit.Assert.assertNotEquals(base, other)
        org.junit.Assert.assertNotEquals(base, otherSha)
    }

    @Test
    fun `文件名后缀序号解析与递增`() {
        assertEquals(0, UploadIds.currentSuffix("replay-20261021_120000-ab12cd34.mp4"))
        assertEquals(2, UploadIds.currentSuffix("replay-20261021_120000-ab12cd34-2.mp4"))
        assertEquals("replay-20261021_120000-ab12cd34-2.mp4",
            UploadIds.fileNameWithSuffix("replay-20261021_120000-ab12cd34.mp4", 2))
        assertEquals("replay-20261021_120000-ab12cd34-3.mp4",
            UploadIds.fileNameWithSuffix("replay-20261021_120000-ab12cd34-2.mp4", 3))
        // 有界:≤3,超过拒绝
        assertNull(UploadIds.fileNameWithSuffix("replay-20261021_120000-ab12cd34.mp4", 1))
        assertNull(UploadIds.fileNameWithSuffix("replay-x-3.mp4", 4))
    }
}
