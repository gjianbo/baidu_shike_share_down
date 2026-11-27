package com.hnwd.bdplayer.extension

import java.net.URI
import java.util.Locale

internal enum class Site(val cookieDomains: Set<String>) {
    BILIBILI(setOf("bilibili.com")),
    X(setOf("x.com", "twitter.com")),
    DOUYIN(setOf("douyin.com", "iesdouyin.com")),
    WEIBO(setOf("weibo.com", "weibo.cn")),
    TIKTOK(setOf("tiktok.com")),
    XIAOHONGSHU(setOf("xiaohongshu.com")),
    YOUTUBE(setOf("youtube.com"));

    fun acceptsCookieDomain(domain: String): Boolean = cookieDomains.any {
        domain == it || domain.endsWith(".$it")
    }
}

internal data class MediaLink(val site: Site, val url: String) {
    companion object {
        private const val MAX_SHARE_TEXT_LENGTH = 65_536
        private const val MAX_MEDIA_URL_LENGTH = 16_384

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

        private val xhsPost = Regex("""/(?:explore|discovery/item)/([0-9a-fA-F]{16,32})""")
        private val xhsShort = Regex("""/(?:(?:o|a|m)/)?[a-zA-Z0-9_-]{4,40}""")
        private val youtubeId = Regex("""[A-Za-z0-9_-]{11}""")
        private val youtubeVideo = Regex("""/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})""")

        fun fromShare(text: String): MediaLink? {
            val shareText = text.take(MAX_SHARE_TEXT_LENGTH)
            return urls.findAll(shareText).firstNotNullOfOrNull { match ->
                if (text.length > shareText.length && match.range.last == shareText.lastIndex) null
                else parse(match.value.trimEnd(',', '.', ';', '!', ')', ']', '》', '」', '』'))
            }
        }

        fun parse(text: String): MediaLink? {
            if (text.length > MAX_MEDIA_URL_LENGTH) return null
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
                "xiaohongshu.com", "www.xiaohongshu.com", "m.xiaohongshu.com", "xhslink.com", "xhslink.cn" -> {
                    val shortHost = host == "xhslink.com" || host == "xhslink.cn"
                    if (!(if (shortHost) xhsShort else xhsPost).matches(path)) return null
                    val canonicalHost = if (shortHost) host else "www.xiaohongshu.com"
                    val canonicalPath = if (shortHost) path else path.lowercase(Locale.ROOT)
                    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
                    val canonicalUrl = "https://$canonicalHost$canonicalPath$query"
                    canonicalUrl.takeIf { it.length <= MAX_MEDIA_URL_LENGTH }?.let { MediaLink(Site.XIAOHONGSHU, it) }
                }
                "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtube-nocookie.com" -> {
                    val id = when {
                        host == "youtu.be" -> path.removePrefix("/")
                        path == "/watch" && host != "www.youtube-nocookie.com" -> query(uri, "v")
                        else -> youtubeVideo.matchEntire(path)?.groupValues?.get(1)
                    }?.takeIf(youtubeId::matches) ?: return null
                    MediaLink(Site.YOUTUBE, "https://www.youtube.com/watch?v=$id")
                }
                else -> null
            }
        }

        private fun query(uri: URI, name: String): String? = uri.rawQuery.orEmpty().split('&')
            .singleOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }
}
