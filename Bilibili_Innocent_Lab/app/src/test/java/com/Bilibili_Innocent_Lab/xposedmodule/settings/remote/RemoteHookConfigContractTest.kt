package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHookConfigContractTest {

    @Test
    fun `complete API 102 config round trips all whitelisted values`() {
        val values = RemoteHookConfigContract.resolveSourceValues(
            mapOf(
                HookEntry.PREF_FREE_COPY_ENABLED to false,
                FeaturePreferences.COMMENT_MIN_LEVEL to 5,
                FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP to true,
                RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION to 42L,
                RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to 84L
            )
        )
        val encoded = RemoteHookConfigContract.encode(
            generation = 123L,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = true,
            noRootRevision = 77L,
            decision = UserTermsDecision.ACCEPTED,
            values = values
        )
        val decoded = RemoteHookConfigContract.decode(encoded)

        assertEquals(RemoteHookConfigContract.persistedKeys, encoded.keys)
        assertTrue(decoded is RemoteHookConfigDecodeResult.Ready)
        val snapshot = (decoded as RemoteHookConfigDecodeResult.Ready).snapshot
        assertEquals(123L, snapshot.generation)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), snapshot.moduleVersionCode)
        assertTrue(snapshot.deliveryEnabled)
        assertEquals(77L, snapshot.noRootRevision)
        assertTrue(snapshot.authorized)
        assertEquals(values, snapshot.values)
        assertEquals(true, snapshot.values[FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP])
        assertEquals(false, defaultValues()[FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP])
        assertEquals(SettingsCatalog.specs.size + 2, snapshot.values.size)
    }

    @Test
    fun `source resolution rejects arbitrary keys and repairs invalid values`() {
        val resolved = RemoteHookConfigContract.resolveSourceValues(
            mapOf(
                "private_token" to "must-not-leak",
                HookEntry.PREF_FREE_COPY_ENABLED to "wrong-type",
                FeaturePreferences.COMMENT_MIN_LEVEL to 999,
                RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION to -1L
            )
        )

        assertEquals(RemoteHookConfigContract.hookValueKeys, resolved.keys)
        assertFalse("private_token" in resolved)
        assertEquals(true, resolved[HookEntry.PREF_FREE_COPY_ENABLED])
        assertEquals(6, resolved[FeaturePreferences.COMMENT_MIN_LEVEL])
        assertEquals(0L, resolved[RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION])
    }

    @Test
    fun `terms and tamper validation fail closed`() {
        UserTermsDecision.entries.forEach { decision ->
            val decoded = RemoteHookConfigContract.decode(
                encode(decision = decision)
            ) as RemoteHookConfigDecodeResult.Ready
            assertEquals(decision.isAuthorized, decoded.snapshot.authorized)
        }
        val disabled = RemoteHookConfigContract.decode(
            encode(decision = UserTermsDecision.ACCEPTED, deliveryEnabled = false)
        ) as RemoteHookConfigDecodeResult.Ready
        assertFalse(disabled.snapshot.authorized)

        val encoded = encode(generation = 7L)
        assertInvalid(encoded.toMutableMap().apply {
            put(HookEntry.PREF_FREE_COPY_ENABLED, false)
        }, "digest")
        assertInvalid(encoded.toMutableMap().apply {
            put("unknown", true)
        }, "key-set")
    }

    @Test
    fun `encoder rejects incomplete values and nonpositive generations`() {
        val incomplete = defaultValues().toMutableMap().apply {
            remove(HookEntry.PREF_FREE_COPY_ENABLED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encode(values = incomplete)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encode(generation = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encode(noRootRevision = -1L)
        }
    }

    @Test
    fun `module version and delivery metadata fail closed`() {
        val encoded = encode(generation = 9L, noRootRevision = 15L)
        assertInvalid(encoded.toMutableMap().apply {
            put(
                RemoteHookConfigContract.KEY_MODULE_VERSION_CODE,
                BuildConfig.VERSION_CODE.toLong() + 1L
            )
        }, "module-version")
        assertInvalid(encoded.toMutableMap().apply {
            put(RemoteHookConfigContract.KEY_DELIVERY_ENABLED, false)
        }, "digest")
        assertInvalid(encoded.toMutableMap().apply {
            put(RemoteHookConfigContract.KEY_NO_ROOT_REVISION, -1L)
        }, "no-root-revision")
    }

    private fun defaultValues(): Map<String, Any> =
        RemoteHookConfigContract.resolveSourceValues(emptyMap<String, Any>())

    private fun encode(
        generation: Long = 1L,
        moduleVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
        deliveryEnabled: Boolean = true,
        noRootRevision: Long = 0L,
        decision: UserTermsDecision = UserTermsDecision.ACCEPTED,
        values: Map<String, Any> = defaultValues()
    ): Map<String, Any> = RemoteHookConfigContract.encode(
        generation = generation,
        moduleVersionCode = moduleVersionCode,
        deliveryEnabled = deliveryEnabled,
        noRootRevision = noRootRevision,
        decision = decision,
        values = values
    )

    private fun assertInvalid(values: Map<String, *>, reasonPrefix: String) {
        val decoded = RemoteHookConfigContract.decode(values)
        assertTrue(decoded is RemoteHookConfigDecodeResult.Invalid)
        assertTrue(
            (decoded as RemoteHookConfigDecodeResult.Invalid).reason,
            decoded.reason.startsWith(reasonPrefix)
        )
    }
}
