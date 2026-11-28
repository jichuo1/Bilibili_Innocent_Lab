package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Member
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FreeCopySelectionMapperTest {

    fun selectedPlaceholderCopiesCustomEmojiToken() {
        val text = "你好\uFFFC世界"
        val replacements = listOf(FreeCopySelectionMapper.Replacement(2, 3, "[dog]"))

        assertEquals("[dog]", FreeCopySelectionMapper.mapSelection(text, 2, 3, replacements))
        assertEquals("好[dog]世", FreeCopySelectionMapper.mapSelection(text, 1, 4, replacements))
    }

    fun actualBilibiliZeroWidthSpanCopiesCustomEmojiToken() {
        val text = "你好\u200B世界"
        val replacements = listOf(FreeCopySelectionMapper.Replacement(2, 3, "[dog]"))

        assertEquals("[dog]", FreeCopySelectionMapper.mapSelection(text, 2, 3, replacements))
        assertEquals("好[dog]世", FreeCopySelectionMapper.mapSelection(text, 1, 4, replacements))
    }

    fun partialSelectionInsideReplacementExpandsToWholeToken() {
        val text = "甲[dog]乙"
        val replacements = listOf(FreeCopySelectionMapper.Replacement(1, 6, "[dog]"))

        assertEquals("[dog]", FreeCopySelectionMapper.mapSelection(text, 3, 4, replacements))
    }

    fun repeatedEmojiKeepTheirOwnOrderAndMeaning() {
        val text = "\uFFFC和\uFFFC"
        val replacements = listOf(
            FreeCopySelectionMapper.Replacement(0, 1, "[dog]"),
            FreeCopySelectionMapper.Replacement(2, 3, "[笑哭]")
        )

        assertEquals("[dog]和[笑哭]", FreeCopySelectionMapper.mapSelection(text, 0, 3, replacements))
    }

    fun ordinaryTextAndUnicodeEmojiRemainUnchanged() {
        val text = "原生😂文本"

        assertEquals(text, FreeCopySelectionMapper.mapSelection(text, 0, text.length, emptyList()))
    }

    fun emptySelectionDoesNotWriteClipboard() {
        assertNull(FreeCopySelectionMapper.mapSelection("文本", 1, 1, emptyList()))
    }

    fun pureCustomEmojiAlignsDespiteTrailingBilibiliSentinel() {
        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = "[dog]",
            displayText = "\u200B\u200B",
            displayReplacementRanges = listOf(0..0),
            expectedTokens = listOf("[dog]")
        )

        assertEquals(
            listOf(FreeCopySelectionMapper.AlignedReplacement(0, 1, 0, 5, "[dog]")),
            aligned
        )
    }

    fun nativeEmojiDoesNotStealCustomEmojiMapping() {
        val raw = "原生😂和[dog]"
        val display = "原生😂和\u200B\u200B"
        val spanStart = display.indexOf('\u200B')

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(spanStart..spanStart),
            expectedTokens = listOf("[dog]")
        )

        assertEquals("[dog]", aligned?.single()?.copyText)
        assertEquals(raw.indexOf("[dog]"), aligned?.single()?.rawStart)
    }

    fun literalBracketTextDoesNotConsumeReplacementToken() {
        val raw = "[说明]和[dog]"
        val display = "[说明]和\u200B\u200B"
        val spanStart = display.indexOf('\u200B')

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(spanStart..spanStart),
            expectedTokens = listOf("[dog]")
        )

        assertEquals("[dog]", aligned?.single()?.copyText)
        assertEquals(raw.indexOf("[dog]"), aligned?.single()?.rawStart)
    }

    fun foldedVisiblePrefixStillMapsToFullRawToken() {
        val raw = "前[dog]后续还有很长的正文"
        val display = "前\u200B后续…\u200B"

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(1..1),
            expectedTokens = listOf("[dog]")
        )

        assertEquals("[dog]", aligned?.single()?.copyText)
        assertEquals(raw.indexOf("[dog]"), aligned?.single()?.rawStart)
    }

    fun repeatedCustomEmojiKeepOneToOneRawRanges() {
        val raw = "[dog]和[dog]"
        val display = "\u200B和\u200B\u200B"

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(0..0, 2..2),
            expectedTokens = listOf("[dog]", "[dog]")
        )

        assertEquals(2, aligned?.size)
        assertEquals(0, aligned?.get(0)?.rawStart)
        assertEquals(raw.lastIndexOf("[dog]"), aligned?.get(1)?.rawStart)
    }

    fun unknownReplacementDoesNotDiscardEarlierConfirmedEmoji() {
        val raw = "[dog]和[未知]"
        val display = "\u200B和\u200B\u200B"

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(0..0, 2..2),
            expectedTokens = listOf("[dog]")
        )

        assertEquals(1, aligned?.size)
        assertEquals("[dog]", aligned?.single()?.copyText)
    }

    fun literalSameTokenDoesNotConsumeModelEmoji() {
        val raw = "[dog]文字[dog]"
        val display = "[dog]文字\u200B\u200B"
        val spanStart = display.indexOf('\u200B')

        val aligned = FreeCopySelectionMapper.alignCustomEmojiTokens(
            rawText = raw,
            displayText = display,
            displayReplacementRanges = listOf(spanStart..spanStart),
            expectedTokens = listOf("[dog]")
        )

        assertEquals(raw.lastIndexOf("[dog]"), aligned?.single()?.rawStart)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FreeCopySelectionMapperTest) -> Unit) {
            val s = FreeCopySelectionMapperTest()
            block(s)
        }
        runIsolated { s -> s.selectedPlaceholderCopiesCustomEmojiToken() }
        runIsolated { s -> s.actualBilibiliZeroWidthSpanCopiesCustomEmojiToken() }
        runIsolated { s -> s.partialSelectionInsideReplacementExpandsToWholeToken() }
        runIsolated { s -> s.repeatedEmojiKeepTheirOwnOrderAndMeaning() }
        runIsolated { s -> s.ordinaryTextAndUnicodeEmojiRemainUnchanged() }
        runIsolated { s -> s.emptySelectionDoesNotWriteClipboard() }
        runIsolated { s -> s.pureCustomEmojiAlignsDespiteTrailingBilibiliSentinel() }
        runIsolated { s -> s.nativeEmojiDoesNotStealCustomEmojiMapping() }
        runIsolated { s -> s.literalBracketTextDoesNotConsumeReplacementToken() }
        runIsolated { s -> s.foldedVisiblePrefixStillMapsToFullRawToken() }
        runIsolated { s -> s.repeatedCustomEmojiKeepOneToOneRawRanges() }
        runIsolated { s -> s.unknownReplacementDoesNotDiscardEarlierConfirmedEmoji() }
        runIsolated { s -> s.literalSameTokenDoesNotConsumeModelEmoji() }
    
        HookPointRegistryCases().verifyAllCases()
    }
}

