package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 直接执行生产保存屏障，所有文件及偏好均隔离，不改测试机用户的开关或授权。 */
@RunWith(AndroidJUnit4::class)
class NoRootDisabledRecoveryInstrumentedTest {
    @Test fun missingSnapshotIsRebuiltAndRepeatedSavesKeepTheSameRevision() = Fixture().use { f ->
        assertEquals(NoRootSupportController.FlushResult.SUCCESS, f.flush())
        val repaired = requireNotNull(NoRootSupportStore.readSnapshot(f.context))
        assertFalse(repaired.enabled)
        assertTrue(repaired.values.isEmpty())
        assertEquals(BuildConfig.VERSION_CODE.toLong(), repaired.moduleVersionCode)
        assertEquals(NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED.name, f.state.getString("sync_state", null))
        assertEquals(NoRootSupportController.FlushResult.SUCCESS, f.flush())
        assertEquals(repaired, NoRootSupportStore.readSnapshot(f.context))
    }

    @Test fun upgradedTombstonePreservesRevisionBoundsAndPendingDisableEvidence() = Fixture().use { f ->
        val old = f.oldSnapshot(System.currentTimeMillis() + 60_000)
        f.write(old)
        assertTrue(f.state.edit().putLong("sync_revision", old.revision + 10)
            .putLong("adapter_reset_revision", 7).putBoolean("disable_was_active", true)
            .putString("sync_state", NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED.name).commit())
        assertNull(NoRootSupportStore.readSnapshot(f.context))
        assertEquals(NoRootSupportController.FlushResult.SUCCESS, f.flush())
        val repaired = requireNotNull(NoRootSupportStore.readSnapshot(f.context))
        assertTrue(repaired.revision > old.revision + 10)
        assertEquals(7L, repaired.adapterResetRevision)
        val status = NoRootSupportStore.readStatus(f.context)
        assertEquals(repaired.revision, status.syncRevision)
        assertTrue(status.disableWasActive)
        assertEquals(NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED.name, f.state.getString("sync_state", null))
        assertFalse(NoRootSupportStore.acceptsDisabledAck(repaired, old.revision, old.moduleVersionCode))
        assertTrue(NoRootSupportStore.acceptsDisabledAck(repaired, repaired.revision, BuildConfig.VERSION_CODE.toLong()))
    }

    @Test fun confirmedCurrentTombstoneDoesNotRequireAnotherDisableAcknowledgement() = Fixture().use { f ->
        val current = f.oldSnapshot(42).copy(moduleVersionCode = BuildConfig.VERSION_CODE.toLong())
        f.write(current)
        assertTrue(f.state.edit().putLong("sync_revision", 42)
            .putString("sync_state", NoRootSupportStore.SyncState.DISABLED.name).commit())
        assertEquals(NoRootSupportController.FlushResult.SUCCESS, f.flush())
        assertEquals(current, NoRootSupportStore.readSnapshot(f.context))
        assertEquals(NoRootSupportStore.SyncState.DISABLED, NoRootSupportStore.readStatus(f.context).syncState)
    }

    @Test fun oldEnabledValuesAndCorruptFilesCannotRestoreEnabledConfiguration() = Fixture().use { f ->
        f.write(f.oldSnapshot(42).copy(enabled = true, values = mapOf("feature" to true)))
        assertEquals(NoRootSupportController.FlushResult.SUCCESS, f.flush())
        assertTrue(requireNotNull(NoRootSupportStore.readSnapshot(f.context)).values.isEmpty())
        f.file.writeText("broken snapshot")
        assertEquals(NoRootSupportController.FlushResult.SUCCESS, f.flush())
        val repaired = requireNotNull(NoRootSupportStore.readSnapshot(f.context))
        assertFalse(repaired.enabled)
        assertTrue(repaired.values.isEmpty())
    }

    @Test fun enabledUserIntentCannotBeReplacedEvenWhenAnotherCarrierMasksIt() = Fixture().use { f ->
        val old = f.oldSnapshot(42)
        f.write(old)
        assertTrue(NoRootSupportStore.setDesiredEnabled(f.context, true))
        assertNull(NoRootSupportStore.ensureDisabledSnapshot(f.context))
        assertEquals(NoRootConfigSnapshotCodec.encode(old), f.file.readText())
        assertTrue(f.state.getBoolean("desired_enabled", false))
    }

    @Test fun writeFailureAndExhaustedRevisionNeverReportSaveSuccess() = Fixture().use { f ->
        assertTrue(f.file.mkdir())
        // AtomicFile 可以替换空目录；非空目录才能确实阻断最终文件的原子替换。
        val blocker = File(f.file, "blocker").apply { writeText("keep") }
        assertEquals(NoRootSupportController.FlushResult.FAILED, f.flush())
        assertTrue(blocker.delete())
        assertTrue(f.file.delete())
        f.write(f.oldSnapshot(Long.MAX_VALUE))
        assertEquals(NoRootSupportController.FlushResult.FAILED, f.flush())
        assertNull(NoRootSupportStore.readSnapshot(f.context))
    }

    private class Fixture : Closeable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        private val prefix = "disabled_recovery_${UUID.randomUUID()}_"
        private val directory = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        private val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = prefix + name
                names += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val state: SharedPreferences = context.getSharedPreferences(NoRootSupportStore.PREF_FILE, Context.MODE_PRIVATE)
        val file = File(directory, "no_root_hook_config.json")
        fun oldSnapshot(revision: Long) = NoRootConfigSnapshot(NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
            SettingsCatalog.CATALOG_VERSION, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong() - 1,
            revision, 0, false, emptyMap())
        fun write(snapshot: NoRootConfigSnapshot) = file.writeText(NoRootConfigSnapshotCodec.encode(snapshot))
        fun flush(): NoRootSupportController.FlushResult {
            val done = CountDownLatch(1)
            val result = AtomicReference<NoRootSupportController.FlushResult>()
            NoRootSupportController.flushBeforeRestart(context, context.getSharedPreferences("bridge", 0)) {
                result.set(it); done.countDown()
            }
            assertTrue("Save callback did not finish", done.await(6, TimeUnit.SECONDS))
            return result.get()
        }
        override fun close() {
            File(file, "blocker").takeIf { it.isFile }?.let { check(it.delete()) }
            directory.listFiles().orEmpty().forEach { check(it.delete()) }
            check(directory.delete())
            names.forEach { base.deleteSharedPreferences(it) }
        }
    }
}
