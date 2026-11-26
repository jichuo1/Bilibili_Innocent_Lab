package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.comment

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentTopologyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ReplyTopologySeed
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TestHookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeFlags
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyTopologySplitComponentsTest {

    private class FakeCommentItem
    private class FakeHolder(val itemView: View, private val item: FakeCommentItem)
    private class FakeBinding(private val root: View) {
        @Suppress("unused")
        fun getRoot(): View = root
    }
    private class FakeFieldBinding(@JvmField val a: View)

    private fun seed(rpid: Long) = ReplyTopologySeed(
        key = ReplyTopologyThreadKey(oid = 1L, type = 1, rootRpid = rpid),
        nodes = listOf(
            ReplyTopologyNodeSnapshot.fromRaw(
                rpid = rpid,
                rootRpid = 0L,
                parentRpid = 0L,
                dialogId = 0L,
                ctime = 100L,
                authorMid = 10L,
                authorName = "user",
                repliedAuthorName = null,
                message = "msg-$rpid",
                flags = ReplyTopologyNodeFlags.ROOT
            )
        ),
        expectedReplyCount = 1
    )

    fun `seedBridge enforces bounded LRU capacity of 192 and reports missing classLoader`() {
        val bridge = ReplyTopologySeedBridge()
        for (i in 1L..200L) {
            bridge.cacheSeed(seed(i))
            if (i == 1L) bridge.findSeed(1L)
        }
        // 1..8 evicted cleanly; most recent 9..200 retained.
        assertNull(bridge.findSeed(1L))
        assertNull(bridge.findSeed(8L))
        assertNotNull(bridge.findSeed(9L))
        assertNotNull(bridge.findSeed(200L))
        assertFalse(bridge.allWireBound())
        assertTrue(bridge.shouldRetryWire(null))
        bridge.markAllWireBound()
        assertEquals("wire", bridge.channelKind)
        assertFalse(bridge.shouldRetryWire(null))
        assertTrue(bridge.shouldRetryWire(VersionAdapter.HookPoint("a", "b")))
    }

    fun `anchorBinder extracts comment item and binding root across holder getRoot and field shapes`() {
        val binder = ReplyTopologyAnchorBinder()
        val view = object : ViewGroup(null) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
        }
        val item = FakeCommentItem()
        val holder = FakeHolder(view, item)

        assertSame(item, binder.findCommentItem(FakeCommentItem::class.java, holder, emptyArray()))
        assertSame(item, binder.findCommentItem(FakeCommentItem::class.java, Any(), arrayOf(item)))
        assertNull(binder.findCommentItem(FakeCommentItem::class.java, Any(), emptyArray()))

        assertSame(view, binder.findBindingRoot(holder, emptyArray()))
        assertSame(view, binder.findBindingRoot(null, arrayOf(FakeBinding(view))))
        assertSame(view, binder.findBindingRoot(null, arrayOf(FakeFieldBinding(view))))
    }

    fun `commentTopologyInstaller integrates AdaptRetryableInstaller via FeatureInstallCoordinator retryAdapted`() {
        val posted = mutableListOf<() -> Unit>()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = javaClass.classLoader,
            hookPoints = HookPointRegistry(javaClass.classLoader),
            registrar = TestHookRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            postToMain = { posted += it }
        )
        val coordinator = FeatureInstallCoordinator(env)
        val installer = CommentTopologyFeatureInstaller(
            enabled = true,
            points = null,
            lowBindPoint = null,
            highBindPoint = null
        )

        val initial = coordinator.installAll(listOf(installer)).single().result
        assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), initial)
        assertTrue(installer.isAdaptationRetryPending)

        coordinator.retryAdapted(adapted = null, ok = true)
        assertFalse("After prepare receives null adapted points, retry loop stops", installer.isAdaptationRetryPending)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (ReplyTopologySplitComponentsTest) -> Unit) {
            val s = ReplyTopologySplitComponentsTest()
            block(s)
        }
        runIsolated { s -> s.`seedBridge enforces bounded LRU capacity of 192 and reports missing classLoader`() }
        runIsolated { s -> s.`anchorBinder extracts comment item and binding root across holder getRoot and field shapes`() }
        runIsolated { s -> s.`commentTopologyInstaller integrates AdaptRetryableInstaller via FeatureInstallCoordinator retryAdapted`() }
    }
}
