package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.contract.beforeOrRest

/** Source layout contracts; device interaction and rendering remain separate acceptance steps. */
class TelemetryMenuStructureTest {
    private val source by lazy {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt"
        LceMotionSource.read(path)
    }

    @Test
    fun `github telemetry entry navigates before any switch is constructed`() {
        val row = SettingsUiSource.function("createTelemetryMenuRow")
        assertTrue(row.contains("showControl: Boolean = false"))
        val entry = row.after("if (!showControl) {")
            .before("var programmaticChange")
        assertTrue(entry.contains("return NativeLinearLayout(this)"))
        assertFalse(entry.contains("setOnClickListener"))
        assertTrue(entry.contains("isClickable = false"))
        assertFalse(entry.contains("writeConsentChoice"))
        assertFalse(entry.contains("telemetrySwitch"))
    }

    @Test
    fun `new bubble shifts without moving GitHub and retains an outside hit target`() {
        val badge = source.after("// The badge stays outside the circular button and does not change its anchor geometry.")
            .before("activationCardView = this")
        // 圆形 GitHub 按钮固定 48dp，徽章只借用其右上角外飘，不改动按钮锚定几何。
        assertTrue(badge.contains("params(48.dp, 48.dp) { marginEnd = 5.dp }"))
        // 负边距徽章要画出宿主外，宿主必须关掉裁剪。
        assertTrue(badge.contains("clipChildren = false"))
        assertTrue(badge.contains("clipToPadding = false"))
        assertTrue(badge.contains("params(22.dp, 15.dp)"))
        assertTrue(badge.contains("marginEnd = -5.dp"))
        assertTrue(badge.contains("topMargin = -5.dp"))
        assertTrue(badge.contains("GithubUpdateBadgeDrawable"))
        assertTrue(badge.contains("toolbar.touchDelegate"))
        assertTrue(badge.contains("badge.visibility == View.VISIBLE"))
    }

    @Test
    fun `the info panel grows from the exclamation mark and covers the GitHub panel in place`() {
        val row = SettingsUiSource.function("createTelemetryMenuRow")
        val click = row.after("contentDescription = getString(R.string.telemetry_info_button)")
            .before("// GitHub 二级页只导航")
        // 折叠端是 ⓘ、展开端是 GitHub 卡片，两张矩形都必须在点击这一刻取：
        // 形变一开始卡片就会被改 alpha 与 outline，事后取到的不是用户看到的位置。
        assertTrue(click.contains("origin = modalAnchorBounds(source)"))
        // 展开端按父面板**画出来的表面**取；用容器矩形会把气泡小角那 9dp 也算进去。
        assertTrue(click.contains("cover = modalSurfaceBounds(dialog, dialogContainer)"))
        assertFalse(click.contains("cover = modalAnchorBounds("))
        // GitHub 面板**不再关闭**——它要留在下面被盖住。
        assertFalse("子面板要叠在父面板上，不能再先收起父面板",
            click.contains("dismissWithAnimation(dialog, dialogContainer)"))
        assertTrue(click.contains("parentDialog = dialog"))

        val detail = SettingsUiSource.function("showTelemetryInfoDialog")
        assertTrue(detail.contains("morphAnchorBounds = origin, coverBounds = cover"))
        // 四个参数都有默认值：从遥测说明返回那条路径仍是原来的居中缩放入场。
        assertTrue(detail.contains("origin: SettingsBackupMotionRect? = null"))
        assertTrue(detail.contains("cover: SettingsBackupMotionRect? = null"))
    }

    @Test
    fun `rows that open a further dialog retire the covered parent themselves`() {
        val detail = SettingsUiSource.function("showTelemetryInfoDialog")
        // 新弹窗的 present 开头会硬关当前弹窗；不提前让父面板走自己的退场动画，
        // 用户就会看到 GitHub 面板"啪"地消失。三个会开新弹窗的入口都要提前收它。
        // 排除 `fun closeCoveredParent()` 那行声明，只数真正的调用点。
        assertEquals(3, Regex("(?<!fun )closeCoveredParent\\(\\)").findAll(detail).count())
        for (marker in listOf("telemetry_explanation_action", "telemetry_preview_action", "telemetry_purge_action")) {
            // 最后一行之后没有下一个 createGitHubMenuRow：有意截到函数末尾。
            val handler = detail.after(marker).beforeOrRest("createGitHubMenuRow")
            assertTrue(marker, handler.contains("closeCoveredParent()"))
        }
        // 关闭按钮与手动上传**不**收父面板：它们不开新弹窗，收起后本来就该露出 GitHub 面板。
        val close = detail.after("createPanelCloseButton").before("presentModalDialog")
        assertFalse(close.contains("closeCoveredParent()"))
        // 两张卡片矩形完全重合，"关闭"必须是同一颗按钮，否则切换时会左右跳。
        assertTrue(SettingsUiSource.function("showGitHubMenuDialog").contains("createPanelCloseButton"))
    }

