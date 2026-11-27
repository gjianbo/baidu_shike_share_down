package com.hnwd.bdplayer.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaLinkTest {
    @Test fun normalizesYouTubeShortsAndShortLinksToOneVideo() {
        val expected = MediaLink(Site.YOUTUBE, "https://www.youtube.com/watch?v=jNQXAC9IVRw")
        assertEquals(expected, MediaLink.fromShare("分享 https://youtu.be/jNQXAC9IVRw?si=tracking"))
        assertEquals(expected, MediaLink.parse("https://m.youtube.com/shorts/jNQXAC9IVRw"))
        assertEquals(expected, MediaLink.parse("https://www.youtube.com/watch?v=jNQXAC9IVRw&list=PLtest"))
        assertNull(MediaLink.parse("https://www.youtube.com/playlist?list=PLtest"))
        assertNull(MediaLink.parse("https://youtu.be.example.com/jNQXAC9IVRw"))
    }

    @Test fun keepsShortXiaohongshuLinksAndSignedNoteQuery() {
        val short = "https://xhslink.cn/o/3dk4TLf0GUb"
        assertEquals(MediaLink(Site.XIAOHONGSHU, short), MediaLink.parse(short))
        val note = MediaLink.parse("http://m.xiaohongshu.com/discovery/item/6B02EEE2000000002A03BE8E?xsec_token=abc%3D&xsec_source=app_share")
        assertEquals(MediaLink(Site.XIAOHONGSHU,
            "https://www.xiaohongshu.com/discovery/item/6b02eee2000000002a03be8e?xsec_token=abc%3D&xsec_source=app_share"), note)
    }
    @Test fun longSignedShareRemainsIntactAcrossShareAndDownloadParsing() {
        val note = "https://www.xiaohongshu.com/explore/6b02eee2000000002a03be8e"
        val url = "$note?padding=${"a".repeat(3_000)}&xsec_token=signed%3D"
        val link = MediaLink.fromShare("发布分享 $url 【小红书】")
        assertEquals(MediaLink(Site.XIAOHONGSHU, url), link)
        assertEquals(link, MediaLink.parse(requireNotNull(link).url))
        assertNull(MediaLink.parse("$note?xsec_token=${"a".repeat(17_000)}"))
        assertNull(MediaLink.fromShare(" ".repeat(65_000) + url))
    }

}
