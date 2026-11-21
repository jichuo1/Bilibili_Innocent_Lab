package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPurifyFeatureInstallerTest {

    private data class UrlFixture(
        private val appUrl: String,
        private val displayText: String
    )

    /**
     * 判据是「跳搜索」这个类别，不是某个 `from` 值。
     *
     * 真值取自 2026-09-15 的 `Reply/MainList` 抓包：
     * `Content.urls{}.value.app_url_schema = bilibili://search?from=appcommentline_search…`。
     */
    fun `the search jump criterion matches the captured comment link by category`() {
        assertTrue(CommentPurifyFeatureInstaller.isSearchJumpUri(
            "bilibili://search?from=appcommentline_search&search_from_source=appcommentline_search" +
                "&direct_return=true&keyword=%E6%A2%81%E5%BF%97%E8%B6%85&seid=2798521577071992304"))
        // 同类别的其它 from 也必须命中——不能退化成"一个词一个开关"。
        assertTrue(CommentPurifyFeatureInstaller.isSearchJumpUri("bilibili://search?from=whatever"))
        assertTrue(CommentPurifyFeatureInstaller.isSearchJumpUri("bilibili://search/result?keyword=x"))
    }

    fun `the search jump criterion does not swallow neighbouring hosts or the web url`() {
        // 前缀写法会把这一条误判成命中，类别判据不会。
        assertFalse(CommentPurifyFeatureInstaller.isSearchJumpUri("bilibili://searchxyz?keyword=x"))
        assertFalse(CommentPurifyFeatureInstaller.isSearchJumpUri("bilibili://video/117246567387357"))
        // pc_url(13) 是无 scheme 的网页地址，不归这条判据管（移动端不走它跳转）。
        assertFalse(CommentPurifyFeatureInstaller.isSearchJumpUri(
            "//search.bilibili.com/all?from_source=webcommentline_search&keyword=x"))
        assertFalse(CommentPurifyFeatureInstaller.isSearchJumpUri(null))
        assertFalse(CommentPurifyFeatureInstaller.isSearchJumpUri(""))
    }

    fun `blocks only implicit comment quick reply origins`() {
        assertTrue(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "CARD"))
        assertTrue(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "TEXT"))
        assertTrue(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, ""))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(false, "CARD"))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "REPLY_BUTTON"))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "MORE_MENU"))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "PIC_BAR"))
    }

    fun `keeps original map when no search destination exists`() {
        val source = linkedMapOf("topic" to "bilibili://topic/1")

        val filtered = CommentPurifyFeatureInstaller.withoutSearchUrls(source) {
            it.startsWith("bilibili://search")
        }

        assertSame(source, filtered)
    }

    fun `removes only search entries and returns immutable ordered copy`() {
        val source = linkedMapOf(
            "search" to "bilibili://search?keyword=test",
            "video" to "bilibili://video/BV1",
            "web" to "https://example.com"
        )

        val filtered = CommentPurifyFeatureInstaller.withoutSearchUrls(source) {
            it.startsWith("bilibili://search")
        }

        assertEquals(listOf("video", "web"), filtered.keys.toList())
        assertFalse(filtered.containsKey("search"))
        assertEquals(3, source.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (filtered as MutableMap<String, String>)["new"] = "value"
        }
    }

    fun `detects search uri from cached private text fields only`() {
        val installer = CommentPurifyFeatureInstaller(
            removeSearchLinks = true,
            removeEmptyGuide = false,
            removeVoteWidgets = false,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = false,
            points = null
        )

        assertTrue(installer.isSearchUrlValue(UrlFixture("bilibili://search?q=test", "test")))
        assertFalse(installer.isSearchUrlValue(UrlFixture("bilibili://video/BV1", "search")))
        assertFalse(installer.isSearchUrlValue(null))
    }

    fun `locates the exact quick reply collector boundary`() {
        val points = requireNotNull(VersionAdapter.locateCommentPurify(requireNotNull(javaClass.classLoader)))

        assertEquals(1, points.quickReplyDialogMethods.size)
        assertEquals("emit", points.quickReplyDialogMethods.single().methodName)
        assertEquals(
            listOf(
                "com.bilibili.app.comment3.data.state.PublishDialogIntent",
                "kotlin.coroutines.Continuation"
            ),
            points.quickReplyDialogMethods.single().paramClassNames
        )
    }

    fun `resolves empty page defaults once and registers both protobuf versions`() {
        val loader = requireNotNull(javaClass.classLoader)
        val points = requireNotNull(VersionAdapter.locateCommentPurify(loader))
        val statuses = linkedMapOf<String, String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses[channel] = status }
        )

        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = true,
            removeVoteWidgets = false,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = false,
            points = points
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(2), result)
        assertEquals("success", statuses["comment_purify_status"])
    }

    fun `registers only structurally adapted vote widget binders`() {
        val loader = requireNotNull(javaClass.classLoader)
        val points = requireNotNull(VersionAdapter.locateCommentPurify(loader))
        val statuses = linkedMapOf<String, String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses[channel] = status }
        )

        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = true,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = false,
            points = points
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(3), result)
        assertEquals("success", statuses["comment_purify_status"])
    }

    fun `registers complete follow visibility state and header bind points`() {
        val loader = requireNotNull(javaClass.classLoader)
        val points = requireNotNull(VersionAdapter.locateCommentPurify(loader))
        val statuses = linkedMapOf<String, String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses[channel] = status }
        )

        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = false,
            removeFollowButtons = true,
            removeQoe = false,
            removeOperations = false,
            points = points
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(5), result)
        assertEquals("success", statuses["comment_purify_status"])
    }

    fun `registers qoe public read boundaries without main list mutation`() {
        val loader = requireNotNull(javaClass.classLoader)
        val points = requireNotNull(VersionAdapter.locateCommentPurify(loader))
        val statuses = linkedMapOf<String, String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses[channel] = status }
        )

        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = false,
            removeFollowButtons = false,
            removeQoe = true,
            removeOperations = false,
            points = points
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(2), result)
        assertEquals("success", statuses["comment_purify_status"])
    }

    fun `registers both operation versions without mutating main list reply`() {
        val loader = requireNotNull(javaClass.classLoader)
        val points = requireNotNull(VersionAdapter.locateCommentPurify(loader))
        val statuses = linkedMapOf<String, String>()
        val environment = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { channel, status -> statuses[channel] = status }
        )

        val result = CommentPurifyFeatureInstaller(
            removeSearchLinks = false,
            removeEmptyGuide = false,
            removeVoteWidgets = false,
            removeFollowButtons = false,
            removeQoe = false,
            removeOperations = true,
            points = points
        ).install(environment)

        assertEquals(FeatureInstallResult.Installed(4), result)
        assertEquals("success", statuses["comment_purify_status"])
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentPurifyFeatureInstallerTest) -> Unit) {
            val s = CommentPurifyFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`the search jump criterion matches the captured comment link by category`() }
        runIsolated { s -> s.`the search jump criterion does not swallow neighbouring hosts or the web url`() }
        runIsolated { s -> s.`blocks only implicit comment quick reply origins`() }
        runIsolated { s -> s.`keeps original map when no search destination exists`() }
        runIsolated { s -> s.`removes only search entries and returns immutable ordered copy`() }
        runIsolated { s -> s.`detects search uri from cached private text fields only`() }
        runIsolated { s -> s.`locates the exact quick reply collector boundary`() }
        runIsolated { s -> s.`resolves empty page defaults once and registers both protobuf versions`() }
        runIsolated { s -> s.`registers only structurally adapted vote widget binders`() }
        runIsolated { s -> s.`registers complete follow visibility state and header bind points`() }
        runIsolated { s -> s.`registers qoe public read boundaries without main list mutation`() }
        runIsolated { s -> s.`registers both operation versions without mutating main list reply`() }
    }
}
