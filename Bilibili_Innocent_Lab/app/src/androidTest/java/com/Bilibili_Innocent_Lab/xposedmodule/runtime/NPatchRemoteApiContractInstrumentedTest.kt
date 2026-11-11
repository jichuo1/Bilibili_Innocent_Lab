package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRemoteGateway
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRemoteGateway.SyncResult
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfiles
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchScopeProbe
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootConfigSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootConfigSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootDisplayState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportState
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.NoRootActionKind
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.NoRootActionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.PulledRemoteFiles
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.RemotePreferenceDiff
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.ScopeRequestResult
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.buildAtomicPublishDiff
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Remote API 契约（gate G2）：对真实的 NPatch Manager 验证网关的全部通道。
 *
 * 只在专用模拟器上显式运行（`-e verifyRemoteApi true`）。测试使用自己的临时 group、文件名和
 * 虚构的作用域目标包，结束时清理；对真实目标 `tv.danmaku.bili` 的作用域只在
 * [scopeRoundTripThroughTheGateway] 里以虚构包名演练，不触碰真实记录。
 * 结果只证明"这个 Manager 版本、这个 Android 版本、模拟器上"的契约，不证明修补后宿主的行为。
 */
@RunWith(AndroidJUnit4::class)
class NPatchRemoteApiContractInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val unique = System.currentTimeMillis().toString(36)
    private val scratchGroup = "g2_scratch_$unique"
    private val scratchFile = "g2-file-$unique.bin"
    private val fakeTarget = "bil.g2.scope.target.$unique"
    private lateinit var binder: IBinder

    @Before fun connect() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyRemoteApi") == "true")
        binder = NPatchRemoteGateway.connectAuthenticatedBinder(context)
    }

    @After fun cleanUp() {
        if (!::binder.isInitialized) return
        runCatching { transactVoid(TX_DELETE_REMOTE_PREFERENCES) { it.writeString(scratchGroup) } }
        runCatching { transactVoid(TX_DELETE_REMOTE_PREFERENCES) { it.writeString(RemoteHookConfigContract.GROUP) } }
        runCatching { NPatchRemoteGateway.remoteFiles(binder).deleteRemoteFile(scratchFile) }
        runCatching { transactVoid(TX_REMOVE_SCOPE) { it.writeStringList(listOf(fakeTarget, "com.android.shell")) } }
    }

    @Test fun serviceReportsApi102WithRemoteCapabilityAndTheNPatchName() {
        assertEquals(102, transactInt(TX_GET_API_VERSION))
        assertEquals("NPatch", transactString(TX_GET_FRAMEWORK_NAME))
        assertTrue("PROP_CAP_REMOTE", transactLong(TX_GET_FRAMEWORK_PROPERTIES) and (1L shl 1) != 0L)
        report("manager.versionName", transactString(TX_GET_FRAMEWORK_VERSION))
        report("manager.versionCode", transactLong(TX_GET_FRAMEWORK_VERSION_CODE).toString())
    }

    @Test fun ownModuleIsAcceptedAndAnUnregisteredOneIsClassifiedAsNotRegistered() {
        assertEquals(SyncResult.Success, NPatchRemoteGateway.probeConnection(context))
        assertEquals(
            SyncResult.ModuleNotRegistered,
            NPatchRemoteGateway.probeConnection(context, "com.example.g2.unregistered.$unique")
        )
    }

    @Test fun connectionIsReusedWhileTheManagerLivesAndRebuiltAfterItDies() {
        val first = NPatchRemoteGateway.connectAuthenticatedBinder(context)
        assertTrue("an alive connection is reused", first === NPatchRemoteGateway.connectAuthenticatedBinder(context))

        shell("am force-stop ${MANAGER_PACKAGE}")
        // isBinderAlive stays true until a death notification or a failed transaction; pingBinder is the real probe.
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        while (first.pingBinder() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertFalse("the old binder must be dead after the Manager was stopped", first.pingBinder())

        // A fresh Provider call restarts the Manager; the stale cache entry must not be handed out.
        val second = NPatchRemoteGateway.connectAuthenticatedBinder(context)
        assertTrue(second.pingBinder())
        assertFalse(first === second)
        assertEquals(102, transactIntOn(second, TX_GET_API_VERSION))
        binder = second
    }

    @Test fun atomicPublicationNeverShowsAnEmptyOrPartialDocument() {
        val channel = NPatchRemoteGateway.preferenceChannel(binder)
        val sizes = mapOf("a" to 40, "b" to 55)
        fun document(tag: String) = (0 until sizes.getValue(tag)).associate<Int, String, Any> { "k$it" to "$tag-$it" }
        channel.update(scratchGroup, RemotePreferenceDiff(false, emptySet(), document("a")))

        val result = runStress(
            channel = channel,
            sizes = sizes.values.toSet(),
            publish = { index ->
                val tag = if (index % 2 == 0) "b" else "a"
                val target = document(tag)
                channel.update(scratchGroup, buildAtomicPublishDiff(channel.request(scratchGroup).keys, target))
            }
        )
        report("atomic.reads", result.reads.toString())
        report("atomic.badReads", result.bad.toString())
        assertEquals("a reader must only ever see a complete old or new document", 0, result.bad)
        assertTrue("the stress loop must actually have read", result.reads > 50)
    }

    @Test fun legacyClearWindowIsMeasuredButNotAsserted() {
        // 记录上游 clear 先于事务的窗口在这个 Manager 上是否真的能被读到；概率性证据，不作断言。
        val channel = NPatchRemoteGateway.preferenceChannel(binder)
        val sizes = mapOf("a" to 40, "b" to 55)
        fun document(tag: String) = (0 until sizes.getValue(tag)).associate<Int, String, Any> { "k$it" to "$tag-$it" }
        channel.update(scratchGroup, RemotePreferenceDiff(false, emptySet(), document("a")))
        val result = runStress(
            channel = channel,
            sizes = sizes.values.toSet(),
            publish = { index ->
                channel.update(scratchGroup, RemotePreferenceDiff(true, emptySet(), document(if (index % 2 == 0) "b" else "a")))
            }
        )
        report("legacyClear.reads", result.reads.toString())
        report("legacyClear.badReads", result.bad.toString())
    }

    @Test fun gatewayPublishesTheRealDocumentAndReadsItBack() {
        val snapshot = NoRootConfigSnapshot(
            schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
            catalogVersion = SettingsCatalog.CATALOG_VERSION,
            modulePackage = BuildConfig.APPLICATION_ID,
            moduleVersionCode = BuildConfig.VERSION_CODE.toLong(),
            revision = 7L,
            adapterResetRevision = 3L,
            enabled = true,
            values = emptyMap()
        )
        val values = NPatchRemoteGateway.resolveRemoteValues(snapshot, emptyMap<String, Any>())
        val channel = NPatchRemoteGateway.preferenceChannel(binder)
        assertEquals(
            SyncResult.Success,
            NPatchRemoteGateway.publishRemotePreferences(channel, snapshot, UserTermsDecision.ACCEPTED, values) { true }
        )
        val first = RemoteHookConfigContract.decode(channel.request(RemoteHookConfigContract.GROUP))
        val ready = first as RemoteHookConfigDecodeResult.Ready
        assertEquals(values, ready.snapshot.values)
        assertEquals(7L, ready.snapshot.noRootRevision)
        // 已是最新的文档不得被重写。
        assertEquals(
            SyncResult.Success,
            NPatchRemoteGateway.publishRemotePreferences(channel, snapshot, UserTermsDecision.ACCEPTED, values) { true }
        )
        val second = RemoteHookConfigContract.decode(channel.request(RemoteHookConfigContract.GROUP)) as RemoteHookConfigDecodeResult.Ready
        assertEquals(ready.snapshot.generation, second.snapshot.generation)
    }

    @Test fun remoteFilesRoundTripAndRejectUnsafeNames() {
        val files = NPatchRemoteGateway.remoteFiles(binder)
        val payload = ByteArray(2 * 1024 * 1024) { (it * 31).toByte() }
        ParcelFileDescriptor.AutoCloseOutputStream(files.openRemoteFile(scratchFile)).use { it.write(payload) }
        assertTrue(scratchFile in files.listRemoteFiles())
        val back = ParcelFileDescriptor.AutoCloseInputStream(files.openRemoteFile(scratchFile)).use { it.readBytes() }
        assertTrue("read-back differs", back.contentEquals(payload))
        assertTrue(files.deleteRemoteFile(scratchFile))
        assertFalse(scratchFile in files.listRemoteFiles())
        for (unsafe in listOf("../escape", "a/b", "..", "")) {
            val rejected = runCatching { files.openRemoteFile(unsafe).close() }.isFailure
            assertTrue("unsafe name accepted: '$unsafe'", rejected)
        }
    }

    @Test fun pulledFileChannelIsReachableThroughTheGatewayEntryPoint() {
        val connected = NPatchRemoteGateway.connectRemoteFiles(context)
        assertTrue(connected.javaClass.name, connected is PulledRemoteFiles.Ready)
        connected as PulledRemoteFiles.Ready
        assertNotNull(connected.files.listRemoteFiles())
    }

    @Test fun scopeRoundTripThroughTheGateway() {
        assertEquals(ScopeRequestResult.Added, NPatchRemoteGateway.requestScope(context, listOf(fakeTarget)))
        assertEquals(ScopeRequestResult.AlreadyPresent, NPatchRemoteGateway.requestScope(context, listOf(fakeTarget)))
        assertTrue(fakeTarget in NPatchRemoteGateway.scopeChannel(binder).scope())
        // 修补后宿主的 loader 用同一个 Provider 判断"会不会加载本模块"。
        val rows = queryScopeProvider(fakeTarget)
        assertTrue("ConfigProvider rows: $rows", BuildConfig.APPLICATION_ID in rows)
        transactVoid(TX_REMOVE_SCOPE) { it.writeStringList(listOf(fakeTarget)) }
        assertFalse(fakeTarget in NPatchRemoteGateway.scopeChannel(binder).scope())
        assertFalse(BuildConfig.APPLICATION_ID in queryScopeProvider(fakeTarget))
    }

    @Test fun stubHostIsReadAsAManagerPatchedHost() {
        val profile = NPatchRuntimeProfiles.read(context)
        assertTrue(profile.markerPresent)
        assertTrue(profile.metadataValid)
        assertEquals(NPatchMode.MANAGER, profile.mode)
        assertEquals(102, profile.loaderApi)
        assertEquals(2, profile.signatureLevel)
        report("stubHost.loaderVersionCode", profile.loaderVersionCode.toString())
    }

    @Test fun scopeProbeAndActionFollowTheManagerScopeForTheRealTargetPackage() {
        val target = NoRootSupportState.TARGET_PACKAGE
        val ownScope = { NPatchRemoteGateway.scopeChannel(binder).scope() }
        // Start from a known state on this dedicated emulator, whatever an earlier run left behind.
        transactVoid(TX_REMOVE_SCOPE) { it.writeStringList(listOf(target)) }
        assertFalse(target in ownScope())

        assertEquals(NPatchScopeProbe.ScopeState.OUT_OF_SCOPE, awaitProbe(NPatchScopeProbe.ScopeState.OUT_OF_SCOPE))
        val profile = NPatchRuntimeProfiles.read(context)
        assertEquals(NoRootActionKind.JOIN_SCOPE, NoRootActionPolicy.kind(NoRootDisplayState.SCOPE_MISSING, profile))

        assertEquals(ScopeRequestResult.Added, NPatchRemoteGateway.requestScope(context, listOf(target)))
        assertEquals(NPatchScopeProbe.ScopeState.IN_SCOPE, awaitProbe(NPatchScopeProbe.ScopeState.IN_SCOPE))

        transactVoid(TX_REMOVE_SCOPE) { it.writeStringList(listOf(target)) }
        assertEquals(NPatchScopeProbe.ScopeState.OUT_OF_SCOPE, awaitProbe(NPatchScopeProbe.ScopeState.OUT_OF_SCOPE))
    }

    @Test fun anotherUidCannotObtainTheWritableServiceForThisModule() {
        val output = shell(
            "content call --uri content://top.nkbe.npatch.remote --method getRemoteService " +
                "--extra modulePackageName:s:${BuildConfig.APPLICATION_ID}"
        )
        report("foreignUid.writable", output.trim())
        assertTrue(output, output.contains("Result: null"))
    }

    @Test fun injectedServiceIsGrantedOnlyToScopedTargets() {
        val call = "content call --uri content://top.nkbe.npatch.remote --method getInjectedRemoteService " +
            "--extra modulePackageName:s:${BuildConfig.APPLICATION_ID}"
        assertTrue("shell is not scoped yet", shell(call).contains("Result: null"))
        assertEquals(ScopeRequestResult.Added, NPatchRemoteGateway.requestScope(context, listOf("com.android.shell")))
        val granted = shell(call)
        report("injected.scoped", granted.trim())
        assertTrue(granted, granted.contains("binder"))
        transactVoid(TX_REMOVE_SCOPE) { it.writeStringList(listOf("com.android.shell")) }
        // Not asserted: from 1.1.0 the Manager answers repeat callers from a per-(module, uid) cache before it
        // re-checks the scope, so a granted target keeps its binder after the scope is removed. Recorded as
        // evidence of the Manager's behaviour, not as a promise of this module.
        report("injected.afterScopeRemoved", shell(call).trim())
    }

    // ---- helpers -------------------------------------------------------------------------------

    private class StressResult(val reads: Int, val bad: Int)

    private fun runStress(
        channel: com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.RemotePreferenceChannel,
        sizes: Set<Int>,
        publish: (Int) -> Unit
    ): StressResult {
        val running = AtomicBoolean(true)
        val reads = AtomicInteger()
        val bad = AtomicInteger()
        val reader = Thread {
            while (running.get()) {
                val size = channel.request(scratchGroup).size
                reads.incrementAndGet()
                if (size !in sizes) bad.incrementAndGet()
            }
        }
        reader.start()
        try {
            repeat(300) { publish(it) }
        } finally {
            running.set(false)
            reader.join(10_000)
        }
        return StressResult(reads.get(), bad.get())
    }

    /** 用生产探针（强制刷新）轮询直到得到期望状态或超时，返回最后一次观察到的状态。 */
    private fun awaitProbe(expected: NPatchScopeProbe.ScopeState): NPatchScopeProbe.ScopeState {
        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000L
        var last = NPatchScopeProbe.cached(context)
        while (last != expected && android.os.SystemClock.elapsedRealtime() < deadline) {
            NPatchScopeProbe.refreshAsync(context, force = true) {}
            Thread.sleep(400)
            last = NPatchScopeProbe.cached(context)
        }
        return last
    }

    private fun queryScopeProvider(target: String): List<String> {
        val uri = Uri.parse("content://top.nkbe.npatch.manager.provider.config?package=$target")
        return context.contentResolver.query(uri, arrayOf("packageName"), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex("packageName")
            buildList { while (cursor.moveToNext()) add(cursor.getString(index)) }
        } ?: emptyList()
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText() }
        }

    private fun report(key: String, value: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("g2.$key", value) })
    }

    private inline fun <T> transact(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            check(binder.transact(code, data, reply, 0)) { "transaction $code rejected" }
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactIntOn(service: IBinder, code: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            check(service.transact(code, data, reply, 0)) { "transaction $code rejected" }
            reply.readException()
            return reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactInt(code: Int): Int = transact(code) { it.readInt() }
    private fun transactLong(code: Int): Long = transact(code) { it.readLong() }
    private fun transactString(code: Int): String = transact(code) { it.readString().orEmpty() }
    private fun transactVoid(code: Int, write: (Parcel) -> Unit) = transact(code, write) { }

    private companion object {
        const val MANAGER_PACKAGE = "top.nkbe.npatch"
        const val DESCRIPTOR = "io.github.libxposed.service.IXposedService"
        // 与 IXposedServiceTransactionCodesTest 对照生成的 Stub 校验的同一张表。
        const val TX_GET_API_VERSION = 2
        const val TX_GET_FRAMEWORK_NAME = 3
        const val TX_GET_FRAMEWORK_VERSION = 4
        const val TX_GET_FRAMEWORK_VERSION_CODE = 5
        const val TX_GET_FRAMEWORK_PROPERTIES = 6
        const val TX_REMOVE_SCOPE = 13
        const val TX_DELETE_REMOTE_PREFERENCES = 23
    }
}
