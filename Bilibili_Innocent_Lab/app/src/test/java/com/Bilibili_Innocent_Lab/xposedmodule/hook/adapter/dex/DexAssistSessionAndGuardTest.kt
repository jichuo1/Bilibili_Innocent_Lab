package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DexAssistSessionAndGuardTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private val queries = setOf(DexAssistQuery.BLOCK_UPDATE, DexAssistQuery.PLAYER_DEFAULT_QUALITY)
    private val loader = javaClass.classLoader!!

    private fun found(): DexAssistResult = DexAssistResult.Candidates(emptyList())

    @Test
    fun `attempt guard and tiered retry manage markers across versions and low memory`() {
        // Marker exists only while pass runs
        run {
            val dir = tempFolder.newFolder()
            val marker = dir.resolve("pending")
            var seenDuringPass = ""
            val guard = DexAssistAttemptGuard(marker, "host|1") { _ ->
                seenDuringPass = marker.readText()
                found()
            }
            val results = guard.resolveAll(queries, listOf("/base.apk"), loader)
            assertEquals("host|1", seenDuringPass)
            assertFalse(marker.exists())
            assertTrue(results.values.all { it is DexAssistResult.Candidates })
        }

        // Unfinished pass for same host is not retried
        run {
            val dir = tempFolder.newFolder()
            val marker = dir.resolve("pending").apply { writeText("host|1") }
            var calls = 0
            val guard = DexAssistAttemptGuard(marker, "host|1") { _ -> calls++; found() }
            val results = guard.resolveAll(queries, listOf("/base.apk"), loader)
            assertEquals(0, calls)
            assertEquals(
                queries.associateWith { DexAssistResult.Unavailable(DexAssistResult.Reason.PREVIOUS_ATTEMPT_UNFINISHED) },
                results
            )
            assertTrue(marker.exists())
        }

        // New host version retries
        run {
            val dir = tempFolder.newFolder()
            val marker = dir.resolve("pending").apply { writeText("host|1") }
            var calls = 0
            val guard = DexAssistAttemptGuard(marker, "host|2") { _ -> calls++; found() }
            guard.resolveAll(queries, listOf("/base.apk"), loader)
            assertEquals(queries.size, calls)
            assertFalse(marker.exists())
        }

        // Failing pass still clears marker
        run {
            val dir = tempFolder.newFolder()
            val marker = dir.resolve("pending")
            val guard = DexAssistAttemptGuard(marker, "host|1") { _ -> error("native crash surfaced as exception") }
            runCatching { guard.resolveAll(queries, listOf("/base.apk"), loader) }
            assertFalse(marker.exists())
        }

        // Creates missing parent directory atomically
        run {
            val dir = tempFolder.newFolder()
            val marker = dir.resolve("nested/sub/pending")
            assertFalse(marker.parentFile!!.exists())
            var seenDuringPass = ""
            val guard = DexAssistAttemptGuard(marker, "host|3") { _ ->
                seenDuringPass = marker.readText()
                found()
            }
            val results = guard.resolveAll(queries, listOf("/base.apk"), loader)
            assertEquals("host|3", seenDuringPass)
            assertTrue(marker.parentFile!!.isDirectory)
            assertFalse(marker.exists())
            assertTrue(results.values.all { it is DexAssistResult.Candidates })
        }

        // Tiered maxAttempts=2 and tier1-only retry / low memory
        run {
            val dir = tempFolder.newFolder()
            val marker = dir.resolve("pending")
            val recordedAllowTier2 = mutableListOf<Boolean>()
            val crashOnceEngine = object : TieredDexAssistEngine {
                override fun resolve(request: DexAssistRequest) = DexAssistResult.Candidates(emptyList())
                override fun resolveAll(queries: Set<DexAssistQuery>, codePaths: List<String>, classLoader: ClassLoader) =
                    resolveAll(queries, codePaths, classLoader, allowTier2 = true)

                override fun resolveAll(
                    queries: Set<DexAssistQuery>,
                    codePaths: List<String>,
                    classLoader: ClassLoader,
                    allowTier2: Boolean
                ): Map<DexAssistQuery, DexAssistResult> {
                    recordedAllowTier2 += allowTier2
                    return queries.associateWith { DexAssistResult.Candidates(emptyList()) }
                }
            }

            marker.writeText("fp|v1")
            dir.resolve("pending.attempts").writeText("fp|v1\n1")
            val guardRetry = DexAssistAttemptGuard(
                marker = marker,
                key = "fp|v1",
                delegate = crashOnceEngine,
                maxAttempts = DexAssistAttemptGuard.TIERED_MAX_ATTEMPTS
            )
            val retryResult = guardRetry.resolveAll(setOf(DexAssistQuery.BLOCK_UPDATE), listOf("/base.apk"), loader)
            assertEquals(listOf(false), recordedAllowTier2)
            assertTrue(retryResult.getValue(DexAssistQuery.BLOCK_UPDATE) is DexAssistResult.Candidates)
            assertFalse(marker.exists())

            marker.writeText("fp|v1")
            dir.resolve("pending.attempts").writeText("fp|v1\n2")
            val blocked = guardRetry.resolveAll(setOf(DexAssistQuery.BLOCK_UPDATE), listOf("/base.apk"), loader)
            assertEquals(
                DexAssistResult.Unavailable(DexAssistResult.Reason.PREVIOUS_ATTEMPT_UNFINISHED),
                blocked.getValue(DexAssistQuery.BLOCK_UPDATE)
            )
            marker.delete()
            dir.resolve("pending.attempts").delete()

            val lowMemGuard = DexAssistAttemptGuard(
                marker = marker,
                key = "fp|v1",
                delegate = crashOnceEngine,
                maxAttempts = DexAssistAttemptGuard.TIERED_MAX_ATTEMPTS,
                isLowMemory = { true }
            )
            lowMemGuard.resolveAll(setOf(DexAssistQuery.BLOCK_UPDATE), listOf("/base.apk"), loader)
            assertEquals(listOf(false, false), recordedAllowTier2)
        }
    }

    @Test
    fun `candidate selector and session batching enforce single owner and query scope`() {
        // CandidateSelector
        val leafCandidates = LeafImplementation::class.java.declaredMethods.filter {
            it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        assertEquals("leaf", DexAssistCandidateSelector.selectUniqueLeaf(leafCandidates)?.name)

        val inheritedCandidates = InheritedLeafImplementation::class.java.declaredMethods.filter {
            it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        assertEquals("leaf", DexAssistCandidateSelector.selectUniqueLeaf(inheritedCandidates)?.name)

        val multiOwner = listOf(
            LeafImplementation::class.java.getDeclaredMethod("leaf", String::class.java),
            OtherImplementation::class.java.getDeclaredMethod("leaf", String::class.java)
        )
        assertNull(DexAssistCandidateSelector.selectUniqueLeaf(multiOwner))

        val mapperCandidates = MapperFacade::class.java.declaredMethods.filter {
            it.parameterTypes.contentEquals(arrayOf(String::class.java))
        }
        val selectedGroup = DexAssistCandidateSelector.selectSingleOwnerGroup(mapperCandidates)
        assertEquals(2, selectedGroup.size)
        assertEquals(listOf("first", "second"), selectedGroup.map { it.name })

        val spreadGroup = listOf(
            MapperFacade::class.java.getDeclaredMethod("first", String::class.java),
            OtherMapperFacade::class.java.getDeclaredMethod("third", String::class.java)
        )
        assertEquals(emptyList<Any>(), DexAssistCandidateSelector.selectSingleOwnerGroup(spreadGroup))
        assertEquals(emptyList<Any>(), DexAssistCandidateSelector.selectSingleOwnerGroup(emptyList()))

        // DexAssistSession
        val engine = RecordingEngine()
        val planned = setOf(
            DexAssistQuery.BLOCK_UPDATE,
            DexAssistQuery.PLAYER_DEFAULT_QUALITY,
            DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN,
            DexAssistQuery.COMMENT_CLASSIC_NATIVE,
            DexAssistQuery.COMMENT_CLASSIC_KOTLIN
        )
        val session = DexAssistSession(engine, listOf("/base.apk"), loader, planned)
        assertTrue(engine.batches.isEmpty())
        planned.forEach { session.result(it) }
        assertEquals(listOf(planned), engine.batches)
        assertTrue(engine.singles.isEmpty())

        val unplannedEngine = RecordingEngine()
        val unplannedSession = DexAssistSession(unplannedEngine, listOf("/base.apk"), loader, setOf(DexAssistQuery.BLOCK_UPDATE))
        unplannedSession.result(DexAssistQuery.COMMENT_REPLY_MAPPER)
        assertEquals(listOf(setOf(DexAssistQuery.BLOCK_UPDATE)), unplannedEngine.batches)
        assertEquals(listOf(DexAssistQuery.COMMENT_REPLY_MAPPER), unplannedEngine.singles)

        val seen = mutableListOf<DexAssistQuery>()
        val fallbackEngine = DexAssistEngine { request ->
            seen += request.query
            DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
        }
        val all = DexAssistQuery.entries.toSet()
        val allResults = fallbackEngine.resolveAll(all, listOf("/base.apk"), loader)
        assertEquals(all, allResults.keys)
        assertEquals(all, seen.toSet())
    }

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

    private interface Contract {
        fun bridge(value: String): String
    }

    private class LeafImplementation : Contract {
        override fun bridge(value: String): String = value
        fun leaf(value: String): String = value
    }

    private interface ChildContract : Contract

    private class InheritedLeafImplementation : ChildContract {
        override fun bridge(value: String): String = value
        fun leaf(value: String): String = value
    }

    private class OtherImplementation {
        fun leaf(value: String): String = value
    }

    @Suppress("unused")
    private class MapperFacade {
        fun first(value: String): String = value
        fun second(value: String): String = value
    }

    @Suppress("unused")
    private class OtherMapperFacade {
        fun third(value: String): String = value
    }
}
