@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

// MainActivity 的**嵌套**类型：扩展函数里嵌套 classifier 不在作用域内，必须显式导入。
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.AppLanguage
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.core.os.LocaleListCompat
import androidx.core.view.setPadding
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundMode
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRealtimeCaptureStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRepository
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.ImageView
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/*
 * 界面美学相关的弹窗，从 MainActivity 外移而来（函数体逐字搬迁，未改行为）：
 * 皮肤选择、Liquid 背景图、Liquid 实时取景确认、应用语言。
 *
 * 写成 `MainActivity` 的扩展函数，是为了原样调用设置页的共用底座——
 * createModalContainer() / presentModalDialog() / dismissWithAnimation()，
 * 那套东西承载了返回手势接管、图标锚点形变、气泡摆放与背景毛玻璃的完整时序。
 *
 * ## 什么不能跟着搬出来
 *
 * 只搬函数。**可变状态与生命周期注册必须留在 Activity 里**：
 *
 * - `liquidBackgroundPicker` 是 `registerForActivityResult(...)`，它在 Activity 构造期
 *   就要向宿主注册，搬成顶层属性会直接失去注册；
 * - `skinSelectionActionInProgress`、`liquidBackgroundDialog` 这类 `var` 一旦变成
 *   文件级顶层属性，就从"每个 Activity 一份"变成**进程级单例**，跨 Activity 重建
 *   仍然残留——这是行为改变，不是重构。
 *
 * 所以它们留在 MainActivity 上，只放宽为 internal 供本文件读写。
 */

/** 界面皮肤单选弹窗；只在同步持久化成功后退场并重建 Activity。 */
internal fun MainActivity.showSkinSelectionDialog() {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    val current = SkinRepository.resolveRequestedSkin(applicationContext)
    skinSelectionActionInProgress = false

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.skin_dialog_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * density).toInt() }
    )

    SkinId.entries.forEachIndexed { index, skin ->
        val title = getString(skinTitleRes(skin))
        val description = getString(skinDescriptionRes(skin))
        val selected = current == skin
        val row = createGitHubMenuRow(
            title = title,
            subtitle = description,
            highlight = selected
        ) {
            selectSkinFromDialog(dialog, container, skin)
        }.apply {
            isSelected = selected
            contentDescription = getString(
                if (selected) R.string.skin_option_selected
                else R.string.skin_option_not_selected,
                title,
                description
            )
        }
        container.addView(
            row,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = (6 * density).toInt()
            }
        )
    }

    val closeRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    closeRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.dialog_close)
            textColor = getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(),
                (11 * density).toInt(),
                (20 * density).toInt(),
                (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!skinSelectionActionInProgress) {
                    dismissWithAnimation(dialog, container) {}
                }
            }
        }
    )
    container.addView(
        closeRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (18 * density).toInt() }
    )

    presentModalDialog(dialog, container)
}

