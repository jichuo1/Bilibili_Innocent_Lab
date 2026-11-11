package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.assertEquals
import org.junit.Test

class NPatchCompatibilityTest {
    private val patch = NPatchRuntimeProfiles.parseJson("""{"useManager":false,"sigBypassLevel":2,"lspConfig":{"API_CODE":102,"VERSION_CODE":833}}""")
    private val baseline = NPatchEnvironment(patch, null, null, 8902100, "8.90.2", 36, listOf("arm64-v8a", "armeabi-v7a"))

    @Test fun baselineParametersDoNotDependOnManagerInstallation() {
        assertEquals(NPatchCompatibility.BASELINE_PARAMETERS, NPatchCompatibility.assess(baseline))
        assertEquals(NPatchCompatibility.BASELINE_PARAMETERS, NPatchCompatibility.assess(baseline.copy(
            patch = patch.copy(mode = NPatchMode.MANAGER), managerVersionCode = 846)))
    }

    @Test fun incompleteMetadataDoesNotClaimCompatibility() {
        for (environment in listOf(baseline.copy(patch = NPatchRuntimeProfile()),
            baseline.copy(patch = patch.copy(metadataValid = false)),
            baseline.copy(patch = patch.copy(signatureLevel = null)),
            baseline.copy(patch = patch.copy(loaderApi = null)),
            baseline.copy(hostVersionCode = null), baseline.copy(androidSdk = null), baseline.copy(deviceAbis = emptyList()))) {
            assertEquals(NPatchCompatibility.UNKNOWN, NPatchCompatibility.assess(environment))
        }
    }

    @Test fun untestedVersionsLevelsAndAbisStayUnverified() {
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
    @Test fun buildsWithoutDeviceEvidenceAreNotVerifiedLoaders() {
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

    @Test fun embeddedUpperBoundIsSpecificToPinnedPatcher() {
        for (level in 3..5) assertEquals(NPatchCompatibility.EMBEDDED_LEVEL_INVALID,
            NPatchCompatibility.assess(baseline.copy(patch = patch.copy(signatureLevel = level))))
        assertEquals(NPatchCompatibility.UNVERIFIED_PARAMETERS,
            NPatchCompatibility.assess(baseline.copy(patch = patch.copy(signatureLevel = 3, loaderVersionCode = 846))))
    }
}
