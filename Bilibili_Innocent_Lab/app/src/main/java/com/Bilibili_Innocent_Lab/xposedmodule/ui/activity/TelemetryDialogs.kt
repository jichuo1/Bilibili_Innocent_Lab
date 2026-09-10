// 跟着代码一起搬过来的：MainActivity.kt 也是文件级抑制这条规则的。
// **文件级注解不会随函数外移**，弹窗每搬一批都要确认这些抑制有没有跟上，
// 否则门禁里会凭空多出几条 Warning（这批就多了 3 条 SetTextI18n）。
@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryActionResult
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryActionStatus
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.telemetry.TelemetryStore
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.toast
import android.widget.FrameLayout as NativeFrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.ScrollView as NativeScrollView
import android.widget.TextView as NativeTextView

/*
 * 遥测相关的弹窗，从 MainActivity 外移而来（函数体逐字搬迁，未改行为）。
 *
 * ## 为什么是 MainActivity 的扩展函数
 *
 * 这些弹窗要用设置页的共用底座——createModalContainer() / presentModalDialog() /
 * dismissWithAnimation()，那套东西承载了返回手势接管、图标锚点形变、气泡摆放、
 * 背景毛玻璃等一整条时序，不可能各自复制一份。写成 `MainActivity` 的扩展函数，
 * 就能原样调用它们（底座已相应放宽为 internal），同时把这些弹窗自己的实现细节
 * 关进本文件：只有真正被外部调用的入口是 internal，其余都是文件私有。
 *
 * ## this@MainActivity 的替代写法
 *
 * 扩展函数里没有 `this@MainActivity` 这个标签（它只存在于类体内），
 * 而嵌套的 `apply { }` 会把 `this` 重新绑定到视图上。所以需要显式引用 Activity 的地方
 * 统一在函数开头取 `val activity = this`。
 */

internal fun MainActivity.showTelemetryDisclosureDialog() {
    val activity = this
    if (isFinishing || isDestroyed) return
    telemetryDisclosurePrompted = true
    val dialog = Dialog(this)
    val container = createModalContainer()
    val density = resources.displayMetrics.density
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.telemetry_disclosure_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(NativeScrollView(this).apply {
        addView(NativeTextView(activity).apply {
            text = getString(R.string.telemetry_disclosure_message) + "\n\n" +
                getString(R.string.telemetry_info_body)
            textColor = getColor(R.color.colorTextDark)
            textSize = 14f
            setLineSpacing(4 * density, 1f)
        })
    }, NativeLinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        minOf((380 * density).toInt(), (resources.displayMetrics.heightPixels * 0.5f).toInt())
    ).apply { topMargin = (12 * density).toInt() })
    fun decide(enabled: Boolean) {
        if (!TelemetryStore.writeConsentChoice(applicationContext, enabled)) {
            toast(getString(R.string.telemetry_choice_save_failed))
            return
        }
        dismissWithAnimation(dialog, container) {
            if (enabled) TelemetryCoordinator.maybeUpload(applicationContext)
        }
    }
    container.addView(createTermsActionButton(getString(R.string.telemetry_disclosure_decline), filled = false) {
        decide(false)
    })
    container.addView(createTermsActionButton(getString(R.string.telemetry_disclosure_accept), filled = true) {
        decide(true)
    })
    presentModalDialog(dialog, container)
}