/** 实验性功能中的自定义背景配置；选择器只授权单个 image URI，不申请媒体库权限。 */
internal fun MainActivity.showLiquidBackgroundDialog() {
    val activity = this
    if (liquidBackgroundImportInProgress) return
    val density = resources.displayMetrics.density
    val state = LiquidBackgroundStore.read(applicationContext)
    val dialog = Dialog(this)
    val container = createModalContainer()
    liquidBackgroundDialog = dialog
    liquidBackgroundDialogContainer = container
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.liquid_background_dialog_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.liquid_background_dialog_description)
            textColor = getColor(R.color.colorTextGray)
            textSize = 13f
            alpha = 0.78f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * density).toInt() }
    )

    if (state.config.mode == LiquidBackgroundMode.CUSTOM && state.assetPresent) {
        val preview = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            contentDescription = getString(R.string.liquid_background_preview_description)
            background = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(monetColors.surfaceVariant)
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        container.addView(
            preview,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (150 * density).toInt()
            ).apply { topMargin = (14 * density).toInt() }
        )
        loadLiquidBackgroundPreview(dialog, preview, state.config)
    }

    val chooseTitle = getString(
        if (state.config.mode == LiquidBackgroundMode.CUSTOM) {
            R.string.liquid_background_replace
        } else R.string.liquid_background_choose
    )
    container.addView(
        createGitHubMenuRow(
            title = chooseTitle,
            subtitle = getString(R.string.liquid_background_choose_description),
            highlight = false
        ) {
            if (!liquidBackgroundImportInProgress) {
                liquidBackgroundPicker.launch(arrayOf("image/*"))
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (14 * density).toInt() }
    )

    if (state.config.mode == LiquidBackgroundMode.CUSTOM) {
        container.addView(
            createGitHubMenuRow(
                title = getString(R.string.liquid_background_restore_automatic),
                subtitle = getString(R.string.liquid_background_restore_description),
                highlight = false
            ) { restoreAutomaticLiquidBackground() },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        )
    }

    val realtimeCaptureEnabled = LiquidRealtimeCaptureStore.isEnabled(applicationContext)
    container.addView(
        createGitHubMenuRow(
            title = getString(R.string.liquid_realtime_capture_title),
            subtitle = liquidRealtimeCaptureSummary(realtimeCaptureEnabled),
            highlight = realtimeCaptureEnabled
        ) {
            when {
                !isLiquidRealtimeCaptureSupported() ->
                    toast(getString(R.string.liquid_realtime_capture_unsupported))
                realtimeCaptureEnabled -> applyLiquidRealtimeCaptureEnabled(false)
                else -> beginLiquidRealtimeCaptureConfirmation()
            }
        }.apply {
            contentDescription = buildString {
                append(getString(R.string.liquid_realtime_capture_title))
                append('，')
                append(liquidRealtimeCaptureSummary(realtimeCaptureEnabled))
            }
            if (!isLiquidRealtimeCaptureSupported()) alpha = 0.55f
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (6 * density).toInt() }
    )

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.liquid_background_backup_notice)
            textColor = getColor(R.color.colorTextGray)
            textSize = 12f
            alpha = 0.64f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (12 * density).toInt() }
    )

    val closeRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(NativeTextView(activity).apply {
            text = getString(R.string.dialog_close)
            textColor = getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(),
                (11 * density).toInt(),
                (20 * density).toInt(),
                (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!liquidBackgroundImportInProgress) {
                    dismissWithAnimation(dialog, container) {}
                }
            }
        })
    }
    container.addView(
        closeRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * density).toInt() }
    )
    presentModalDialog(dialog, container)
    dialog.setOnDismissListener {
        if (activeConfirmDialog === dialog) activeConfirmDialog = null
        if (liquidBackgroundDialog === dialog) {
            liquidBackgroundDialog = null
            liquidBackgroundDialogContainer = null
        }
    }
}

/** 高负载模式首次开启必须由用户显式确认；关闭保持一键可逆。 */
internal fun MainActivity.showLiquidRealtimeCaptureConfirmDialog() {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.liquid_realtime_capture_confirm_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.liquid_realtime_capture_confirm_message)
            textColor = getColor(R.color.colorTextGray)
            textSize = 13f
            alpha = 0.82f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (10 * density).toInt() }
    )

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.dialog_cancel)
            textColor = getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(),
                (11 * density).toInt(),
                (20 * density).toInt(),
                (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissWithAnimation(dialog, container) {
                    if (!isFinishing && !isDestroyed) showLiquidBackgroundDialog()
                }
            }
        }
    )
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.liquid_realtime_capture_confirm_enable)
            textColor = monetColors.onPrimary
            textSize = 15f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(
                (22 * density).toInt(),
                (11 * density).toInt(),
                (22 * density).toInt(),
                (11 * density).toInt()
            )
            val radius = 20 * density
            val content = GradientDrawable().apply {
                cornerRadius = radius
                setColor(monetColors.primary)
            }
            val rippleMask = GradientDrawable().apply {
                cornerRadius = radius
                setColor(Color.WHITE)
            }
            background = RippleDrawable(
                ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(monetColors.onPrimary, 0x33)
                ),
                content,
                rippleMask
            )
            skinActionButton(this, filled = true)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismissWithAnimation(dialog, container) {
                    applyLiquidRealtimeCaptureEnabled(true)
                }
            }
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = (16 * density).toInt() }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (22 * density).toInt() }
    )

    presentModalDialog(dialog, container)
}

