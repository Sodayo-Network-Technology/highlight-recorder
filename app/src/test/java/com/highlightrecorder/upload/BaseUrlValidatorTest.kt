package com.highlightrecorder.upload

import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BaseUrlValidatorTest {

    private fun v(raw: String): HttpUrl? = BaseUrlValidator.validate(raw)

    @Test
    fun `公网域名与公网IP放行`() {
        assertNotNull(v("https://drive.example.com"))
        assertNotNull(v("https://drive.example.com:8621/base/"))
        assertNotNull(v("https://1.2.3.4"))
        assertNotNull(v("https://172.32.0.1")) // 172 段私网只到 172.31
        assertNotNull(v("https://[2408:8207:1234::1]"))
    }

    @Test
    fun `非https与坏输入拒绝`() {
        assertNull(v("http://drive.example.com"))
        assertNull(v("ftp://drive.example.com"))
        assertNull(v("not a url"))
        assertNull(v(""))
    }

    @Test
    fun `本机与内网域名拒绝`() {
        assertNull(v("https://localhost:8621"))
        assertNull(v("https://api.localhost"))
        assertNull(v("https://nas.local"))
        assertNull(v("https://svc.internal"))
    }

    @Test
    fun `私网与保留IPv4拒绝`() {
        assertNull(v("https://127.0.0.1"))
        assertNull(v("https://10.1.2.3"))
        assertNull(v("https://192.168.1.1"))
        assertNull(v("https://172.16.0.1"))
        assertNull(v("https://172.31.255.255"))
        assertNull(v("https://169.254.10.10"))
        assertNull(v("https://100.64.0.1"))   // CGNAT
        assertNull(v("https://0.0.0.0"))
        assertNull(v("https://198.18.0.1"))
        assertNull(v("https://224.0.0.1"))    // 组播
        assertNull(v("https://255.255.255.255"))
    }

    @Test
    fun `环回与私网IPv6拒绝`() {
        assertNull(v("https://[::1]:8621"))
        assertNull(v("https://[::]"))
        assertNull(v("https://[fe80::1]"))
        assertNull(v("https://[fc00::1]"))
        assertNull(v("https://[fd12:3456::1]"))
        assertNull(v("https://[::ffff:127.0.0.1]"))
        assertNull(v("https://[::ffff:192.168.1.1]"))
        assertNull(v("https://[2001:db8::1]"))
    }

    @Test
    fun `URL内嵌凭据拒绝`() {
        assertNull(v("https://user:pass@drive.example.com"))
    }
}
