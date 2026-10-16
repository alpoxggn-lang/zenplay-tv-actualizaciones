package tv.own.owntv.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamM3uLinkTest {
    @Test fun readsTheAccountFromAPlainLink() {
        val link = XtreamM3uLink.parse("http://cf.example.ru/get.php?username=abc123&password=s3cret&type=m3u_plus&output=ts")!!
        assertEquals("http://cf.example.ru", link.server)
        assertEquals("abc123", link.username)
        assertEquals("s3cret", link.password)
        assertFalse(link.preferHls)
    }

    @Test fun undoesHtmlEscapedAmpersands() {
        val link = XtreamM3uLink.parse("http://cf.example.ru/get.php?username=abc123&amp;password=s3cret&amp;output=hls")!!
        assertEquals("abc123", link.username)
        assertEquals("s3cret", link.password)
        assertTrue(link.preferHls)
    }

    @Test fun keepsPortSchemeAndPathPrefix() {
        val link = XtreamM3uLink.parse("https://Host.example:8080/iptv/get.php?password=p&username=u&output=m3u8")!!
        assertEquals("https://Host.example:8080/iptv", link.server)
        assertTrue(link.preferHls)
    }

    @Test fun decodesPercentEncodedCredentials() {
        val link = XtreamM3uLink.parse("http://h.example/get.php?username=a%40b&password=p%26q")!!
        assertEquals("a@b", link.username)
        assertEquals("p&q", link.password)
    }

    @Test fun anythingElseStaysM3u() {
        assertNull(XtreamM3uLink.parse("http://h.example/playlist.m3u"))
        assertNull(XtreamM3uLink.parse("http://h.example/get.php?username=u"))
        assertNull(XtreamM3uLink.parse("content://x/get.php?username=u&password=p"))
        assertNull(XtreamM3uLink.parse("not a url"))
    }
}
