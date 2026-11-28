package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostOpusBackgroundAccess
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

/** 成员选择/缓存/调度与 9.16–9.19 代际归属契约；原生查询需单独的真包证据。 */
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
    private val cached = FollowFeedPoints(
        "renamed.follow.DriftPainter", "renamedPaint", "tS.G", "tS.H",
        "com.bilibili.bplus.followinglist.home.z0", "current"
    )
    private fun offsets(type: Class<*>) = type.getDeclaredMethod(
        "getItemOffsets", Rect::class.java, View::class.java, RecyclerView::class.java, RecyclerView.State::class.java
    )

    @Test
    fun `feed adapter selection cache revalidation and decoration discovery`() {
        // Disabled needsQuery/locate perform no class loads or DEX calls
        run {
            val l = loader(true)
            var dexCalls = 0
            val session = DexAssistSession(
                DexAssistEngine { dexCalls++; DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH) },
                listOf("test.apk"), l, setOf(DexAssistQuery.FOLLOW_FEED_STYLE)
            )
            assertFalse(FollowFeedAdapter.needsQuery(l, cached, enabled = false, attempted = false))
            assertSame(cached, FollowFeedAdapter.locate(l, session, enabled = false, cached, planned = true))
            assertEquals(0, l.calls); assertEquals(0, dexCalls)
        }

        // Failed history / unplanned locate never request bridge
        run {
            val l = loader(true)
            var dexCalls = 0
            val session = DexAssistSession(
                DexAssistEngine { dexCalls++; DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH) },
                listOf("test.apk"), l, emptySet()
            )
            assertFalse(FollowFeedAdapter.needsQuery(l, null, enabled = true, attempted = true))
            assertNull(FollowFeedAdapter.locate(l, session, enabled = true, cached = null, planned = false))
            assertEquals(0, dexCalls)
        }

        // Valid drifted cached members remain usable without planned query
        run {
            val l = loader(true)
            assertNotNull(FollowFeedAdapter.resolve(l, cached))
            assertFalse(FollowFeedAdapter.needsQuery(l, cached, enabled = true, attempted = false))
            assertEquals(cached, FollowFeedAdapter.locate(l, null, enabled = true, cached, planned = false))
        }

        // Direct members & single assisted owner revalidated through full model & home ABI
        run {
            val l = loader()
            val direct = requireNotNull(FollowFeedAdapter.direct(l))
            assertEquals("iU.d", direct.painterOwnerClass)
            assertEquals("com.bilibili.bplus.followinglist.home.Q", direct.homeDecorationClass)
            assertNotNull(FollowFeedAdapter.resolve(l, direct))
            val assisted = FollowFeedAdapter.assisted(l, listOf(offsets(renamed.follow.DriftPainter::class.java)))
            assertEquals(cached, assisted)
        }

        // Discovered decorations resolve drifted subclasses by shape and stay fail-closed on ambiguity
        run {
            val l = object : ClassLoader(javaClass.classLoader!!) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name == "com.bilibili.bplus.followinglist.home.z0") throw ClassNotFoundException(name)
                    return super.loadClass(name, resolve)
                }
            }
            val discovered = FollowFeedAdapter.DiscoveredDecorations(
                home = setOf(renamed.follow.DriftDecorations.Home::class.java),
                personalPage = setOf(
                    renamed.follow.DriftDecorations.Personal::class.java,
                    renamed.follow.DriftDecorations.Separator::class.java
                )
            )
            val assisted = FollowFeedAdapter.assisted(l, listOf(offsets(renamed.follow.DriftPainter::class.java)), discovered)
            assertEquals(renamed.follow.DriftDecorations.Home::class.java.name, assisted?.homeDecorationClass)
            assertEquals(renamed.follow.DriftDecorations.Personal::class.java.name, assisted?.personalDecorationClass)
            assertNotNull(FollowFeedAdapter.resolve(l, assisted))
            val ambiguous = FollowFeedAdapter.assisted(
                l, listOf(offsets(renamed.follow.DriftPainter::class.java)),
                discovered.copy(personalPage = discovered.personalPage + renamed.follow.DriftDecorations.SecondPersonal::class.java)
            )
            assertNull(ambiguous?.personalDecorationClass)
        }

        // Multiple owners, wrong query ABI, and foreign home rejected
        run {
            val l = loader()
            assertNull(FollowFeedAdapter.assisted(l, listOf(offsets(iU.d::class.java), offsets(renamed.follow.DriftPainter::class.java))))
            val paint = iU.d::class.java.getDeclaredMethod("f", android.graphics.Canvas::class.java, RecyclerView::class.java, View::class.java, tS.G::class.java)
            assertNull(FollowFeedAdapter.assisted(l, listOf(paint)))
            assertNull(FollowFeedAdapter.resolve(l, cached.copy(homeDecorationClass = "java.lang.Object")))
            assertNull(FollowFeedAdapter.resolve(l, cached.copy(painterMethod = "missing")))
            assertNull(FollowFeedAdapter.resolve(l, cached.copy(modelFamily = "legacy")))
        }

        // Cached points resolve static opus access even when direct is hidden
        run {
            val l = FollowFeedAdapterTest::class.java.classLoader!!
            requireNotNull(FollowFeedAdapter.direct(l))
            val hiddenDirectLoader = object : ClassLoader(l) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name == "gv1.c") throw ClassNotFoundException(name)
                    return super.loadClass(name, resolve)
                }
            }
            assertNotNull(FollowFeedAdapter.resolve(hiddenDirectLoader, cached))
        }
    }

    private data class NewHost(
        val painter: String, val module: String, val item: String,
        val home: String, val personal: String, val separator: String, val opus: String
    )

    private val newerBuilds = mapOf(
        9180100 to NewHost("sU.d", "DS.I", "DS.J", "com.bilibili.bplus.followinglist.home.U",
            "com.bilibili.bplus.followinglist.quick.consume.E", "com.bilibili.bplus.followinglist.quick.consume.U",
            "com.bilibili.bplus.followinglist.opus.p"),
        9180200 to NewHost("sU.d", "DS.I", "DS.J", "com.bilibili.bplus.followinglist.home.Y",
            "com.bilibili.bplus.followinglist.quick.consume.G", "com.bilibili.bplus.followinglist.quick.consume.U",
            "com.bilibili.bplus.followinglist.opus.o"),
        9180300 to NewHost("sU.d", "DS.I", "DS.J", "com.bilibili.bplus.followinglist.home.b0",
            "com.bilibili.bplus.followinglist.quick.consume.H", "com.bilibili.bplus.followinglist.quick.consume.W",
            "com.bilibili.bplus.followinglist.opus.r"),
        9190200 to NewHost("ey80", "b890", "cb90", "w1o0", "sov1", "nuh2", "nnl1")
    )

    open class ModuleWithB(
        @JvmField val b: tS.H,
        viewType: Int = com.bilibili.bplus.followinglist.module.item.ModuleEnum.Author.viewType()
    ) {
        private val descriptor = tS.UiDescriptor(viewType)
        constructor(item: tS.H) : this(item, com.bilibili.bplus.followinglist.module.item.ModuleEnum.Author.viewType())
        init {
            @Suppress("UNCHECKED_CAST")
            (b.n as MutableList<Any>).add(this)
        }
        fun M(): tS.H = b.j ?: b
        open fun O(): Boolean = true
        fun Q(): tS.UiDescriptor = descriptor
    }

    open class ModuleWithUnknown(
        @JvmField val unknown: tS.H,
        viewType: Int = com.bilibili.bplus.followinglist.module.item.ModuleEnum.Author.viewType()
    ) {
        private val descriptor = tS.UiDescriptor(viewType)
        constructor(item: tS.H) : this(item, com.bilibili.bplus.followinglist.module.item.ModuleEnum.Author.viewType())
        init {
            @Suppress("UNCHECKED_CAST")
            (unknown.n as MutableList<Any>).add(this)
        }
        fun M(): tS.H = unknown.j ?: unknown
        open fun O(): Boolean = true
        fun Q(): tS.UiDescriptor = descriptor
    }

    private class Host916(
        localField: String = "b",
        hideOpus: Boolean = false,
        profile917: Boolean = false,
        ambientNewSeparator: Boolean = false,
        build300: Boolean = false,
        newer: NewHost? = null
    ) {
        private val modern = profile917 || build300 || newer != null
        private val painterName = newer?.painter ?: if (modern) "rU.d" else "mU.d"
        private val moduleName = newer?.module ?: if (modern) "CS.I" else "xS.G"
        private val itemName = newer?.item ?: if (modern) "CS.J" else "xS.H"
        private val homeLeaf = if (build300) "X" else if (profile917) "a0" else "U"
        private val personalLeaf = if (build300) "D" else if (profile917) "F" else "I"
        private val separatorLeaf = if (build300) "P" else if (profile917) "T" else "W"
        private val opusName = newer?.opus ?: ("com.bilibili.bplus.followinglist.opus." + if (build300) "p" else if (profile917) "t" else "k")
        private val moduleTemplate: Class<*> = when (localField) {
            "a" -> tS.G::class.java
            "b" -> ModuleWithB::class.java
            else -> ModuleWithUnknown::class.java
        }

        val classLoader: ClassLoader

        init {
            val definitions = linkedMapOf<String, Class<*>>(
                painterName to follow916.Painter::class.java,
                moduleName to moduleTemplate,
                itemName to tS.H::class.java,
                "xS.TestModule" to follow916.ConcreteModule::class.java,
                (newer?.home ?: "com.bilibili.bplus.followinglist.home.$homeLeaf") to com.bilibili.bplus.followinglist.home.Q::class.java,
                (newer?.personal ?: "com.bilibili.bplus.followinglist.quick.consume.$personalLeaf") to followtest.roles.PersonalDecoration::class.java,
                (newer?.separator ?: "com.bilibili.bplus.followinglist.quick.consume.$separatorLeaf") to followtest.roles.PersonalSeparator::class.java,
                opusName to follow916.Opus::class.java
            )
            val extra = linkedMapOf<String, Class<*>>()
            if (build300) {
                for (name in listOf(
                    "com.bilibili.bplus.followinglist.home.U", "com.bilibili.bplus.followinglist.home.T",
                    "com.bilibili.bplus.followinglist.quick.consume.F", "com.bilibili.bplus.followinglist.quick.consume.I",
                    "com.bilibili.bplus.followinglist.quick.consume.T", "com.bilibili.bplus.followinglist.opus.t",
                    "com.bilibili.bplus.followinglist.opus.k"
                )) extra[name] = followtest.roles.Unrelated::class.java
            } else if (newer?.opus == "com.bilibili.bplus.followinglist.opus.r") {
                extra["com.bilibili.bplus.followinglist.opus.t"] = follow916.Opus::class.java
            }
            if (ambientNewSeparator && !modern) {
                extra["com.bilibili.bplus.followinglist.quick.consume.T"] = followtest.roles.Unrelated::class.java
            }

            val replacements = linkedMapOf<String, String>()
            definitions.forEach { (name, template) ->
                replacements[template.name.replace('.', '/')] = name.replace('.', '/')
            }
            replacements["tS/G"] = moduleName.replace('.', '/')
            replacements["tS/H"] = itemName.replace('.', '/')
            replacements["iU/d"] = painterName.replace('.', '/')

            val hidden = linkedSetOf("gv1.c", "iU.d")
            if (modern) hidden += "mU.d"
            if (hideOpus) hidden += opusName

            classLoader = TestRenamingClassLoader(
                parent = FollowFeedAdapterTest::class.java.classLoader!!,
                templates = definitions + extra,
                replacements = replacements,
                hiddenNames = hidden
            )
        }

        fun item(): Any = classLoader.loadClass(itemName).getConstructor().newInstance()
        fun module(item: Any): Any = classLoader.loadClass("xS.TestModule").getConstructor(classLoader.loadClass(itemName)).newInstance(item)
    }

    @Test
    fun `host generation 916 to 919 ownership opus color and stale cache isolation`() {
        run {
            val host = Host916()
            val l = host.classLoader
            val points = requireNotNull(FollowFeedAdapter.direct(l))
            assertEquals("mU.d", points.painterOwnerClass)
            assertEquals("com.bilibili.bplus.followinglist.home.U", points.homeDecorationClass)
            assertEquals("com.bilibili.bplus.followinglist.quick.consume.I", points.personalDecorationClass)
            assertEquals("com.bilibili.bplus.followinglist.quick.consume.W", points.personalSeparatorClass)
            val resolved = requireNotNull(FollowFeedAdapter.resolve(l, points))
            assertEquals(points, resolved.points)
            assertTrue(resolved.host.embeddedVideoSupported)
        }

        for ((build, expected) in newerBuilds) {
            val l = Host916(localField = "a", newer = expected).classLoader
            val points = requireNotNull(FollowFeedAdapter.direct(l)) { "build $build" }
            assertEquals(expected.painter, points.painterOwnerClass)
            assertEquals(expected.module, points.moduleClass)
            assertEquals(expected.item, points.itemClass)
            assertEquals(expected.home, points.homeDecorationClass)
            assertEquals(expected.personal, points.personalDecorationClass)
            assertEquals(expected.separator, points.personalSeparatorClass)
            assertEquals(listOf(expected.opus), FollowFeedAdapter.opusDecorationCandidates(points))
            val resolved = requireNotNull(FollowFeedAdapter.resolve(l, points))
            assertTrue(resolved.host.embeddedVideoSupported)
            assertNotNull(HostOpusBackgroundAccess.resolve(l))
        }
        run {
            val rebuilt = newerBuilds.getValue(9180300)
            val missing = Host916(localField = "a", hideOpus = true, newer = rebuilt).classLoader
            assertTrue(missing.loadClass(rebuilt.painter).isAssignableFrom(missing.loadClass("com.bilibili.bplus.followinglist.opus.t")))
            assertNotNull(HostOpusBackgroundAccess.resolve(missing))
        }

        run {
            val host = Host916()
            val l = host.classLoader
            val resolved = requireNotNull(FollowFeedAdapter.resolve(l, FollowFeedAdapter.direct(l)))
            val item = host.item()
            val module = host.module(item)
            assertEquals(false, resolved.host.isQuotedModule(module))
            item.javaClass.getField("j").set(item, host.item())
            assertEquals(true, resolved.host.isQuotedModule(module))
            assertNull(resolved.host.isQuotedModule(Any()))

            val item2 = host.item()
            val module2 = host.module(item2)
            val access = requireNotNull(HostOpusBackgroundAccess.resolve(l))
            assertTrue(Modifier.isStatic(access.color.modifiers))
            assertTrue(access.accepts(null, module2))
            item2.javaClass.getField("j").set(item2, host.item())
            assertFalse(access.accepts(null, module2))
            assertFalse(access.accepts(null, Any()))
            assertFalse(access.accepts(null, null))
        }

        assertNull(FollowFeedAdapter.direct(Host916(localField = "unknown").classLoader))
        assertNull(HostOpusBackgroundAccess.resolve(Host916(localField = "unknown").classLoader))
        assertNotNull(HostOpusBackgroundAccess.resolve(Host916(hideOpus = true).classLoader))

        run {
            val host = Host916(profile917 = true)
            val l = host.classLoader
            val points = requireNotNull(FollowFeedAdapter.direct(l))
            assertEquals("rU.d", points.painterOwnerClass)
            assertEquals("CS.I", points.moduleClass)
            assertEquals("CS.J", points.itemClass)
            assertEquals("com.bilibili.bplus.followinglist.home.a0", points.homeDecorationClass)
            assertEquals("com.bilibili.bplus.followinglist.quick.consume.F", points.personalDecorationClass)
            assertEquals("com.bilibili.bplus.followinglist.quick.consume.T", points.personalSeparatorClass)
            val fHost = requireNotNull(FollowFeedAdapter.resolve(l, points)).host
            val item = host.item()
            val module = host.module(item)
            assertEquals(false, fHost.isQuotedModule(module))
            item.javaClass.getField("j").set(item, host.item())
            assertEquals(true, fHost.isQuotedModule(module))
            assertFalse(requireNotNull(HostOpusBackgroundAccess.resolve(l)).accepts(null, module))

            val old = requireNotNull(FollowFeedAdapter.direct(Host916().classLoader))
            val refreshed = requireNotNull(FollowFeedAdapter.locate(l, null, true, old, planned = false))
            assertEquals("rU.d", refreshed.painterOwnerClass)
            assertEquals("com.bilibili.bplus.followinglist.home.a0", refreshed.homeDecorationClass)
            assertNull(FollowFeedAdapter.direct(Host916(localField = "unknown", profile917 = true).classLoader))
        }

        run {
            val points = requireNotNull(FollowFeedAdapter.direct(Host916(ambientNewSeparator = true).classLoader))
            assertEquals("mU.d", points.painterOwnerClass)
            assertEquals("com.bilibili.bplus.followinglist.quick.consume.W", points.personalSeparatorClass)
        }

        run {
            val host = Host916(localField = "a", build300 = true)
            val l = host.classLoader
            val points = requireNotNull(FollowFeedAdapter.direct(l))
            assertEquals("rU.d", points.painterOwnerClass)
            assertEquals("CS.I", points.moduleClass)
            assertEquals("CS.J", points.itemClass)
            assertEquals("com.bilibili.bplus.followinglist.home.X", points.homeDecorationClass)
            assertEquals("com.bilibili.bplus.followinglist.quick.consume.D", points.personalDecorationClass)
            assertEquals("com.bilibili.bplus.followinglist.quick.consume.P", points.personalSeparatorClass)
            val resolved = requireNotNull(FollowFeedAdapter.resolve(l, points))
            assertEquals(points, resolved.points)
            assertTrue(resolved.host.embeddedVideoSupported)
            val item = host.item()
            val module = host.module(item)
            assertEquals(false, resolved.host.isQuotedModule(module))
            item.javaClass.getField("j").set(item, host.item())
            assertEquals(true, resolved.host.isQuotedModule(module))

            val access = requireNotNull(HostOpusBackgroundAccess.resolve(l))
            assertTrue(Modifier.isStatic(access.color.modifiers))
            val item2 = host.item()
            val module2 = host.module(item2)
            assertTrue(access.accepts(null, module2))
            item2.javaClass.getField("j").set(item2, host.item())
            assertFalse(access.accepts(null, module2))
            assertEquals("com.bilibili.bplus.followinglist.opus.p", FollowFeedAdapter.opusDecorationCandidates(points).first())
            assertNotNull(HostOpusBackgroundAccess.resolve(Host916(localField = "a", build300 = true, hideOpus = true).classLoader))

            val old = requireNotNull(FollowFeedAdapter.direct(Host916(profile917 = true).classLoader))
            assertEquals("com.bilibili.bplus.followinglist.home.a0", old.homeDecorationClass)
            val refreshed = requireNotNull(FollowFeedAdapter.locate(l, null, true, old, planned = false))
            assertEquals("com.bilibili.bplus.followinglist.home.X", refreshed.homeDecorationClass)
            assertEquals("com.bilibili.bplus.followinglist.quick.consume.D", refreshed.personalDecorationClass)
            assertEquals("com.bilibili.bplus.followinglist.quick.consume.P", refreshed.personalSeparatorClass)
            assertNull(FollowFeedAdapter.resolve(l, old))
            assertNull(FollowFeedAdapter.resolve(Host916(profile917 = true).classLoader, refreshed))
            assertNull(FollowFeedAdapter.direct(Host916(localField = "unknown", build300 = true).classLoader))
            assertNull(HostOpusBackgroundAccess.resolve(Host916(localField = "unknown", build300 = true).classLoader))
        }
    }
}
