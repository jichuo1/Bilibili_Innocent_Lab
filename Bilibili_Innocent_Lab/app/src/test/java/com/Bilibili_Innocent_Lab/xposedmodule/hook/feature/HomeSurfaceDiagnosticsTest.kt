package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** Execute the real before/after callbacks, then consume their data/visibility as the host does. */
class HomeSurfaceDiagnosticsTest {
    class Resource(@JvmField val id: String, @JvmField val title: String, @JvmField val uri: String)
    class Tabs { fun build(source: List<Resource>): List<Resource> = source }
    open class Fragment(private val parent: Fragment? = null) {
        fun getParentFragment(): Fragment? = parent
        fun onViewCreated(@Suppress("UNUSED_PARAMETER") root: View) = Unit
    }
    class BaseHomeFragment : Fragment()
    class CandidateComponent(parent: Fragment) : Fragment(parent)

    /** Skip the SDK stub constructor; only the state touched by these real callbacks is supplied. */
    class Root : View(null) {
        private var shown = 0
        private var clickableValue = false
        private var enabledValue = false
        private var alphaValue = 0f
        override fun getVisibility() = shown
        override fun setVisibility(value: Int) { shown = value }
        override fun isClickable() = clickableValue
        override fun setClickable(value: Boolean) { clickableValue = value }
        override fun isEnabled() = enabledValue
        override fun setEnabled(value: Boolean) { enabledValue = value }
        override fun getAlpha() = alphaValue
        override fun setAlpha(value: Float) { alphaValue = value }
    }