/** 应用语言单选弹窗：沿用现有模态容器、选中强调色和统一进退场动画。 */
internal fun MainActivity.showAppLanguageDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this)
    val container = createModalContainer()
    val current = currentAppLanguage()

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.app_language_dialog_title)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.app_language_tip)
            textColor = getColor(R.color.colorTextDark)
            textSize = 12f
            alpha = 0.72f
            setLineSpacing(3 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (5 * density).toInt()
            bottomMargin = (12 * density).toInt()
        }
    )

    AppLanguage.entries.forEachIndexed { index, language ->
        container.addView(
            createAppLanguageRow(
                title = getString(language.labelRes),
                selected = language == current
            ) {
                dismissWithAnimation(dialog, container) {
                    applyAppLanguage(language)
                }
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = (6 * density).toInt()
            }
        )
    }

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    buttonRow.addView(
        NativeTextView(this).apply {
            text = getString(R.string.dialog_close)
            textColor = getColor(R.color.colorTextGray)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(
                (20 * density).toInt(),
                (11 * density).toInt(),
                (20 * density).toInt(),
                (11 * density).toInt()
            )
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissWithAnimation(dialog, container) {} }
        }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (18 * density).toInt() }
    )

    presentModalDialog(dialog, container, anchor)
}

private fun MainActivity.selectSkinFromDialog(
    dialog: Dialog,
    container: NativeLinearLayout,
    target: SkinId
) {
    if (skinSelectionActionInProgress) return
    skinSelectionActionInProgress = true
    if (SkinRepository.resolveRequestedSkin(applicationContext) == target) {
        dialog.setCancelable(false)
        dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        dismissWithAnimation(dialog, container) {
            skinSelectionActionInProgress = false
        }
        return
    }
    val result = runCatching {
        SkinRepository.beginSelection(applicationContext, target)
    }.onFailure { throwable ->
        Log.e("BilibiliInnocentLab", "persist skin selection failed", throwable)
    }.getOrNull()
    if (result?.persisted != true) {
        skinSelectionActionInProgress = false
        toast(getString(R.string.skin_save_failed))
        return
    }
    dialog.setCancelable(false)
    dialog.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
    dismissWithAnimation(dialog, container) {
        if (!isFinishing && !isDestroyed) recreate()
    }
}

