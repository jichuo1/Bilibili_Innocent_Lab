package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRemoteGateway.SyncResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NPatchCompatibilityTest {
    private val patch = NPatchRuntimeProfiles.parseJson("""{"useManager":false,"sigBypassLevel":2,"lspConfig":{"API_CODE":102,"VERSION_CODE":833}}""")
    private val baseline = NPatchEnvironment(patch, null, null, 8902100, "8.90.2", 36, listOf("arm64-v8a", "armeabi-v7a"))

    fun baselineParametersDoNotDependOnManagerInstallation() {
        assertEquals(NPatchCompatibility.BASELINE_PARAMETERS, NPatchCompatibility.assess(baseline))
        assertEquals(NPatchCompatibility.BASELINE_PARAMETERS, NPatchCompatibility.assess(baseline.copy(
            patch = patch.copy(mode = NPatchMode.MANAGER), managerVersionCode = 846)))
    }

    fun incompleteMetadataDoesNotClaimCompatibility() {
        for (environment in listOf(baseline.copy(patch = NPatchRuntimeProfile()),
            baseline.copy(patch = patch.copy(metadataValid = false)),
            baseline.copy(patch = patch.copy(signatureLevel = null)),
            baseline.copy(patch = patch.copy(loaderApi = null)),
            baseline.copy(hostVersionCode = null), baseline.copy(androidSdk = null), baseline.copy(deviceAbis = emptyList()))) {
            assertEquals(NPatchCompatibility.UNKNOWN, NPatchCompatibility.assess(environment))
        }
    }

    fun untestedVersionsLevelsAndAbisStayUnverified() {
        for (environment in listOf(baseline.copy(hostVersionCode = 8902101), baseline.copy(hostVersionName = "other"),
            baseline.copy(androidSdk = 35), baseline.copy(deviceAbis = listOf("x86_64", "arm64-v8a")),
            baseline.copy(patch = patch.copy(loaderVersionCode = 846)), baseline.copy(patch = patch.copy(loaderApi = 103)),
            baseline.copy(patch = patch.copy(signatureLevel = 1)),
            baseline.copy(patch = patch.copy(mode = NPatchMode.MANAGER, signatureLevel = 5)))) {
            assertEquals(NPatchCompatibility.UNVERIFIED_PARAMETERS, NPatchCompatibility.assess(environment))
        }
    }

    /**
     * 上游 CI 构建 851 和 release 857 目前只有源码契约/JVM（以及后续模拟器）层面的证据，
     * 没有真机证据，所以必须停留在 UNVERIFIED_PARAMETERS。把它们加入 [VerifiedNPatchLoaders]
     * 时必须同时更新本测试和 docs/npatch-release-support.md 中的证据记录。
     */
    fun buildsWithoutDeviceEvidenceAreNotVerifiedLoaders() {
        assertEquals(setOf(833L), VerifiedNPatchLoaders.baseline)
        assertEquals(setOf(833L), VerifiedNPatchLoaders.roamingCacheAdapter)
        for (build in listOf(851L, 857L)) {
            val unverified = patch.copy(loaderVersionCode = build)
            assertEquals(NPatchCompatibility.UNVERIFIED_PARAMETERS,
                NPatchCompatibility.assess(baseline.copy(patch = unverified)))
            assertEquals(false, VerifiedNPatchLoaders.contains(VerifiedNPatchLoaders.roamingCacheAdapter, build))
        }
        assertEquals(false, VerifiedNPatchLoaders.contains(VerifiedNPatchLoaders.baseline, null))
    }

    fun embeddedUpperBoundIsSpecificToPinnedPatcher() {
        for (level in 3..5) assertEquals(NPatchCompatibility.EMBEDDED_LEVEL_INVALID,
            NPatchCompatibility.assess(baseline.copy(patch = patch.copy(signatureLevel = level))))
        assertEquals(NPatchCompatibility.UNVERIFIED_PARAMETERS,
            NPatchCompatibility.assess(baseline.copy(patch = patch.copy(signatureLevel = 3, loaderVersionCode = 846))))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (NPatchCompatibilityTest) -> Unit) {
            val s = NPatchCompatibilityTest()
            block(s)
        }
        runIsolated { s -> s.baselineParametersDoNotDependOnManagerInstallation() }
        runIsolated { s -> s.incompleteMetadataDoesNotClaimCompatibility() }
        runIsolated { s -> s.untestedVersionsLevelsAndAbisStayUnverified() }
        runIsolated { s -> s.buildsWithoutDeviceEvidenceAreNotVerifiedLoaders() }
        runIsolated { s -> s.embeddedUpperBoundIsSpecificToPinnedPatcher() }
    
        NPatchEmbeddedBuildCases().verifyAllCases()
        NPatchHealthCases().verifyAllCases()
        NPatchModuleDriftCases().verifyAllCases()
        NPatchProcessCodecCases().verifyAllCases()
        NPatchRemoteFailureClassificationCases().verifyAllCases()
    }
}

