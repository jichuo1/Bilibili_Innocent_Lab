package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.*
import org.junit.Test

class NPatchStartupHistoryPolicyTest {
    @Test fun absentProcessHistorySurvivesWithoutBecomingCurrentEvidence() {
        val main = row("tv.danmaku.bili", 1)
        val web = row("tv.danmaku.bili:web", 2)
        val first = NPatchStartupHistoryPolicy.merge(emptyList(), listOf(main, web))
        val quiet = NPatchStartupHistoryPolicy.merge(first.persisted, listOf(main))
        assertEquals(listOf(main), quiet.current)
        assertEquals(2, quiet.persisted.size)
        val nextWeb = web.copy(pid = 99, startups = listOf(sample(3)))
        val next = NPatchStartupHistoryPolicy.merge(quiet.persisted, listOf(main, nextWeb))
        assertEquals(listOf(sample(2), sample(3)), next.current.single { it.processName == web.processName }.startups)
        assertEquals(99, next.current.single { it.processName == web.processName }.pid)
    }

    @Test fun hostModuleAndCarrierChangesDiscardAllPreviousProcessHistory() {
        val main = row("tv.danmaku.bili", 1)
        val web = row("tv.danmaku.bili:web", 2)
        listOf(main.copy(targetUpdateTime = 2), main.copy(moduleVersionCode = 23), main.copy(mode = NPatchMode.EMBEDDED)).forEach { fresh ->
            val update = NPatchStartupHistoryPolicy.merge(listOf(main, web), listOf(fresh))
            assertEquals(listOf(fresh), update.current)
            assertEquals(listOf(fresh), update.persisted)
        }
    }

    @Test fun processAndPerProcessHistoryRemainBoundedWithFreshRowsPrioritized() {
        val old = (1..16).map { row("tv.danmaku.bili:p$it", it) }
        val main = row("tv.danmaku.bili", 17)
        val update = NPatchStartupHistoryPolicy.merge(old, listOf(main))
        assertEquals(16, update.persisted.size)
        assertEquals(main, update.persisted.first())
        assertFalse(update.persisted.any { it.processName == "tv.danmaku.bili:p1" })
        val previous = main.copy(startups = (1..8).map(::sample))
        val next = NPatchStartupHistoryPolicy.merge(listOf(previous), listOf(main.copy(startups = listOf(sample(9)))))
        assertEquals((2..9).map(::sample), next.current.single().startups)
        assertNotNull(NPatchProcessCodec.decode(NPatchProcessCodec.encode(update.persisted)))
    }

    @Test fun inconsistentFreshInstallationAndDuplicateProcessesAreRejected() {
        val main = row("tv.danmaku.bili", 1)
        assertThrows(IllegalArgumentException::class.java) {
            NPatchStartupHistoryPolicy.merge(emptyList(), listOf(main, main))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NPatchStartupHistoryPolicy.merge(emptyList(), listOf(main, row("tv.danmaku.bili:web", 2).copy(moduleVersionCode = 23)))
        }
    }

    private fun row(name: String, boot: Int) = NPatchProcessSnapshot(name, boot, NPatchMode.MANAGER,
        NPatchProcessState.INSTALLED, "module_direct", 10, 1, 22, startups = listOf(sample(boot)))
    private fun sample(index: Int) = NPatchStartupEvidence("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        index.toLong(), 1, 10, 20, 30, "granted", "provider", listOf(NPatchRouteEvidence("provider", "granted")))
}
