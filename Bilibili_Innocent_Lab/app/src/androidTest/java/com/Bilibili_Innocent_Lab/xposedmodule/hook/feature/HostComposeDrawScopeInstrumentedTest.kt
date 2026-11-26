package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.pm.PackageManager
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostComposeDrawScopeAccess
import dalvik.system.PathClassLoader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostComposeDrawScopeInstrumentedTest {
    @Test fun sharedDrawScopeResolvesRectFillOnInstalledHost() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = try {
            context.packageManager.getApplicationInfo("tv.danmaku.bili", 0)
        } catch (missing: PackageManager.NameNotFoundException) {
            assumeNoException("Official host is not installed for this user", missing)
            throw missing
        }
        val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
        val loader = PathClassLoader(paths.joinToString(File.pathSeparator), ClassLoader.getSystemClassLoader().parent)

        val access = HostComposeDrawScopeAccess.resolve(loader)

        assertNotNull("Installed host must expose a Compose shared draw scope", access)
        val resolved = requireNotNull(access)
        val view = Class.forName("androidx.compose.ui.platform.AndroidComposeView", false, loader)
        assertEquals(resolved.scope, view.getDeclaredMethod("getSharedDrawScope").returnType)
        assertEquals(resolved.scope, resolved.draw.declaringClass)
        assertEquals("dispatchDraw", resolved.dispatch.name)
        assertEquals(listOf(Canvas::class.java), resolved.dispatch.parameterTypes.toList())
        assertTrue(resolved.draw.name.startsWith("drawRect-"))
        assertEquals(Void.TYPE, resolved.draw.returnType)
        assertEquals(7, resolved.draw.parameterCount)
        assertTrue(resolved.draw.parameterTypes.take(3).all { it == Long::class.javaPrimitiveType })
        assertTrue(resolved.scope.declaredMethods.any { it.name == "drawContent" })
        assertNotNull("Canvas translation is required to distinguish fixed chrome from scrolling panels", resolved.drawContext)
        assertNotNull("Canvas translation is required to distinguish fixed chrome from scrolling panels", resolved.canvas)
        assertNotNull("Canvas translation is required to distinguish fixed chrome from scrolling panels", resolved.nativeCanvas)
        assertEquals("getNativeCanvas", resolved.nativeCanvas?.name)
    }
}
