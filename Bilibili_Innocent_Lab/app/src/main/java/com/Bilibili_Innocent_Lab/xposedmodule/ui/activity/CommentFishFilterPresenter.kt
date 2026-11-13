package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz.LinearSettingsScope
import io.github.jichuo1.meguviz.android.extensions.textColor
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/**
 * 「屏蔽 AI 评论与回复」的设置行：总开关、敏感度、强力模式、两份自定义词、放行名单、高等级保护。
 *
 * 判据本身在 [FishScorePolicy]；这里只负责设置页。开关走设置模型（[applyModeledToggle]），
 * 其余是弹窗取值，沿用评论关键词 / 发布者行的写法：行文本 = 标题 + 换行 + 当前值，弹窗标题复用来源行标题。
 */
internal fun MainActivity.commentFishFilterSettingsRows(performer: LinearSettingsScope) {
    with(performer) {
        materialSwitch(key = "CommentFishFilterPresenter:enabled",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.COMMENT_FISH_FILTER_ENABLED, directToggle = true)
            text = stringResource(R.string.comment_fish_filter)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = commentFishFilterEnabled
            setOnCheckedChangeListener { button, checked ->
                applyModeledToggle(FeaturePreferences.COMMENT_FISH_FILTER_ENABLED, checked, button)
            }
        }
        textView(key = "CommentFishFilterPresenter:tip", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.comment_fish_filter_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        fishSettingRow(
            this, "CommentFishFilterPresenter:sensitivity", FeaturePreferences.COMMENT_FISH_SENSITIVITY,
            initialValue = commentFishSensitivity,
            summary = { commentFishSensitivitySummaryText(it) },
            mirror = { commentFishSensitivity = it }
        ) { anchor, onSaved ->
            showCommentFishSensitivityDialog(anchor, onSaved)
        }
        materialSwitch(key = "CommentFishFilterPresenter:strong-mode",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.COMMENT_FISH_STRONG_MODE, directToggle = true)
            text = stringResource(R.string.comment_fish_strong_mode)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = commentFishStrongMode
            setOnCheckedChangeListener { button, checked ->
                applyModeledToggle(FeaturePreferences.COMMENT_FISH_STRONG_MODE, checked, button)
            }
        }
        textView(key = "CommentFishFilterPresenter:strong-mode-tip", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.comment_fish_strong_mode_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        fishRuleRow(this, "CommentFishFilterPresenter:nickname", FeaturePreferences.COMMENT_FISH_NICKNAME_WORDS,
            R.string.comment_fish_nickname_words, R.string.comment_fish_words_dialog_hint,
            { commentFishNicknameWords }, { commentFishNicknameWords = it })
        fishRuleRow(this, "CommentFishFilterPresenter:text", FeaturePreferences.COMMENT_FISH_TEXT_WORDS,
            R.string.comment_fish_text_words, R.string.comment_fish_words_dialog_hint,
            { commentFishTextWords }, { commentFishTextWords = it })
        fishRuleRow(this, "CommentFishFilterPresenter:whitelist", FeaturePreferences.COMMENT_FISH_WHITELIST,
            R.string.comment_fish_whitelist, R.string.comment_fish_whitelist_dialog_hint,
            { commentFishWhitelist }, { commentFishWhitelist = it })
        materialSwitch(key = "CommentFishFilterPresenter:protect",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.COMMENT_FISH_PROTECT_HIGH_LEVEL, directToggle = true)
            text = stringResource(R.string.comment_fish_protect_high_level)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = commentFishProtectHighLevel
            setOnCheckedChangeListener { button, checked ->
                applyModeledToggle(FeaturePreferences.COMMENT_FISH_PROTECT_HIGH_LEVEL, checked, button)
            }
        }
        textView(key = "CommentFishFilterPresenter:protect-tip", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.comment_fish_protect_high_level_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}

/** 一行"标题 + 当前值"的规则入口：点开沿用项目的规则编辑弹窗，确认后写模块配置。 */
private fun MainActivity.fishRuleRow(
    performer: LinearSettingsScope,
    viewKey: String,
    preferenceKey: String,
    @StringRes titleRes: Int,
    @StringRes hintRes: Int,
    read: () -> String,
    mirror: (String) -> Unit
) {
    fishSettingRow(
        performer, viewKey, preferenceKey, initialValue = read(),
        summary = { ruleEntryText(titleRes, R.string.comment_fish_words_empty, R.string.comment_fish_current, it) },
        mirror = mirror,
        configure = {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
    ) { anchor, onSaved ->
        // 标题复用来源行的 string，否则 ModalTitleMotion 配不上（见 showRuleEditorDialog 所在文件）。
        showRuleEditorDialog(titleRes, hintRes, read(), anchor = anchor, onConfirm = onSaved)
    }
}

private fun MainActivity.fishSettingRow(
    performer: LinearSettingsScope,
    viewKey: String,
    preferenceKey: String,
    initialValue: String,
    summary: (String) -> CharSequence,
    mirror: (String) -> Unit,
    configure: NativeTextView.() -> Unit = {},
    openEditor: (View, (String) -> Unit) -> Unit
) {
    with(performer) {
        textViewAction(
            key = viewKey,
            lparams = params(widthMatchParent = true),
            init = {
                bindSettingDestination(this, preferenceKey)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                setLineSpacing(5f, 1f)
                setPadding(12.dp, 10.dp, 12.dp, 10.dp)
                background = selfRippleBackground(10f)
                isClickable = true
                isFocusable = true
                configure()
                text = summary(initialValue)
            }
        ) { ref, anchor ->
            val token = ref.token()
            openEditor(anchor) { value ->
                mirror(value)
                writeFishText(preferenceKey, value)
                if (token.isCurrent()) ref.get().text = summary(value)
            }
        }
    }
}

private fun MainActivity.writeFishText(key: String, value: String) {
    runCatching { prefs().edit { putString(key, value) } }.onFailure { throwable ->
        Log.e("BilibiliInnocentLab", "write comment fish filter prefs failed", throwable)
    }
}

internal fun MainActivity.commentFishSensitivitySummaryText(id: String): String =
    getString(R.string.comment_fish_sensitivity) + "\n" +
        getString(R.string.comment_fish_current, commentFishSensitivityLabel(FishScorePolicy.Sensitivity.fromId(id)))

private fun MainActivity.commentFishSensitivityLabel(sensitivity: FishScorePolicy.Sensitivity): String = getString(
    when (sensitivity) {
        FishScorePolicy.Sensitivity.LOOSE -> R.string.comment_fish_sensitivity_loose
        FishScorePolicy.Sensitivity.STANDARD -> R.string.comment_fish_sensitivity_standard
        FishScorePolicy.Sensitivity.STRICT -> R.string.comment_fish_sensitivity_strict
    }
)

private fun MainActivity.commentFishSensitivityTip(sensitivity: FishScorePolicy.Sensitivity): String = getString(
    when (sensitivity) {
        FishScorePolicy.Sensitivity.LOOSE -> R.string.comment_fish_sensitivity_loose_tip
        FishScorePolicy.Sensitivity.STANDARD -> R.string.comment_fish_sensitivity_standard_tip
        FishScorePolicy.Sensitivity.STRICT -> R.string.comment_fish_sensitivity_strict_tip
    }
)

/** 判定敏感度三选一；与评论等级选择共用同一套弹窗结构与关闭动画。 */
internal fun MainActivity.showCommentFishSensitivityDialog(anchor: View? = null, onSelected: (String) -> Unit) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()

    container.addView(
        NativeTextView(this).apply {
            // 复用来源行标题（文字平移要求）。
            text = getString(R.string.comment_fish_sensitivity)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * density).toInt() }
    )
    FishScorePolicy.Sensitivity.entries.forEachIndexed { index, sensitivity ->
        container.addView(
            createGitHubMenuRow(
                title = commentFishSensitivityLabel(sensitivity),
                subtitle = commentFishSensitivityTip(sensitivity),
                highlight = sensitivity.id == commentFishSensitivity
            ) {
                onSelected(sensitivity.id)
                dismissWithAnimation(dialog, container) {}
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (index > 0) topMargin = (4 * density).toInt() }
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
            setPadding((20 * density).toInt(), (11 * density).toInt(), (20 * density).toInt(), (11 * density).toInt())
            background = selfRippleBackground(14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissWithAnimation(dialog, container) {} }
        }
    )
    container.addView(
        closeRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (18 * density).toInt() }
    )
    presentModalDialog(dialog, container, anchor)
}
