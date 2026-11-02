package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernApiSupportTest {
    @Test
    fun `module metadata requires API 102 with static scope and no reload`() {
        val root = sequenceOf(File("src/main"), File("app/src/main")).first(File::isDirectory)
        val properties = Properties().apply {
            File(root, "resources/META-INF/xposed/module.prop").inputStream().use(::load)
        }
        assertEquals(ModernApiSupport.MIN_API.toString(), properties.getProperty("minApiVersion"))
        assertEquals(ModernApiSupport.MIN_API.toString(), properties.getProperty("targetApiVersion"))
        assertEquals("protective", properties.getProperty("exceptionMode"))
        assertEquals("true", properties.getProperty("staticScope"))
        assertEquals("false", properties.getProperty("autoHotReload"))
        assertEquals(listOf("tv.danmaku.bili", "android"), File(root,
            "resources/META-INF/xposed/scope.list").readLines().filter(String::isNotBlank))
    }

    @Test
    fun `framework initiated hot reload is refused explicitly and diagnostics are drained at snapshot time`() {
        val entry = SourceContract.read("hook/HookEntry.kt")
        val refusal = entry.substringAfter("override fun onHotReloading(").substringBefore("override fun onPackageReady(")
        assertTrue(refusal.contains("return false"))
        assertTrue("a refusal must not claim to hand hooks over", !refusal.contains("return true"))
        // 逃出功能回调的异常经快照折成既有的 runtimeError 位：入口只接线，不起线程、不改线协议。
        assertTrue(entry.contains("HostRuntimeDiagnosticsBridge.hookFaults = modernRuntime::drainFaults"))
        val bridge = SourceContract.read("runtime/HostRuntimeDiagnosticsBridge.kt")
        val snapshot = bridge.substringAfter("internal fun snapshot()").substringBefore("private fun absorbHookFaults")
        assertTrue(snapshot.indexOf("absorbHookFaults()") in 0 until snapshot.indexOf("synchronized(lock)"))
        assertTrue(SourceContract.read("hook/feature/FeatureInstaller.kt")
            .contains("registrar = FeatureScopedRegistrar(environment.registrar, installer.id)"))
    }
}
