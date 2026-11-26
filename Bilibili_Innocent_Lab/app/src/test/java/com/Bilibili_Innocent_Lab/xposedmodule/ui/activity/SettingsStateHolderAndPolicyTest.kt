package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SettingsStateHolderAndPolicyTest {

    private class FakePreferences(
        var commitResult: Boolean = true,
        var failWrite: Boolean = false,
        var simulateStaleReadback: Boolean = false
    ) {
        val data = LinkedHashMap<String, Any?>()

        val prefs: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean" -> {
                    val key = args[0] as String
                    val def = args[1] as Boolean
                    val stored = (data[key] as? Boolean) ?: def
                    if (simulateStaleReadback) !stored else stored
                }
                "getString" -> {
                    val key = args[0] as String
                    val def = args[1] as String?
                    val stored = (data[key] as? String) ?: def
                    if (simulateStaleReadback && stored != null) "$stored-stale" else stored
                }
                "getInt" -> {
                    val key = args[0] as String
                    val def = args[1] as Int
                    val stored = (data[key] as? Int) ?: def
                    if (simulateStaleReadback) stored + 1 else stored
                }
                "edit" -> editorProxy()
                "contains" -> (args[0] as String) in data
                "getAll" -> LinkedHashMap(data)
                else -> null
            }
        } as SharedPreferences

        private fun editorProxy(): SharedPreferences.Editor {
            val staged = LinkedHashMap<String, Any?>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)
            ) { proxy, method, args ->
                when (method.name) {
                    "putBoolean" -> { staged[args[0] as String] = args[1] as Boolean; proxy }
                    "putString" -> { staged[args[0] as String] = args[1] as String?; proxy }
                    "putInt" -> { staged[args[0] as String] = args[1] as Int; proxy }
                    "putLong" -> { staged[args[0] as String] = args[1] as Long; proxy }
                    "apply" -> {
                        if (failWrite) error("apply failed")
                        data.putAll(staged)
                        null
                    }
                    "commit" -> {
                        if (failWrite) error("commit failed")
                        if (commitResult) data.putAll(staged)
                        commitResult
                    }
                    "clear" -> { data.clear(); staged.clear(); proxy }
                    else -> proxy
                }
            } as SharedPreferences.Editor
        }
    }

    @Test
    fun settingWritePolicyVerifiesReadbackAndRollsBackOnAllFailureModes() {
        val fake = FakePreferences()

        val ok = SettingWritePolicy.attemptBoolean(
            preferencesProvider = { fake.prefs },
            key = "k_bool",
            previous = false,
            requested = true,
            verifyReadback = true,
            extraWrites = { putLong("k_rev", 42L) }
        )
        assertTrue(ok.saved)
        assertTrue(ok.confirmed)
        assertNull(ok.failure)
        assertEquals(true, fake.data["k_bool"])
        assertEquals(42L, fake.data["k_rev"])

        // 空 preferences 回滚到 previous 并记录异常。
        val missing = SettingWritePolicy.attemptBoolean(
            preferencesProvider = { null },
            key = "k_bool",
            previous = true,
            requested = false
        )
        assertFalse(missing.saved)
        assertTrue(missing.confirmed)
        assertNotNull(missing.failure)

        // sync commit 返回 false 回滚。
        fake.commitResult = false
        val failedCommit = SettingWritePolicy.attemptBoolean(
            preferencesProvider = { fake.prefs },
            key = "k_bool",
            previous = true,
            requested = false,
            commitSynchronously = true
        )
        assertFalse(failedCommit.saved)
        assertTrue(failedCommit.confirmed)

        // 回读校验不一致时阻止错误确认并回滚。
        fake.commitResult = true
        fake.simulateStaleReadback = true
        val stale = SettingWritePolicy.attemptBoolean(
            preferencesProvider = { fake.prefs },
            key = "k_bool",
            previous = true,
            requested = false,
            verifyReadback = true
        )
        assertFalse(stale.saved)
        assertTrue(stale.confirmed)

        // String 与 Int 边界值（含 Int.MIN_VALUE 哨兵）校验。
        fake.simulateStaleReadback = false
        val sOk = SettingWritePolicy.attemptString({ fake.prefs }, "k_str", "old", "new")
        assertTrue(sOk.saved)
        assertEquals("new", sOk.confirmed)
        val iOk = SettingWritePolicy.attemptInt({ fake.prefs }, "k_int", 0, Int.MIN_VALUE)
        assertTrue(iOk.saved)
        assertEquals(Int.MIN_VALUE, iOk.confirmed)
    }

    @Test
    fun settingsStateHolderUnifiesMirrorsVerificationRollbackAndCallbacks() {
        val fake = FakePreferences()
        val errors = ArrayList<String>()
        val commits = ArrayList<Pair<String, Boolean>>()
        val holder = SettingsStateHolder(
            preferencesProvider = { fake.prefs },
            onWriteError = { message, _ -> errors += message },
            onStateCommitted = { key, saved -> commits += key to saved }
        )

        var mirrorField = false
        holder.registerBooleanMirror(
            key = "feature.sample",
            failureMessage = "write sample failed",
            getter = { mirrorField },
            setter = { mirrorField = it },
            verifyReadback = true
        )

        val enableOutcome = holder.persistRegisteredBoolean("feature.sample", true)!!
        assertTrue(enableOutcome.saved)
        assertTrue(mirrorField)
        assertTrue(holder.readBoolean("feature.sample"))
        assertEquals(listOf("feature.sample" to true), commits)

        // 模拟写入抛异常，镜像字段必须自动回滚为 true。
        fake.failWrite = true
        val failOutcome = holder.persistRegisteredBoolean("feature.sample", false)!!
        assertFalse(failOutcome.saved)
        assertTrue(failOutcome.confirmed)
        assertTrue(mirrorField)
        assertEquals(listOf("write sample failed"), errors)
        assertEquals("feature.sample" to false, commits.last())

        // 同步镜像方法更新字段并反映到状态读口。
        assertTrue(holder.syncMirror("feature.sample", false))
        assertFalse(mirrorField)
        assertFalse(holder.readBoolean("feature.sample"))
        assertFalse(holder.syncMirror("feature.unknown", true))

        // commitCustomBoolean 当二级镜像写入校验失败时也统一回滚。
        fake.failWrite = false
        val customFailed = holder.commitCustomBoolean(
            key = "feature.sample",
            previous = false,
            requested = true,
            failureMessage = "custom mirror write failed"
        ) {
            fake.prefs.edit().putBoolean("feature.sample", true).apply()
            error("atomic mirror write returned false")
        }
        assertFalse(customFailed.saved)
        assertFalse(mirrorField)
        assertFalse(holder.readBoolean("feature.sample"))
        assertEquals("custom mirror write failed", errors.last())
    }
}
