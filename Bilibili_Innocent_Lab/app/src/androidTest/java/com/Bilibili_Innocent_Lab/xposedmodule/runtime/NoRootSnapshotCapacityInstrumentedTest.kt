package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootConfigSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootConfigSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsDecision
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 独立测试进程执行真实 Binder 往返，不发布夹具到用户配置或宿主。 */
@RunWith(AndroidJUnit4::class)
class NoRootSnapshotCapacityInstrumentedTest {
    @Test fun chineseSnapshotAndDirectDocumentRoundTripThroughAnotherAndroidProcess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // 一加会阻止跨 App 后台拉起。显式展示测试 APK 自己的临时窗口，不修改后台策略；
        // 解绑后窗口随服务关闭，绑定失败也会在 20 秒内自动结束。
        val activity = ComponentName(instrumentation.context.packageName, NoRootPayloadTestActivity::class.java.name)
        val launch = instrumentation.uiAutomation.executeShellCommand("am start -W -n ${activity.flattenToString()}").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
        assertFalse("Cannot launch payload fixture: $launch", launch.contains("Error:"))
        val connected = CountDownLatch(1)
        val endpoint = AtomicReference<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                endpoint.set(service)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val component = ComponentName(instrumentation.context.packageName, NoRootPayloadTestService::class.java.name)
        assertTrue("Cannot bind test payload service from the foreground",
            context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Payload test service did not connect", connected.await(10, TimeUnit.SECONDS))
            val rules = "中文规则\n".repeat(10_000)
            val remote = RemoteHookConfigContract.resolveSourceValues(mapOf(
                FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS to rules,
                FeaturePreferences.COMMENT_FILTER_KEYWORDS to rules))
            val values = SettingsCatalog.specs.associate { it.storageKey to remote.getValue(it.storageKey) }
            val snapshot = NoRootConfigSnapshot(NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
                SettingsCatalog.CATALOG_VERSION, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE.toLong(),
                42, 0, true, values)
            val payload = NoRootConfigSnapshotCodec.encode(snapshot)
            assertTrue(payload.toByteArray(Charsets.UTF_8).size > 256 * 1024)
            val document = HostAdmissionContract.encode(RemoteHookConfigContract.encode(1,
                BuildConfig.VERSION_CODE.toLong(), true, snapshot.revision, UserTermsDecision.ACCEPTED, remote))
            for ((key, value) in listOf("snapshot" to payload, "document" to document)) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(NoRootPayloadTestService.DESCRIPTOR)
                    data.writeBundle(Bundle().apply { putString(key, value) })
                    assertTrue("Payload exceeds existing Parcel budget", data.dataSize() < HostReceiptWire.MAX_PARCEL_BYTES)
                    assertTrue("Payload service rejected $key (${data.dataSize()} Parcel bytes)",
                        endpoint.get().transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
                    reply.readException()
                    val result = requireNotNull(reply.readBundle(Bundle::class.java.classLoader))
                    assertNotEquals(android.os.Process.myPid(), result.getInt("pid"))
                    assertEquals(value, result.getString(key))
                    if (key == "snapshot") assertEquals(snapshot, NoRootConfigSnapshotCodec.decode(result.getString(key).orEmpty()))
                    else assertTrue(HostAdmissionContract.decode(result.getString(key).orEmpty())?.let {
                        RemoteHookConfigContract.decode(it) is RemoteHookConfigDecodeResult.Ready
                    } == true)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
