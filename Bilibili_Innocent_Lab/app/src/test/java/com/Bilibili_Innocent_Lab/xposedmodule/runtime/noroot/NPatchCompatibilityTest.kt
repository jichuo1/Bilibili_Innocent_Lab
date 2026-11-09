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

    @Test fun embeddedUpperBoundIsSpecificToPinnedPatcher() {
        for (level in 3..5) assertEquals(NPatchCompatibility.EMBEDDED_LEVEL_INVALID,
            NPatchCompatibility.assess(baseline.copy(patch = patch.copy(signatureLevel = level))))
        assertEquals(NPatchCompatibility.UNVERIFIED_PARAMETERS,
            NPatchCompatibility.assess(baseline.copy(patch = patch.copy(signatureLevel = 3, loaderVersionCode = 846))))
    }
}