private class NPatchEmbeddedBuildCases {
    val temporary = TemporaryFolder()
    private val entry = "assets/npatch/modules/${BuildConfig.APPLICATION_ID}.apk"
    private fun host(bytes: ByteArray, name: String = entry): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
    }
    private fun module(bytes: ByteArray): File = temporary.newFile().also { it.writeBytes(bytes) }

    fun `exact installed bytes match regardless of outer apk compression`() {
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        assertEquals(NPatchEmbeddedBuildState.MATCHED, NPatchEmbeddedBuild.compare(host(bytes), module(bytes)))
    }
    fun `same size module with changed bytes is different`() {
        val old = "version=120;code=old".toByteArray()
        val updated = "version=120;code=new".toByteArray()
        assertEquals(NPatchEmbeddedBuildState.DIFFERENT, NPatchEmbeddedBuild.compare(host(old), module(updated)))
    }
    fun `another embedded module cannot satisfy our exact entry`() {
        val bytes = byteArrayOf(1, 2)
        assertEquals(NPatchEmbeddedBuildState.MODULE_MISSING,
            NPatchEmbeddedBuild.compare(host(bytes, "assets/npatch/modules/other.apk"), module(bytes)))
    }
    fun `corrupt or unavailable packages remain unknown`() {
        val installed = module(byteArrayOf(1))
        assertEquals(NPatchEmbeddedBuildState.UNKNOWN, NPatchEmbeddedBuild.compare(module(byteArrayOf(4)), installed))
        assertEquals(NPatchEmbeddedBuildState.UNKNOWN, NPatchEmbeddedBuild.compare(File(temporary.root, "absent.apk"), installed))
    }
    fun `empty and oversized content cannot be reported as matching`() {
        assertEquals(NPatchEmbeddedBuildState.UNKNOWN, NPatchEmbeddedBuild.compare(host(byteArrayOf()), module(byteArrayOf())))
        val bytes = ByteArray(65)
        assertEquals(NPatchEmbeddedBuildState.UNKNOWN, NPatchEmbeddedBuild.compare(host(bytes), module(bytes), 64))
        assertEquals(NPatchEmbeddedBuildState.UNKNOWN, NPatchEmbeddedBuild.compare(host(byteArrayOf(1)), module(bytes), 64))
    }
    fun `same path replacement is checked again instead of reusing stale success`() {
        val bytes = byteArrayOf(1, 2, 3)
        val host = host(bytes); val installed = module(bytes)
        assertEquals(NPatchEmbeddedBuildState.MATCHED, NPatchEmbeddedBuild.compare(host, installed))
        val time = installed.lastModified()
        installed.writeBytes(byteArrayOf(1, 2, 4)); installed.setLastModified(time)
        assertEquals(NPatchEmbeddedBuildState.DIFFERENT, NPatchEmbeddedBuild.compare(host, installed))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NPatchEmbeddedBuildCases) -> Unit) {
            val s = NPatchEmbeddedBuildCases()
            s.temporary.create()
            try {
                block(s)
            } finally {
                s.temporary.delete()
            }
        }
        runIsolated { s -> s.`exact installed bytes match regardless of outer apk compression`() }
        runIsolated { s -> s.`same size module with changed bytes is different`() }
        runIsolated { s -> s.`another embedded module cannot satisfy our exact entry`() }
        runIsolated { s -> s.`corrupt or unavailable packages remain unknown`() }
        runIsolated { s -> s.`empty and oversized content cannot be reported as matching`() }
        runIsolated { s -> s.`same path replacement is checked again instead of reusing stale success`() }
    }
}