private fun MainActivity.createAppLanguageRow(
    title: CharSequence,
    selected: Boolean,
    onClick: () -> Unit
): NativeTextView {
    val density = resources.displayMetrics.density
    return NativeTextView(this).apply {
        text = title
        textColor = if (selected) monetColors.primary else getColor(R.color.colorTextGray)
        textSize = 16f
        typeface = Typeface.create(
            Typeface.DEFAULT,
            if (selected) Typeface.BOLD else Typeface.NORMAL
        )
        setPadding(
            (16 * density).toInt(),
            (13 * density).toInt(),
            (16 * density).toInt(),
            (13 * density).toInt()
        )
        background = selfRippleBackground(14f)
        isSelected = selected
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}

private fun MainActivity.loadLiquidBackgroundPreview(
    dialog: Dialog,
    preview: android.widget.ImageView,
    config: LiquidBackgroundConfig
) {
    val backgroundColor = monetColors.background
    val dark = ColorUtils.calculateLuminance(monetColors.surface) < 0.5
    liquidBackgroundWorker.execute {
        val bitmap = LiquidBackgroundStore.decodeBackdrop(
            context = applicationContext,
            config = config,
            targetWidth = 640,
            targetHeight = 360,
            backgroundColor = backgroundColor,
            dark = dark
        ) ?: return@execute
        runOnUiThread {
            if (!isFinishing && !isDestroyed && dialog.isShowing &&
                liquidBackgroundDialog === dialog
            ) {
                preview.setImageBitmap(bitmap)
            } else bitmap.recycle()
        }
    }
}

private fun MainActivity.restoreAutomaticLiquidBackground() {
    if (liquidBackgroundImportInProgress) return
    liquidBackgroundImportInProgress = true
    liquidBackgroundDialog?.setCancelable(false)
    liquidBackgroundTask = liquidBackgroundWorker.submit {
        val restored = LiquidBackgroundStore.restoreAutomatic(applicationContext)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            liquidBackgroundImportInProgress = false
            liquidBackgroundDialog?.setCancelable(true)
            if (restored) {
                toast(getString(R.string.liquid_background_restore_success))
                finishLiquidBackgroundChange()
            } else toast(getString(R.string.liquid_background_storage_failed))
        }
    }
}

private fun MainActivity.applyLiquidRealtimeCaptureEnabled(enabled: Boolean) {
    if (!LiquidRealtimeCaptureStore.setEnabled(applicationContext, enabled)) {
        toast(getString(R.string.liquid_realtime_capture_save_failed))
        if (liquidBackgroundDialog == null && !isFinishing && !isDestroyed) {
            showLiquidBackgroundDialog()
        }
        return
    }
    toast(
        getString(
            if (enabled) R.string.liquid_realtime_capture_enabled
            else R.string.liquid_realtime_capture_disabled
        )
    )
    finishLiquidBackgroundChange()
}

/** 仅在弹窗完成退场后调用；AppCompat 会按需重建 Activity。 */
private fun MainActivity.applyAppLanguage(language: AppLanguage) {
    val locales = language.languageTag?.let(LocaleListCompat::forLanguageTags)
        ?: LocaleListCompat.getEmptyLocaleList()
    InjectedUiLocale.setMirrorAndBroadcast(
        applicationContext,
        language.languageTag ?: InjectedUiLocale.TAG_SYSTEM
    )
    if (AppCompatDelegate.getApplicationLocales().toLanguageTags() == locales.toLanguageTags()) return
    AppCompatDelegate.setApplicationLocales(locales)
}

private fun MainActivity.beginLiquidRealtimeCaptureConfirmation() {
    val backgroundDialog = liquidBackgroundDialog
    val backgroundContainer = liquidBackgroundDialogContainer
    if (backgroundDialog != null && backgroundContainer != null && backgroundDialog.isShowing) {
        dismissWithAnimation(backgroundDialog, backgroundContainer) {
            if (!isFinishing && !isDestroyed) showLiquidRealtimeCaptureConfirmDialog()
        }
    } else showLiquidRealtimeCaptureConfirmDialog()
}

private fun MainActivity.liquidRealtimeCaptureSummary(enabled: Boolean): String = getString(
    when {
        !isLiquidRealtimeCaptureSupported() ->
            R.string.liquid_realtime_capture_summary_unsupported
        enabled -> R.string.liquid_realtime_capture_summary_enabled
        else -> R.string.liquid_realtime_capture_summary_disabled
    }
)

@StringRes
private fun MainActivity.skinDescriptionRes(skin: SkinId): Int = when (skin) {
    SkinId.MATERIAL_YOU -> R.string.skin_material_desc
    SkinId.LIQUID -> R.string.skin_liquid_desc
}

@StringRes
private fun MainActivity.skinTitleRes(skin: SkinId): Int = when (skin) {
    SkinId.MATERIAL_YOU -> R.string.skin_material_title
    SkinId.LIQUID -> R.string.skin_liquid_title
}
