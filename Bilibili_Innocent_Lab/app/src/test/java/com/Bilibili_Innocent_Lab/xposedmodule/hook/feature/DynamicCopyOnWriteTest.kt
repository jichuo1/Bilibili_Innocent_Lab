package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bapis.bilibili.app.dynamic.v2.*
import com.google.android.material.tabs.TabLayout
import org.junit.Assert.*
import org.junit.Test

class DynamicCopyOnWriteTest {
    private val stages = mutableListOf<FeatureRuntimeStage>()
    private fun purify(reply: DynAllReply, loader: ClassLoader = javaClass.classLoader!!): DynAllReply {
        val installer = DynamicPurifyFeatureInstaller(false, "", false, "", false, false, true, true)
        val environment = HookEnvironment("tv.danmaku.bili", javaClass.classLoader,
            HookPointRegistry(javaClass.classLoader), TestHookRegistrar,
            { _, _ -> }, { _, _ -> }, { _, _ -> }, runtimeEvidence = { _, stage, _ -> stages += stage })
        val specType = installer.javaClass.declaredClasses.single { it.simpleName == "FeedSpec" }
        val spec = specType.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(DynAllReply::class.java.name, "executeDynAll", "dynAll")
        val feed = installer.javaClass.declaredMethods.single { it.name == "resolveFeed" }.apply { isAccessible = true }
            .invoke(installer, loader, DynamicMoss::class.java, spec)
        assertNotNull(feed)
        val plan = DynamicPurifyPolicy.Plan(emptySet(), AuthorRuleSet.EMPTY, false, false)
        return installer.javaClass.declaredMethods.single { it.name == "purify" }.apply { isAccessible = true }
            .invoke(installer, environment, reply, feed, null, plan) as DynAllReply
    }

    fun `missing dynamic item carrier does not disable topic and UP list filtering`() {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> {
                if (name == DynamicItem::class.java.name) throw ClassNotFoundException(name)
                return super.loadClass(name)
            }
        }
        val original = DynAllReply(ups = CardVideoUpList(listOf(UpListItem(1, 1), UpListItem(0, 2))))
        val updated = purify(original, loader)
        assertFalse(updated.topic); assertEquals(1, updated.ups.first.size)
        assertSame(original.items, updated.items)
        assertEquals(2, original.ups.first.size)
    }

    fun `UP list failure cannot clear either original list change positions or remove topic`() {
        for (failure in listOf("position", "second", "build")) {
            stages.clear()
            val first = UpListItem(0, 10, failure == "position")
            val second = UpListItem(0, 30)
            val original = DynAllReply(ups = CardVideoUpList(listOf(first, UpListItem(1, 20)), listOf(second), failure == "second"), failBuild = failure == "build")
            assertSame(original, purify(original)); assertTrue(original.topic)
            assertEquals(2, original.ups.first.size); assertEquals(1, original.ups.second.size)
            assertEquals(10L, first.position); assertEquals(30L, second.position)
            assertTrue(FeatureRuntimeStage.ERROR in stages); assertFalse(FeatureRuntimeStage.APPLIED in stages)
        }
    }

    fun `UP list copies preserve two rows and continuously renumber without touching source`() {
        val first = UpListItem(0, 10)
        val second = UpListItem(0, 30)
        val original = DynAllReply(ups = CardVideoUpList(listOf(first, UpListItem(1, 20)), listOf(second)))
        val updated = purify(original)
        assertNotSame(original, updated); assertSame(original.unrelated, updated.unrelated)
        assertFalse(updated.topic); assertTrue(original.topic)
        assertEquals(listOf(1L), updated.ups.first.map { it.position })
        assertEquals(listOf(2L), updated.ups.second.map { it.position })
        assertEquals(10L, first.position); assertEquals(30L, second.position)
        assertSame(original.items, updated.items)
    }

    fun `unchanged and default replies preserve identity`() {
        val original = DynAllReply(topic = false)
        assertSame(original, purify(original))
        assertSame(DynAllReply.getDefaultInstance(), purify(DynAllReply.getDefaultInstance()))
    }

    open class TemplateBaseTabLayout
    class TemplateMediatorTabLayout : TemplateBaseTabLayout()
    class TemplateDynamicTabItemRenamed(@JvmField val id: Int, @JvmField val titleCol: String, @JvmField val nameCol: String)
    class TemplateDynamicTabItemAmbiguous(@JvmField val a1: String, @JvmField val b1: String, @JvmField val c1: String)
    class TemplateMediatorFragment {
        fun fetchTabs(): List<TemplateDynamicTabItemRenamed> = emptyList()
    }
    class TemplateMediatorFragmentAmbiguous {
        fun fetchTabs(): List<TemplateDynamicTabItemAmbiguous> = emptyList()
    }

    fun `locateDynamicTabs resolves unique non-ab 2 String fields and rejects 3 String fields`() {
        val fragName = "com.bilibili.bplus.followinglist.home.mediator.MediatorFragment"
        val tabName = "com.bilibili.bplus.followinglist.home.mediator.MediatorTabLayout"
        val tabLayoutSuper = TabLayout::class.java.name.replace('.', '/')
        val okLoader = TestRenamingClassLoader(
            parent = javaClass.classLoader!!,
            templates = mapOf(
                fragName to TemplateMediatorFragment::class.java,
                tabName to TemplateMediatorTabLayout::class.java
            ),
            replacements = mapOf(TemplateBaseTabLayout::class.java.name.replace('.', '/') to tabLayoutSuper)
        )
        val ok = requireNotNull(VersionAdapter.locateDynamicTabs(okLoader))
        assertEquals("fetchTabs", ok.listGetter.methodName)
        assertEquals("titleCol", ok.itemTitleField)
        assertEquals("nameCol", ok.itemNameField)

        val badLoader = TestRenamingClassLoader(
            parent = javaClass.classLoader!!,
            templates = mapOf(
                fragName to TemplateMediatorFragmentAmbiguous::class.java,
                tabName to TemplateMediatorTabLayout::class.java
            ),
            replacements = mapOf(TemplateBaseTabLayout::class.java.name.replace('.', '/') to tabLayoutSuper)
        )
        assertNull(VersionAdapter.locateDynamicTabs(badLoader))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (DynamicCopyOnWriteTest) -> Unit) {
            val s = DynamicCopyOnWriteTest()
            block(s)
        }
        runIsolated { s -> s.`missing dynamic item carrier does not disable topic and UP list filtering`() }
        runIsolated { s -> s.`UP list failure cannot clear either original list change positions or remove topic`() }
        runIsolated { s -> s.`UP list copies preserve two rows and continuously renumber without touching source`() }
        runIsolated { s -> s.`unchanged and default replies preserve identity`() }
        runIsolated { s -> s.`locateDynamicTabs resolves unique non-ab 2 String fields and rejects 3 String fields`() }
    }
}