private class NPatchHealthCases {
    fun managerPresenceScopeAndReachabilityRemainSeparate() {
        assertEquals(NPatchManagerReachability.NOT_APPLICABLE,
            NPatchScopeProbe.classifyReachability(NPatchMode.EMBEDDED, NPatchScopeProbe.ScopeState.IN_SCOPE))
        assertEquals(NPatchManagerReachability.REACHABLE,
            NPatchScopeProbe.classifyReachability(NPatchMode.MANAGER, NPatchScopeProbe.ScopeState.OUT_OF_SCOPE))
        assertEquals(NPatchManagerReachability.UNKNOWN,
            NPatchScopeProbe.classifyReachability(NPatchMode.MANAGER, NPatchScopeProbe.ScopeState.UNKNOWN))
        assertEquals(NPatchManagerReachability.UNKNOWN,
            NPatchScopeProbe.classifyReachability(NPatchMode.UNKNOWN, NPatchScopeProbe.ScopeState.IN_SCOPE))
    }
    private fun sample(index: Int, installed: Long? = null) = NPatchStartupEvidence(
        "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}", index.toLong(), 1, 10,
        installed, 10 + (installed ?: 0), "granted", "provider", listOf(NPatchRouteEvidence("provider", "granted")))

    fun missingAndCancelledTransportResultsNeverBecomeFailuresOrZeroPercent() {
        val stats = NPatchHealth.summarize(listOf(sample(1, 20)))
        assertEquals(100, stats.routes.single { it.route == "provider" }.grantedPercent)
        assertNull(stats.routes.single { it.route == "binder" }.grantedPercent)
        assertEquals(0, stats.routes.single { it.route == "broadcast" }.completed)
        assertEquals(1, stats.grantedBoots)
    }

    fun measurementsExcludeUnfinishedInstallationAndRetryCountsStaySeparate() {
        val samples = listOf(sample(1, 20), sample(2, 40), sample(3).copy(attempt = 2, result = "timeout",
            selectedRoute = "unknown", routes = listOf(NPatchRouteEvidence("provider", "timeout"))))
        val stats = NPatchHealth.summarize(samples)
        assertEquals(3, stats.observedBoots)
        assertEquals(2, stats.grantedBoots)
        assertEquals(1, stats.retryCount)
        assertEquals(2, stats.measuredBoots)
        assertEquals(30L, stats.p50Ms)
        assertEquals(50L, stats.maxMs)
        assertEquals(66, stats.routes.single { it.route == "provider" }.grantedPercent)
    }

    fun noSamplesRemainUnknownAndRefreshesDoNotChangeDenominators() {
        val empty = NPatchHealth.summarize(emptyList())
        assertNull(empty.p50Ms)
        assertNull(empty.maxMs)
        assertTrue(empty.routes.all { it.grantedPercent == null })
        val sample = sample(1, 0)
        val once = NPatchHealth.summarize(listOf(sample))
        assertEquals(once, NPatchHealth.summarize(NPatchStartupCodec.merge(listOf(sample), listOf(sample))))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NPatchHealthCases) -> Unit) {
            val s = NPatchHealthCases()
            block(s)
        }
        runIsolated { s -> s.managerPresenceScopeAndReachabilityRemainSeparate() }
        runIsolated { s -> s.missingAndCancelledTransportResultsNeverBecomeFailuresOrZeroPercent() }
        runIsolated { s -> s.measurementsExcludeUnfinishedInstallationAndRetryCountsStaySeparate() }
        runIsolated { s -> s.noSamplesRemainUnknownAndRefreshesDoNotChangeDenominators() }
    }
}

private class NPatchModuleDriftCases {
    private val row = NPatchProcessSnapshot("tv.danmaku.bili", 1, NPatchMode.MANAGER, NPatchProcessState.INSTALLED,
        "module_direct", 49, 123, 20)

    fun oldAndMixedVersionsAreObservationsButNeverCurrentProcessEvidence() {
        val old = NPatchModuleObservation.fromRows(listOf(row), 21)
        assertEquals(listOf(20L), old.versions)
        assertTrue(old.processes.isEmpty())
        val mixed = NPatchModuleObservation.fromRows(listOf(row, row.copy(processName = "tv.danmaku.bili:web", moduleVersionCode = 21)), 21)
        assertEquals(listOf(20L, 21L), mixed.versions)
        assertTrue(mixed.processes.isEmpty())
        val current = row.copy(moduleVersionCode = 21)
        assertEquals(listOf(current), NPatchModuleObservation.fromRows(listOf(current), 21).processes)
    }

