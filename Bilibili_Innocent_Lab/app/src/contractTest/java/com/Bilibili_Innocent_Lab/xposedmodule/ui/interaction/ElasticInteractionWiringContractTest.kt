package com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction

import com.Bilibili_Innocent_Lab.xposedmodule.contract.KotlinSourceScanner
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.afterLast
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingsUiSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主侧弹性交互装配与排除边界护栏：
 * - 可展开标题与「模块已激活」整卡参与全局长按弹性，并保持自绘圆角涟漪轮廓；
 * - 只有角标（不独占图标手势）与 scrub 条（自管触摸）保留 `EXCLUDED_TAG`；
 * - `SkinnedActivity` 为主窗口与弹窗控制器统一设置 `PARENT_BOUNDS` 行程策略。
 */
class ElasticInteractionWiringContractTest {

    private fun headerInitBefore(anchor: String): String =
        SettingsUiSource.settingsPageFiles()
            .map { SourceContract.normalize(it.second) }
            .singleOrNull { it.contains(anchor) }
            .let { requireNotNull(it) { "anchor must appear in exactly one settings file: $anchor" } }
            .before(anchor)
            .afterLast("init = {")

    @Test
    fun expandableHeadersAndActivationCardParticipateWithRoundedSelfRipples() {
        for (anchor in listOf(
            "setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.APPEARANCE) }",
            "setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.COMPATIBILITY) }"
        )) {
            val block = headerInitBefore(anchor)
            assertFalse(anchor, block.contains("EXCLUDED_TAG"))
            assertTrue(anchor, block.contains("background = selfRippleBackground(10f)"))
        }

        val advancedHeader = SettingsUiSource.function("advancedCategoryScope")
            .after("header = linearLayout(")
            .before("}) {")
        assertFalse(advancedHeader.contains("EXCLUDED_TAG"))
        assertTrue(advancedHeader.contains("foreground = selfRippleBackground(12f)"))
        assertTrue(advancedHeader.contains("setOnClickListener { toggleAdvancedCategory(category) }"))

        val cardInit = SettingsUiSource.function("activationPageCard")
            .after("init = {")
            .before("imageView(")
        assertFalse(cardInit.contains("EXCLUDED_TAG"))
        assertTrue(cardInit.contains("isClickable = true"))
        assertFalse("整卡不能再跳转诊断", cardInit.contains("launchDiagnostics()"))
        assertFalse(cardInit.contains("setOnClickListener"))
        assertTrue(cardInit.contains("selfRippleBackground(ActivationCardVisualSpec.CORNER_RADIUS_DP)"))

        val mainPage = SettingsUiSource.settingsPage()
        assertEquals(1, Regex(Regex.escape("setOnClickListener { launchDiagnostics() }")).findAll(mainPage).count())

        val render = SettingsUiSource.function("renderActivationUi")
            .after("activationCardView?.apply {")
            .before("val activationContentColor")
        assertTrue(render.contains("selfRippleBackground(ActivationCardVisualSpec.CORNER_RADIUS_DP)"))
        assertTrue(render.contains("ActivationCardAccentDrawable(accentColor"))
        assertFalse(render.contains("foreground = if (activated) null"))

        val ripple = SettingsUiSource.function("selfRippleBackground")
        assertTrue(ripple.contains("val content = GradientDrawable()"))
        assertTrue(ripple.contains("val mask = GradientDrawable()"))
        assertFalse(ripple.contains("ColorDrawable(Color.TRANSPARENT)"))
        assertEquals(2, ripple.lines().count { it.trimStart().startsWith("cornerRadius = ") })
        assertTrue(ripple.contains("val darkTheme = ColorUtils.calculateLuminance(monetColors.surface) < 0.5"))
        assertTrue(ripple.contains("ColorUtils.setAlphaComponent(Color.WHITE, LIGHT_THEME_RIPPLE_ALPHA)"))
        assertTrue(ripple.contains("return CoverableRippleDrawable(rippleColor, content, mask)"))

        val diagnostics = SourceContract.read("ui/activity/DiagnosticsActivity.kt")
        val diagnosticsRipple = KotlinSourceScanner.functions(diagnostics, "rippleBackground").single()
        assertFalse(diagnosticsRipple.contains("toDrawable()"))
        assertTrue(diagnosticsRipple.contains("RecipeDrawable.forView(this, contentRecipe)"))
        assertTrue(diagnosticsRipple.contains("RecipeDrawable.forView(this, maskRecipe)"))
        for (recipe in listOf("transparent14Recipe", "mask14Recipe", "transparent20Recipe", "mask20Recipe")) {
            assertTrue(
                recipe,
                KotlinSourceScanner.properties(diagnostics, recipe).single()
                    .contains("ShapeSpec.roundRect(Length.dp(")
            )
        }
    }

    @Test
    fun gestureExclusionAndHostTravelPolicyStayScopedToLocalOwners() {
        val mainPage = SettingsUiSource.settingsPage()
        val scope = SourceContract.read("ui/meguviz/SettingsScope.kt")
        assertEquals(1, mainPage.split("EXCLUDED_TAG").size - 1)
        assertEquals(1, scope.split("EXCLUDED_TAG").size - 1)

        val badge = mainPage.after("githubUpdateBadge = this").before("visibility = View.INVISIBLE")
        assertTrue(badge.contains("ElasticInteractionController.EXCLUDED_TAG"))

        val scrub = KotlinSourceScanner.functions(scope, "segmentScrubRow").single()
        assertTrue(scrub.contains("ElasticInteractionController.EXCLUDED_TAG"))

        val host = SourceContract.read("ui/skin/activity/SkinnedActivity.kt")
        assertEquals(
            2,
            host.split("travelPolicy = com.lumen.coacervation.engine.interaction.ElasticTravelPolicy.PARENT_BOUNDS").size - 1
        )
        val controllerAlias = SourceContract.read("ui/interaction/ElasticInteractionController.kt")
        assertTrue(
            controllerAlias.contains(
                "typealias ElasticInteractionController = com.lumen.coacervation.engine.interaction.ElasticInteractionController"
            )
        )
    }
}
