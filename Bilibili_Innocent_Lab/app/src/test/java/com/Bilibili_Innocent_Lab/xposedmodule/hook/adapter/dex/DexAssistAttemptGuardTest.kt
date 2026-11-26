package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DexAssistAttemptGuardTest {

    val folder = TemporaryFolder()

    private val queries = setOf(DexAssistQuery.BLOCK_UPDATE, DexAssistQuery.PLAYER_DEFAULT_QUALITY)
    private val loader = javaClass.classLoader!!

    private fun found(): DexAssistResult = DexAssistResult.Candidates(emptyList())

    fun `marker exists only while the pass runs`() {
        val marker = folder.root.resolve("pending")
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

    fun `an unfinished pass for the same host is not retried`() {
        val marker = folder.root.resolve("pending").apply { writeText("host|1") } // 上次进程死在查询中
        var calls = 0
        val guard = DexAssistAttemptGuard(marker, "host|1") { _ -> calls++; found() }

        val results = guard.resolveAll(queries, listOf("/base.apk"), loader)

        assertEquals(0, calls)
        assertEquals(
            queries.associateWith {
                DexAssistResult.Unavailable(DexAssistResult.Reason.PREVIOUS_ATTEMPT_UNFINISHED)
            },
            results
        )
        assertTrue(marker.exists()) // 闸门保持关闭，直到宿主换版或手动重适配
    }

    fun `a new host version retries`() {
        val marker = folder.root.resolve("pending").apply { writeText("host|1") }
        var calls = 0
        val guard = DexAssistAttemptGuard(marker, "host|2") { _ -> calls++; found() }

        guard.resolveAll(queries, listOf("/base.apk"), loader)

        assertEquals(queries.size, calls)
        assertFalse(marker.exists())
    }

    fun `a failing pass still clears the marker`() {
        val marker = folder.root.resolve("pending")
        val guard = DexAssistAttemptGuard(marker, "host|1") { _ -> error("native crash surfaced as exception") }

        runCatching { guard.resolveAll(queries, listOf("/base.apk"), loader) }

        assertFalse(marker.exists())
    }

    fun `creates missing parent directory when writing marker atomically`() {
        val marker = folder.root.resolve("nested/sub/pending")
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (DexAssistAttemptGuardTest) -> Unit) {
            val s = DexAssistAttemptGuardTest()
            s.folder.create()
            try {
                block(s)
            } finally {
                s.folder.delete()
            }
        }
        runIsolated { s -> s.`marker exists only while the pass runs`() }
        runIsolated { s -> s.`an unfinished pass for the same host is not retried`() }
        runIsolated { s -> s.`a new host version retries`() }
        runIsolated { s -> s.`a failing pass still clears the marker`() }
        runIsolated { s -> s.`creates missing parent directory when writing marker atomically`() }
    }
}
