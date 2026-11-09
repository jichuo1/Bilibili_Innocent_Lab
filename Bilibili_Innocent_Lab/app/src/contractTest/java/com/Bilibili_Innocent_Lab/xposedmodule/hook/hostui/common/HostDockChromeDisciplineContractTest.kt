package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮栏安全区与顶栏渐隐跑在宿主进程的预绘制、布局和绘制回调里。
 * lint 的 `NewApi` 不管类型引用，JVM 单测也不做 ART 类解析，这里守住两条真机才会暴露的线：
 * API 31 类型不漏出隔离层，宿主栈上的入口不让模块异常逃逸。
 */
class HostDockChromeDisciplineContractTest {
    private val readerName = "com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockClearanceReader"

    class UnguardedFixture {
        fun leaksUnavailableType(value: android.view.RoundedCorner?): Boolean = value != null
    }

    private class Api30LinkageLoader(parent: ClassLoader, private val target: String) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("android.view.RoundedCorner")) throw ClassNotFoundException(name)
            if (!name.startsWith(target)) return super.loadClass(name, resolve)
            synchronized(this) {
                val loaded = findLoadedClass(name) ?: run {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() }
                        ?: throw ClassNotFoundException(name)
                    defineClass(name, bytes, 0, bytes.size)
                }
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }
    }

    @Test fun theRestrictedLoaderActuallyRejectsALeakingMethodSignature() {
        val name = UnguardedFixture::class.java.name
        val loader = Api30LinkageLoader(javaClass.classLoader, name)
        assertThrows(LinkageError::class.java) { Class.forName(name, false, loader).declaredMethods }
    }

    @Test fun theSharedReaderLinksWithoutRoundedCorner() {
        val loader = Api30LinkageLoader(javaClass.classLoader, readerName)
        val reader = Class.forName(readerName, false, loader)
        assertSame(loader, reader.classLoader)
        assertTrue(reader.declaredMethods.isNotEmpty())
        val references = reader.declaredMethods.flatMap { it.parameterTypes.toList() + it.returnType } +
            reader.declaredFields.map { it.type }
        assertFalse(references.any { it.name.startsWith("android.view.RoundedCorner") })
    }

    @Test fun roundedCornerStaysInsideTheIsolationLayerBehindAVersionCheck() {
        val reader = SourceContract.read("hook/hostui/common/HostDockClearance.kt")
        assertFalse(reader.contains("RoundedCorner"))
        assertTrue(reader.contains("AndroidVersion.isAtLeast(AndroidVersion.S)"))
        assertTrue(reader.contains("HostDockCornerApi31.bottomIntrusion"))
        val isolation = SourceContract.read("hook/hostui/common/HostDockCornerApi31.kt")
        assertTrue(isolation.contains("@RequiresApi(31)"))
        assertTrue(isolation.contains("@DoNotInline"))
    }

    @Test fun insetsAreReadThroughTheCompatLayerOnly() {
        val reader = SourceContract.read("hook/hostui/common/HostDockClearance.kt")
        assertTrue(reader.contains("ViewCompat.getRootWindowInsets"))
        // 手写版本分支曾在 minSdk 27 上直接调用 API 28 的 DisplayCutout。
        for (legacy in listOf("Build.VERSION", "systemWindowInset", "displayCutout?.", "WindowInsets.Type", "android.view.WindowInsets")) {
            assertFalse("Legacy inset access came back: $legacy", reader.contains(legacy))
        }
    }

    @Test fun hostStackEntriesCannotLetModuleFailuresEscape() {
        val reader = SourceContract.read("hook/hostui/common/HostDockClearance.kt")
        assertTrue(reader.contains("HostThreadGuard.call(\"host.dock.clearance\""))
        assertTrue(reader.contains("HostThreadGuard.call(\"host.dock.side\""))
        val fade = SourceContract.read("hook/hostui/top/HostTopTabEdgeFade.kt")
        for (key in listOf("host.top.fade.draw", "host.top.fade.layout", "host.top.fade.attach", "host.top.fade.detach")) {
            assertTrue("Unguarded host entry: $key", fade.contains("HostThreadGuard.run(\"$key\""))
        }
        // 原生 fading edge 只能画竖直硬线；边界形状由共用的圆弧遮罩负责。
        assertFalse(fade.contains("FadingEdge"))
        val compose = SourceContract.read("hook/hostui/chrome/HostComposeTopTabFade.kt")
        assertTrue("Module failures must fall open to the host's plain draw", compose.contains("fail(failure, onError)"))
        assertTrue("Host exceptions must keep their identity", compose.contains("throw failure.targetException"))
    }

    @Test fun theComposeFadeFollowsTheCapsuleSwitchNotTheUnconditionalCentering() {
        val entry = SourceContract.read("hook/HookEntry.kt")
        assertTrue(entry.contains("edgeFade = prefs.getBoolean(FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS, false)"))
        val centering = SourceContract.read("hook/hostui/chrome/HostComposeTopTabCentering.kt")
        assertTrue(centering.contains("if (edgeFade) HostComposeTopTabFade.resolve(loader, fadeError) else null"))
        // 可选的渐隐失败不能把居中报成错误，也不能丢掉居中的位置观察。
        assertTrue(centering.contains("runCatching { fade.decorate(sized, root.state::edges, fadeError) }.onFailure(fadeError).getOrDefault(sized)"))
    }

    @Test fun theMaskNeverPunchesThroughTheGlassAndTheStripTailStaysScrollable() {
        val fade = SourceContract.read("hook/hostui/top/HostTopTabEdgeFade.kt")
        assertTrue(fade.contains("if (scroll.layerType != View.LAYER_TYPE_HARDWARE) return@run"))
        val island = SourceContract.read("hook/hostui/top/HostTopIslandBinding.kt")
        assertTrue(island.contains("tabsCanScrollRight = canScrollTabs(scroll, 1)"))
        assertTrue(island.contains("scroll.scrollX < scroll.tabScrollRange()"))
    }

    @Test fun hostPropertyOverridesGoThroughTheValueLedger() {
        val fade = SourceContract.read("hook/hostui/top/HostTopTabEdgeFade.kt")
        assertTrue(fade.contains("HostChromeValueLedger<HorizontalScrollView, Int>"))
        assertFalse(fade.contains("class Ownership"))
        val bottom = SourceContract.read("hook/hostui/bottom/HostBottomBarFxController.kt")
        assertTrue(bottom.contains("HostChromeValueLedger<TextView, Float>"))
    }
}
