package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class AdaptationCacheMaintenanceContractTest {
    fun `cache hit schedules maintenance without synchronous file operations`() {
        val source = SourceContract.read("hook/VersionAdapter.kt")
        val hit = source.after("internal fun ensureAdapted(").after("if (cached != null) {")
            .before("if (!adaptationRunning.compareAndSet(false, true))")
        assertTrue(hit.contains("auditDexSourceAsync(context, cached)"))
        for (operation in listOf("cacheFile()", "writeCache(", "exists()", "inspectHostDexSource(", "codeCacheDir"))
            assertFalse(operation, hit.contains(operation))
        val worker = source.after("private fun auditDexSourceAsync(").before("fun ensureAdapted(")
        assertTrue(worker.contains("HostThreadGuard.run(\"adapter.dex_audit\")"))
        assertTrue(worker.contains("if (!cacheFile().exists()) writeCache(cached)"))
        assertTrue(worker.contains("if (expected == DEX_SOURCE_UNAVAILABLE) return@run"))
        assertTrue(worker.contains("dexAuditRunning.compareAndSet(false, true)"))
    }

    fun `repack reuse is background only and verifies content and protocol across all modes`() {
        val source = SourceContract.read("hook/VersionAdapter.kt")
        val startup = source.after("internal fun readStartupCache(").before("fun cacheStatus()")
        assertFalse(startup.contains("rebindRepackedCache("))
        assertFalse(startup.contains("inspectHostDexSource("))
        val ensureBeforeWorker = source.after("internal fun ensureAdapted(").before("Thread({")
        assertFalse("onAdaptStarted must be decided inside worker after rebind check", ensureBeforeWorker.contains("onAdaptStarted("))
        val worker = source.after("HostThreadGuard.run(\"adapter.adapt_worker\")").before("result = repacked ?:")
        assertFalse("All-mode rebind must not be gated to NPatch only", worker.contains("HookRuntimeMode.NPATCH_LEGACY"))
        assertTrue(worker.contains("readRawCacheCandidates(context, resetTimestamp)"))
        assertTrue(worker.contains("verifyProtocolFingerprint(classLoader, previous)"))
        assertTrue(worker.contains("rebindRepackedCache(previous, startup.identity, resetTimestamp, dexSource?.value, actualProtocol)"))
        assertTrue(worker.contains("AdaptDomainCatalog.staleDomains("))
        val startupRead = source.after("internal fun readStartupCache(").before("fun cacheStatus()")
        assertFalse(startupRead.contains("readRawCacheCandidates("))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AdaptationCacheMaintenanceContractTest) -> Unit) {
            val s = AdaptationCacheMaintenanceContractTest()
            block(s)
        }
        runIsolated { s -> s.`cache hit schedules maintenance without synchronous file operations`() }
        runIsolated { s -> s.`repack reuse is background only and verifies content and protocol across all modes`() }
    }
}
