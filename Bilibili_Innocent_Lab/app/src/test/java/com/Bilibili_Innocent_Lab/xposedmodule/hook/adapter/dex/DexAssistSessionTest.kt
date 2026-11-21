package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DexAssistSessionTest {

    private class RecordingEngine : DexAssistEngine {
        val batches = mutableListOf<Set<DexAssistQuery>>()
        val singles = mutableListOf<DexAssistQuery>()

        override fun resolve(request: DexAssistRequest): DexAssistResult {
            singles += request.query
            return DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
        }

        override fun resolveAll(
            queries: Set<DexAssistQuery>,
            codePaths: List<String>,
            classLoader: ClassLoader
        ): Map<DexAssistQuery, DexAssistResult> {
            batches += queries
            return queries.associateWith { DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH) }
        }
    }

    private fun session(engine: DexAssistEngine, planned: Set<DexAssistQuery>) =
        DexAssistSession(engine, listOf("/base.apk"), javaClass.classLoader!!, planned)

    fun `planned queries share one pass`() {
        val engine = RecordingEngine()
        val planned = setOf(DexAssistQuery.BLOCK_UPDATE, DexAssistQuery.PLAYER_DEFAULT_QUALITY,
            DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN, DexAssistQuery.COMMENT_CLASSIC_NATIVE, DexAssistQuery.COMMENT_CLASSIC_KOTLIN)
        val session = session(engine, planned)

        assertTrue(engine.batches.isEmpty()) // 不取结果就不建桥
        session.result(DexAssistQuery.BLOCK_UPDATE)
        session.result(DexAssistQuery.PLAYER_DEFAULT_QUALITY)
        session.result(DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN)
        session.result(DexAssistQuery.COMMENT_CLASSIC_NATIVE)
        session.result(DexAssistQuery.COMMENT_CLASSIC_KOTLIN)

        assertEquals(listOf(planned), engine.batches)
        assertTrue(engine.singles.isEmpty())
    }

    fun `an unplanned query is still answered on its own`() {
        val engine = RecordingEngine()
        val session = session(engine, setOf(DexAssistQuery.BLOCK_UPDATE))

        session.result(DexAssistQuery.COMMENT_REPLY_MAPPER)

        assertEquals(listOf(setOf(DexAssistQuery.BLOCK_UPDATE)), engine.batches)
        assertEquals(listOf(DexAssistQuery.COMMENT_REPLY_MAPPER), engine.singles)
    }

    fun `default batch falls back to one resolve per query`() {
        val seen = mutableListOf<DexAssistQuery>()
        val engine = DexAssistEngine { request ->
            seen += request.query
            DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
        }
        val all = DexAssistQuery.entries.toSet()

        val results = engine.resolveAll(all, listOf("/base.apk"), javaClass.classLoader!!)

        assertEquals(all, results.keys)
        assertEquals(all, seen.toSet())
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (DexAssistSessionTest) -> Unit) {
            val s = DexAssistSessionTest()
            block(s)
        }
        runIsolated { s -> s.`planned queries share one pass`() }
        runIsolated { s -> s.`an unplanned query is still answered on its own`() }
        runIsolated { s -> s.`default batch falls back to one resolve per query`() }
    }
}
