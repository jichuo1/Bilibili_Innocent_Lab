package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.*
import org.junit.Assert.*
import org.junit.Test

class BrandSplashDecoderAdapterTest {
    private val loader = javaClass.classLoader!!
    private val first = BrandDecoderFixtures.First::class.java.getDeclaredMethod("a", tv.danmaku.bili.ui.splash.brand.model.BrandShowInfo::class.java)
    private val second = BrandDecoderFixtures.Second::class.java.declaredMethods.single()
    private fun mapped(vararg owners: Pair<String, Class<*>>) = object : ClassLoader(loader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> =
            owners.firstOrNull { it.first == name }?.second ?: super.loadClass(name, resolve)
    }

    @Test fun `both verified 915 owners resolve without loading native fallback`() {
        for (owner in listOf("Or1.a", "Nr1.a")) {
            val host = mapped(owner to first.declaringClass)
            assertEquals(first, BrandSplashDecoderAdapter.direct(host))
            assertFalse(BrandSplashDecoderAdapter.needsQuery(host, null, true, false))
            assertEquals(BrandSplashDecoderAdapter.point(first), BrandSplashDecoderAdapter.locate(host,
                DexAssistSession(DexAssistEngine { error("direct opened DEX") }, listOf("base.apk"), host, emptySet()), true, null, false))
        }
        assertNull(BrandSplashDecoderAdapter.direct(mapped("Or1.a" to first.declaringClass, "Nr1.a" to second.declaringClass)))
    }

    @Test fun `cached verified decoder and completed failure never reopen an unplanned query`() {
        val point = BrandSplashDecoderAdapter.point(first)
        val session = DexAssistSession(DexAssistEngine { error("cache opened DEX") }, listOf("base.apk"), loader, emptySet())
        assertFalse(BrandSplashDecoderAdapter.needsQuery(loader, point, true, false))
        assertEquals(point, BrandSplashDecoderAdapter.locate(loader, session, true, point, false))
        assertFalse(BrandSplashDecoderAdapter.needsQuery(loader, null, true, true))
        assertNull(BrandSplashDecoderAdapter.locate(loader, session, true, null, false))
        var loads = 0
        val trap = object : ClassLoader(loader) { override fun loadClass(name: String, resolve: Boolean): Class<*> {
            loads++; error("disabled loaded host class")
        } }
        assertEquals(point, BrandSplashDecoderAdapter.locate(trap, session, false, point, false))
        assertEquals(0, loads)
    }

    @Test fun `918 owners and 919 method rename resolve the verified decoder only`() {
        for (name in listOf("Vx1.a", "Ux1.a")) {
            assertEquals(first, BrandSplashDecoderAdapter.direct(mapped(name to first.declaringClass)))
        }
        val latest = BrandDecoderFixtures.Latest::class.java
        val b = latest.getDeclaredMethod("b", tv.danmaku.bili.ui.splash.brand.model.BrandShowInfo::class.java)
        val host = mapped("cgk" to latest)
        assertEquals(b, BrandSplashDecoderAdapter.direct(host))
        assertFalse(BrandSplashDecoderAdapter.needsQuery(host, null, true, false))
        assertNull(BrandSplashDecoderAdapter.direct(mapped("cgk" to latest, "Vx1.a" to first.declaringClass)))
    }

    @Test fun `semantic candidates need unique owner and exact runtime ABI`() {
        fun select(methods: List<java.lang.reflect.Method>) = BrandSplashDecoderAdapter.locate(loader,
            DexAssistSession(DexAssistEngine { DexAssistResult.Candidates(methods) }, listOf("base.apk"), loader,
                setOf(BrandSplashDecoderAdapter.query)), true, null, true)
        assertEquals(BrandSplashDecoderAdapter.point(first), select(listOf(first, first)))
        assertNull(select(listOf(first, second)))
        assertNull(select(List(33) { first }))
        assertNull(select(BrandDecoderFixtures.Instance::class.java.declaredMethods.toList()))
        assertNull(select(BrandDecoderFixtures.First::class.java.declaredMethods.filter { it.name == "unrelated" }))
        assertNull(BrandSplashDecoderAdapter.resolve(loader, BrandSplashDecoderAdapter.point(first).copy(paramClassNames = emptyList())))
    }
}