private class HookPointRegistryCases {

    private class FixtureException(message: String) : RuntimeException(message)

    private class Fixture {
        @Suppress("unused")
        private val stableField: String = "value"

        @Suppress("unused")
        private fun exact(value: Int): String = value.toString()

        @Suppress("unused")
        private fun overloaded(value: Int) = value

        @Suppress("unused")
        private fun overloaded(value: String) = value
    }

    private lateinit var registry: HookPointRegistry

    fun setUp() {
        KavaMemberLookup.resetForTests()
        registry = HookPointRegistry(requireNotNull(Fixture::class.java.classLoader))
    }

    fun tearDown() {
        KavaMemberLookup.resetForTests()
    }

    fun `resolves adapted primitive signature and prevents duplicate registration`() {
        val method = registry.resolveAdapted(
            id = "fixture.exact",
            className = Fixture::class.java.name,
            methodName = "exact",
            parameterClassNames = listOf("int")
        )

        assertNotNull(method)
        assertTrue(registry.claim("fixture.exact", requireNotNull(method)))
        registry.markInstalled("fixture.exact", method)
        assertFalse(registry.claim("fixture.exact", method))
        assertEquals(HookPointRegistry.State.DUPLICATE, registry.snapshot().single().state)
    }

    fun `prevents duplicate constructor registration`() {
        val constructor = Fixture::class.java.getDeclaredConstructor()

        assertTrue(registry.claim("fixture.constructor", constructor))
        registry.markInstalled("fixture.constructor", constructor)
        assertFalse(registry.claim("fixture.constructor", constructor))

        val diagnostic = registry.snapshot().single()
        assertEquals(HookPointRegistry.State.DUPLICATE, diagnostic.state)
        assertTrue(diagnostic.member.orEmpty().contains("#<init>()"))
    }

