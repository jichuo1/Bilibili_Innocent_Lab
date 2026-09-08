package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeExtraCardPolicyTest {
    @Test fun explicitTypesWorkInEachExistingCarrier() {
        for (token in listOf("bangumi", "bangumi_av", "bangumi_p", "bangumi_rcmd", "pgc", "CARD_TYPE_PGC")) {
            assertEquals(HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify(token, null, null, null))
            assertEquals(HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify(null, token, null, null))
            assertEquals(HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify(null, null, token, null))
        }
        for (token in listOf("special", "special_s", "special_s_p", "CARD_TYPE_SPECIAL")) {
            assertEquals(HomeExtraCardPolicy.Kind.SPECIAL, HomeExtraCardPolicy.classify(token, null, null, null))
        }
    }

    @Test fun exactPlaybackRoutesWinOverSpecialAppearance() {
        for (uri in listOf(
            "bilibili://bangumi/play/ep123?from=home", "bilibili://bangumi/season/123",
            "bilibili://pgc/play/ss123", "https://www.bilibili.com/bangumi/play/ep123",
            "https://m.bilibili.com/bangumi/play/ss123/", "https://bilibili.com/bangumi/play/ep1#x"
        )) {
            assertEquals(uri, HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify("special", null, null, uri))
        }
        assertEquals(HomeExtraCardPolicy.Kind.PGC, HomeExtraCardPolicy.classify("special", "pgc", null, null))
    }

    @Test fun ugcAndUnknownTypesNeverMatchBySubstring() {
        for (token in listOf(null, "", "av", "bangumi_ugc", "special_attention", "special_future",
            "bangumi_future", "not_pgc", "movie_review", "电视剧解说", "small_cover_v2")) {
            assertEquals(token, HomeExtraCardPolicy.Kind.UNKNOWN, HomeExtraCardPolicy.classify(token, null, null, null))
        }
        assertEquals(HomeExtraCardPolicy.Kind.UNKNOWN, HomeExtraCardPolicy.classify("bangumi_ugc", "bangumi", "av", null))
    }

    @Test fun untrustedOrUnrecognizedRoutesFailOpen() {
        for (uri in listOf(null, "", "not a uri", "https://evil.test/bangumi/play/ep123",
            "https://www.bilibili.com.evil.test/bangumi/play/ep123",
            "https://evil.test@www.bilibili.com/bangumi/play/ep123",
            "https://www.bilibili.com:123/bangumi/play/ep123",
            "https://www.bilibili.com/video/BV123?next=/bangumi/play/ep123",
            "bilibili://video/123?from=bangumi", "bilibili://bangumi/play/ep0",
            "bilibili://bangumi/play/ep123evil", "bilibili://bangumi/unknown/123",
            "bilibili://bangumi/play/%65p123", "bilibili://bangumi/play/ep123/" + "x".repeat(4096))) {
            assertEquals(uri, HomeExtraCardPolicy.Kind.UNKNOWN, HomeExtraCardPolicy.classify(null, null, null, uri))
        }
    }
}
