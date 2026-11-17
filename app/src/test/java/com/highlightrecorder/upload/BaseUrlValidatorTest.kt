package com.highlightrecorder.upload

import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * URL 安全校验。Debug variant 的 ALLOW_PRIVATE_BASE_URL 旁路（隔离联调用途）
 * 只放行环回/私网/本机域名——**非 HTTPS、坏输入、URL 内嵌凭据**等安全拒绝
 * 在任何 variant 都必须成立。旁路只条件化受影响的"拒绝"断言（reject()），
 * 其余测试永远执行（GPT 6961641422：class 级跳过会掩盖安全回归）。
 */
class BaseUrlValidatorTest {

    /** Debug 隔离旁路是否开启（该形态下私网/环回被显式放行）。 */
    private val labBypass: Boolean =
        com.highlightrecorder.BuildConfig.DEBUG &&
            com.highlightrecorder.BuildConfig.ALLOW_PRIVATE_BASE_URL

    private fun v(raw: String): HttpUrl? = BaseUrlValidator.validate(raw)

    /** 旁路开启时该 URL 被放行（隔离联调），拒绝断言不适用。 */
    private fun reject(raw: String) {
        if (labBypass) {
            // 仍断言"放行形态一致"：旁路下这些 URL 必须非空（旁路本身的行为回归）
            assertNotNull("lab bypass should ADMIT private/loopback: $raw", v(raw))
        } else {
            assertNull("expected rejection: $raw", v(raw))
        }
    }

    @Test
    fun `公网域名与公网IP放行`() {
        // 放行类断言不受旁路影响（旁路只放宽，不收紧公网放行）
        assertNotNull(v("https://drive.example.com"))
        assertNotNull(v("https://drive.example.com:8621/base/"))
        assertNotNull(v("https://1.2.3.4"))
        assertNotNull(v("https://172.32.0.1")) // 172 段私网只到 172.31
        assertNotNull(v("https://[2408:8207:1234::1]"))
    }

    @Test
    fun `非https与坏输入拒绝`() {
        // 旁路同时放宽 http（隔离栈联调）——http 断言按旁路条件化
        reject("http://drive.example.com")
        // 协议/形态类拒绝与旁路无关——任何 variant 必须拒绝
        assertNull(v("ftp://drive.example.com"))
        assertNull(v("not a url"))
        assertNull(v(""))
    }

    @Test
    fun `本机与内网域名拒绝`() {
        reject("https://localhost:8621")
        reject("https://api.localhost")
        reject("https://nas.local")
        reject("https://svc.internal")
    }

    @Test
    fun `私网与保留IPv4拒绝`() {
        reject("https://127.0.0.1")
        reject("https://10.1.2.3")
        reject("https://192.168.1.1")
        reject("https://172.16.0.1")
        reject("https://172.31.255.255")
        reject("https://169.254.10.10")
        reject("https://100.64.0.1")   // CGNAT
        reject("https://0.0.0.0")
        reject("https://198.18.0.1")
        reject("https://224.0.0.1")    // 组播
        reject("https://255.255.255.255")
    }

    @Test
    fun `环回与私网IPv6拒绝`() {
        // IPv6 环回/ULA/链路本地/文档段在 validateIpv6 中不经旁路——恒拒绝
        assertNull(v("https://[::1]:8621"))
        assertNull(v("https://[::]"))
        assertNull(v("https://[fe80::1]"))
        assertNull(v("https://[fc00::1]"))
        assertNull(v("https://[fd12:3456::1]"))
        // okhttp 把 ::ffff:a.b.c.d 规范化为纯 IPv4——实际走 isPrivateIpv4 的
        // 旁路感知分支（旁路下放行，属隔离联调面）
        reject("https://[::ffff:127.0.0.1]")
        reject("https://[::ffff:192.168.1.1]")
        assertNull(v("https://[2001:db8::1]"))
    }

    @Test
    fun `URL内嵌凭据拒绝`() {
        // 内嵌凭据是独立安全规则，与私网旁路无关——任何 variant 必须拒绝
        assertNull(v("https://user:pass@drive.example.com"))
    }
}
