package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostComposeChromeApiInstrumentedTest {
    @Test fun modifierApplicationResolvesConcreteLayoutNodeMethod() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = try {
            context.packageManager.getApplicationInfo("tv.danmaku.bili", 0)
        } catch (missing: PackageManager.NameNotFoundException) {
            assumeNoException("Official host is not installed for this user", missing)
            throw missing
        }
        val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
        val loader = PathClassLoader(paths.joinToString(File.pathSeparator), ClassLoader.getSystemClassLoader().parent)

        val api = HostComposeChromeApi.resolve(loader)

        assertNotNull("Installed host must expose the Compose chrome ABI", api)
        val method = requireNotNull(api).applyModifier
        val uiNode = Class.forName("androidx.compose.ui.node.ComposeUiNode", false, loader)
        assertFalse("Xposed cannot hook the abstract ComposeUiNode interface method", Modifier.isAbstract(method.modifiers))
        assertFalse(method.declaringClass.isInterface)
        assertTrue(uiNode.isAssignableFrom(method.declaringClass))
    }
}