    fun historicalVersionIsDistinguishedFromCurrentObservationAndUnknown() {
        assertEquals(NPatchModuleDrift.UNKNOWN, NPatchModuleDrift.assess(21, emptyList(), 21, NPatchEmbeddedBuildState.UNKNOWN))
        assertEquals(NPatchModuleDrift.PREVIOUS_DIFFERENT, NPatchModuleDrift.assess(21, emptyList(), 20, NPatchEmbeddedBuildState.UNKNOWN))
        assertEquals(NPatchModuleDrift.CURRENT_VERSION, NPatchModuleDrift.assess(21, listOf(21), 20, NPatchEmbeddedBuildState.NOT_APPLICABLE))
        assertEquals(NPatchModuleDrift.RUNTIME_DIFFERENT, NPatchModuleDrift.assess(21, listOf(20), 21, NPatchEmbeddedBuildState.UNKNOWN))
    }

    fun equalVersionCodesCannotOverrideDifferentEmbeddedBytes() {
        assertEquals(NPatchModuleDrift.EMBEDDED_DIFFERENT, NPatchModuleDrift.assess(21, listOf(21), 21, NPatchEmbeddedBuildState.DIFFERENT))
        assertEquals(NPatchModuleDrift.EMBEDDED_DIFFERENT, NPatchModuleDrift.assess(21, emptyList(), 0, NPatchEmbeddedBuildState.MODULE_MISSING))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NPatchModuleDriftCases) -> Unit) {
            val s = NPatchModuleDriftCases()
            block(s)
        }
        runIsolated { s -> s.oldAndMixedVersionsAreObservationsButNeverCurrentProcessEvidence() }
        runIsolated { s -> s.historicalVersionIsDistinguishedFromCurrentObservationAndUnknown() }
        runIsolated { s -> s.equalVersionCodesCannotOverrideDifferentEmbeddedBytes() }
    }
}

private class NPatchProcessCodecCases {
    private val row = NPatchProcessSnapshot("tv.danmaku.bili:download", 123,
        NPatchMode.EMBEDDED, NPatchProcessState.INSTALLED, "module_direct", 0, 456L, 21L)

    fun `loaded child with zero hooks remains a distinct observation`() {
        assertEquals(listOf(row), NPatchProcessCodec.decode(NPatchProcessCodec.encode(listOf(row))))
        assertEquals(emptyList<NPatchProcessSnapshot>(), NPatchProcessCodec.decode("[]"))
        val isolated = row.copy(processName = "tv.danmaku.bili:web", state = NPatchProcessState.GATED)
        assertEquals(listOf(isolated), NPatchProcessCodec.decode(NPatchProcessCodec.encode(listOf(isolated))))
    }

    fun `protocol rejects forged shape types and unbounded content`() {
        val raw = NPatchProcessCodec.encode(listOf(row))
        for (invalid in listOf(
            raw.replace("tv.danmaku.bili:download", "other.package:download"),
            raw.replace("module_direct", "invented_source"),
            raw.replace("\"hooks\":0", "\"hooks\":\"0\""),
            raw.replace("\"pid\":123", "\"pid\":4294967419"),
            raw.replace("\"hooks\":0", "\"hooks\":4097"),
            raw.replace("\"hooks\":0", "\"hooks\":1").replace("INSTALLED", "DISABLED"),
            raw.replace("\"moduleVersion\":21", "\"moduleVersion\":0"),
            raw.replace("\"pid\":123", "\"pid\":123,\"extra\":true"),
            raw.replace("\"process64\":null", "\"process64\":\"true\""),
            raw.replace("\"dexkit\":\"UNKNOWN\"", "\"dexkit\":\"invented\""),
            raw.replace("\"frameNative\":\"UNKNOWN\"", "\"frameNative\":\"invented\""),
            " ".repeat(NPatchProcessCodec.MAX_CHARS + 1),
            "[$raw,$raw]"
        )) assertNull(invalid.take(120), NPatchProcessCodec.decode(invalid))
        val objectText = raw.removePrefix("[").removeSuffix("]")
        assertNull(NPatchProcessCodec.decode("[$objectText,$objectText]"))
        val tooMany = (1..17).map { row.copy(processName = "tv.danmaku.bili:p$it") }
        assertThrows(IllegalArgumentException::class.java) { NPatchProcessCodec.encode(tooMany) }
    }

