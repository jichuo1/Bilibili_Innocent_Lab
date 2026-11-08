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
                FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS to true,
                FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND to true,
                FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC to true,
                FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS to false,
                FeaturePreferences.PLAYER_UNLOCK_BACKGROUND to true,
                FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW to true,
                FeaturePreferences.PLAYER_UNLOCK_CAST to true,
                FeaturePreferences.PLAYER_DISABLE_LONG_PRESS to true,
                FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT to 275,
                FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT to 125,
                RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION to 42L,
                RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP to 84L,
                RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY to "  jev-test-key  "
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
        assertEquals(true, snapshot.values[FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS])
        assertEquals(true, snapshot.values[FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND])
        assertEquals(false, defaultValues()[FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND])
        assertEquals(false, defaultValues()[FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS])
        assertEquals(true, snapshot.values[FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC])
        assertEquals(false, snapshot.values[FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS])
        assertEquals(false, defaultValues()[FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC])
        assertEquals(false, defaultValues()[FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS])
        assertEquals(false, defaultValues()[FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP])
        assertEquals(true, snapshot.values[FeaturePreferences.PLAYER_UNLOCK_BACKGROUND])
        assertEquals(true, snapshot.values[FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW])
        assertEquals(true, snapshot.values[FeaturePreferences.PLAYER_UNLOCK_CAST])
        assertEquals(true, snapshot.values[FeaturePreferences.PLAYER_DISABLE_LONG_PRESS])
        assertEquals(275, snapshot.values[FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT])
        assertEquals(125, snapshot.values[FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT])
        assertTrue(RemoteHookConfigContract.decode(encoded - FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT)
            is RemoteHookConfigDecodeResult.Invalid)
        assertEquals("jev-test-key", snapshot.values[RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY])
        // 目录外只有两个运行时修订字段和全部运行时凭据（判定来源 + AI Insight），不能遗漏或多导出其它偏好。
        val runtimeKeys = setOf(RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION,
            RemoteHookConfigContract.KEY_ADAPTER_RESET_TIMESTAMP) + RemoteHookConfigContract.RUNTIME_API_KEYS
        assertEquals(runtimeKeys, snapshot.values.keys - SettingsCatalog.specs.map { it.storageKey }.toSet())
        assertEquals(SettingsCatalog.specs.size + 2 + RemoteHookConfigContract.RUNTIME_API_KEYS.size, snapshot.values.size)
    }

    /** JEV API Key：随 hook_config 下发且受摘要保护，但不是目录项（不进备份），超长/非字符串按未配置处理。 */
    @Test
    fun `jev api key is a signed runtime credential outside the backup catalog`() {
        val key = RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY
        assertTrue(key in RemoteHookConfigContract.hookValueKeys)
        assertTrue(SettingsCatalog.specs.none { it.storageKey == key })
        assertEquals("", defaultValues()[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(mapOf(key to 42))[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(
            mapOf(key to "x".repeat(RemoteHookConfigContract.MAX_SEMANTIC_JEV_API_KEY_LENGTH + 1)))[key])

        val encoded = RemoteHookConfigContract.encode(
            generation = 1L,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = true,
            noRootRevision = 0L,
            decision = UserTermsDecision.ACCEPTED,
            values = RemoteHookConfigContract.resolveSourceValues(mapOf(key to "secret"))
        )
        // 换 Key 不重签 ⇒ 摘要不符，宿主整组拒收（与其它值一样 fail-closed）。
        assertTrue(RemoteHookConfigContract.decode(encoded + (key to "other")) is RemoteHookConfigDecodeResult.Invalid)
        assertTrue(RemoteHookConfigContract.decode(encoded + (key to 7)) is RemoteHookConfigDecodeResult.Invalid)
    }

    @Test
    fun `source resolution rejects arbitrary keys and repairs invalid values`() {
        val resolved = RemoteHookConfigContract.resolveSourceValues(
            mapOf(
                "private_token" to "must-not-leak",
                HookEntry.PREF_FREE_COPY_ENABLED to "wrong-type",
                FeaturePreferences.COMMENT_MIN_LEVEL to 999,
                FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT to 999,
                FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT to Float.NaN,
                RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION to -1L
            )
        )

        assertEquals(RemoteHookConfigContract.hookValueKeys, resolved.keys)
        assertFalse("private_token" in resolved)
        assertEquals(true, resolved[HookEntry.PREF_FREE_COPY_ENABLED])
        assertEquals(6, resolved[FeaturePreferences.COMMENT_MIN_LEVEL])
        assertEquals(0, resolved[FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT])
        assertEquals(0, resolved[FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT])
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

    /** 2–8 号判定来源的 Key 与 1 号同等对待：签名保护、不进目录、超长按未配置。 */
    @Test
    fun `extra semantic source keys are signed runtime credentials too`() {
        assertEquals(8, RemoteHookConfigContract.SEMANTIC_API_KEYS.size)
        assertEquals(RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY, RemoteHookConfigContract.semanticApiKey(1))
        RemoteHookConfigContract.SEMANTIC_API_KEYS.drop(1).forEach { key ->
            assertTrue(key in RemoteHookConfigContract.hookValueKeys)
            assertTrue(SettingsCatalog.specs.none { it.storageKey == key })
            assertEquals("", defaultValues()[key])
            assertEquals("", RemoteHookConfigContract.resolveSourceValues(
                mapOf(key to "x".repeat(RemoteHookConfigContract.MAX_SEMANTIC_JEV_API_KEY_LENGTH + 1)))[key])
            val encoded = RemoteHookConfigContract.encode(
                generation = 1L,
                moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
                deliveryEnabled = true,
                noRootRevision = 0L,
                decision = UserTermsDecision.ACCEPTED,
                values = RemoteHookConfigContract.resolveSourceValues(mapOf(key to "secret"))
            )
            val decoded = RemoteHookConfigContract.decode(encoded)
            assertEquals("secret", (decoded as RemoteHookConfigDecodeResult.Ready).snapshot.values[key])
            assertTrue(RemoteHookConfigContract.decode(encoded + (key to "other")) is RemoteHookConfigDecodeResult.Invalid)
        }
    }

    /** AI Insight 的 Key 与判定来源 Key 同等对待：签名保护、不进目录、超长或非字符串按未配置处理。 */
    @Test
    fun `ai insight api key is a signed runtime credential outside the backup catalog`() {
        val key = RemoteHookConfigContract.KEY_AI_INSIGHT_API_KEY
        assertTrue(key in RemoteHookConfigContract.hookValueKeys)
        assertTrue(key in RemoteHookConfigContract.RUNTIME_API_KEYS)
        assertTrue(SettingsCatalog.specs.none { it.storageKey == key })
        assertEquals("", defaultValues()[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(mapOf(key to 42))[key])
        assertEquals("", RemoteHookConfigContract.resolveSourceValues(
            mapOf(key to "x".repeat(RemoteHookConfigContract.MAX_SEMANTIC_JEV_API_KEY_LENGTH + 1)))[key])
        assertEquals("secret", RemoteHookConfigContract.resolveSourceValues(mapOf(key to "  secret  "))[key])
        val encoded = RemoteHookConfigContract.encode(
            generation = 1L,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            deliveryEnabled = true,
            noRootRevision = 0L,
            decision = UserTermsDecision.ACCEPTED,
            values = RemoteHookConfigContract.resolveSourceValues(mapOf(key to "secret"))
        )
        assertEquals("secret", (RemoteHookConfigContract.decode(encoded)
                as RemoteHookConfigDecodeResult.Ready).snapshot.values[key])
        // 改 Key 不重签 ⇒ 摘要不符，宿主整组拒收（与其它运行时凭据一样 fail-closed）。
        assertTrue(RemoteHookConfigContract.decode(encoded + (key to "other")) is RemoteHookConfigDecodeResult.Invalid)
    }
}