    private class Harness {
        val recorder = PlayerPortTestRegistrar()
        val statuses = arrayListOf<Pair<String, String>>()
        val evidence = arrayListOf<Triple<String, FeatureRuntimeStage, Int>>()
        private val loader = javaClass.classLoader!!
        private val registry = HookPointRegistry(loader)
        val environment = HookEnvironment("tv.danmaku.bili", loader, registry, object : HookRegistrar by recorder {
            override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy,
                                 block: ModernMemberHookCreator.() -> Unit) {
                val method = checkNotNull(registry.resolveAdapted(id, point.className, point.methodName, point.paramClassNames))
                recorder.exact(id, method.declaringClass, method.name, *method.parameterTypes, block = block)
            }
        }, { _, _ -> }, { _, message -> fail(message) }, { channel, value -> statuses += channel to value },
            runtimeEvidence = { id, stage, count -> evidence += Triple(id, stage, count) })
    }

    private fun root(): Root {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        return (unsafe.allocateInstance(Root::class.java) as Root).also {
            it.visibility = View.VISIBLE; it.alpha = 1f; it.isClickable = true; it.isEnabled = true
        }
    }
    private fun componentPoints() = VersionAdapter.HomeComponentPoints(
        VersionAdapter.HookPoint(Fragment::class.java.name, "onViewCreated", listOf(View::class.java.name)),
        VersionAdapter.HookPoint(Fragment::class.java.name, "getParentFragment", emptyList()))

    @Test fun legacyTabRegistrationIsNotApplicationAndTheActualConsumedListReportsRemovalCount() {
        val h = Harness()
        HomeTabFilterFeatureInstaller("隐藏", points = VersionAdapter.HomeTabPoints(
            VersionAdapter.HookPoint(Tabs::class.java.name, "build", listOf(List::class.java.name)),
            Resource::class.java.name, "id", "title", "uri", null),
            frameResolver = { HomeFrameTabLocator.Resolution.Absent }).install(h.environment)
        assertTrue("home_tab_filter_status" to "installed:mode=filter+scan" in h.statuses)
        assertTrue(h.evidence.isEmpty())
        val source = listOf(Resource("1", "保留", "bili://1"), Resource("2", "隐藏", "bili://2"))
        val consumed = h.recorder.invoke("home.tabs.build", Tabs(), arrayOf(source)) { it[0] } as List<*>
        assertEquals(listOf(source.first()), consumed)
        assertEquals(2, source.size)
        assertTrue("home_tab_filter_status" to "observed:path=legacy,count=2" in h.statuses)
        assertTrue("home_tab_filter_status" to "applied:path=legacy,count=1" in h.statuses)
        assertEquals(1, h.evidence.single { it.second == FeatureRuntimeStage.APPLIED }.third)
    }

    @Test fun emptyLegacyQueryStillCountsAsObservedWithoutClaimingAnAppliedChange() {
        val h = Harness()
        HomeTabFilterFeatureInstaller("", points = VersionAdapter.HomeTabPoints(
            VersionAdapter.HookPoint(Tabs::class.java.name, "build", listOf(List::class.java.name)),
            Resource::class.java.name, "id", "title", "uri", null),
            frameResolver = { HomeFrameTabLocator.Resolution.Absent }).install(h.environment)
        repeat(2) { h.recorder.invoke("home.tabs.build", Tabs(), arrayOf(emptyList<Resource>())) }
        assertEquals(1, h.statuses.count { it.second == "observed:path=legacy,count=0" })
        assertEquals(1, h.evidence.count { it.second == FeatureRuntimeStage.OBSERVED })
        assertFalse(h.evidence.any { it.second == FeatureRuntimeStage.APPLIED })
    }

    @Test fun componentReportsApplicationOnlyAfterItChangesActualVisibility() {
        val h = Harness()
        HomeComponentFilterFeatureInstaller("CandidateComponent", points = componentPoints()).install(h.environment)
        assertTrue("home_component_filter_status" to "installed:mode=filter+scan" in h.statuses)
        assertTrue(h.evidence.isEmpty())
        val component = CandidateComponent(BaseHomeFragment())
        val root = root()
        repeat(2) { h.recorder.invoke("home.components.view", component, arrayOf(root)) }
        assertEquals(View.GONE, root.visibility)
        assertEquals(1, h.evidence.count { it.second == FeatureRuntimeStage.OBSERVED })
        assertEquals(1, h.evidence.count { it.second == FeatureRuntimeStage.APPLIED })
        assertTrue("home_component_filter_status" to "applied:path=component,count=1" in h.statuses)
        assertTrue(h.statuses.none { "CandidateComponent" in it.second })
    }

    @Test fun componentScanAndAnAlreadyHiddenNativeViewNeverClaimApplied() {
        for ((rules, visibility) in listOf("" to View.VISIBLE, "CandidateComponent" to View.GONE)) {
            val h = Harness()
            HomeComponentFilterFeatureInstaller(rules, points = componentPoints()).install(h.environment)
            val root = root().also { it.visibility = visibility }
            h.recorder.invoke("home.components.view", CandidateComponent(BaseHomeFragment()), arrayOf(root))
            assertEquals(visibility, root.visibility)
            assertTrue(h.evidence.any { it.second == FeatureRuntimeStage.OBSERVED })
            assertFalse(h.evidence.any { it.second == FeatureRuntimeStage.APPLIED })
        }
    }

    @Test fun tabhostReportsOneActuallyChangedSlotWithoutClaimingTheWholeMatchedList() {
        val h = Harness()
        val host = com.bilibili.lib.homepage.widget.TabHost()
        val keep = com.bilibili.lib.homepage.widget.TabHost.h().also { it.name = "保留"; it.uri = "bili://keep" }
        val hide = com.bilibili.lib.homepage.widget.TabHost.h().also { it.name = "隐藏"; it.uri = "bili://hide" }
        host.javaClass.getDeclaredField("tabs").apply { isAccessible = true }.set(host, listOf(keep, hide))
        BottomBarFeatureInstaller("隐藏", points = VersionAdapter.BottomBarPoints(
            VersionAdapter.HookPoint(host.javaClass.name, "getTabs", emptyList()),
            VersionAdapter.HookPoint(host.javaClass.name, "bind", listOf("int", View::class.java.name)),
            hide.javaClass.name, listOf("name", "uri")), frameResolver = { HomeFrameTabLocator.Resolution.Absent })
            .install(h.environment)
        assertTrue("bottom_bar_status" to "installed:mode=filter+scan" in h.statuses)
        val view = root()
        repeat(2) { h.recorder.invoke("bottom.bind", host, arrayOf(1, view)) }
        assertEquals(View.GONE, view.visibility)
        assertFalse(view.isClickable)
        assertFalse(view.isEnabled)
        assertEquals(0f, view.alpha, 0f)
        assertTrue("bottom_bar_status" to "observed:path=tabhost,count=2" in h.statuses)
        assertTrue("bottom_bar_status" to "applied:path=tabhost,count=1" in h.statuses)
        assertEquals(1, h.evidence.single { it.second == FeatureRuntimeStage.APPLIED }.third)
    }
}
