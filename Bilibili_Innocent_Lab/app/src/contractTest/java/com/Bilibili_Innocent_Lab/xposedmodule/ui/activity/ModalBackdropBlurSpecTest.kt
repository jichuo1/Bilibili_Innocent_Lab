package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after

class ModalBackdropBlurSpecTest {

    fun rampEndpointsAreExactSoTheBackgroundNeverStaysBlurred() {
        assertEquals(0f, ModalBackdropBlurSpec.fraction(0f), 0f)
        assertEquals(1f, ModalBackdropBlurSpec.fraction(0.65f), 0f)
        assertEquals(1f, ModalBackdropBlurSpec.fraction(1f), 0f)
        // 关闭后必须精确归零，否则背景会永远留着一层糊。
        assertEquals(0, ModalBackdropBlurSpec.radiusPx(0f, 33))
        assertEquals(33, ModalBackdropBlurSpec.radiusPx(1f, 33))
        assertEquals(33, ModalBackdropBlurSpec.radiusPx(0.65f, 33))
    }

    fun theRampIsMonotonicBoundedAndSettlesBeforeThePanelLands() {
        var previous = -1f
        for (step in -10..1010) {
            val value = ModalBackdropBlurSpec.fraction(step / 1000f)
            assertTrue(value in 0f..1f)
            assertTrue("ramp must not go backwards at $step", value >= previous)
            previous = value
        }
        // 65% 之前就到位：背景分层是"面板正在浮起来"的铺垫，不能等形状停住才糊完。
        assertTrue(ModalBackdropBlurSpec.fraction(0.5f) > 0.8f)
        assertTrue(ModalBackdropBlurSpec.fraction(0.2f) < 0.4f)
    }

    fun invalidProgressIsClampedAndNanFallsBackToNoBlur() {
        for (input in listOf(Float.NaN, Float.NEGATIVE_INFINITY, -100f)) {
            assertEquals(0f, ModalBackdropBlurSpec.fraction(input), 0f)
            assertEquals(0, ModalBackdropBlurSpec.radiusPx(input, 33))
        }
        for (input in listOf(Float.POSITIVE_INFINITY, 100f)) {
            assertEquals(1f, ModalBackdropBlurSpec.fraction(input), 0f)
            assertEquals(33, ModalBackdropBlurSpec.radiusPx(input, 33))
        }
        // 非法上限一律当作"不启用"。
        for (max in listOf(0, -1, Int.MIN_VALUE)) {
            for (step in 0..10) {
                assertEquals(0, ModalBackdropBlurSpec.radiusPx(step / 10f, max))
            }
        }
    }

    /**
     * 半径必须按台阶量化：改它要写 `WindowManager.LayoutParams`，
     * 那是一次 binder 往返 + 一次 relayout，逐帧写会抖。
     */
    fun radiusIsQuantizedSoTheWindowIsNotRelaidOutEveryFrame() {
        val max = 33
        val distinct = (0..1000).map { ModalBackdropBlurSpec.radiusPx(it / 1000f, max) }.distinct()
        assertTrue("too many window updates: $distinct", distinct.size <= 12)
        distinct.forEach { radius ->
            assertTrue(radius in 0..max)
            // 端点之外的每一档都落在台阶上。
            assertTrue(
                "unquantized radius $radius",
                radius == 0 || radius == max ||
                    radius % ModalBackdropBlurSpec.RADIUS_STEP_PX == 0
            )
        }
        // 单调不回头，否则会看到背景忽清忽糊。
        var previous = -1
        for (step in 0..1000) {
            val radius = ModalBackdropBlurSpec.radiusPx(step / 1000f, max)
            assertTrue(radius >= previous)
            previous = radius
        }
    }

    /**
     * 用户开关：未设置时**默认关闭**，且它是四道门里的第一道。
     *
     * 默认关闭不是保守，是实测：真机 8 轮气泡开合，开启后面板动画掉帧率
     * **3.62% → 10.41%**、UI 线程 90 分位 9ms → 16ms（GPU 分位几乎不变，
     * 代价出在写 `LayoutParams` 触发的 `relayoutWindow`）。
     */
    fun theUserToggleDefaultsToOffAndIsTheFirstGate() {
        val store = File("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/settings/appearance/ModalBackdropBlurStore.kt")
            .takeIf(File::isFile)
            ?: File("app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/settings/appearance/ModalBackdropBlurStore.kt")
        val storeCode = store.readText()
        assertTrue(storeCode.contains("const val DEFAULT = false"))
        assertTrue(storeCode.contains("getBoolean(PREF_KEY, DEFAULT)"))
        // 读失败也必须落回默认关闭，不能因为一次异常把模糊打开。
        assertTrue(storeCode.contains("getOrDefault(DEFAULT)"))
        val code = source("ModalBackdropBlur")
        // 开关是第一道门：不开就连系统能力都不去问。
        val body = code.after("fun createOrNull(").before("val maxRadiusPx")
        assertTrue(
            body.indexOf("!userEnabled) return null") < body.indexOf("!materialYouSkin) return null")
        )
        assertTrue(
            body.indexOf("!userEnabled) return null") < body.indexOf("isCrossWindowBlurEnabled")
        )
        // 开关行在外观卡片里，已外移到 SettingsCardSections.kt；扫整个设置页。
        val main = SettingsUiSource.settingsPage()
        assertTrue(main.contains("userEnabled = ModalBackdropBlurStore.read(this)"))
        // 开关落在配色规范那条的下面，并且登记进备份目录。先后只在同一个文件里比才有意义，
        // 设置页是多文件拼接，跨文件比较的是拼接顺序。
        val layout = SettingsUiSource.settingsPageFiles().map { it.second }.singleOrNull {
            it.contains("R.string.panel_window_blur_title") &&
                it.contains("R.string.material_color_spec_summary")
        }
        assertTrue("blur toggle and color-spec row must live in one settings file", layout != null)
        assertTrue(
            layout!!.indexOf("R.string.panel_window_blur_title") >
                layout.indexOf("R.string.material_color_spec_summary")
        )
        assertTrue(main.contains("putBoolean(ModalBackdropBlurStore.PREF_KEY, checked)"))
    }