internal fun MainActivity.showTelemetryInfoDialog() {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.telemetry_info_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    container.addView(createTelemetryMenuRow(dialog, container, showControl = true))
    container.addView(
        createGitHubMenuRow(
            title = getString(R.string.telemetry_explanation_action),
            subtitle = getString(R.string.telemetry_explanation_summary),
            highlight = false
        ) {
            dismissWithAnimation(dialog, container) { showTelemetryExplanationDialog() }
        }
    )
    container.addView(
        createGitHubMenuRow(
            title = getString(R.string.telemetry_preview_action),
            subtitle = getString(R.string.telemetry_preview_note),
            highlight = false
        ) {
            dismissWithAnimation(dialog, container) {
                toast(getString(R.string.telemetry_collecting))
                TelemetryCoordinator.preview(applicationContext) { result ->
                    if (isFinishing || isDestroyed) return@preview
                    val payload = result.preview
                    if (result.status == TelemetryActionStatus.SUCCESS && payload != null) {
                        showTelemetryPayloadPreview(payload)
                    } else {
                        toast(getString(telemetryActionMessage(result)))
                    }
                }
            }
        }
    )
    container.addView(
        createGitHubMenuRow(
            title = getString(R.string.telemetry_upload_action),
            // 开关状态由上方控制行实时展示，避免切换后保留过期的状态文案。
            subtitle = getString(R.string.telemetry_manual_summary),
            highlight = false
        ) {
            dismissWithAnimation(dialog, container) {
                TelemetryCoordinator.maybeUpload(
                    applicationContext,
                    manual = true
                ) { result ->
                    if (!isFinishing && !isDestroyed) {
                        toast(getString(telemetryActionMessage(result)))
                    }
                }
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
    )
    container.addView(
        createGitHubMenuRow(
            title = getString(R.string.telemetry_purge_action),
            subtitle = getString(R.string.telemetry_purge_summary),
            highlight = false
        ) {
            dismissWithAnimation(dialog, container) {
                showTelemetryPurgeConfirmDialog()
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
    )

    val closeRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(
            createTermsActionButton(getString(R.string.dialog_close), filled = false) {
                dismissWithAnimation(dialog, container) {}
            }
        )
    }
    container.addView(
        closeRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (14 * density).toInt() }
    )
    presentModalDialog(dialog, container)
}

private fun MainActivity.showTelemetryExplanationDialog() {
    val activity = this
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.telemetry_explanation_action)
        textColor = getColor(R.color.colorTextDark)
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    val bodyScroll = NativeScrollView(this).apply {
        isVerticalScrollBarEnabled = true
        addView(
            NativeTextView(activity).apply {
                text = getString(R.string.telemetry_info_body)
                textColor = getColor(R.color.colorTextDark)
                textSize = 13f
                setLineSpacing(4 * density, 1f)
            },
            NativeFrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    container.addView(
        bodyScroll,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf((300 * density).toInt(), (resources.displayMetrics.heightPixels * 0.42f).toInt())
        ).apply {
            topMargin = (12 * density).toInt()
            bottomMargin = (10 * density).toInt()
        }
    )
    container.addView(createTermsActionButton(getString(R.string.dialog_close), filled = false) {
        dismissWithAnimation(dialog, container) { showTelemetryInfoDialog() }
    })
    presentModalDialog(dialog, container)
}

private fun MainActivity.showTelemetryPayloadPreview(payload: String) {
    val activity = this
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.telemetry_preview_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    )
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.telemetry_preview_note)
            textColor = getColor(R.color.colorTextDark)
            textSize = 12f
            alpha = 0.72f
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
    )
    val scroll = NativeScrollView(this).apply {
        isVerticalScrollBarEnabled = true
        addView(
            NativeTextView(activity).apply {
                text = payload
                textColor = getColor(R.color.colorTextDark)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
        )
    }
    container.addView(
        scroll,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            minOf((430 * density).toInt(), (resources.displayMetrics.heightPixels * 0.58f).toInt())
        ).apply { topMargin = (12 * density).toInt() }
    )
    val closeRow = NativeLinearLayout(this).apply {
        gravity = Gravity.END
        addView(
            createTermsActionButton(getString(R.string.dialog_close), filled = false) {
                dismissWithAnimation(dialog, container) {}
            }
        )
    }
    container.addView(
        closeRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() }
    )
    presentModalDialog(dialog, container)
}

private fun MainActivity.showTelemetryPurgeConfirmDialog() {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.telemetry_purge_confirm_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    )
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.telemetry_purge_confirm_message)
            textColor = getColor(R.color.colorTextDark)
            textSize = 13f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
    )
    val buttons = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END
        addView(
            createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
                dismissWithAnimation(dialog, container) {}
            },
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addView(
            createTermsActionButton(getString(R.string.dialog_confirm), filled = true) {
                dismissWithAnimation(dialog, container) {
                    TelemetryCoordinator.purge(applicationContext) { result ->
                        if (!isFinishing && !isDestroyed) {
                            toast(getString(telemetryActionMessage(result, purge = true)))
                        }
                    }
                }
            },
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (8 * density).toInt() }
        )
    }
    container.addView(
        buttons,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (18 * density).toInt() }
    )
    presentModalDialog(dialog, container)
}

private fun telemetryActionMessage(
    result: TelemetryActionResult,
    purge: Boolean = false
): Int = when (result.status) {
    TelemetryActionStatus.SUCCESS -> if (purge) {
        R.string.telemetry_purge_success
    } else {
        R.string.telemetry_upload_success
    }
    TelemetryActionStatus.DISABLED -> R.string.telemetry_upload_disabled
    TelemetryActionStatus.NOT_DUE -> R.string.telemetry_upload_not_due
    TelemetryActionStatus.MANUAL_LIMIT_REACHED -> R.string.telemetry_manual_limit_reached
    TelemetryActionStatus.BUSY -> R.string.telemetry_action_busy
    TelemetryActionStatus.HOST_UNAVAILABLE -> R.string.telemetry_host_unavailable
    TelemetryActionStatus.IDENTITY_UNAVAILABLE -> R.string.telemetry_identity_unavailable
    TelemetryActionStatus.RETIRED -> R.string.telemetry_service_retired
    TelemetryActionStatus.RATE_LIMITED -> R.string.telemetry_rate_limited
    TelemetryActionStatus.REJECTED -> R.string.telemetry_request_rejected
    TelemetryActionStatus.FAILED -> R.string.telemetry_request_failed
    TelemetryActionStatus.NOTHING_TO_DELETE -> R.string.telemetry_nothing_to_delete
}