    @Test
    fun `a covered child panel keeps the parent alive and does not stack a second blur`() {
        val present = SettingsUiSource.function("presentSizedModalDialog")
        // 父面板不能被硬关，否则"盖住"无从谈起。
        assertTrue(present.contains("if (cover == null) activeConfirmDialog?.dismiss()"))
        // 子面板收起后父面板要变回"当前弹窗"，否则更新检查会在它脸上再弹一个。
        assertTrue(present.contains("activeConfirmDialog = coveredParent?.takeIf { it.isShowing }"))
        // 两层 blur-behind 会把底页糊到发灰，还会糊掉特意留在下面的父面板。
        assertTrue(present.contains("if (cover != null) null else ModalBackdropBlur.createOrNull("))
        // 位置没定就压首帧，展开端会按旧的居中矩形算，形变往错的地方长。
        val preDraw = present.after("if (morphController != null && morphLayer != null)")
        assertTrue(preDraw.indexOf("applyCoverPlacement()") < preDraw.indexOf("prepareFirstFrame()"))
        // 高度必须算出来写死。WRAP_CONTENT + minimumHeight 会被 FrameLayout 的剩余空间
        // 撑到窗口底部（实测 [278,314][1398,3078]，父面板底边其实是 2337）。
        assertFalse("minimumHeight 撑不住，别再退回去", present.contains("container.minimumHeight ="))
        // 至少盖住父面板；但不能超出窗口（从父面板顶边到 root 底边），否则长内容的子面板
        //（如 NPatch 向导的后一页）会把底部按钮挤出屏幕，多出的部分由面板里的滚动区承担。
        assertTrue(present.contains("val available = root.height - top - (16 * density).toInt()"))
        assertTrue(present.contains("maxOf(target.height.toInt(), minOf(container.measuredHeight, available))"))
        assertTrue(present.contains("View.MeasureSpec.UNSPECIFIED"))
        // 按可见表面对齐，不是容器矩形：气泡的小角在 padding 里，会多出 9dp。
        val row = SettingsUiSource.function("createTelemetryMenuRow")
        assertTrue(row.contains("cover = modalSurfaceBounds(dialog, dialogContainer)"))
        val surface = SettingsUiSource.function("modalSurfaceBounds")
        assertTrue(surface.contains("bounds.top + insets.top"))
        assertTrue(surface.contains("bounds.bottom - insets.bottom"))
    }

    @Test
    fun `a captured rect never hijacks the bubble path or the live anchor`() {
        val present = SettingsUiSource.function("presentSizedModalDialog")
        // 气泡要拿来源 ImageView 做图案交接（见 09-10 的图案守恒铁律），静态矩形顶不了，
        // 只准服务居中卡片这一条；实时 View 永远优先，既有 30 个调用点行为不变。
        assertTrue(present.contains("morphAnchor == null && anchorStyle == AnchorStyle.CONTAINER"))
        assertTrue(present.contains("morphAnchor?.let(::modalAnchorBounds) ?: capturedAnchorBounds"))
        // 关掉系统动画时静态矩形同样不得启用形变。
        assertTrue(present.contains("capturedAnchorBounds?.takeIf { ValueAnimator.areAnimatorsEnabled() }"))
        // 退场与旋转要走同一条解析，不能一边用实时 View 一边用陈旧矩形。
        assertTrue(present.contains("resolveAnchorOnScreen()?.let { currentAnchor ->"))
    }

    @Test
    fun `detail owns the switch and retains disclosure and save failure checks`() {
        val detail = SettingsUiSource.function("showTelemetryInfoDialog")
        assertTrue(detail.contains("createTelemetryMenuRow(dialog, container, showControl = true)"))
        val row = SettingsUiSource.function("createTelemetryMenuRow")
        assertTrue(row.contains("enabled && !TelemetryStore.hasCurrentDisclosure(applicationContext)"))
        assertTrue(row.contains("if (!saved)"))
        assertTrue(row.contains("telemetry_choice_save_failed"))
        assertTrue(row.contains("animateTelemetrySummary(summary, getString("))
        assertTrue(row.contains("android.text.StaticLayout.Builder.obtain"))
    }
}
