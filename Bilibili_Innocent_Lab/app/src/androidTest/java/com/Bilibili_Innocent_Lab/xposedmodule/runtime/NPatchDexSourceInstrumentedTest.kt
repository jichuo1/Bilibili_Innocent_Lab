package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationNative
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRuntimeProfiles
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.LspatchRuntimeProfiles
import dalvik.system.DexClassLoader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** 显式只读已安装宿主的原版来源；真实 ART/DexKit 契约，不注册 Hook 或修改账号配置。 */
@RunWith(AndroidJUnit4::class)
class NPatchDexSourceInstrumentedTest {
    @Test fun originalApkFingerprintAndNativeDexQueryAgree() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyDexSource") == "true")
        assertTrue(NPatchRuntimeProfiles.read(InstrumentationRegistry.getInstrumentation().targetContext).markerPresent)
        verifyOriginal("npatch")
    }

    @Test fun lspatchOriginalApkFingerprintAndNativeDexQueryAgree() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyLspatchDexSource") == "true")
        assertTrue(LspatchRuntimeProfiles.read(InstrumentationRegistry.getInstrumentation().targetContext).modern)
        verifyOriginal("lspatch")
    }

    private fun verifyOriginal(carrier: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        @Suppress("DEPRECATION")
        val info = requireNotNull(context.packageManager.getPackageInfo("tv.danmaku.bili", 0).applicationInfo)
        val directory = File(context.codeCacheDir, "bil-host-dex-proof")
        val source = requireNotNull(DexSourceFingerprint.inspect(info, directory, materializeArchiveView = true))
        val original = File(source.codePaths.single())
        assertEquals(directory.canonicalFile, original.parentFile.canonicalFile)
        assertFalse(original.canWrite())
        ZipFile(info.sourceDir).use { zip ->
            val entry = requireNotNull(zip.getEntry("assets/$carrier/origin.apk"))
            assertEquals(entry.size, original.length())
            assertArrayEquals(zip.getInputStream(entry).use(::digest), original.inputStream().use(::digest))
            assertTrue(source.dexEntryCount > zip.entries().asSequence().count { it.name.matches(Regex("classes[0-9]*\\.dex")) })
        }
        assertEquals(DexSourceFingerprint.inspectCodePaths(listOf(original.path))?.value, source.value)
        val loader = DexClassLoader(original.path, context.codeCacheDir.path, null, ClassLoader.getSystemClassLoader())
        val isolateNativeFailure = InstrumentationRegistry.getArguments().getString("verifyNativeIsolation") == "true"
        if (isolateNativeFailure) {
            // Fail only optional frame preparation in the instrumentation process; no host state is changed.
            val missing = File(context.codeCacheDir, "missing-frame-module-${System.nanoTime()}.apk")
            assertEquals(FrameNativeLoader.Status.FAILED, FrameInterpolationNative.prepare(missing, context.codeCacheDir))
        }
        val query = DexAssistQuery.PLAYER_DEFAULT_QUALITY
        val result = DexKitAssistEngine.resolveAll(setOf(query), source.codePaths, loader).getValue(query)
        assertTrue("Native DexKit did not resolve original host code: $result", result is DexAssistResult.Candidates)
        assertTrue((result as DexAssistResult.Candidates).methods.isNotEmpty())
        if (isolateNativeFailure) {
            assertEquals(FrameNativeLoader.Status.FAILED, FrameInterpolationNative.status)
            assertEquals("AVAILABLE", DexKitAssistEngine.nativeAvailability())
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\n$carrier original DEX: count=${source.dexEntryCount}; " +
                "fingerprint=${source.value}; original bytes matched; native query passed\n")
        })
    }

    private fun digest(input: InputStream): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            sha.update(buffer, 0, read)
        }
        return sha.digest()
    }
}