    fun `native observations remain independent of hook counts and device bitness`() {
        for (bitness in listOf(true, false, null)) {
            val observed = row.copy(process64Bit = bitness, dexKitState = "AVAILABLE", frameNativeState = "FAILED")
            assertEquals(listOf(observed), NPatchProcessCodec.decode(NPatchProcessCodec.encode(listOf(observed))))
        }
        val notQueried = row.copy(dexKitState = "NOT_TRIED", frameNativeState = "NOT_PREPARED")
        assertEquals(listOf(notQueried), NPatchProcessCodec.decode(NPatchProcessCodec.encode(listOf(notQueried))))
        val legacy = org.json.JSONObject(NPatchProcessCodec.encode(listOf(row)).removeSurrounding("[", "]"))
        legacy.remove("process64"); legacy.remove("dexkit"); legacy.remove("frameNative")
        assertNull(NPatchProcessCodec.decode("[$legacy]"))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NPatchProcessCodecCases) -> Unit) {
            val s = NPatchProcessCodecCases()
            block(s)
        }
        runIsolated { s -> s.`loaded child with zero hooks remains a distinct observation`() }
        runIsolated { s -> s.`protocol rejects forged shape types and unbounded content`() }
        runIsolated { s -> s.`native observations remain independent of hook counts and device bitness`() }
    }
}

private class NPatchRemoteFailureClassificationCases {
    fun `a provider that answered without a service means the module was not accepted`() {
        assertEquals(
            SyncResult.ModuleNotRegistered,
            NPatchRemoteGateway.classifyFailure(NPatchRemoteGateway.ManagerUnavailableException(rejected = true))
        )
    }

    fun `a missing or unreachable provider still means the manager is missing`() {
        assertEquals(
            SyncResult.ManagerMissing,
            NPatchRemoteGateway.classifyFailure(NPatchRemoteGateway.ManagerUnavailableException())
        )
        assertEquals(
            SyncResult.ManagerMissing,
            NPatchRemoteGateway.classifyFailure(
                NPatchRemoteGateway.ManagerUnavailableException(SecurityException("platform denied the provider"))
            )
        )
    }

    fun `an explicit security denial means the module was not accepted`() {
        assertEquals(SyncResult.ModuleNotRegistered, NPatchRemoteGateway.classifyFailure(SecurityException("denied")))
    }

    fun `unrelated failures keep their type name as the code`() {
        assertEquals(SyncResult.Failure("IllegalArgumentException"),
            NPatchRemoteGateway.classifyFailure(IllegalArgumentException("bad")))
        assertEquals(SyncResult.Failure("remote_read_only"),
            NPatchRemoteGateway.classifyFailure(UnsupportedOperationException()))
    }

    fun `a rejected connection is retried once and keeps its classification`() {
        var calls = 0
        val error = assertThrows(NPatchRemoteGateway.ManagerUnavailableException::class.java) {
            NPatchRemoteGateway.connectWithRetry({ true }) {
                calls++
                throw NPatchRemoteGateway.ManagerUnavailableException(rejected = true)
            }
        }
        assertEquals(2, calls)
        assertTrue(error.rejected)
        assertEquals(SyncResult.ModuleNotRegistered, NPatchRemoteGateway.classifyFailure(error))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (NPatchRemoteFailureClassificationCases) -> Unit) {
            val s = NPatchRemoteFailureClassificationCases()
            block(s)
        }
        runIsolated { s -> s.`a provider that answered without a service means the module was not accepted`() }
        runIsolated { s -> s.`a missing or unreachable provider still means the manager is missing`() }
        runIsolated { s -> s.`an explicit security denial means the module was not accepted`() }
        runIsolated { s -> s.`unrelated failures keep their type name as the code`() }
        runIsolated { s -> s.`a rejected connection is retried once and keeps its classification`() }
    }
}
