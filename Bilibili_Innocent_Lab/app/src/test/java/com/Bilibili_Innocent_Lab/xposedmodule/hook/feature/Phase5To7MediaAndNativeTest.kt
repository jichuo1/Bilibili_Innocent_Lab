package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioRescueConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.AudioRescueFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.AudioAsset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets.AudioAssetStore
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameCaptureBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameSourceCadence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ModuleNativeLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Phase5To7MediaAndNativeTest {
    @get:Rule
    val tempFolder = TemporaryFolder(File("build/tmp/phase5to7-tests").apply { mkdirs() })

    private fun env(process: String = "tv.danmaku.bili") = HookEnvironment(
        processName = process,
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> }
    )

    @Test
    fun `ModuleNativeLoader falls back to canonical APK entry on UnsatisfiedLinkError`() {
        ModuleNativeLoader.clearRegisteredApkForTest()
        val fakeApk = tempFolder.newFile("module.apk").canonicalFile
        ModuleNativeLoader.registerModuleApk(fakeApk.path)

        val loadedPaths = mutableListOf<String>()
        ModuleNativeLoader.loadWith(
            name = "innocent_danmaku_wire",
            loadLibrary = { throw UnsatisfiedLinkError("not on namespace") },
            loadPath = { path -> loadedPaths += path },
            abiProvider = { "arm64-v8a" }
        )
        assertEquals(listOf("${fakeApk.path}!/lib/arm64-v8a/libinnocent_danmaku_wire.so"), loadedPaths)

        assertEquals("arm64-v8a", ModuleNativeLoader.selectAbi(true, arrayOf("x86_64", "arm64-v8a"), arrayOf("armeabi-v7a")))
        assertEquals("armeabi-v7a", ModuleNativeLoader.selectAbi(false, arrayOf("arm64-v8a"), arrayOf("armeabi-v7a", "armeabi")))

        ModuleNativeLoader.clearRegisteredApkForTest()
        ModuleNativeLoader.registerModuleApk(File(tempFolder.root, "missing.apk").path)
        val primary = UnsatisfiedLinkError("missing")
        val thrown = assertThrows(UnsatisfiedLinkError::class.java) {
            ModuleNativeLoader.loadWith(
                name = "bil_frame_window",
                loadLibrary = { throw primary },
                loadPath = { error("must not call loadPath without canonical existing APK") },
                abiProvider = { "arm64-v8a" }
            )
        }
        assertSame(primary, thrown)
    }

    @Test
    fun `AudioAssetStore enforces 0700 dir and 0400 file permissions for hostAbi and repairs existing cache`() {
        val bytes = ByteArray(128) { (it * 7).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val part = AudioAsset("libonnxruntime.so", bytes.size.toLong(), sha)

        val events = mutableListOf<Pair<Path, Int>>()
        val hostStore = AudioAssetStore(tempFolder.newFolder(), hostAbi = "arm64-v8a") { path, mode ->
            events += path to mode
        }
        assertEquals(1, events.size)
        assertEquals(hostStore.directory.toPath(), events[0].first)
        assertEquals(AudioAssetStore.MODE_DIR, events[0].second)

        hostStore.install(part) { bytes.inputStream() }
        assertTrue(events.any { it.first.fileName.toString().endsWith(".part") && it.second == AudioAssetStore.MODE_FILE })

        events.clear()
        // Re-installing an already-valid cached file repairs its mode via 0400 without opening remote.
        hostStore.install(part) { error("must not re-download valid file") }
        assertEquals(listOf(hostStore.file(part).toPath() to AudioAssetStore.MODE_FILE), events)

        // hostAbi == null never invokes POSIX chmod (preserves Windows JVM unit test mutability).
        val moduleEvents = mutableListOf<Pair<Path, Int>>()
        val moduleStore = AudioAssetStore(tempFolder.newFolder(), hostAbi = null) { path, mode ->
            moduleEvents += path to mode
        }
        moduleStore.install(part) { bytes.inputStream() }
        assertTrue(moduleEvents.isEmpty())
        assertTrue(moduleStore.file(part).canWrite())
    }

    @Test
    fun `FrameInterpolationFeatureInstaller gates processes and matches fractional display refresh rates`() {
        val installer = FrameInterpolationFeatureInstaller(
            enabled = true,
            verificationAllowed = false,
            moduleApk = tempFolder.newFile("fake.apk")
        )
        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            installer.install(env("tv.danmaku.bili:download"))
        )
        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            installer.install(env("tv.danmaku.bili:web"))
        )

        val ntscModes = listOf(
            FrameInterpolationFeatureInstaller.DisplayModeCandidate(1, 1440, 3200, 60.0f),
            FrameInterpolationFeatureInstaller.DisplayModeCandidate(2, 1440, 3200, 89.91f),
            FrameInterpolationFeatureInstaller.DisplayModeCandidate(3, 1440, 3200, 119.88f),
            FrameInterpolationFeatureInstaller.DisplayModeCandidate(4, 1080, 2400, 120.0f)
        )
        assertEquals(3, FrameInterpolationFeatureInstaller.selectPreferredDisplayModeId(ntscModes, 1440, 3200, 120))
        assertEquals(2, FrameInterpolationFeatureInstaller.selectPreferredDisplayModeId(ntscModes, 1440, 3200, 90))

        val panel144 = listOf(
            FrameInterpolationFeatureInstaller.DisplayModeCandidate(10, 1080, 2400, 60.0f),
            FrameInterpolationFeatureInstaller.DisplayModeCandidate(11, 1080, 2400, 144.0f)
        )
        assertEquals(11, FrameInterpolationFeatureInstaller.selectPreferredDisplayModeId(panel144, 1080, 2400, 120))
        assertEquals(11, FrameInterpolationFeatureInstaller.selectPreferredDisplayModeId(panel144, 1080, 2400, 72))
        assertEquals(0, FrameInterpolationFeatureInstaller.selectPreferredDisplayModeId(panel144.take(1), 1080, 2400, 120))
    }

    @Test
    fun `FrameCaptureBridge displayModes handles null context and FrameSourceCadence is thread-safe`() {
        assertEquals(emptyList<Float>(), FrameCaptureBridge.displayModes(null))

        val cadence = FrameSourceCadence(targetFps = 60)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val writer = pool.submit<Unit> {
                start.await(5, TimeUnit.SECONDS)
                for (i in 1..4000) {
                    cadence.add(i * 33_333L)
                    if (i % 500 == 0) cadence.reset()
                }
            }
            val reader = pool.submit<Unit> {
                start.await(5, TimeUnit.SECONDS)
                repeat(4000) {
                    cadence.verdict()
                    cadence.sourceFps()
                    cadence.describe()
                }
            }
            start.countDown()
            writer.get(10, TimeUnit.SECONDS)
            reader.get(10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `AudioRescueFeatureInstaller keeps exact hook counts and isolates unrelated processes`() {
        val installer = AudioRescueFeatureInstaller(AudioRescueConfig(peakGuard = true))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"), installer.install(env("tv.danmaku.bili:web")))
        val installed = installer.install(env("tv.danmaku.bili"))
        assertTrue(installed is FeatureInstallResult.Installed && installed.hookCount == 10)
        assertFalse(File("src/main/resources/META-INF/xposed/scope.list").takeIf { it.isFile }?.readText()?.contains("android") == true)
    }
}
