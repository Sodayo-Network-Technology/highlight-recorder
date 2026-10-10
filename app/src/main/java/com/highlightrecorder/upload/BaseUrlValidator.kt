package com.highlightrecorder.upload

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 上传服务器地址校验(安全约束,所有发起请求前的必经检查):
 * 仅允许 https + 公网主机;拒绝明文 http、localhost/环回/私网/链路本地/保留地址、
 * URL 内嵌用户凭据(user:pass@host 形式)。
 * 对应对接协议 §9「全程 HTTPS,不把 localhost 写进发行包」。
 */
object BaseUrlValidator {

    /** 校验并返回规范化地址;不合法返回 null。 */
    fun validate(raw: String): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val host = url.host.lowercase()
        if (host.isEmpty()) return null
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")
        ) return null
        return if (host.contains(':')) validateIpv6(host, url) else validateIpv4OrDomain(host, url)
    }

    private fun validateIpv4OrDomain(host: String, url: HttpUrl): HttpUrl? {
        if (!host[0].isDigit()) return url // 普通域名放行
        if (!IPV4_REGEX.matches(host)) return null
        return if (isPrivateIpv4(host)) null else url
    }

    private fun validateIpv6(host: String, url: HttpUrl): HttpUrl? {
        if (host == "::" || host == "::1" || host == "0:0:0:0:0:0:0:0" || host == "0:0:0:0:0:0:0:1") return null
        // IPv4 映射地址(::ffff:a.b.c.d)按 IPv4 规则复核
        MAPPED_V4_REGEX.find(host)?.let { m ->
            return if (isPrivateIpv4(m.groupValues[1])) null else url
        }
        if (host.startsWith("2001:db8")) return null // 文档保留段
        val firstGroup = host.trimStart(':').take(2)
        if (firstGroup == "fc" || firstGroup == "fd") return null // ULA 私网
        if (firstGroup == "fe" && host.length > 2 && host[2] in '8'..'b') return null // 链路本地 fe80::/10
        return url
    }

    private val IPV4_REGEX = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val MAPPED_V4_REGEX = Regex("""^::ffff:(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$""")

    /** 环回/私网/链路本地/共享地址/保留段的 v4 判定。 */
    private fun isPrivateIpv4(ip: String): Boolean {
        val m = IPV4_REGEX.find(ip) ?: return true
        val (a, b) = m.groupValues.let { it[1].toInt() to it[2].toInt() }
        if (m.groupValues.drop(1).any { it.toInt() > 255 }) return true
        return when {
            a == 0 || a == 10 || a == 127 -> true
            a == 100 && b in 64..127 -> true // CGNAT
            a == 169 && b == 254 -> true     // 链路本地
            a == 172 && b in 16..31 -> true
            a == 192 && b == 0 -> true       // 192.0.0.0/24 与 192.0.2.0/24
            a == 192 && b == 168 -> true
            a == 198 && (b == 18 || b == 19) -> true // 基准测试段
            a == 198 && b == 51 -> true
            a == 203 && b == 0 -> true
            a >= 224 -> true                 // 组播与保留
            else -> false
        }
    }
}
