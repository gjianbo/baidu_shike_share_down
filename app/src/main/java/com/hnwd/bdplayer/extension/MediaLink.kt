package com.hnwd.bdplayer.extension

import java.net.URI
import java.util.Locale

internal enum class Site(val cookieDomains: Set<String>) {
    BILIBILI(setOf("bilibili.com")),
    X(setOf("x.com", "twitter.com")),
    DOUYIN(setOf("douyin.com", "iesdouyin.com")),
    WEIBO(setOf("weibo.com", "weibo.cn")),
    TIKTOK(setOf("tiktok.com"));

    fun acceptsCookieDomain(domain: String): Boolean = cookieDomains.any {
        domain == it || domain.endsWith(".$it")
    }
}

internal data class MediaLink(val site: Site, val url: String) {
    companion object {
        private val urls = Regex("""https?://[^\s<>"'（）【】，。；！、]+""", RegexOption.IGNORE_CASE)
        private val bilibili = Regex("""/video/(BV[a-zA-Z0-9]{10}|av\d{1,15})""")
        private val x = Regex("""/(?:[a-zA-Z0-9_]+|i/web)/status/(\d{5,25})(?:/.*)?""")
        private val douyin = Regex("""/video/(\d{8,25})""")
        private val shortBilibili = Regex("""/[a-zA-Z0-9]{4,32}""")
        private val shortDouyin = Regex("""/[a-zA-Z0-9_-]{4,40}""")

        private val weiboPost = Regex("""/(?:[0-9]{4,20}|status|detail)/([A-Za-z0-9]{5,25})""")
        private val weiboVideo = Regex("""/tv/show/(1034:[0-9]{5,25})""")
        private val tiktokVideo = Regex("""/(?:@[A-Za-z0-9_.-]+/video|embed)/([0-9]{8,25})""")
        private val tiktokShort = Regex("""/(?:t/)?([A-Za-z0-9]{4,24})""")

        fun fromShare(text: String): MediaLink? = urls.findAll(text.take(16_384)).firstNotNullOfOrNull {
            parse(it.value.trimEnd(',', '.', ';', '!', ')', ']', '》', '」', '』'))
        }

        fun parse(text: String): MediaLink? {
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
                uri.rawUserInfo != null || uri.port != -1) return null
            val path = uri.rawPath.orEmpty().trimEnd('/')
            return when (val host = uri.host?.lowercase(Locale.ROOT)) {
                "www.bilibili.com", "bilibili.com", "m.bilibili.com" -> {
                    val id = bilibili.matchEntire(path)?.groupValues?.get(1) ?: return null
                    val page = query(uri, "p")?.toIntOrNull()?.takeIf { it > 0 }
                    MediaLink(Site.BILIBILI, "https://www.bilibili.com/video/$id/" + (page?.let { "?p=$it" } ?: ""))
                }
                "b23.tv" -> if (shortBilibili.matches(path)) MediaLink(Site.BILIBILI, "https://$host$path") else null
                "x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com" -> {
                    val id = x.matchEntire(path)?.groupValues?.get(1) ?: return null
                    MediaLink(Site.X, "https://x.com/i/status/$id")
                }
                "www.douyin.com", "douyin.com" -> {
                    val id = douyin.matchEntire(path)?.groupValues?.get(1)
                        ?: query(uri, "modal_id")?.takeIf { path == "/jingxuan" && it.matches(Regex("""\d{8,25}""")) }
                        ?: return null
                    MediaLink(Site.DOUYIN, "https://www.douyin.com/video/$id/")
                }
                "v.douyin.com" -> if (shortDouyin.matches(path)) MediaLink(Site.DOUYIN, "https://$host$path/") else null
                "weibo.com", "www.weibo.com", "m.weibo.cn" -> {
                    if (!weiboPost.matches(path) && !weiboVideo.matches(path)) return null
                    MediaLink(Site.WEIBO, "https://$host$path")
                }
                "tiktok.com", "www.tiktok.com", "vm.tiktok.com", "vt.tiktok.com" -> {
                    val shortHost = host == "vm.tiktok.com" || host == "vt.tiktok.com"
                    val isShort = (shortHost || path.startsWith("/t/")) && tiktokShort.matches(path)
                    if (!isShort && (shortHost || !tiktokVideo.matches(path))) return null
                    val canonicalHost = if (shortHost) host else "www.tiktok.com"
                    MediaLink(Site.TIKTOK, "https://$canonicalHost$path/")
                }
                else -> null
            }
        }

        private fun query(uri: URI, name: String): String? = uri.rawQuery.orEmpty().split('&')
            .singleOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }
}
