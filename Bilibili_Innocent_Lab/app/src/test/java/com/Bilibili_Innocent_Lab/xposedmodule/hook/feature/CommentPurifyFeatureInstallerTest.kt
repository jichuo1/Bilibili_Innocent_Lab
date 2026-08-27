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

    @Test
    fun `blocks only implicit comment quick reply origins`() {
        assertTrue(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "CARD"))
        assertTrue(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "TEXT"))
        assertTrue(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, ""))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(false, "CARD"))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "REPLY_BUTTON"))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "MORE_MENU"))
        assertFalse(CommentPurifyFeatureInstaller.shouldBlockQuickReply(true, "PIC_BAR"))
    }

    @Test
    fun `keeps original map when no search destination exists`() {
        val source = linkedMapOf("topic" to "bilibili://topic/1")

        val filtered = CommentPurifyFeatureInstaller.withoutSearchUrls(source) {
            it.startsWith("bilibili://search")
        }

        assertSame(source, filtered)
    }

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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
}
