package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.*
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 显式 lifecycle=checkpoint/unavailable/retained/fresh/restored 的备用机安装序列。
 * 强停、覆盖安装和卸载由外部 ADB 完成；仅 restored 阶段重启宿主，避免掩盖恢复失败。
 * 检查点留在独立测试包私有目录，不备份条款、租约、账号凭据或宿主数据。
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class NPatchEmbeddedLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testPackage = "com.Bilibili_Innocent_Lab.xposedmodule.test"
    private val checkpointFile = "files/npatch-embedded-lifecycle.json"

    @Test fun installedSettingsLifecycleUsesCurrentAuthority() {
        val phase = InstrumentationRegistry.getArguments().getString("lifecycle")
        assumeTrue("Explicit lifecycle phase required", phase != null)
        assertEquals(NPatchMode.EMBEDDED, NPatchRuntimeProfiles.read(context).mode)
        @Suppress("DEPRECATION")
        assertNull("Embedded lifecycle requires no Manager",
            runCatching { context.packageManager.getPackageInfo("top.nkbe.npatch", 0) }.getOrNull())
        when (phase) {
            "checkpoint" -> checkpoint()
            "unavailable" -> {
                assertRetainedPublication()
                assertRejectedHost(HostAdmissionRouteClassifier.REASON_UNAVAILABLE)
            }
            "retained" -> retained()
            "fresh" -> fresh()
            "restored" -> restored()
            else -> fail("Unknown lifecycle phase")
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nEmbedded lifecycle: phase=$phase passed; current identity checked\n")
        })
    }

    private fun checkpoint() {
        val publication = requireNotNull(PublicationAuthorityStore.current(context))
        assertTrue(publication.decision.isAuthorized)
        assertTrue(NoRootSupportStore.isDesiredEnabled(context))
        assertCurrentHost()
        val store = ModuleSettingsStore(context.modulePreferences())
        val backup = SettingsBackupFactory.createDocument(store,
            BackupSource(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.APPLICATION_ID))
        val record = JSONObject()
            .put("identity", HostAdmissionContract.identityJson(publication.identity))
            .put("noRootRevision", publication.noRootRevision)
            .put("backup", SettingsBackupCodec.encode(backup))
        // Shell 以测试包 UID 写入；使用标准输入传数据，不能把备份放进命令行参数。
        shell("run-as $testPackage mkdir -p files")
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("run-as $testPackage sh -c cat>$checkpointFile")
        val raw = record.toString()
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        assertEquals(record.toString(), readCheckpoint().toString())
    }

    private fun retained() {
        assertRetainedPublication()
        assertCurrentHost()
    }

    private fun assertRetainedPublication() {
        val record = readCheckpoint()
        val expected = requireNotNull(HostAdmissionContract.identityJson(record.getJSONObject("identity")))
        val publication = requireNotNull(PublicationAuthorityStore.current(context))
        assertEquals("Preserved installation must retain exact publication identity", expected, publication.identity)
        assertEquals(record.getLong("noRootRevision"), publication.noRootRevision)
        assertTrue(publication.decision.isAuthorized)
        assertTrue(NoRootSupportStore.isDesiredEnabled(context))
    }

    private fun fresh() {
        val previous = requireNotNull(HostAdmissionContract.identityJson(readCheckpoint().getJSONObject("identity")))
        assertEquals(UserTermsDecision.UNDECIDED, UserTermsConsentStore.readOrInitialize(context))
        assertFalse(NoRootSupportStore.isDesiredEnabled(context))
        val publication = requireNotNull(PublicationAuthorityStore.current(context))
        assertNotEquals("Reinstall must not resurrect old authority", previous.incarnation, publication.identity.incarnation)
        assertRejectedHost()
    }

    private fun assertRejectedHost(reason: String? = null) {
        val bootstrap = awaitHost { it.bootstrap.configState in setOf(HostConfigState.REJECTED, HostConfigState.NOT_AUTHORIZED) }.bootstrap
        assertEquals(HostInstallChainState.NOT_STARTED, bootstrap.installChainState)
        assertEquals(0, bootstrap.hookPointInstalledCount)
        assertNull(bootstrap.admission)
        if (reason != null) assertEquals(reason, bootstrap.configReasonCode)
    }

    private fun restored() {
        val record = readCheckpoint()
        val previous = requireNotNull(HostAdmissionContract.identityJson(record.getJSONObject("identity")))
        assertEquals(UserTermsDecision.UNDECIDED, UserTermsConsentStore.readOrInitialize(context))
        assertFalse(NoRootSupportStore.isDesiredEnabled(context))
        val backup = SettingsBackupCodec.decode(record.getString("backup").toByteArray(Charsets.UTF_8))
        val store = ModuleSettingsStore(context.modulePreferences())
        val plan = SettingsImportPlanner().plan(backup, store.snapshot())
        val result = SettingsImportApplier(context, store).apply(plan)
        assertTrue("Restore failed: $result", result is SettingsApplyResult.Success || result == SettingsApplyResult.NothingToApply)
        // 导入业务设置不能恢复授权、条款或免 Root 开关。
        assertEquals(UserTermsDecision.UNDECIDED, UserTermsConsentStore.readOrInitialize(context))
        assertFalse(NoRootSupportStore.isDesiredEnabled(context))
        val after = store.snapshot()
        backup.settings.filter { it.explicit && SettingsCatalog.byId[it.id]?.restorePolicy == RestorePolicy.AUTOMATIC }
            .forEach { assertEquals("Setting not restored: ${it.id}", it.value, after[it.id]?.value) }
        assertTrue(UserTermsConsentStore.writeDecision(context, UserTermsDecision.ACCEPTED))
        assertTrue(NoRootSupportController.setDesiredEnabled(context, true))
        assertNotEquals(previous.incarnation, PublicationAuthorityStore.current(context)?.identity?.incarnation)
        shell("am force-stop tv.danmaku.bili")
        shell("am start -n tv.danmaku.bili/.MainActivityV2")
        assertCurrentHost()
        assertEquals(NPatchEmbeddedBuildState.MATCHED, NPatchEmbeddedBuild.read(context))
    }

    private fun assertCurrentHost() {
        val current = requireNotNull(PublicationAuthorityStore.current(context))
        val snapshot = awaitHost { it.bootstrap.installChainState == HostInstallChainState.COMPLETED }
        assertEquals(HostConfigState.ACCEPTED, snapshot.bootstrap.configState)
        assertEquals(HostAdmissionContract.DIRECT, snapshot.bootstrap.configSource)
        assertEquals(current.identity, snapshot.bootstrap.admission)
        assertEquals("Publication changed during query", current.identity, PublicationAuthorityStore.current(context)?.identity)
        assertTrue(snapshot.bootstrap.hookPointInstalledCount > 0)
    }

    private fun readCheckpoint(): JSONObject {
        val raw = shell("run-as $testPackage cat $checkpointFile").trim()
        require(raw.length in 1..1_000_000)
        return JSONObject(raw)
    }

    private fun awaitHost(accept: (HostRuntimeDiagnosticsSnapshot) -> Boolean): HostRuntimeDiagnosticsSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        var last: HostRuntimeDiagnosticsQueryClient.Result? = null
        while (System.nanoTime() < deadline) {
            val done = CountDownLatch(1)
            val response = AtomicReference<HostRuntimeDiagnosticsQueryClient.Result>()
            HostRuntimeDiagnosticsQueryClient.query(context) { response.set(it); done.countDown() }
            if (done.await(5, TimeUnit.SECONDS)) {
                last = response.get()
                last?.snapshot?.takeIf(accept)?.let { return it }
            }
            Thread.sleep(500)
        }
        fail("Current host did not reach expected lifecycle state: ${last?.status}/${last?.failure}/${last?.snapshot?.bootstrap?.configState}")
        error("unreachable")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
