package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 模块 UID 不能冒充宿主，真实 IPC 路线均不可授权，正好验证最终回退边界。文档只在内存中构造。 */
@RunWith(AndroidJUnit4::class)
class HostAdmissionFallbackInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val standard = RemoteHookConfigSnapshot(1L, BuildConfig.VERSION_CODE.toLong(),
        true, 0L, UserTermsDecision.ACCEPTED,
        RemoteHookConfigContract.resolveSourceValues(emptyMap<String, Any>()))

    @Test fun npatchWithoutAManagerDocumentStillNeedsACurrentGrant() {
        val result = HostAdmissionClient.admit(context, null, "remote_group_unavailable", nPatchRuntime = true)
        assertNull(result.grant)
        assertEquals("admission_unavailable", result.reason)
    }

    @Test fun npatchRejectsOldStandardDocumentWhenAllAuthorizingEndpointsAreUnavailable() {
        val result = HostAdmissionClient.admit(context, standard, null, nPatchRuntime = true)
        assertNull(result.grant)
        assertEquals("manager_sync_required", result.reason)
    }

    @Test fun standardFrameworkKeepsItsValidatedFallback() {
        val result = HostAdmissionClient.admit(context, standard, null, nPatchRuntime = false)
        assertEquals("remote_config_transport_fallback", result.grant?.source)
    }

    @Test fun lspatchCannotTreatAnOldStandardMirrorAsCurrentAuthorization() {
        val result = HostAdmissionClient.admit(context, standard, null, lspatchRuntime = true)
        assertNull(result.grant)
        // 不可信调用可立即被拒，也可等到系统跨包运输超时；两者都不能退回旧镜像。
        assertTrue(result.reason in setOf("admission_unavailable", "admission_timeout"))
    }

    @Test fun npatchCannotUseAnOldPositiveRevisionAfterUserDisablesWhileManagerIsUnavailable() {
        val result = HostAdmissionClient.admit(context, standard.copy(noRootRevision = 42L), null, nPatchRuntime = true)
        assertNull(result.grant)
        assertEquals("admission_unavailable", result.reason)
    }
}