    fun `failed method and constructor registrations can retry without duplicating successful installs`() {
        val members = listOf<Member>(
            Fixture::class.java.getDeclaredMethod("exact", Int::class.javaPrimitiveType),
            Fixture::class.java.getDeclaredConstructor()
        )
        members.forEachIndexed { index, member ->
            val id = "fixture.retry.$index"
            val failure = FixtureException("native registration failed")
            var nativeAttempts = 0
            fun install(fails: Boolean): Boolean {
                if (!registry.claim(id, member)) return false
                try {
                    nativeAttempts++
                    if (fails) throw failure
                    registry.markInstalled(id, member)
                    return true
                } catch (throwable: Throwable) {
                    registry.markFailed(id, member, throwable)
                    throw throwable
                }
            }

            try {
                install(fails = true)
                fail("Expected the native registration failure")
            } catch (actual: FixtureException) {
                assertSame(failure, actual)
            }
            assertEquals(HookPointRegistry.State.FAILED, registry.snapshot().single { it.id == id }.state)
            assertTrue(install(fails = false))
            assertFalse(install(fails = false))
            assertEquals(2, nativeAttempts)
        }
    }

    fun `concurrent in progress registration throws instead of reporting a successful duplicate`() {
        val member = Fixture::class.java.getDeclaredMethod("exact", Int::class.javaPrimitiveType)
        val id = "fixture.inflight"
        val claimed = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                assertTrue(registry.claim(id, member))
                claimed.countDown()
                assertTrue(finish.await(5, TimeUnit.SECONDS))
                registry.markInstalled(id, member)
            } catch (throwable: Throwable) {
                workerFailure.set(throwable)
            }
        }.also(Thread::start)

        try {
            assertTrue(claimed.await(5, TimeUnit.SECONDS))
            try {
                registry.claim(id, member)
                fail("An in progress registration must not return a duplicate success")
            } catch (_: IllegalStateException) {
                // 第一个原生安装尚未完成，调用方必须保留 partial 状态。
            }
        } finally {
            finish.countDown()
            worker.join(5_000)
        }
        assertFalse(worker.isAlive)
        assertNull(workerFailure.get())
        assertFalse(registry.claim(id, member))
    }

    fun `unclaimed failure cannot release an already installed registration`() {
        val member = Fixture::class.java.getDeclaredMethod("exact", Int::class.javaPrimitiveType)
        val id = "fixture.installed"
        assertTrue(registry.claim(id, member))
        registry.markInstalled(id, member)

        registry.markFailed(id, member, FixtureException("legacy unclaimed failure"))

        assertFalse(registry.claim(id, member))
    }

    fun `legacy failure without a claim does not reserve a registration`() {
        val member = Fixture::class.java.getDeclaredMethod("exact", Int::class.javaPrimitiveType)
        registry.markFailed("fixture.legacy", member, FixtureException("unclaimed failure"))
        registry.markFailed("fixture.unknown", null, FixtureException("unresolved failure"))

        assertTrue(registry.claim("fixture.legacy", member))
        assertTrue(registry.claim("fixture.unknown", member))
    }

    fun `failed registration releases only its exact id and member`() {
        val method = Fixture::class.java.getDeclaredMethod("exact", Int::class.javaPrimitiveType)
        val constructor = Fixture::class.java.getDeclaredConstructor()
        assertTrue(registry.claim("fixture.shared", method))
        assertTrue(registry.claim("fixture.other", method))
        assertTrue(registry.claim("fixture.shared", constructor))
        registry.markInstalled("fixture.other", method)
        registry.markInstalled("fixture.shared", constructor)

        registry.markFailed("fixture.shared", method, FixtureException("scoped failure"))

        assertTrue(registry.claim("fixture.shared", method))
        assertFalse(registry.claim("fixture.other", method))
        assertFalse(registry.claim("fixture.shared", constructor))
    }

    fun `reports ambiguous and missing hook points without throwing`() {
        assertNull(
            registry.resolveAdapted(
                id = "fixture.ambiguous",
                className = Fixture::class.java.name,
                methodName = "overloaded",
                parameterClassNames = null
            )
        )
        assertNull(
            registry.resolveFirst(
                id = "fixture.missing",
                className = "missing.Fixture",
                methodName = "none"
            )
        )

        val states = registry.snapshot().associate { it.id to it.state }
        assertEquals(HookPointRegistry.State.AMBIGUOUS_METHOD, states["fixture.ambiguous"])
        assertEquals(HookPointRegistry.State.MISSING_CLASS, states["fixture.missing"])
    }

    fun `resolves fields through unified diagnostics`() {
        val field = registry.resolveField(
            "fixture.field",
            Fixture::class.java.name,
            "stableField"
        )
        val missing = registry.resolveField(
            "fixture.field.missing",
            Fixture::class.java,
            "absent"
        )

        assertNotNull(field)
        assertEquals("value", requireNotNull(field).get(Fixture()))
        assertNull(missing)
        val diagnostics = registry.snapshot().associateBy { it.id }
        assertEquals(HookPointRegistry.State.RESOLVED, diagnostics["fixture.field"]?.state)
        assertTrue(diagnostics["fixture.field"]?.member.orEmpty().endsWith("#stableField"))
        assertEquals(
            HookPointRegistry.State.MISSING_FIELD,
            diagnostics["fixture.field.missing"]?.state
        )
    }

    fun `resolves constructor and reports missing signature`() {
        val constructor = registry.resolveConstructor(
            "fixture.exception",
            FixtureException::class.java.name,
            listOf(String::class.java.name)
        )
        val missing = registry.resolveConstructor(
            "fixture.exception.missing",
            FixtureException::class.java.name,
            listOf("int")
        )

        assertNotNull(constructor)
        assertTrue(requireNotNull(constructor).newInstance("blocked") is FixtureException)
        assertNull(missing)
        val diagnostics = registry.snapshot().associateBy { it.id }
        assertEquals(HookPointRegistry.State.RESOLVED, diagnostics["fixture.exception"]?.state)
        assertEquals(
            HookPointRegistry.State.MISSING_CONSTRUCTOR,
            diagnostics["fixture.exception.missing"]?.state
        )
    }

    /** 2026-09-30：自由复制缓存签名含 int，旧映射只认 long/boolean，整段注册被跳过。 */
    fun `cached parameter names resolve every primitive and fail as a whole`() {
        val primitives = listOf("boolean", "byte", "char", "short", "int", "long", "float", "double")
        val resolved = requireNotNull(registry.resolveParameterClasses(primitives))
        assertEquals(primitives, resolved.map { it.name })
        assertTrue(resolved.all { it.isPrimitive })
        assertEquals(String::class.java, registry.resolveParameterClasses(listOf("java.lang.String"))!!.single())
        assertEquals(0, registry.resolveParameterClasses(emptyList())!!.size)
        // 任一个找不到就整体为 null，不拿半截签名去找方法。
        assertNull(registry.resolveParameterClasses(listOf("int", "does.not.Exist")))
        // 用缓存名解析出的类能精确命中 int 参数的方法。
        val params = requireNotNull(registry.resolveParameterClasses(listOf("int")))
        assertNotNull(registry.resolveExact("fixture:exact-int", Fixture::class.java, "exact", *params))
    }

    fun `resolves jvm array descriptors`() {
        val classes = registry.resolveParameterClasses(listOf("[Ljava.lang.String;", "[I", "[[B", "int"))
        assertNotNull(classes)
        assertArrayEquals(
            arrayOf(Array<String>::class.java, IntArray::class.java, Array<ByteArray>::class.java, Int::class.javaPrimitiveType),
            classes!!
        )
    }

    fun verifyAllCases() {
        fun runIsolated(block: (HookPointRegistryCases) -> Unit) {
            val s = HookPointRegistryCases()
            s.setUp()
            try {
                block(s)
            } finally {
                s.tearDown()
            }
        }
        runIsolated { s -> s.`resolves adapted primitive signature and prevents duplicate registration`() }
        runIsolated { s -> s.`prevents duplicate constructor registration`() }
        runIsolated { s -> s.`failed method and constructor registrations can retry without duplicating successful installs`() }
        runIsolated { s -> s.`concurrent in progress registration throws instead of reporting a successful duplicate`() }
        runIsolated { s -> s.`unclaimed failure cannot release an already installed registration`() }
        runIsolated { s -> s.`legacy failure without a claim does not reserve a registration`() }
        runIsolated { s -> s.`failed registration releases only its exact id and member`() }
        runIsolated { s -> s.`reports ambiguous and missing hook points without throwing`() }
        runIsolated { s -> s.`resolves fields through unified diagnostics`() }
        runIsolated { s -> s.`resolves constructor and reports missing signature`() }
        runIsolated { s -> s.`cached parameter names resolve every primitive and fail as a whole`() }
        runIsolated { s -> s.`resolves jvm array descriptors`() }
    }
}
