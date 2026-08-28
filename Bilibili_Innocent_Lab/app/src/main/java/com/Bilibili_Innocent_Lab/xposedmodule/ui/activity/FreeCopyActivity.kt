/*
 * FreeCopyActivity: 评论区自由复制界面
 *
 * 由 HookEntry 在 B 站进程内通过显式 Intent 启动（跨进程）：
 *   - Intent.setClassName("com.Bilibili_Innocent_Lab.xposedmodule", 本类全名)
 *   - extra "comment_text" 携带评论全文
 *
 * 界面：顶部 AppBar（关闭 + 标题）+ 提示行 + 可选择文本区。
 * 文本区用 TextView + setTextIsSelectable(true)，长按即触发
 * 系统级文本选择菜单（与系统全选/复制完全一致），零自绘选择逻辑。
 * 风格：Material You（Monet 动态取色）+ 与 MainActivity 一致的圆角卡片语言。
 */
@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.view.updatePadding
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.extension.setContentView
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.android.widget.TextView
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.yukihookapi.hook.factory.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors

class FreeCopyActivity : AppViewsActivity() {

    companion object {
        /** 防御性上限：即使未来重新暴露 Activity，也不接受无限大的外部文本。 */
        private const val MAX_TEXT_LENGTH = 12_000
    }

    private val monetColors by lazy { MonetColors.fromWallpaper(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!UserTermsConsentStore.readOrInitialize(applicationContext).isAuthorized) {
            finish()
            return
        }
        // 预见式返回（跟随主界面的实验性开关；Android 14+ 才有 window 级运行时接口）
        val predictiveBackEnabled = runCatching {
            prefs().getBoolean(com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry.PREF_PREDICTIVE_BACK_ENABLED, false)
        }.getOrDefault(false)
        com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack.apply(window, predictiveBackEnabled)
        val commentText = intent?.getStringExtra("comment_text")
            ?.take(MAX_TEXT_LENGTH)
            ?.takeIf { it.isNotBlank() } ?: run {
            finish()
            return
        }

        setContentView {
            LinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(monetColors.background)
                }
            ) {
                // ===== 顶部 AppBar =====
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER or Gravity.START
                        setBackgroundColor(monetColors.surfaceVariant)
                        updatePadding(left = 4, top = 6, right = 16, bottom = 6)
                    }
                ) {
                    // 关闭按钮（✕，48dp 点击热区）
                    TextView(
                        lparams = LayoutParams(48.dp, 48.dp),
                        init = {
                            text = "✕"
                            contentDescription = stringResource(R.string.free_copy_close)
                            gravity = Gravity.CENTER
                            textSize = 16f
                            textColor = colorResource(R.color.colorTextGray)
                            setOnClickListener { finish() }
                        }
                    )
                    // 标题
                    TextView(
                        lparams = LayoutParams {
                            weight = 1f
                        },
                        init = {
                            text = stringResource(R.string.free_copy_title)
                            textColor = colorResource(R.color.colorTextGray)
                            textSize = 17f
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        }
                    )
                }
                // ===== 提示行 =====
                TextView(
                    lparams = LayoutParams(widthMatchParent = true) {
                        leftMargin = 20.dp
                        topMargin = 14.dp
                        rightMargin = 20.dp
                    },
                    init = {
                        text = stringResource(R.string.free_copy_hint)
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 12f
                        alpha = 0.7f
                    }
                )
                // ===== 可选择文本区（卡片内 TextView，系统级选择复制）=====
                NestedScrollView(
                    lparams = LayoutParams(widthMatchParent = true) {
                        weight = 1f
                        leftMargin = 15.dp
                        topMargin = 10.dp
                        rightMargin = 15.dp
                        bottomMargin = 15.dp
                    }
                ) {
                    TextView(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            text = commentText
                            textColor = colorResource(R.color.colorTextGray)
                            textSize = 16f
                            setLineSpacing(6f, 1f)
                            updatePadding(left = 18, top = 16, right = 18, bottom = 16)
                            background = GradientDrawable().apply {
                                cornerRadius = resources.displayMetrics.density * 15f
                                setColor(monetColors.surfaceVariant)
                            }
                            // ★核心：系统级文本选择（长按出系统选择菜单，自由拖选片段）
                            setTextIsSelectable(true)
                        }
                    )
                }
            }
        }
    }
}
