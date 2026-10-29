package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/** 性能报告 P2-01：只要第一项的调用方在已缓存的成员集合上短路，结果与完整 filter 后取第一项一致。 */
class KavaMemberLookupFirstMatchTest {
    @Before fun reset() { KavaMemberLookup.resetForTests() }

    private open class Base {
        @Suppress("unused") protected var inheritedValue = 1
        @Suppress("unused") fun baseCall(value: Int) = value
    }

    private class Sample : Base() {
        @Suppress("unused") var alpha = 1
        @Suppress("unused") var beta = ""
        @Suppress("unused") var gamma = 2L
        @Suppress("unused") fun read() = alpha
        @Suppress("unused") fun read(value: Int) = value
        @Suppress("unused") fun read(value: String) = value
        @Suppress("unused") fun write(value: Int) = value
        @Suppress("unused") private fun hidden() = 1
    }

    @Test fun firstMethodIsTheFirstElementOfTheFullFilteredList() {
        val owners = listOf(Sample::class.java, Base::class.java, java.util.ArrayList::class.java, String::class.java, Object::class.java)
        val predicates = listOf<(Method) -> Boolean>(
            { true }, { it.name == "read" }, { it.parameterCount == 1 }, { it.name.startsWith("w") },
            { it.returnType == Integer.TYPE }, { it.name == "missing" }, { it.parameterCount == 2 }
        )
        for (owner in owners) for (inherit in listOf(false, true)) for (makeAccessible in listOf(false, true)) for (predicate in predicates) {
            val full = KavaMemberLookup.methods(owner, inherit, makeAccessible, predicate).firstOrNull()
            val first = KavaMemberLookup.firstMethodOrNull(owner, inherit, makeAccessible, predicate)
            assertEquals("${owner.simpleName} inherit=$inherit access=$makeAccessible", full, first)
        }
    }

    @Test fun firstFieldIsTheFirstElementOfTheFullFilteredList() {
        val owners = listOf(Sample::class.java, Base::class.java, java.util.ArrayList::class.java)
        val predicates = listOf<(java.lang.reflect.Field) -> Boolean>(
            { true }, { it.type == String::class.java }, { it.name == "gamma" }, { it.name == "missing" }, { it.type == Integer.TYPE }
        )
        for (owner in owners) for (inherit in listOf(false, true)) for (makeAccessible in listOf(false, true)) for (predicate in predicates) {
            val full = KavaMemberLookup.fields(owner, inherit, makeAccessible, predicate).firstOrNull()
            val first = KavaMemberLookup.firstFieldOrNull(owner, inherit, makeAccessible, predicate)
            assertEquals("${owner.simpleName} inherit=$inherit access=$makeAccessible", full, first)
        }
        assertSame(KavaMemberLookup.declaredFields(Sample::class.java).first(), KavaMemberLookup.firstDeclaredFieldOrNull(Sample::class.java))
    }

    @Test fun predicateStopsAtTheFirstMatchInsteadOfVisitingEveryMember() {
        val total = KavaMemberLookup.declaredMethods(Sample::class.java).size
        assertTrue("sample must have several methods: $total", total >= 6)
        var fullCalls = 0
        KavaMemberLookup.declaredMethods(Sample::class.java, predicate = { fullCalls++; true }).firstOrNull()
        var firstCalls = 0
        val first = KavaMemberLookup.firstDeclaredMethodOrNull(Sample::class.java) { firstCalls++; true }
        assertEquals("the legacy path evaluates every member", total, fullCalls)
        assertEquals("the first-match path stops at the first member", 1, firstCalls)
        assertEquals(KavaMemberLookup.declaredMethods(Sample::class.java).first(), first)
        // 没有命中时必须看完整个集合，结论一致。
        var missCalls = 0
        assertNull(KavaMemberLookup.firstDeclaredMethodOrNull(Sample::class.java) { missCalls++; false })
        assertEquals(total, missCalls)
    }

    @Test fun inaccessibleLeadingCandidatesAreSkippedLikeBefore() {
        // makeAccessible 失败的候选被跳过，继续找下一项，而不是把失败当成「没有」。
        val accessible = KavaMemberLookup.firstDeclaredMethodOrNull(Sample::class.java, makeAccessible = true) { it.name == "hidden" }
        val legacy = KavaMemberLookup.declaredMethods(Sample::class.java, makeAccessible = true) { it.name == "hidden" }.firstOrNull()
        assertEquals(legacy, accessible)
        assertEquals(legacy != null, accessible != null)
    }

    @Test fun collectionsAreCachedSoRepeatedFirstMatchLookupsDoNotRescan() {
        val before = KavaMemberLookup.diagnostics()
        repeat(50) { KavaMemberLookup.firstDeclaredMethodOrNull(Sample::class.java) { it.name == "write" } }
        val after = KavaMemberLookup.diagnostics()
        assertEquals("one collection resolve, then cache hits", before.cacheMisses + 1, after.cacheMisses)
        assertTrue(after.cacheHits >= before.cacheHits + 49)
    }
}
