package com.Bilibili_Innocent_Lab.xposedmodule.runtime
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class HostAdmissionContractTest {
    private val values = RemoteHookConfigContract.resolveSourceValues(emptyMap<String, Any>())
    private fun document(generation: Long = 1, decision: UserTermsDecision = UserTermsDecision.ACCEPTED) = RemoteHookConfigContract.encode(
        generation, BuildConfig.VERSION_CODE.toLong(), true, 0L, decision, values)
    fun completeDocumentRoundTripsWithExactLongAndIntTypes() {
        val raw = document(); val decoded = HostAdmissionContract.decode(HostAdmissionContract.encode(raw))
        assertEquals(raw, decoded)
        assertTrue(RemoteHookConfigContract.decode(decoded!!) is RemoteHookConfigDecodeResult.Ready)
    }
    fun truncatedOrDuplicateOrUnknownKeysAreRejected() {
        val raw = HostAdmissionContract.encode(document()); val array = JSONArray(raw)
        array.remove(0); assertNull(HostAdmissionContract.decode(array.toString()))
        val duplicate = JSONArray(raw); duplicate.put(0, duplicate.getJSONObject(1)); assertNull(HostAdmissionContract.decode(duplicate.toString()))
        val unknown = JSONArray(raw); unknown.getJSONObject(0).put("k", "arbitrary_private_key"); assertNull(HostAdmissionContract.decode(unknown.toString()))
        assertNull(HostAdmissionContract.decode("x".repeat(HostAdmissionContract.MAX_DOCUMENT_CHARS+1)))
    }
    fun claimedTypesDoNotCoerceStringsOrFloatingPointNumbers() {
        val raw = HostAdmissionContract.encode(document()); val a=JSONArray(raw)
        a.getJSONObject(0).put("t","l").put("v","1"); assertNull(HostAdmissionContract.decode(a.toString()))
        a.getJSONObject(0).put("v",1.5); assertNull(HostAdmissionContract.decode(a.toString()))
        a.getJSONObject(0).put("t","b").put("v",1); assertNull(HostAdmissionContract.decode(a.toString()))
    }
    fun contentIdentityDoesNotOrderIndependentManagerGenerations() {
        val a=(RemoteHookConfigContract.decode(document(999999)) as RemoteHookConfigDecodeResult.Ready).snapshot
        val b=(RemoteHookConfigContract.decode(document(1)) as RemoteHookConfigDecodeResult.Ready).snapshot
        assertEquals(RemoteHookConfigContract.contentFingerprint(a),RemoteHookConfigContract.contentFingerprint(b))
        val denied=(RemoteHookConfigContract.decode(document(1,UserTermsDecision.DECLINED)) as RemoteHookConfigDecodeResult.Ready).snapshot
        assertNotEquals(RemoteHookConfigContract.contentFingerprint(a),RemoteHookConfigContract.contentFingerprint(denied))
    }

    fun staleProtocolDetectionAllowsDirectFallbackWithoutAcceptingTamperedConfigs() {
        val currentRaw = document()
        assertFalse(HookEntry.isStaleRemoteProtocol(currentRaw))
        assertTrue(HookEntry.isStaleRemoteProtocol(currentRaw + (RemoteHookConfigContract.KEY_SCHEMA_VERSION to 4)))
        if (BuildConfig.VERSION_CODE > 1) {
            assertTrue(HookEntry.isStaleRemoteProtocol(
                currentRaw + (RemoteHookConfigContract.KEY_MODULE_VERSION_CODE to BuildConfig.VERSION_CODE.toLong() - 1L)
            ))
        }
        if (SettingsCatalog.CATALOG_VERSION > 1) {
            assertTrue(HookEntry.isStaleRemoteProtocol(
                currentRaw + (RemoteHookConfigContract.KEY_CATALOG_VERSION to SettingsCatalog.CATALOG_VERSION - 1)
            ))
        }
        // 伪造或超前版本号不能被误判成旧协议。
        assertFalse(HookEntry.isStaleRemoteProtocol(
            currentRaw + (RemoteHookConfigContract.KEY_SCHEMA_VERSION to RemoteHookConfigContract.SCHEMA_VERSION + 1)
        ))
        assertFalse(HookEntry.isStaleRemoteProtocol(
            currentRaw + (RemoteHookConfigContract.KEY_MODULE_VERSION_CODE to BuildConfig.VERSION_CODE.toLong() + 1L)
        ))
        // remote_stale_protocol 仍禁止把过期 Remote Preferences 当作回退，而允许直达握手。
        assertFalse(
            HostAdmissionRouteClassifier.allowRemoteConfigFallback(
                sawDenied = false,
                normalFailure = "remote_stale_protocol",
                authorized = true,
                moduleVersionMatches = true,
                generation = 1L
            )
        )
        assertEquals(
            HostAdmissionContract.DIRECT,
            HostAdmissionSourcePolicy.select(
                noRootSelected = false,
                normalNoRootRevision = 0L,
                expectedFingerprint = "fp",
                normalFingerprint = null,
                allowDirect = true,
                directEnabled = true
            )
        )
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostAdmissionContractTest) -> Unit) {
            val s = HostAdmissionContractTest()
            block(s)
        }
        runIsolated { s -> s.completeDocumentRoundTripsWithExactLongAndIntTypes() }
        runIsolated { s -> s.truncatedOrDuplicateOrUnknownKeysAreRejected() }
        runIsolated { s -> s.claimedTypesDoNotCoerceStringsOrFloatingPointNumbers() }
        runIsolated { s -> s.contentIdentityDoesNotOrderIndependentManagerGenerations() }
        runIsolated { s -> s.staleProtocolDetectionAllowsDirectFallbackWithoutAcceptingTamperedConfigs() }
    }
}