    /** 四道门缺一不可，且只借平台的跨窗口模糊，不自绘也不截屏。 */
    fun blurIsGatedOnMaterialYouPlatformSupportAndSystemPermission() {
        val code = source("ModalBackdropBlur")
        assertTrue(code.contains("!materialYouSkin) return null"))
        // 版本守卫必须写成 Build.VERSION.SDK_INT：lint 的 NewApi 只认得它，
        // 而且 apply() 与工厂不在同一方法里，两处都要各自带一个本地守卫。
        assertEquals(
            2,
            Regex("Build\\.VERSION\\.SDK_INT < Build\\.VERSION_CODES\\.S").findAll(code).count()
        )
        assertFalse("must not gate on the third-party version helper",
            code.contains("AndroidVersion.isAtLeast"))
        assertFalse("must not gate on the third-party version helper",
            code.contains("AndroidVersion.isLessThan"))
        // 项目自定义 lint 规则会劝人换回那个助手，换了 NewApi 就报错——抑制必须留着。
        assertFalse(code.contains("import com.highcapable.betterandroid"))
        assertTrue(code.contains("isCrossWindowBlurEnabled"))
        assertTrue(code.contains("FLAG_BLUR_BEHIND"))
        assertTrue(code.contains("params.blurBehindRadius"))
        // 不做退化模拟：糊不动就保持清晰，不许自绘一层假模糊。
        for (forbidden in listOf("RenderEffect", "Bitmap", "PixelCopy", "RenderScript", "drawColor")) {
            assertFalse(forbidden, code.contains(forbidden))
        }
        // 重复值不写窗口属性。
        assertTrue(code.contains("if (radius == appliedRadius) return"))
        // 只跟弹窗自己的时钟，不另开 animator。
        assertFalse(code.contains("ValueAnimator"))
        assertFalse(code.contains("postDelayed"))
    }

    /** Liquid 皮肤自己在做实时玻璃，不许再叠一层跨窗口模糊。 */
    fun theSkinGateIsWiredToMaterialYouOnly() {
        val skinned = source("../skin/activity/SkinnedActivity")
        assertTrue(skinned.contains("isMaterialYouSkinEffective"))
        assertTrue(skinned.contains("effectiveSkin != SkinId.LIQUID"))
        val main = source("MainActivity")
        assertTrue(main.contains("materialYouSkin = isMaterialYouSkinEffective"))
        // 两条形变时钟都要驱动，无锚点弹窗借自己的入场进度。
        // 钉的是"两条时钟都推了模糊"，不是那行 lambda 写成一行还是多行——
        // 覆盖式子面板要在同一个 onFrame 里顺带淡出父面板，那行已经不是单行了。
        assertEquals(
            2,
            Regex("onFrame = \\{ progress ->[\\s\\S]{0,160}?backdropBlur\\?\\.apply\\(progress\\)")
                .findAll(main).count()
        )
        // 同一个监听器里还要顺带推 scrim 淡入，断言块内语义而不是单行写法。
        assertTrue(
            Regex("setUpdateListener \\{[\\s\\S]{0,160}?backdropBlur\\?\\.apply\\(container\\.alpha\\)")
                .containsMatchIn(main)
        )
        assertTrue(main.contains("backdropBlur?.clear()"))
    }

    private fun source(name: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
        return LceMotionSource.read(path)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (ModalBackdropBlurSpecTest) -> Unit) {
            val s = ModalBackdropBlurSpecTest()
            block(s)
        }
        runIsolated { s -> s.rampEndpointsAreExactSoTheBackgroundNeverStaysBlurred() }
        runIsolated { s -> s.theRampIsMonotonicBoundedAndSettlesBeforeThePanelLands() }
        runIsolated { s -> s.invalidProgressIsClampedAndNanFallsBackToNoBlur() }
        runIsolated { s -> s.radiusIsQuantizedSoTheWindowIsNotRelaidOutEveryFrame() }
        runIsolated { s -> s.theUserToggleDefaultsToOffAndIsTheFirstGate() }
        runIsolated { s -> s.blurIsGatedOnMaterialYouPlatformSupportAndSystemPermission() }
        runIsolated { s -> s.theSkinGateIsWiredToMaterialYouOnly() }
    }
}
