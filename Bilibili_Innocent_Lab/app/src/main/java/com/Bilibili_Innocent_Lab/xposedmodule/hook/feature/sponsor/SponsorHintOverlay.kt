package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiMessages
import java.lang.ref.WeakReference

/**
 * 页面内 Toast 样式提示；撤销期限由播放控制器维护，不建立第二套计时器。
 *
 * [Placement.STORY] 用于竖屏短视频：右下是宿主的操作按钮列，所以提示改为底部居中，
 * 并把系统栏下沿加进底边距。[Placement.DETAIL] 保持原有位置。
 */
internal class SponsorHintOverlay(
    root: FrameLayout,
    private val placement: Placement = Placement.DETAIL,
    private val action: (SponsorPlaybackController.Hint) -> Unit
) {
    enum class Placement { DETAIL, STORY }
    private val root = WeakReference(root)
    private var widget = WeakReference<View>(null)
    private var shown = SponsorPlaybackController.Hint.NONE

    fun hide() {
        if (shown == SponsorPlaybackController.Hint.NONE && widget.get() == null) return
        widget.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        widget = WeakReference(null)
        shown = SponsorPlaybackController.Hint.NONE
    }

    fun render(hint: SponsorPlaybackController.Hint, messages: InjectedUiMessages) {
        if (hint == SponsorPlaybackController.Hint.NONE) { hide(); return }
        if (shown == hint && widget.get()?.parent != null) return
        hide()
        val root = root.get() ?: return
        val context = root.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun surface() = GradientDrawable().apply {
            setColor(0xDD282828.toInt()); cornerRadius = dp(28).toFloat()
        }
        val button = Button(context).apply {
            isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
            minHeight = dp(48); minimumWidth = dp(48); minWidth = dp(48)
            text = if (hint == SponsorPlaybackController.Hint.UNDO) messages.sponsorUndoAction else messages.sponsorSkip
            contentDescription = if (hint == SponsorPlaybackController.Hint.UNDO) messages.sponsorUndo else messages.sponsorSkip
            setPadding(dp(12), dp(8), dp(12), dp(8))
            // 点击在宿主窗口的 UI 线程分发，逃逸异常会直接杀宿主进程，框架的 PROTECTIVE 兜不住。
            setOnClickListener { HostThreadGuard.run("sponsorblock.hint.click") { if (shown == hint) action(hint) } }
        }
        val view = if (hint == SponsorPlaybackController.Hint.UNDO) {
            // 只消费按钮自己的触摸区域，提示文字与容器不截获宿主手势。
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(8), dp(4))
                background = surface(); elevation = dp(6).toFloat()
                addView(TextView(context).apply {
                    text = messages.sponsorSkipped; textSize = 13f; setTextColor(Color.WHITE)
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    val width = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    maxWidth = (width - dp(160)).coerceAtLeast(dp(48))
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
                button.setTextColor(0xFFB8D9FF.toInt())
                button.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                    ColorDrawable(Color.TRANSPARENT), GradientDrawable().apply {
                        setColor(Color.WHITE); cornerRadius = dp(24).toFloat()
                    })
                addView(button)
            }
        } else button.apply { background = surface(); elevation = dp(6).toFloat() }
        val story = placement == Placement.STORY
        val horizontal = if (story || hint == SponsorPlaybackController.Hint.UNDO) Gravity.CENTER_HORIZONTAL else Gravity.END
        val inset = if (story) ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0 else 0
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or horizontal).apply {
            marginStart = dp(16); marginEnd = dp(16)
            bottomMargin = (if (story) dp(120) else dp(80)) + inset
        })
        widget = WeakReference(view)
        shown = hint
    }
}
