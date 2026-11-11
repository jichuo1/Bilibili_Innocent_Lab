package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistRequest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizAssistEngine
import dalvik.system.DexClassLoader
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 显式传入设备上宿主 APK 的只读路径，在独立验证包中运行真实 Adapter；不启动宿主或修改其配置。
 * am instrument -w -e class <此类> -e hostApkPath /data/app/.../base.apk <验证包>.test/androidx.test.runner.AndroidJUnitRunner
 * 这是 ART/真实宿主类兼容测试；不替代 LSPosed 播放器端到端验收。
 */
@RunWith(AndroidJUnit4::class)
class PlayerSpeedMenuHostTest {
    @Test fun dexMeguvizDiscoversTheRealHostAdapterAndItsCacheResolves() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apk = InstrumentationRegistry.getArguments().getString("hostApkPath")
        org.junit.Assume.assumeTrue("Pass the installed host base.apk path to opt in", !apk.isNullOrBlank())
        val loader = DexClassLoader(apk!!, context.codeCacheDir.absolutePath, null, context.classLoader)
        val result = DexMeguvizAssistEngine.resolve(DexAssistRequest(PlayerSpeedMenuAdapter.query, listOf(apk), loader))
        assertTrue("Real DexMeguviz query must return semantic candidates: $result", result is DexAssistResult.Candidates)
        val selected = requireNotNull(PlayerSpeedMenuAdapter.select(loader, result as DexAssistResult.Candidates))
        assertNotNull(selected.adapter)
        val restored = requireNotNull(PlayerSpeedMenuPoints.fromJson(selected.toJson()))
        assertNotNull(PlayerSpeedMenuAdapter.resolve(loader, restored)?.adapter)
    }

    @Test fun hostAdapterSupportsCustomRangeAndAdjacentHundredthClicks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val apk = InstrumentationRegistry.getArguments().getString("hostApkPath")
        org.junit.Assume.assumeTrue("Pass the installed host base.apk path to opt in", !apk.isNullOrBlank())
        val loader = DexClassLoader(apk!!, context.codeCacheDir.absolutePath, null, context.classLoader)
        val point = requireNotNull(PlayerSpeedLocator.speedMenu(loader))
        val adapterPoint = requireNotNull(point.adapter)
        val adapter = adapterPoint.adapterClass.getDeclaredConstructor(Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }.newInstance(1)
        val speeds = listOf(8f, 4.75f, 3f, 2f, 1.99f, 1.25f, 1.05f, 1.01f, 1f, 0.75f, 0.5f, 0.1f)
        val arrayField = adapterPoint.speedsArrayField
        val previous = arrayField?.get(adapter)
        try {
            if (arrayField != null) {
                arrayField.set(adapter, speeds.toFloatArray())
                adapterPoint.populateMethod.invoke(adapter, 1f)
            } else {
                val options = requireNotNull(PlayerSpeedFeatureInstaller.buildOptionModels(
                    requireNotNull(adapterPoint.optionConstructor), requireNotNull(adapterPoint.optionSpeedField),
                    requireNotNull(adapterPoint.optionAvailableField), speeds))
                adapterPoint.populateMethod.invoke(adapter, 1f, options)
            }
            val items = adapterPoint.itemsField.get(adapter) as List<*>
            assertEquals(speeds, items.map { adapterPoint.itemSpeedField.getFloat(it) })
            assertTrue("Host comparison should expose the neighboring-speed regression",
                items.count { adapterPoint.itemSelectedField.getBoolean(it) } > 1)
            assertTrue(PlayerSpeedFeatureInstaller.refineAdapterItems(adapter, adapterPoint, 1f, speeds))
            assertEquals(listOf(1f), items.filter { adapterPoint.itemSelectedField.getBoolean(it) }
                .map { adapterPoint.itemSpeedField.getFloat(it) })

            val listenerField = adapterPoint.adapterClass.declaredFields.single {
                !Modifier.isStatic(it.modifiers) && it.type.isInterface && it.type.declaredMethods.any { method ->
                    method.returnType == Void.TYPE && method.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
                }
            }.apply { isAccessible = true }
            val selected = mutableListOf<Float>()
            listenerField.set(adapter, Proxy.newProxyInstance(loader, arrayOf(listenerField.type)) { _, method, args ->
                if (method.returnType == Void.TYPE && args?.singleOrNull() is Float) selected += args[0] as Float
                null
            })
            instrumentation.runOnMainSync {
                val adjacent = items.single { adapterPoint.itemSpeedField.getFloat(it) == 1.05f }
                (adapter as View.OnClickListener).onClick(View(context).apply { tag = adjacent })
            }
            assertEquals(listOf(1.05f), selected)
            assertEquals(listOf(1.05f), items.filter { adapterPoint.itemSelectedField.getBoolean(it) }
                .map { adapterPoint.itemSpeedField.getFloat(it) })
        } finally {
            if (arrayField != null) arrayField.set(adapter, previous)
        }
    }
}
