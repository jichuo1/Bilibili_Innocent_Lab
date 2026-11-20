package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexMeguvizQueries
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexSourceFingerprint
import dalvik.system.PathClassLoader
import io.github.jichuo1.dexmeguviz.NativeEngine
import io.github.jichuo1.meguviz.hook.dex.DexRefHookBridge
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

/** Resolve the installed official APK's declared entry points in ART without initializing host classes. */
@RunWith(AndroidJUnit4::class)
class FrameHostRoutesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = instrumentation.targetContext.packageManager
    private val hostPackage = "tv.danmaku.bili"

    @Suppress("DEPRECATION")
    private fun hostPaths(): List<String> {
        val app = try {
            manager.getApplicationInfo(hostPackage, 0)
        } catch (missing: PackageManager.NameNotFoundException) {
            assumeNoException("Official host is not installed for this user", missing)
            throw missing
        }
        return listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
    }

    private fun hostLoader(paths: List<String>) =
        PathClassLoader(paths.joinToString(File.pathSeparator), ClassLoader.getSystemClassLoader().parent)

    @Suppress("DEPRECATION")
    @Test fun dexBatchBindsInstalledPlayerRoutesToTheSameArtMethods() {
        val paths = hostPaths()
        val loader = hostLoader(paths)
        val player = Class.forName("tv.danmaku.ijk.media.player.IjkMediaPlayer", false, loader)
        val client = Class.forName("tv.danmaku.ijk.media.player.services.IjkMediaPlayerClient", false, loader)
        val expected = listOf(player.getDeclaredMethod("setSpeed", Float::class.javaPrimitiveType),
            client.getDeclaredMethod("setPropertyFloat", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType))
        val rules = arrayOf(DexMeguvizQueries.declaration("L${player.name.replace('.', '/')};", "setSpeed", "(F)V"),
            DexMeguvizQueries.declaration("L${client.name.replace('.', '/')};", "setPropertyFloat", "(IF)V"))
        val hits = IntArray(rules.size)
        assertEquals(1, NativeEngine.abiVersion())
        val archives = checkNotNull(DexSourceFingerprint.inspectCodePaths(paths)).codePaths
        for (path in archives) DexMeguvizAssistEngine.withArchiveSnapshot(path) { snapshot ->
            val batch = DexRefHookBridge.queryAndBindBatch(snapshot, rules, loader, 2, 1024L * 1024, 1_000_000L)
            val results = batch.bindings().queries()
            assertEquals(rules.size, results.size)
            results.forEachIndexed { i, result ->
                assertTrue(result.executionAccepted())
                assertEquals(snapshot.info().snapshotId, result.query().snapshotId)
                assertEquals(result.query().totalMethods, result.query().visitedMethods)
                for (candidate in result.candidates()) {
                    assertEquals(expected[i], candidate.method())
                    assertSame(loader, expected[i].declaringClass.classLoader)
                    hits[i]++
                }
            }
        }
        assertEquals(listOf(1, 1), hits.toList())
        instrumentation.sendStatus(0, Bundle().apply {
            putString("frameDexBindings", "${manager.getPackageInfo(hostPackage, 0).versionName}: ${archives.size} archives, 2 exact ART bindings")
        })
    }

    @Suppress("DEPRECATION")
    @Test fun installedHostDeclaresFrameInterpolationRoutes() {
        val paths = hostPaths()
        val loader = hostLoader(paths)
        fun host(name: String): Class<*> = Class.forName(name, false, loader).also { assertSame(loader, it.classLoader) }
        fun exact(owner: Class<*>, name: String, result: Class<*>, vararg args: Class<*>) {
            val method = owner.getDeclaredMethod(name, *args)
            assertEquals(method.toString(), result, method.returnType)
            assertFalse(method.toString(), Modifier.isStatic(method.modifiers))
        }
        val video = "tv.danmaku.videoplayer.coreV2"
        val adapter = host("$video.adapter.ijk.IjkMediaPlayAdapter")
        val player = host("tv.danmaku.ijk.media.player.IjkMediaPlayer")
        val client = host("tv.danmaku.ijk.media.player.services.IjkMediaPlayerClient")
        val void = Void.TYPE
        exact(adapter, "setVideoDisPlay", void, host("$video.VideoDisplay"))
        exact(adapter, "invokeOp", Any::class.java, host("$video.adapter.IMediaPlayAdapter\$Ops"), Any::class.java)
        exact(player, "setSurface", void, Surface::class.java)
        exact(player, "setDisplay", void, SurfaceHolder::class.java)
        exact(player, "setSpeed", void, Float::class.javaPrimitiveType!!)
        exact(player, "release", void)
        exact(client, "setSurface", void, Surface::class.java)
        exact(client, "_setVideoSurface", void, Surface::class.java)
        exact(client, "setPropertyFloat", void, Int::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!)
        exact(client, "release", void)
        val version = manager.getPackageInfo(hostPackage, 0)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("frameHostRoutes", "${version.versionName} (${version.versionCode}), ${paths.size} APKs, 10 declared methods")
        })
    }
}
