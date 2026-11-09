package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.TreeMap

class IgnetHttpSanitizerTest {
    private val identity = IncognitoSession.Identity(1, "session-buvid", "session-id")
    private val noSign: (Map<String, String>) -> String = { error("must not re-sign") }

    @Test fun targetIsHostAndEncodedPath() {
        assertEquals(IgnetHttpSanitizer.Target("app.bilibili.com", "/x/resource/show/tab/v2"),
            IgnetHttpSanitizer.target("https://APP.bilibili.com./x/resource/show/tab/v2?access_key=&a=1#f"))
        assertEquals(IgnetHttpSanitizer.Target("dataflow.biliapi.com", "/"),
            IgnetHttpSanitizer.target("https://user@dataflow.biliapi.com:443"))
        assertThrows(IllegalArgumentException::class.java) { IgnetHttpSanitizer.target("file:///sdcard/a") }
        assertThrows(IllegalArgumentException::class.java) { IgnetHttpSanitizer.target("https:///x") }
    }

    @Test fun cleanUrlIsReturnedUntouched() {
        val url = "https://api.bilibili.com/x/v2/view?aid=170001&keyword=a%20b&sign=abc"
        assertSame(url, IgnetHttpSanitizer.url(url, identity, noSign))
        val bare = "https://api.bilibili.com/x/v2/view"
        assertSame(bare, IgnetHttpSanitizer.url(bare, identity, noSign))
    }

    @Test fun accountParametersAreDroppedAndDeviceIdsReplaced() {
        assertEquals("https://app.bilibili.com/x/tab?a=1&buvid=session-buvid#top",
            IgnetHttpSanitizer.url("https://app.bilibili.com/x/tab?access_key=real&a=1&buvid=real-device#top", identity, noSign))
        assertEquals("https://app.bilibili.com/x/tab",
            IgnetHttpSanitizer.url("https://app.bilibili.com/x/tab?access_key=&csrf=t", identity, noSign))
    }

    @Test fun changedSignedQueryIsResignedFromDecodedParameters() {
        var seen: Map<String, String>? = null
        val out = IgnetHttpSanitizer.url("https://app.bilibili.com/x/tab?access_key=real&keyword=a%20b&sign=old", identity) {
            seen = it
            "keyword=a%20b&sign=new"
        }
        assertEquals(mapOf("keyword" to "a b"), seen)
        assertEquals("https://app.bilibili.com/x/tab?keyword=a%20b&sign=new", out)
    }

    @Test fun malformedEscapesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            IgnetHttpSanitizer.url("https://app.bilibili.com/x?access_key=%zz", identity, noSign)
        }
    }

    @Test fun headersAreSealedCaseInsensitivelyInPlace() {
        val map: MutableMap<String, String> = TreeMap(String.CASE_INSENSITIVE_ORDER)
        map.putAll(mapOf("Authorization" to "identify_v1 real", "Cookie" to "SESSDATA=real", "x-bili-mid" to "42",
            "Buvid" to "real-device", "session_id" to "old", "x-bili-metadata-bin" to "AAAA",
            "is-kmp-neuron" to "1", "User-Agent" to "ua"))
        IgnetHttpSanitizer.headers(map, identity)
        assertEquals(mapOf("Buvid" to "session-buvid", "is-kmp-neuron" to "1", "session_id" to "session-id",
            "User-Agent" to "ua"), map.toMap())
        assertFalse(map.containsKey("AUTHORIZATION"))
    }
}
