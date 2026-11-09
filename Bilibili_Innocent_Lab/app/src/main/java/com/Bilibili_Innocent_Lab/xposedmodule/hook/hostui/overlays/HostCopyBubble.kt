package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.overlays

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.lumen.coacervation.engine.motion.LumenEasing
import com.lumen.coacervation.engine.widget.BubbleDrawable

/** 引擎绘制外壳；宿主适配保留原来的定位、独立文字层与 200/150ms 进退场。 */
internal class HostCopyBubble(private val activity: Activity, private val anchor: View,
    private val content: TextView, private val light: Boolean, private val outlined: Boolean) {
    private val density = activity.resources.displayMetrics.density
    private var released = false
    private var closing = false
    private var registered = false
    private var animation: Animator? = null
    private lateinit var surface: BubbleDrawable
    private lateinit var body: LinearLayout
    var onClosed: () -> Unit = {}
    val dialog = object : Dialog(activity, R.style.FreeCopyBubble) {
        override fun dismiss() { try { super.dismiss() } finally { release() } }
    }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityDestroyed(owner: Activity) { if (owner === activity) dialog.dismiss() }
        override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(owner: Activity) = Unit
        override fun onActivityResumed(owner: Activity) = Unit
        override fun onActivityPaused(owner: Activity) = Unit
        override fun onActivityStopped(owner: Activity) = Unit
        override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
    }

    fun show() {
        try {
            val metrics = activity.resources.displayMetrics
            val bounds = if (Build.VERSION.SDK_INT >= 30)
                runCatching { activity.windowManager.currentWindowMetrics.bounds }.getOrNull() else null
            val width = bounds?.width()?.takeIf { it > 0 } ?: metrics.widthPixels
            val height = bounds?.height()?.takeIf { it > 0 } ?: metrics.heightPixels
            val maxContentWidth = (width * .72).toInt()
            val maxBubbleWidth = maxContentWidth + dp(36)
            val location = IntArray(2).also(anchor::getLocationOnScreen)
            val x = HostCopyBubblePlacement.left(location[0], maxBubbleWidth, width, dp(16), dp(8))
            val tailWidth = dp(12).toFloat()
            val radius = 14f * density
            val tailOffset = (location[0] + anchor.width / 2f - x).coerceIn(
                tailWidth / 2f + radius, maxBubbleWidth - tailWidth / 2f - radius)
            surface = BubbleDrawable(if (light) Color.WHITE else 0xFF2A2B2E.toInt(),
                tailWidth, dp(6).toFloat(), radius, tailOffset,
                if (outlined) Color.BLACK else 0, if (outlined) dp(1).toFloat() else 0f)
            body = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = surface
                addView(TextView(activity).apply {
                    setText(content.text, TextView.BufferType.SPANNABLE)
                    textSize = 15f
                    setLineSpacing(dp(3).toFloat(), 1f)
                    maxLines = 12
                    maxWidth = maxContentWidth
                    alpha = 0f
                })
            }
            content.maxWidth = maxContentWidth
            content.setTextColor(if (light) 0xFF1C1B1F.toInt() else 0xFFE8E8E8.toInt())
            body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val y = HostCopyBubblePlacement.top(location[1], anchor.height, anchor.isShown,
                body.measuredHeight, height, dp(4), dp(8))
            val root = FrameLayout(activity).apply {
                addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            body.x = x; body.y = y
            content.x = x + dp(18); content.y = y + dp(14)
            root.defaultFocusHighlightEnabled = false
            body.defaultFocusHighlightEnabled = false
            content.defaultFocusHighlightEnabled = false
            body.setOnClickListener { }
            content.setOnClickListener { }
            root.setOnClickListener { closeAnimated() }
            dialog.setOwnerActivity(activity)
            dialog.setContentView(root)
            dialog.setCanceledOnTouchOutside(false)
            prepareWindow()
            activity.application.registerActivityLifecycleCallbacks(lifecycle)
            registered = true
            surface.scale = .9f; surface.strokeAlpha = 0f
            body.alpha = 0f; content.alpha = 0f
            content.setTextIsSelectable(false)
            dialog.show()
            prepareWindow()
            @Suppress("DEPRECATION")
            dialog.window?.apply {
                statusBarColor = Color.BLACK
                navigationBarColor = Color.BLACK
                decorView.systemUiVisibility = decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            enter()
        } catch (error: Throwable) { dialog.dismiss(); throw error }
    }

    private fun prepareWindow() {
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setElevation(0f)
            decorView.setBackgroundColor(Color.TRANSPARENT)
            decorView.elevation = 0f
        }
    }

    private fun enter() {
        animation = AnimatorSet().apply {
            playTogether(ValueAnimator.ofFloat(.9f, 1f).apply {
                addUpdateListener {
                    HostThreadGuard.run("host.copy_bubble.frame") { surface.scale = it.animatedValue as Float }
                }
            }, ObjectAnimator.ofFloat(body, View.ALPHA, 0f, 1f), ObjectAnimator.ofFloat(content, View.ALPHA, 0f, 1f))
            duration = 200L
            interpolator = LumenEasing.standardDecelerate()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = HostThreadGuard.run("host.copy_bubble.enter_end") {
                    if (released || closing) return@run
                    content.setTextIsSelectable(true)
                    this@HostCopyBubble.animation = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 120L
                        interpolator = LumenEasing.standardDecelerate()
                        addUpdateListener {
                            HostThreadGuard.run("host.copy_bubble.frame") { surface.strokeAlpha = it.animatedValue as Float }
                        }
                        start()
                    }
                }
            })
        }
        animation?.start()
    }

    private fun closeAnimated() {
        if (released || closing) return
        closing = true
        cancelAnimation()
        content.setTextIsSelectable(false)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        animation = AnimatorSet().apply {
            playTogether(ValueAnimator.ofFloat(surface.scale, .92f).apply {
                duration = 150L
                addUpdateListener {
                    HostThreadGuard.run("host.copy_bubble.frame") { surface.scale = it.animatedValue as Float }
                }
            }, ObjectAnimator.ofFloat(body, View.ALPHA, 0f).apply { duration = 150L },
                ObjectAnimator.ofFloat(content, View.ALPHA, 0f).apply { duration = 150L },
                ValueAnimator.ofFloat(surface.strokeAlpha, 0f).apply {
                    duration = 100L
                    addUpdateListener {
                        HostThreadGuard.run("host.copy_bubble.frame") { surface.strokeAlpha = it.animatedValue as Float }
                    }
                })
            interpolator = LumenEasing.standardAccelerate()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) =
                    HostThreadGuard.run("host.copy_bubble.exit_end") { dialog.dismiss() }
            })
        }
        animation?.start()
    }

    private fun cancelAnimation() {
        animation?.removeAllListeners()
        animation?.cancel()
        animation = null
    }

    private fun release() {
        if (released) return
        released = true
        cancelAnimation()
        if (registered) { activity.application.unregisterActivityLifecycleCallbacks(lifecycle); registered = false }
        content.customSelectionActionModeCallback = null
        onClosed()
        onClosed = {}
    }

    private fun dp(value: Int) = (value * density).toInt()
}
