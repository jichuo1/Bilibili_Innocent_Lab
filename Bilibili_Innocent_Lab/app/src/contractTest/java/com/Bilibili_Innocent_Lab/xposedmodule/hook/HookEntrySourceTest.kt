package com.Bilibili_Innocent_Lab.xposedmodule.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue

/**
 * [HookEntrySource] 自身的自检门禁，防止切片退化或未纳管分卷逃逸。
 */
class HookEntrySourceTest {

    fun `braces inside strings templates and comments do not end the slice`() {
        val source = """
            class Fixture {
                private fun target(count: Int = 1) {
                    val text = "not a } close brace {"
                    val templated = "nested ${'$'}{if (count > 0) "a } b" else "c { d"}"
                    // } neither is this {
                    /* } /* nested */ nor this { */
                    val raw = ${'"'}${'"'}${'"'} } still not { ${'"'}${'"'}${'"'}
                    val marker = count
                }
                private fun after() {
                    val other = 0
                }
            }
        """.trimIndent()
        val body = HookEntrySource.functions(source, "target").single()
        assertTrue(body.contains("val marker = count"))
        assertTrue(body.contains("not a } close brace {"))
        assertFalse(body.contains("val other = 0"))
        assertEquals(body.count { it == '{' }, body.count { it == '}' })
    }

    fun `property slicer captures multiline val lambdas and object expressions`() {
        val source = """
            private val targetRunnable: Runnable =
                HostThreadGuard.runnable("k") {
                    val inner = 1
                }
            private val afterProp = 2
        """.trimIndent()
        val prop = HookEntrySource.properties(source, "targetRunnable").single()
        assertTrue(prop.contains("val inner = 1"))
        assertFalse(prop.contains("afterProp"))
    }

    fun `missing or ambiguous functions fail loudly instead of returning empty or full file`() {
        val missing = assertThrows(IllegalStateException::class.java) {
            HookEntrySource.function("thisHookFunctionDoesNotExistAnywhere")
        }
        assertTrue(missing.message.orEmpty().contains("not found"))
        val dup = "fun dup() {\n}\nfun dup() {\n}\n"
        assertEquals(2, HookEntrySource.functions(dup, "dup").size)
        val ambiguous = assertThrows(IllegalStateException::class.java) {
            HookEntrySource.function("dup", files = listOf("Dup.kt" to dup))
        }
        assertTrue(ambiguous.message.orEmpty().contains("matched 2 times"))
    }

    fun `real HookEntry and FeatureInstaller slices stay bounded and complete`() {
        val files = HookEntrySource.hookEntryFiles().map { it.first }
        assertTrue("hook/HookEntry.kt" in files)
        assertTrue("hook/feature/FeatureInstaller.kt" in files)
        assertTrue("hook/feature/CommentTopologyFeatureInstaller.kt" in files)

        val finished = HookEntrySource.function("onAdaptFinished")
        val whole = HookEntrySource.hookEntry()
        assertTrue(finished.contains("applyAdaptOutcome"))
        assertTrue("slice ${finished.length} should be much smaller than ${whole.length}", finished.length < whole.length / 10)
        assertTrue(finished.trimEnd().endsWith("}"))
        assertFalse(finished.contains("fun onSilentCacheReady"))
    }

    fun `stripping comments preserves non-nested function counts across every scanned file`() {
        var total = 0
        HookEntrySource.hookEntryFiles().forEach { (path, raw) ->
            val stripped = HookEntrySource.code(raw)
            val onRaw = HookEntrySource.nonNestedFunctions(raw)
            val onStripped = HookEntrySource.nonNestedFunctions(stripped)
            assertEquals(
                "$path: stripping comments must not change declared function list",
                onRaw.map { it.first },
                onStripped.map { it.first }
            )
            total += onRaw.size
        }
        assertTrue("scanned hook surface should contain plenty of functions: $total", total > 30)
    }

    fun `no extracted FreeCopy or CommentTopology files escape HookEntrySource`() {
        assertEquals(
            "extracted hook files must live inside HookEntrySource's scanned roots",
            emptyList<String>(),
            HookEntrySource.unscannedFeatureExtractions()
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HookEntrySourceTest) -> Unit) {
            val s = HookEntrySourceTest()
            block(s)
        }
        runIsolated { s -> s.`braces inside strings templates and comments do not end the slice`() }
        runIsolated { s -> s.`property slicer captures multiline val lambdas and object expressions`() }
        runIsolated { s -> s.`missing or ambiguous functions fail loudly instead of returning empty or full file`() }
        runIsolated { s -> s.`real HookEntry and FeatureInstaller slices stay bounded and complete`() }
        runIsolated { s -> s.`stripping comments preserves non-nested function counts across every scanned file`() }
        runIsolated { s -> s.`no extracted FreeCopy or CommentTopology files escape HookEntrySource`() }
    }
}
