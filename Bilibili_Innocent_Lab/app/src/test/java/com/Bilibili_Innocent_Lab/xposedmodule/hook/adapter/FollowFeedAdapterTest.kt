package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import org.junit.Assert.*
import org.junit.Test

/** Only member selection/cache/scheduling contracts; native queries need separate real-APK evidence. */
class FollowFeedAdapterTest {
    private class CountingLoader(parent: ClassLoader, private val hideDirect: Boolean = false) : ClassLoader(parent) {
        var calls = 0
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            calls++
            if (hideDirect && name in setOf("gv1.c", "iU.d")) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }
    private fun loader(hideDirect: Boolean = false) = CountingLoader(javaClass.classLoader!!, hideDirect)
    private val cached = FollowFeedPoints("renamed.follow.DriftPainter", "renamedPaint", "tS.G", "tS.H",
        "com.bilibili.bplus.followinglist.home.z0", "current")
    private fun offsets(type: Class<*>) = type.getDeclaredMethod("getItemOffsets", Rect::class.java,
        View::class.java, RecyclerView::class.java, RecyclerView.State::class.java)

    fun disabledNeedsQueryAndLocatePerformNoClassLoadsOrDexCalls() {
        val loader = loader(true)
        var dexCalls = 0
        val session = DexAssistSession(DexAssistEngine { dexCalls++; DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH) },
            listOf("test.apk"), loader, setOf(DexAssistQuery.FOLLOW_FEED_STYLE))
        assertFalse(FollowFeedAdapter.needsQuery(loader, cached, enabled = false, attempted = false))
        assertSame(cached, FollowFeedAdapter.locate(loader, session, enabled = false, cached, planned = true))
        assertEquals(0, loader.calls); assertEquals(0, dexCalls)
    }

    fun failedHistoryAndUnplannedLocateNeverRequestAnExtraBridge() {
        val loader = loader(true)
        var dexCalls = 0
        val session = DexAssistSession(DexAssistEngine { dexCalls++; DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH) },
            listOf("test.apk"), loader, emptySet())
        assertFalse(FollowFeedAdapter.needsQuery(loader, null, enabled = true, attempted = true))
        assertNull(FollowFeedAdapter.locate(loader, session, enabled = true, cached = null, planned = false))
        assertEquals(0, dexCalls)
    }

    fun validDriftedCachedMembersRemainUsableWithoutAPlannedQuery() {
        val loader = loader(true)
        assertNotNull(FollowFeedAdapter.resolve(loader, cached))
        assertFalse(FollowFeedAdapter.needsQuery(loader, cached, enabled = true, attempted = false))
        assertEquals(cached, FollowFeedAdapter.locate(loader, null, enabled = true, cached, planned = false))
    }

    fun directMembersAndSingleAssistedOwnerAreRevalidatedThroughFullModelAndHomeAbi() {
        val loader = loader()
        val direct = requireNotNull(FollowFeedAdapter.direct(loader))
        assertEquals("iU.d", direct.painterOwnerClass)
        assertEquals("com.bilibili.bplus.followinglist.home.Q", direct.homeDecorationClass)
        assertNotNull(FollowFeedAdapter.resolve(loader, direct))
        val assisted = FollowFeedAdapter.assisted(loader, listOf(offsets(renamed.follow.DriftPainter::class.java)))
        assertEquals(cached, assisted)
    }

    fun discoveredDecorationsResolveDriftedSubclassesByShapeAndStayFailClosedOnAmbiguity() {
        // 隐藏名表命中的 z0，模拟逐构建改名后名表整体失效、只能靠构造边发现的宿主。
        val loader = object : ClassLoader(javaClass.classLoader!!) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.bilibili.bplus.followinglist.home.z0") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val discovered = FollowFeedAdapter.DiscoveredDecorations(
            home = setOf(renamed.follow.DriftDecorations.Home::class.java),
            personalPage = setOf(renamed.follow.DriftDecorations.Personal::class.java,
                renamed.follow.DriftDecorations.Separator::class.java))
        val assisted = FollowFeedAdapter.assisted(loader,
            listOf(offsets(renamed.follow.DriftPainter::class.java)), discovered)
        assertEquals(renamed.follow.DriftDecorations.Home::class.java.name, assisted?.homeDecorationClass)
        assertEquals(renamed.follow.DriftDecorations.Personal::class.java.name, assisted?.personalDecorationClass)
        // 缓存恢复只认父类形状：漂移后的根包类名不在任何名表里也必须能复验。
        assertNotNull(FollowFeedAdapter.resolve(loader, assisted))
        // 同角色再多一个同父类候选即歧义，personal 必须缺失而不是猜一个。
        val ambiguous = FollowFeedAdapter.assisted(loader, listOf(offsets(renamed.follow.DriftPainter::class.java)),
            discovered.copy(personalPage = discovered.personalPage + renamed.follow.DriftDecorations.SecondPersonal::class.java))
        assertNull(ambiguous?.personalDecorationClass)
    }

    fun multipleOwnersWrongQueryAbiAndForeignHomeAreRejected() {
        val loader = loader()
        assertNull(FollowFeedAdapter.assisted(loader,
            listOf(offsets(iU.d::class.java), offsets(renamed.follow.DriftPainter::class.java))))
        val paint = iU.d::class.java.getDeclaredMethod("f", android.graphics.Canvas::class.java,
            RecyclerView::class.java, View::class.java, tS.G::class.java)
        assertNull(FollowFeedAdapter.assisted(loader, listOf(paint)))
        assertNull(FollowFeedAdapter.resolve(loader, cached.copy(homeDecorationClass = "java.lang.Object")))
        assertNull(FollowFeedAdapter.resolve(loader, cached.copy(painterMethod = "missing")))
        assertNull(FollowFeedAdapter.resolve(loader, cached.copy(modelFamily = "legacy")))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FollowFeedAdapterTest) -> Unit) {
            val s = FollowFeedAdapterTest()
            block(s)
        }
        runIsolated { s -> s.disabledNeedsQueryAndLocatePerformNoClassLoadsOrDexCalls() }
        runIsolated { s -> s.failedHistoryAndUnplannedLocateNeverRequestAnExtraBridge() }
        runIsolated { s -> s.validDriftedCachedMembersRemainUsableWithoutAPlannedQuery() }
        runIsolated { s -> s.directMembersAndSingleAssistedOwnerAreRevalidatedThroughFullModelAndHomeAbi() }
        runIsolated { s -> s.discoveredDecorationsResolveDriftedSubclassesByShapeAndStayFailClosedOnAmbiguity() }
        runIsolated { s -> s.multipleOwnersWrongQueryAbiAndForeignHomeAreRejected() }
    }
}
