package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 端点/持久化契约测试；handleBroadcast 的 UID 由测试提供，不冒充真实宿主 IPC 验收。 */
@RunWith(AndroidJUnit4::class)
class NPatchDirectPublicationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val targetUid get() = context.packageManager.getApplicationInfo(NoRootSupportState.TARGET_PACKAGE, 0).uid

    @Before fun enableDirectWithoutCompatibilityMode() {
        assertTrue(NPatchRuntimeProfiles.read(context).markerPresent)
        assertTrue(UserTermsConsentStore.writeDecision(context, UserTermsDecision.ACCEPTED))
        assertTrue(CommunicationCompatibilityStore.setEnabled(context, false))
        assertTrue(NoRootSupportStore.disable(context))
        assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
    }

    @Test fun currentDirectDocumentCarriesPositiveRevisionWithoutRemoteSync() {
        val publication = PublicationAuthorityStore.current(context)!!
        assertFalse(publication.directAllowed)
        val ready = RemoteHookConfigContract.decode(publication.noRootDocument()) as RemoteHookConfigDecodeResult.Ready
        assertTrue(ready.snapshot.noRootRevision > 0)
        assertFalse(NoRootSupportStore.isRemoteSynced(context, ready.snapshot.noRootRevision))
        assertTrue(NoRootSupportStore.exportState(context, true).valid)
        val standard = RemoteHookConfigContract.decode(publication.document()) as RemoteHookConfigDecodeResult.Ready
        assertEquals(0L, standard.snapshot.noRootRevision)
        assertEquals(publication.identity.fingerprint, RemoteHookConfigContract.contentFingerprint(ready.snapshot))
        val prepared = prepare()
        assertEquals("prepared", prepared.getString("status"))
        assertEquals(HostAdmissionContract.DIRECT, prepared.getString("source"))
        assertEquals("granted", confirm(prepared).getString("status"))
        assertEquals("stale", confirm(prepared).getString("status"))
    }

    @Test fun changedSettingsInvalidatePreparedLease() {
        val prepared = prepare()
        val prefs = context.modulePreferences()
        val key = RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION
        assertTrue(prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1).commit())
        assertEquals("stale", confirm(prepared).getString("status"))
    }

    @Test fun disabledIntentRejectsBothFreshAndPreviouslyPreparedRequests() {
        val prepared = prepare()
        assertTrue(NoRootSupportStore.disable(context))
        assertNotEquals("granted", confirm(prepared).getString("status"))
        val denied = prepare()
        assertEquals("manager_sync_required", denied.getString("status"))
        assertTrue(denied.getLong(HostAdmissionContract.DISABLED_REVISION) > 0L)
    }

    @Test fun revokedTermsRejectThePreparedLease() {
        val prepared = prepare()
        assertTrue(UserTermsConsentStore.writeDecision(context, UserTermsDecision.DECLINED))
        assertEquals("denied", confirm(prepared).getString("status"))
    }

    @Test fun actualModuleUidCannotReadHostDocument() {
        val response = HostAdmissionEndpoint.handle(context, HostAdmissionContract.METHOD_PREPARE, request())
        assertEquals("identity_rejected", response.getString("status"))
        assertNull(response.getString("document"))
    }

    @Test fun incompatibleAdmissionVersionsNeverIssueDocumentsOrLeases() {
        listOf(-1, 0, HostAdmissionContract.VERSION + 1).forEach { version ->
            val request = request().apply { putInt("version", version) }
            listOf(HostAdmissionContract.METHOD_PREPARE, HostAdmissionContract.METHOD_CONFIRM).forEach { method ->
                val rejected = HostAdmissionEndpoint.handleBroadcast(context, method, request, targetUid)
                assertEquals("protocol_rejected", rejected.getString("status"))
                assertNull(rejected.getString("document"))
                assertNull(rejected.getString("challenge"))
            }
        }
        // 拒绝未知版本后，现有版本仍须重新完成准备、确认和单次租约消耗。
        val prepared = prepare()
        assertEquals("prepared", prepared.getString("status"))
        assertEquals("granted", confirm(prepared).getString("status"))
        assertEquals("stale", confirm(prepared).getString("status"))
    }

    @Test fun restartFlushOnlyNeedsDurableCurrentDirectPublication() {
        val done = CountDownLatch(1)
        var result: NoRootSupportController.FlushResult? = null
        NoRootSupportController.flushBeforeRestart(context, context.modulePreferences()) {
            result = it
            done.countDown()
        }
        assertTrue(done.await(6, TimeUnit.SECONDS))
        assertEquals(NoRootSupportController.FlushResult.SUCCESS, result)
        val revision = NoRootSupportStore.readSnapshot(context)!!.revision
        assertFalse(NoRootSupportStore.isRemoteSynced(context, revision))
    }

    private fun request() = Bundle().apply {
        putInt("version", HostAdmissionContract.VERSION)
        putString("nonce", UUID.randomUUID().toString())
        putLong("deadline", android.os.SystemClock.elapsedRealtime() + HostAdmissionContract.CHALLENGE_TIMEOUT_MS)
        putBoolean("allowDirect", true)
    }

    private fun prepare(): Bundle = HostAdmissionEndpoint.handleBroadcast(
        context, HostAdmissionContract.METHOD_PREPARE, request(), targetUid)

    private fun confirm(prepared: Bundle): Bundle = HostAdmissionEndpoint.handleBroadcast(
        context, HostAdmissionContract.METHOD_CONFIRM, Bundle().apply {
            putInt("version", HostAdmissionContract.VERSION)
            putString("nonce", prepared.getString("nonce"))
            putString("challenge", prepared.getString("challenge"))
            HostAdmissionContract.putIdentity(this, HostAdmissionContract.identity(prepared)!!)
        }, targetUid)
}
