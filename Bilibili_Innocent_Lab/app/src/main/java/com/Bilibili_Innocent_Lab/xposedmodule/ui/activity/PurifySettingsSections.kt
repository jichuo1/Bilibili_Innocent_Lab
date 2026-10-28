@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz.LinearSettingsScope

import android.graphics.Typeface
import android.util.Log
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast
import io.github.jichuo1.meguviz.android.extensions.updatePadding
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.StoryActionIcon
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ExactRuleSetCodec
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.AdvancedSettingsCategory

/** 推荐过滤的"标题 + 当前摘要"可点行及其说明；时长、播放量、发布时间三行同构。 */
internal fun MainActivity.recommendSummaryRow(
    scope: LinearSettingsScope,
    @StringRes titleRes: Int,
    @StringRes tipRes: Int,
    settingIds: List<String>,
    summary: () -> String,
    bindSummaryView: (NativeTextView) -> Unit,
    onClick: (View) -> Unit
) = with(scope) {
    textView(key = "recommend-summary:$titleRes",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
        }
    ) {
        bindSummaryView(this)
        text = stringResource(titleRes) + "\n" + summary()
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        settingIds.forEach { settingsDestinations.bind(it, this) }
        setOnClickListener(onClick)
    }
    textView(key = "recommend-summary-tip:$titleRes",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(tipRes)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶净化 · PURIFY_SHARED 分类；标题即 marker，必须是本组第一个子控件。 */
internal fun MainActivity.purifySharedCategory(scope: LinearSettingsScope) = with(scope) {
    advancedCategoryTitle(AdvancedSettingsCategory.PURIFY_SHARED) {
        advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_SHARED] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_purify_shared)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    recommendSummaryRow(this, R.string.recommend_video_duration_range, R.string.recommend_video_duration_tip, emptyList(), { recommendVideoDurationSummary() }, { recommendVideoDurationSummaryView = it }) {
        showRecommendVideoDurationRangeDialog(it)
    }
    recommendSummaryRow(this, R.string.recommend_video_play_count_range, R.string.recommend_video_play_count_tip, listOf(SettingsCatalog.ID_RECOMMEND_VIDEO_MIN_PLAY_COUNT, SettingsCatalog.ID_RECOMMEND_VIDEO_MAX_PLAY_COUNT), { recommendVideoPlayCountSummary() }, { recommendVideoPlayCountSummaryView = it }) {
        showRecommendVideoPlayCountRangeDialog(it)
    }
    recommendSummaryRow(this, R.string.recommend_video_publish_time_range, R.string.recommend_video_publish_time_tip, listOf(SettingsCatalog.ID_RECOMMEND_VIDEO_PUBLISH_TIME_RULE), { recommendVideoPublishTimeSummary() }, { recommendVideoPublishTimeSummaryView = it }) {
        showRecommendVideoPublishTimeDialog(it)
    }
}

/** 进阶净化 · PURIFY_SHARE 分类；标题即 marker，必须是本组第一个子控件。 */
internal fun MainActivity.purifyShareCategory(scope: LinearSettingsScope) = with(scope) {
    advancedCategoryTitle(AdvancedSettingsCategory.PURIFY_SHARE) {
        advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_SHARE] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_purify_share)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    materialSwitch(key = "PurifySettingsSections:117",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.PURIFY_SHARE_CONTENT, directToggle = true)
        text = stringResource(R.string.purify_share_content)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = purifyShareContent
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.PURIFY_SHARE_CONTENT, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:147",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.purify_share_content_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:156",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK, directToggle = true)
        text = stringResource(R.string.share_mini_program_direct_link)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = shareMiniProgramDirectLink
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.SHARE_MINI_PROGRAM_DIRECT_LINK, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:186",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.share_mini_program_direct_link_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶净化 · PURIFY_STARTUP 分类；标题即 marker，必须是本组第一个子控件。 */
internal fun MainActivity.purifyStartupCategory(scope: LinearSettingsScope) = with(scope) {
    advancedCategoryTitle(AdvancedSettingsCategory.PURIFY_STARTUP) {
        advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_STARTUP] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_purify_startup)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    materialSwitch(key = "PurifySettingsSections:212",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.PURIFY_SPLASH_ADS, directToggle = true)
        text = stringResource(R.string.purify_splash_ads)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = purifySplashAds
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.PURIFY_SPLASH_ADS, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:231",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.purify_splash_ads_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    textView(key = "PurifySettingsSections:240",
        lparams = params(widthMatchParent = true) {
            topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
            bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
        }
    ) {
        text = stringResource(R.string.prompt_purify_settings)
        applyAdvancedSubsectionStyle()
    }
    materialSwitch(key = "PurifySettingsSections:249",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT, directToggle = true)
        text = stringResource(R.string.block_teenagers_mode_prompt)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = blockTeenagersModePrompt
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.BLOCK_TEENAGERS_MODE_PROMPT, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:278",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.block_teenagers_mode_prompt_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    textView(key = "PurifySettingsSections:287",
        lparams = params(widthMatchParent = true) {
            topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
            bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
        }
    ) {
        text = stringResource(R.string.client_update_settings)
        applyAdvancedSubsectionStyle()
    }
    materialSwitch(key = "PurifySettingsSections:296",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.BLOCK_APP_UPDATE, directToggle = true)
        text = stringResource(R.string.block_app_update)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = blockAppUpdate
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.BLOCK_APP_UPDATE, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:325",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.block_app_update_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:334",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD, directToggle = true)
        text = stringResource(R.string.block_component_library_download)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = blockComponentLibraryDownload
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.BLOCK_COMPONENT_LIBRARY_DOWNLOAD, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:373", lparams = params(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.block_component_library_download_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    textView(key = "PurifySettingsSections:380",
        lparams = params(widthMatchParent = true) { topMargin = 12.dp }
    ) {
        componentPoolRulesSummaryView = this
        text = stringResource(R.string.component_pool_block) + "\n" +
            componentPoolPickerSurface().summaryText()
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            queryComponentSnapshotAndOpenPicker(componentPoolPickerSurface())
        }
    }
    textView(key = "PurifySettingsSections:399", lparams = params(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.component_pool_block_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶净化 · PURIFY_COMMENTS 分类；标题即 marker，必须是本组第一个子控件。 */
internal fun MainActivity.purifyCommentsCategory(scope: LinearSettingsScope) = with(scope) {
    advancedCategoryTitle(AdvancedSettingsCategory.PURIFY_COMMENTS) {
        advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_COMMENTS] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_purify_comments)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    materialSwitch(key = "PurifySettingsSections:423",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_COMMENT_SECTION, directToggle = true)
        text = stringResource(R.string.hide_comment_section)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hideCommentSection
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_COMMENT_SECTION, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:442",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_comment_section_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:451",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS, directToggle = true)
        text = stringResource(R.string.remove_comment_search_links)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeCommentSearchLinks
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_COMMENT_SEARCH_LINKS, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:481",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_comment_search_links_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:490",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE, directToggle = true)
        text = stringResource(R.string.remove_comment_empty_guide)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeCommentEmptyGuide
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_COMMENT_EMPTY_GUIDE, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:520",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_comment_empty_guide_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:529",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS, directToggle = true)
        text = stringResource(R.string.remove_comment_vote_widgets)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeCommentVoteWidgets
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_COMMENT_VOTE_WIDGETS, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:559",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_comment_vote_widgets_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:568",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS, directToggle = true)
        text = stringResource(R.string.remove_comment_follow_buttons)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeCommentFollowButtons
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_COMMENT_FOLLOW_BUTTONS, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:598",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_comment_follow_buttons_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:607",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_COMMENT_QOE, directToggle = true)
        text = stringResource(R.string.remove_comment_qoe)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeCommentQoe
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_COMMENT_QOE, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:637",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_comment_qoe_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:646",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_COMMENT_OPERATIONS, directToggle = true)
        text = stringResource(R.string.remove_comment_operations)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeCommentOperations
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_COMMENT_OPERATIONS, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:676",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_comment_operations_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    commentReplyButtonSettingsRows(this, removeCommentReplyButton) { removeCommentReplyButton = it }
    materialSwitch(key = "PurifySettingsSections:686",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED, directToggle = true)
        settingsDestinations.bind(SettingsCatalog.ID_COMMENT_SEMANTIC_FILTER, this)
        text = stringResource(R.string.comment_semantic_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = commentSemanticFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.COMMENT_SEMANTIC_FILTER_ENABLED, checked, button)
        }
    }
    semanticFilterDetails(this, SemanticSurface.COMMENT, SettingsCatalog.ID_COMMENT_SEMANTIC_RULES, R.string.comment_semantic_filter_tip)
    materialSwitch(key = "PurifySettingsSections:705",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED, directToggle = true)
        text = stringResource(R.string.comment_keyword_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = commentKeywordFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.COMMENT_KEYWORD_FILTER_ENABLED, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:735",
        lparams = params(widthMatchParent = true)
    ) {
        bindSettingDestination(this, FeaturePreferences.COMMENT_FILTER_KEYWORDS)
        commentKeywordSummaryView = this
        text = stringResource(R.string.comment_keyword_rules) + "\n" +
            if (commentFilterKeywords.isBlank()) {
                stringResource(R.string.comment_keyword_rules_empty)
            } else {
                stringResource(
                    R.string.comment_keyword_rules_current,
                    commentFilterKeywords
                )
            }
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            showRuleEditorDialog(
                // 标题复用来源行的 string，否则 ModalTitleMotion 配不上（见该文件类注释）。
                R.string.comment_keyword_rules,
                R.string.comment_keyword_dialog_hint,
                commentFilterKeywords,
                anchor = it
            ) { value ->
                commentFilterKeywords = value
                prefs().edit {
                    putString(
                        FeaturePreferences.COMMENT_FILTER_KEYWORDS,
                        value
                    )
                }
                commentKeywordSummaryView?.text =
                    stringResource(R.string.comment_keyword_rules) + "\n" +
                        if (value.isBlank()) {
                            stringResource(R.string.comment_keyword_rules_empty)
                        } else {
                            stringResource(
                                R.string.comment_keyword_rules_current,
                                value
                            )
                        }
            }
        }
    }
    textView(key = "PurifySettingsSections:786",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.comment_keyword_filter_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:795",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED, directToggle = true)
        text = stringResource(R.string.comment_min_level_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = commentMinLevelFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.COMMENT_MIN_LEVEL_FILTER_ENABLED, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:825",
        lparams = params(widthMatchParent = true)
    ) {
        bindSettingDestination(this, FeaturePreferences.COMMENT_MIN_LEVEL)
        commentLevelSummaryView = this
        text = stringResource(R.string.comment_min_level_current, commentMinLevel)
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener { showCommentMinLevelDialog(it) }
    }
    textView(key = "PurifySettingsSections:839",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.comment_min_level_filter_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    // @ 整条与发布者过滤跟随关键词/等级过滤，共用同一批列表边界。
    materialSwitch(key = "PurifySettingsSections:849",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_AT_ONLY_COMMENTS, directToggle = true)
        text = stringResource(R.string.remove_at_only_comments)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeAtOnlyComments
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_AT_ONLY_COMMENTS, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:879",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_at_only_comments_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:888",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.COMMENT_USER_FILTER_ENABLED, directToggle = true)
        text = stringResource(R.string.comment_user_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = commentUserFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.COMMENT_USER_FILTER_ENABLED, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:918",
        lparams = params(widthMatchParent = true)
    ) {
        bindSettingDestination(this, FeaturePreferences.COMMENT_USER_FILTER_RULES)
        commentUserFilterSummaryView = this
        text = commentUserFilterSummaryText(commentUserFilterRules)
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            showRuleEditorDialog(
                R.string.comment_user_filter_rules,
                R.string.comment_user_filter_dialog_hint,
                commentUserFilterRules,
                anchor = it
            ) { value ->
                commentUserFilterRules = value
                prefs().edit {
                    putString(
                        FeaturePreferences.COMMENT_USER_FILTER_RULES,
                        value
                    )
                }
                commentUserFilterSummaryView?.text =
                    commentUserFilterSummaryText(value)
            }
        }
    }
    textView(key = "PurifySettingsSections:952",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.comment_user_filter_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶净化 · PURIFY_PLAYBACK 分类；标题即 marker，必须是本组第一个子控件。 */
internal fun MainActivity.purifyPlaybackCategory(scope: LinearSettingsScope) = with(scope) {
    advancedCategoryTitle(AdvancedSettingsCategory.PURIFY_PLAYBACK) {
        advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_PLAYBACK] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_purify_playback)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    materialSwitch(key = "PurifySettingsSections:978",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL, directToggle = true)
        text = stringResource(R.string.hide_player_portrait_control)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hidePlayerPortraitControl
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_PLAYER_PORTRAIT_CONTROL, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:1007",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_player_portrait_control_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:1016",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS, directToggle = true)
        text = stringResource(R.string.hide_player_interactive_overlays)
        settingsDestinations.bind("player.interactive_overlays.hidden",this)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hidePlayerInteractiveOverlays
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_PLAYER_INTERACTIVE_OVERLAYS, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:1047",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_player_interactive_overlays_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:1056",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION, directToggle = true)
        text = stringResource(R.string.hide_player_popup_promotion)
        settingsDestinations.bind("player.popup_promotion.hidden",this)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hidePlayerPopupPromotion
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_PLAYER_POPUP_PROMOTION, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:1087",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_player_popup_promotion_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:1096",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND, directToggle = true)
        text = stringResource(R.string.hide_player_end_page_recommend)
        settingsDestinations.bind("player.end_page_recommend.hidden",this)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hidePlayerEndPageRecommend
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_PLAYER_END_PAGE_RECOMMEND, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:1127",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_player_end_page_recommend_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:1136",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP, directToggle = true)
        text = stringResource(R.string.hide_pgc_auto_activity_popup)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hidePgcAutoActivityPopup
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_PGC_AUTO_ACTIVITY_POPUP, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:1166",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_pgc_auto_activity_popup_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    linearLayout(key = "PurifySettingsSections:1175",
        lparams = params(widthMatchParent = true) {
            topMargin = 14.dp
            bottomMargin = 8.dp
        },
        init = {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = selfRippleBackground(10f)
            updatePadding(horizontal = 4.dp, vertical = 9.dp)
            isClickable = true
            isFocusable = true
            contentDescription = stringResource(R.string.detail_module_purify_settings)
            setOnClickListener { showDetailModuleFilterDialog(anchor = it) }
        }
    ) {
        linearLayout(key = "PurifySettingsSections:1191",
            lparams = params { weight = 1f },
            init = { orientation = LinearLayout.VERTICAL }
        ) {
            textView(key = "PurifySettingsSections:1195", lparams = params(widthMatchParent = true)) {
                text = stringResource(R.string.detail_module_purify_settings)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            textView(key = "PurifySettingsSections:1200",
                lparams = params(widthMatchParent = true) {
                    topMargin = 4.dp
                }
            ) {
                detailModuleFilterSummaryView = this
                alpha = 0.68f
                text = detailModuleFilterSummary()
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            // 供设置项搜索命中子项：只做索引，不显示。
            textView(key = "PurifySettingsSections:1212", lparams = params(widthMatchParent = true)) {
                visibility = View.GONE
                text = listOf(
                    R.string.remove_detail_honor,
                    R.string.remove_detail_live_order,
                    R.string.remove_detail_ugc_season,
                    R.string.remove_detail_up_vip_label,
                    R.string.remove_detail_topic_tags,
                    R.string.remove_detail_staff_follow,
                    R.string.remove_detail_hot_banner
                ).joinToString(separator = " ") { stringResource(it) }
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
        }
        imageView(key = "PurifySettingsSections:1227", lparams = params(18.dp, 18.dp)) {
            setImageResource(R.drawable.ic_chevron_down)
            rotation = -90f
            alpha = 0.8f
            imageTintList = stateColorResource(R.color.colorTextGray)
        }
    }
    linearLayout(key = "PurifySettingsSections:1234",
        lparams = params(widthMatchParent = true) {
            topMargin = 14.dp
            bottomMargin = 8.dp
        },
        init = {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = selfRippleBackground(10f)
            updatePadding(horizontal = 4.dp, vertical = 9.dp)
            isClickable = true
            isFocusable = true
            contentDescription = stringResource(
                R.string.portrait_content_filter_title
            )
            setOnClickListener { showPortraitContentFilterDialog(it) }
        }
    ) {
        linearLayout(key = "PurifySettingsSections:1252",
            lparams = params { weight = 1f },
            init = { orientation = LinearLayout.VERTICAL }
        ) {
            textView(key = "PurifySettingsSections:1256", lparams = params(widthMatchParent = true)) {
                text = stringResource(R.string.portrait_content_filter_title)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            textView(key = "PurifySettingsSections:1261",
                lparams = params(widthMatchParent = true) {
                    topMargin = 4.dp
                }
            ) {
                portraitContentFilterSummaryView = this
                alpha = 0.68f
                text = portraitContentFilterSummary()
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            // 供设置项搜索命中子项：13 个勾选都在弹窗里，开关本体已 GONE，
            // 没有这条索引就只有面板标题能被搜到（与其余四个弹窗面板的兜底同口径）。
            textView(key = "PurifySettingsSections:1274", lparams = params(widthMatchParent = true)) {
                visibility = View.GONE
                text = PortraitContentFilterCatalog.options.joinToString(separator = " ") { option ->
                    stringResource(portraitContentFilterLabel(option.preferenceKey))
                }
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
        }
        imageView(key = "PurifySettingsSections:1283", lparams = params(18.dp, 18.dp)) {
            setImageResource(R.drawable.ic_chevron_down)
            rotation = -90f
            alpha = 0.8f
            imageTintList = stateColorResource(R.color.colorTextGray)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1290",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_ADS, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_ads)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryAds
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_ADS, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1309",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_LIVE, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_live)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryLive
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_LIVE, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1329",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_GAMES, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_games)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryGames
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_GAMES, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1349",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_BANGUMI, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_bangumi)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryBangumi
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_BANGUMI, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1369",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_COURSES, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_courses)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryCourses
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_COURSES, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1389",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_SHORT_DRAMA, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_short_drama)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryShortDrama
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_SHORT_DRAMA, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1412",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_SHOPPING, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_shopping)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryShopping
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_SHOPPING, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1435",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_MOVIES, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_movies)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryMovies
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_MOVIES, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1455",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_DOCUMENTARIES, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_documentaries)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryDocumentaries
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_DOCUMENTARIES, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1478",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_TV, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_tv)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryTv
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_TV, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1498",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_VARIETY, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_variety)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryVariety
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_VARIETY, checked, button)
        }
    }
    materialSwitch(key = "PurifySettingsSections:1518",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_STORY_MUSIC, directToggle = true)
        visibility = View.GONE
        text = stringResource(R.string.remove_story_music)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeStoryMusic
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_STORY_MUSIC, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:1538",
        lparams = params(widthMatchParent = true)
    ) {
        visibility = View.GONE
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.story_purify_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    linearLayout(key = "PurifySettingsSections:1548",
        lparams = params(widthMatchParent = true) {
            topMargin = 14.dp
            bottomMargin = 8.dp
        },
        init = {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = selfRippleBackground(10f)
            updatePadding(horizontal = 4.dp, vertical = 9.dp)
            isClickable = true
            isFocusable = true
            contentDescription = stringResource(R.string.story_action_icons_title)
            setOnClickListener { showStoryActionIconsDialog(anchor = it) }
        }
    ) {
        linearLayout(key = "PurifySettingsSections:1564",
            lparams = params { weight = 1f },
            init = { orientation = LinearLayout.VERTICAL }
        ) {
            textView(key = "PurifySettingsSections:1568", lparams = params(widthMatchParent = true)) {
                text = stringResource(R.string.story_action_icons_title)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            textView(key = "PurifySettingsSections:1573",
                lparams = params(widthMatchParent = true) {
                    topMargin = 4.dp
                }
            ) {
                storyActionIconsSummaryView = this
                alpha = 0.68f
                text = storyActionIconsSummary()
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            // 供设置项搜索命中子项：只做索引，不显示。
            textView(key = "PurifySettingsSections:1585", lparams = params(widthMatchParent = true)) {
                visibility = View.GONE
                text = StoryActionIcon.entries.joinToString(separator = " ") {
                    stringResource(storyActionIconLabel(it))
                }
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
        }
        imageView(key = "PurifySettingsSections:1594", lparams = params(18.dp, 18.dp)) {
            setImageResource(R.drawable.ic_chevron_down)
            rotation = -90f
            alpha = 0.8f
            imageTintList = stateColorResource(R.color.colorTextGray)
        }
    }
    linearLayout(key = "PurifySettingsSections:1601",
        lparams = params(widthMatchParent = true) {
            topMargin = 14.dp
            bottomMargin = 8.dp
        },
        init = {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = selfRippleBackground(10f)
            updatePadding(horizontal = 4.dp, vertical = 9.dp)
            isClickable = true
            isFocusable = true
            contentDescription = stringResource(
                R.string.video_relate_filter_settings
            )
            setOnClickListener { showVideoRelateFilterDialog(it) }
        }
    ) {
        linearLayout(key = "PurifySettingsSections:1619",
            lparams = params { weight = 1f },
            init = { orientation = LinearLayout.VERTICAL }
        ) {
            textView(key = "PurifySettingsSections:1623", lparams = params(widthMatchParent = true)) {
                text = stringResource(R.string.video_relate_filter_settings)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            textView(key = "PurifySettingsSections:1628",
                lparams = params(widthMatchParent = true) {
                    topMargin = 4.dp
                }
            ) {
                videoRelateFilterSummaryView = this
                alpha = 0.68f
                text = videoRelateFilterSummary()
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            textView(key = "PurifySettingsSections:1639", lparams = params(widthMatchParent = true)) {
                visibility = View.GONE
                text = listOf(
                    R.string.remove_relate_commercial,
                    R.string.remove_relate_game,
                    R.string.remove_relate_live,
                    R.string.remove_relate_course,
                    R.string.remove_relate_special,
                    R.string.video_relate_matching_enhancement,
                    R.string.video_relate_strong_mode,
                    R.string.video_relate_reason_filter,
                    R.string.video_relate_reason_filter_keywords
                ).joinToString(" · ") { stringResource(it) }
            }
        }
        imageView(key = "PurifySettingsSections:1654", lparams = params(18.dp, 18.dp)) {
            setImageResource(R.drawable.ic_chevron_down)
            rotation = -90f
            alpha = 0.8f
            imageTintList = stateColorResource(R.color.colorTextGray)
        }
    }
    // 详情页协议里没有标签 id，"按标签过滤"在这里退到 UP 主与标签名两档；判据是整串相等。
    textView(key = "PurifySettingsSections:1662", lparams = params(widthMatchParent = true)) {
        videoRelateBlockedAuthorsSummaryView = this
        text = ruleEntryText(
            R.string.video_relate_blocked_authors,
            R.string.video_relate_blocked_authors_empty,
            R.string.video_relate_blocked_authors_current,
            videoRelateBlockedAuthors
        )
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            showRuleEditorDialog(
                R.string.video_relate_blocked_authors,
                R.string.video_relate_blocked_authors_hint,
                videoRelateBlockedAuthors,
                anchor = it
            ) { value ->
                val normalized = ExactRuleSetCodec.encode(ExactRuleSetCodec.parse(value))
                videoRelateBlockedAuthors = normalized
                prefs().edit {
                    putString(FeaturePreferences.VIDEO_RELATE_BLOCKED_AUTHORS, normalized)
                }
                videoRelateBlockedAuthorsSummaryView?.text = ruleEntryText(
                    R.string.video_relate_blocked_authors,
                    R.string.video_relate_blocked_authors_empty,
                    R.string.video_relate_blocked_authors_current,
                    normalized
                )
            }
        }
    }
    textView(key = "PurifySettingsSections:1700",
        lparams = params(widthMatchParent = true) { topMargin = 4.dp }
    ) {
        videoRelateBlockedTagsSummaryView = this
        text = ruleEntryText(
            R.string.video_relate_blocked_tags,
            R.string.video_relate_blocked_tags_empty,
            R.string.video_relate_blocked_tags_current,
            videoRelateBlockedTags
        )
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            showRuleEditorDialog(
                R.string.video_relate_blocked_tags,
                R.string.video_relate_blocked_tags_hint,
                videoRelateBlockedTags,
                anchor = it
            ) { value ->
                val normalized = ExactRuleSetCodec.encode(ExactRuleSetCodec.parse(value))
                videoRelateBlockedTags = normalized
                prefs().edit {
                    putString(FeaturePreferences.VIDEO_RELATE_BLOCKED_TAGS, normalized)
                }
                videoRelateBlockedTagsSummaryView?.text = ruleEntryText(
                    R.string.video_relate_blocked_tags,
                    R.string.video_relate_blocked_tags_empty,
                    R.string.video_relate_blocked_tags_current,
                    normalized
                )
            }
        }
    }
    textView(key = "PurifySettingsSections:1740", lparams = params(widthMatchParent = true) { topMargin = 4.dp }) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.video_relate_blocked_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    // 弹幕净化：内容流（DmSegMobileReply）侧的权重与会员彩字，
    // 与播放器互动组件（指令弹幕/角标）分属两条链路，各自独立开关。
    materialSwitch(key = "PurifySettingsSections:1749",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED, directToggle = true)
        text = stringResource(R.string.danmaku_weight_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = danmakuWeightFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.DANMAKU_WEIGHT_FILTER_ENABLED, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:1779",
        lparams = params(widthMatchParent = true)
    ) {
        bindSettingDestination(this, FeaturePreferences.DANMAKU_WEIGHT_FILTER_MINIMUM)
        danmakuWeightSummaryView = this
        text = stringResource(
            R.string.danmaku_weight_current,
            danmakuWeightMinimum
        )
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener { showDanmakuWeightDialog(it) }
    }
    textView(key = "PurifySettingsSections:1796",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.danmaku_weight_filter_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:1805",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU, directToggle = true)
        text = stringResource(R.string.remove_vip_colorful_danmaku)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeVipColorfulDanmaku
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_VIP_COLORFUL_DANMAKU, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:1835",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_vip_colorful_danmaku_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:1844",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED, directToggle = true)
        settingsDestinations.bind(SettingsCatalog.ID_DANMAKU_SEMANTIC_FILTER, this)
        text = stringResource(R.string.danmaku_semantic_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = danmakuSemanticFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.DANMAKU_SEMANTIC_FILTER_ENABLED, checked, button)
        }
    }
    semanticFilterDetails(this, SemanticSurface.DANMAKU, SettingsCatalog.ID_DANMAKU_SEMANTIC_RULES, R.string.danmaku_semantic_filter_tip)
}

/**
 * 智能过滤（JEV）开关下方的说明与「屏蔽类型」勾选入口，四个过滤面共用。
 * 开关本身在各调用点按常规写明（常用收藏护栏要求开关与初值字段一一对应）；
 * JEV 本身（Key / 地址 / 灵敏度 / 首屏等待）只在实验性功能 → 兼容配置一次。
 */
internal fun MainActivity.semanticFilterDetails(
    scope: LinearSettingsScope,
    surface: SemanticSurface,
    rulesSettingId: String,
    tipRes: Int
) = with(scope) {
    textView(key = "PurifySettingsSections:1877", lparams = params(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(tipRes)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    textView(key = "PurifySettingsSections:1884", lparams = params(widthMatchParent = true) { topMargin = 4.dp }) {
        settingsDestinations.bind(rulesSettingId, this)
        settingsDestinations.bind(SettingsCatalog.semanticCustomRulesId(surface), this)
        text = semanticRulesEntryText(surface)
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener { view ->
            showSemanticRulesDialog(surface, anchor = view) { text = semanticRulesEntryText(surface) }
        }
    }
    // 判定来源：自动分流或固定某个来源（来源本身在 实验性功能 → 兼容 → AI 语义判定 里填）。
    textView(key = "PurifySettingsSections:1900", lparams = params(widthMatchParent = true) { topMargin = 2.dp }) {
        settingsDestinations.bind(SettingsCatalog.semanticRouteId(surface), this)
        text = semanticRouteEntryText(surface)
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener { view ->
            showSemanticRouteDialog(surface, anchor = view) { text = semanticRouteEntryText(surface) }
        }
    }
}

/** 进阶净化 · PURIFY_MINE 分类；标题即 marker，必须是本组第一个子控件。 */
internal fun MainActivity.purifyMineCategory(scope: LinearSettingsScope) = with(scope) {
    advancedCategoryTitle(AdvancedSettingsCategory.PURIFY_MINE) {
        advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_MINE] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_purify_mine)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    materialSwitch(key = "PurifySettingsSections:1931",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_MINE_VIP, directToggle = true)
        text = stringResource(R.string.hide_mine_vip)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hideMineVip
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_MINE_VIP, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:1960",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_mine_vip_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:1969",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.KEEP_MINE_VIP_SPACE, directToggle = true)
        text = stringResource(R.string.keep_mine_vip_space)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = keepMineVipSpace
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.KEEP_MINE_VIP_SPACE, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:1999",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.keep_mine_vip_space_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    textView(key = "PurifySettingsSections:2008",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
        }
    ) {
        mineComponentRulesSummaryView = this
        text = stringResource(R.string.custom_mine_component_hide) + "\n" +
            mineComponentPickerSurface().summaryText()
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            queryComponentSnapshotAndOpenPicker(
                mineComponentPickerSurface()
            )
        }
    }
    textView(key = "PurifySettingsSections:2031",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        text = stringResource(R.string.custom_mine_component_hide_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶净化 · PURIFY_SEARCH 分类；标题即 marker，必须是本组第一个子控件。 */
internal fun MainActivity.purifySearchCategory(scope: LinearSettingsScope) = with(scope) {
    advancedCategoryTitle(AdvancedSettingsCategory.PURIFY_SEARCH) {
        advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_SEARCH] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_purify_search)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    materialSwitch(key = "PurifySettingsSections:2056",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND, directToggle = true)
        text = stringResource(R.string.hide_search_home_recommend)
        settingsDestinations.bind("search.home_recommend.hidden",this)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hideSearchHomeRecommend
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_SEARCH_HOME_RECOMMEND, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2087",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_search_home_recommend_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2096",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_SEARCH_COMMERCIAL, directToggle = true)
        text = stringResource(R.string.remove_search_commercial)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeSearchCommercial
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_SEARCH_COMMERCIAL, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2126",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_search_commercial_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2135",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED, directToggle = true)
        text = stringResource(R.string.search_keyword_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = searchKeywordFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.SEARCH_KEYWORD_FILTER_ENABLED, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2165",
        lparams = params(widthMatchParent = true)
    ) {
        bindSettingDestination(this, FeaturePreferences.SEARCH_FILTER_KEYWORDS)
        searchKeywordSummaryView = this
        text = ruleEntryText(
            R.string.search_keyword_rules,
            R.string.search_keyword_rules_empty,
            R.string.search_keyword_rules_current,
            searchFilterKeywords
        )
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            showRuleEditorDialog(
                R.string.search_keyword_rules,
                R.string.search_keyword_dialog_hint,
                searchFilterKeywords,
                anchor = it
            ) { value ->
                searchFilterKeywords = value
                prefs().edit {
                    putString(
                        FeaturePreferences.SEARCH_FILTER_KEYWORDS,
                        value
                    )
                }
                searchKeywordSummaryView?.text = ruleEntryText(
                    R.string.search_keyword_rules,
                    R.string.search_keyword_rules_empty,
                    R.string.search_keyword_rules_current,
                    value
                )
            }
        }
    }
    textView(key = "PurifySettingsSections:2208",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.search_keyword_filter_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2217",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED, directToggle = true)
        text = stringResource(R.string.search_author_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = searchAuthorFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.SEARCH_AUTHOR_FILTER_ENABLED, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2247",
        lparams = params(widthMatchParent = true)
    ) {
        bindSettingDestination(this, FeaturePreferences.SEARCH_AUTHOR_FILTER_RULES)
        searchAuthorSummaryView = this
        text = ruleEntryText(
            R.string.search_author_filter_rules,
            R.string.search_author_filter_rules_empty,
            R.string.search_author_filter_rules_current,
            searchAuthorFilterRules
        )
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            showRuleEditorDialog(
                R.string.search_author_filter_rules,
                R.string.search_author_filter_dialog_hint,
                searchAuthorFilterRules,
                anchor = it
            ) { value ->
                searchAuthorFilterRules = value
                prefs().edit {
                    putString(
                        FeaturePreferences.SEARCH_AUTHOR_FILTER_RULES,
                        value
                    )
                }
                searchAuthorSummaryView?.text = ruleEntryText(
                    R.string.search_author_filter_rules,
                    R.string.search_author_filter_rules_empty,
                    R.string.search_author_filter_rules_current,
                    value
                )
            }
        }
    }
    textView(key = "PurifySettingsSections:2290",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.search_author_filter_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶净化 · PURIFY_NAVIGATION 分类；标题即 marker，必须是本组第一个子控件。 */
internal fun MainActivity.purifyNavigationCategory(scope: LinearSettingsScope) = with(scope) {
    advancedCategoryTitle(AdvancedSettingsCategory.PURIFY_NAVIGATION) {
        advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_NAVIGATION] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_purify_navigation)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    textView(key = "PurifySettingsSections:2316",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
        }
    ) {
        bottomBarRulesSummaryView = this
        text = stringResource(R.string.custom_bottom_bar_hide) + "\n" +
            bottomBarPickerSurface().summaryText()
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            queryComponentSnapshotAndOpenPicker(bottomBarPickerSurface())
        }
    }
    textView(key = "PurifySettingsSections:2337",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        text = stringResource(R.string.custom_bottom_bar_hide_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    textView(key = "PurifySettingsSections:2345",
        lparams = params(widthMatchParent = true) {
            topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
            bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
        }
    ) {
        text = stringResource(R.string.dynamic_page_settings)
        applyAdvancedSubsectionStyle()
    }
    materialSwitch(key = "PurifySettingsSections:2354",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_DYNAMIC_CITY_TAB, directToggle = true)
        text = stringResource(R.string.hide_dynamic_city_tab)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hideDynamicCityTab
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_DYNAMIC_CITY_TAB, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2383",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_dynamic_city_tab_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2392",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB, directToggle = true)
        text = stringResource(R.string.hide_dynamic_school_tab)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hideDynamicSchoolTab
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_DYNAMIC_SCHOOL_TAB, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2422",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_dynamic_school_tab_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    // 动态内容过滤与上面的页签净化同属动态页，放在同一区域。
    textView(key = "PurifySettingsSections:2432",
        lparams = params(widthMatchParent = true) {
            topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
            bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
        }
    ) {
        text = stringResource(R.string.dynamic_content_settings)
        applyAdvancedSubsectionStyle()
    }
    materialSwitch(key = "PurifySettingsSections:2441",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED, directToggle = true)
        settingsDestinations.bind(SettingsCatalog.ID_DYNAMIC_SEMANTIC_FILTER, this)
        text = stringResource(R.string.dynamic_semantic_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = dynamicSemanticFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_ENABLED, checked, button)
        }
    }
    semanticFilterDetails(this, SemanticSurface.DYNAMIC, SettingsCatalog.ID_DYNAMIC_SEMANTIC_RULES, R.string.dynamic_semantic_filter_tip)
    materialSwitch(key = "PurifySettingsSections:2460",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED, directToggle = true)
        text = stringResource(R.string.dynamic_keyword_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = dynamicKeywordFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.DYNAMIC_KEYWORD_FILTER_ENABLED, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2490",
        lparams = params(widthMatchParent = true)
    ) {
        bindSettingDestination(this, FeaturePreferences.DYNAMIC_FILTER_KEYWORDS)
        dynamicKeywordSummaryView = this
        text = ruleEntryText(
            R.string.dynamic_keyword_rules,
            R.string.dynamic_keyword_rules_empty,
            R.string.dynamic_keyword_rules_current,
            dynamicFilterKeywords
        )
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            showRuleEditorDialog(
                R.string.dynamic_keyword_rules,
                R.string.dynamic_keyword_dialog_hint,
                dynamicFilterKeywords,
                anchor = it
            ) { value ->
                dynamicFilterKeywords = value
                prefs().edit {
                    putString(
                        FeaturePreferences.DYNAMIC_FILTER_KEYWORDS,
                        value
                    )
                }
                dynamicKeywordSummaryView?.text = ruleEntryText(
                    R.string.dynamic_keyword_rules,
                    R.string.dynamic_keyword_rules_empty,
                    R.string.dynamic_keyword_rules_current,
                    value
                )
            }
        }
    }
    textView(key = "PurifySettingsSections:2533",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.dynamic_keyword_filter_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2542",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED, directToggle = true)
        text = stringResource(R.string.dynamic_author_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = dynamicAuthorFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.DYNAMIC_AUTHOR_FILTER_ENABLED, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2572",
        lparams = params(widthMatchParent = true)
    ) {
        bindSettingDestination(this, FeaturePreferences.DYNAMIC_AUTHOR_FILTER_RULES)
        dynamicAuthorSummaryView = this
        text = ruleEntryText(
            R.string.dynamic_author_filter_rules,
            R.string.dynamic_author_filter_rules_empty,
            R.string.dynamic_author_filter_rules_current,
            dynamicAuthorFilterRules
        )
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            showRuleEditorDialog(
                R.string.dynamic_author_filter_rules,
                R.string.dynamic_author_filter_dialog_hint,
                dynamicAuthorFilterRules,
                anchor = it
            ) { value ->
                dynamicAuthorFilterRules = value
                prefs().edit {
                    putString(
                        FeaturePreferences.DYNAMIC_AUTHOR_FILTER_RULES,
                        value
                    )
                }
                dynamicAuthorSummaryView?.text = ruleEntryText(
                    R.string.dynamic_author_filter_rules,
                    R.string.dynamic_author_filter_rules_empty,
                    R.string.dynamic_author_filter_rules_current,
                    value
                )
            }
        }
    }
    textView(key = "PurifySettingsSections:2615",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.dynamic_author_filter_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2624",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS, directToggle = true)
        text = stringResource(R.string.remove_dynamic_promotions)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeDynamicPromotions
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_DYNAMIC_PROMOTIONS, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2654",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_dynamic_promotions_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2663",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY, directToggle = true)
        text = stringResource(R.string.remove_dynamic_charge_only)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeDynamicChargeOnly
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_DYNAMIC_CHARGE_ONLY, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2693",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_dynamic_charge_only_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2702",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS, directToggle = true)
        text = stringResource(R.string.hide_dynamic_frequent_visits)
        settingsDestinations.bind("dynamic.frequent_visits.hidden",this)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hideDynamicFrequentVisits
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_DYNAMIC_FREQUENT_VISITS, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2733",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_dynamic_frequent_visits_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2742",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST, directToggle = true)
        text = stringResource(R.string.hide_dynamic_topic_list)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hideDynamicTopicList
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_DYNAMIC_TOPIC_LIST, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2772",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_dynamic_topic_list_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2781",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES, directToggle = true)
        text = stringResource(R.string.remove_dynamic_live_up_entries)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeDynamicLiveUpEntries
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_DYNAMIC_LIVE_UP_ENTRIES, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2811",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_dynamic_live_up_entries_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶净化 · PURIFY_HOME 分类；标题即 marker，必须是本组第一个子控件。 */
internal fun MainActivity.purifyHomeCategory(scope: LinearSettingsScope) = with(scope) {
    advancedCategoryTitle(AdvancedSettingsCategory.PURIFY_HOME) {
        advancedCategoryMarkers[AdvancedSettingsCategory.PURIFY_HOME] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_purify_home)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    materialSwitch(key = "PurifySettingsSections:2837",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_HOME_GAME_MENU, directToggle = true)
        text = stringResource(R.string.hide_home_game_menu)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hideHomeGameMenu
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_HOME_GAME_MENU, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2866",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_home_game_menu_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2875",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, directToggle = true)
        text = stringResource(R.string.hide_home_search_default_word)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hideHomeSearchDefaultWord
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_HOME_SEARCH_DEFAULT_WORD, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2905",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_home_search_default_word_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    materialSwitch(key = "PurifySettingsSections:2914",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HIDE_LIVE_HOME_PROMOTION, directToggle = true)
        text = stringResource(R.string.hide_live_home_promotion)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = hideLiveHomePromotion
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_LIVE_HOME_PROMOTION, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:2935", lparams = params(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.hide_live_home_promotion_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    textView(key = "PurifySettingsSections:2942",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
        }
    ) {
        homeTabRulesSummaryView = this
        text = stringResource(R.string.custom_home_tab_hide) + "\n" +
            homeTabPickerSurface().summaryText()
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            queryComponentSnapshotAndOpenPicker(homeTabPickerSurface())
        }
    }
    textView(key = "PurifySettingsSections:2963",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        text = stringResource(R.string.custom_home_tab_hide_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    textView(key = "PurifySettingsSections:2971",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
        }
    ) {
        homeComponentRulesSummaryView = this
        text = stringResource(R.string.custom_home_component_hide) + "\n" +
            homeComponentPickerSurface().summaryText()
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            queryComponentSnapshotAndOpenPicker(
                homeComponentPickerSurface()
            )
        }
    }
    textView(key = "PurifySettingsSections:2994",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        text = stringResource(R.string.custom_home_component_hide_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    textView(key = "PurifySettingsSections:3002",
        lparams = params(widthMatchParent = true) {
            topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
            bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
        }
    ) {
        text = stringResource(R.string.home_recommend_purify_settings)
        applyAdvancedSubsectionStyle()
    }
    materialSwitch(key = "PurifySettingsSections:3011",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2, directToggle = true)
        text = stringResource(R.string.remove_home_recommend_cm_v2)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = removeHomeRecommendCmV2
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.REMOVE_HOME_RECOMMEND_CM_V2, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:3040",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 12.dp
        }
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.remove_home_recommend_cm_v2_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    linearLayout(key = "PurifySettingsSections:3051",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 8.dp
        },
        init = {
            homeRecommendFilterEntryView = this
            HomeRecommendFilterCatalog.preferenceKeys.forEach { key ->
                settingsDestinations.bind(SettingsCatalog.byStorageKey.getValue(key).id, this)
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = selfRippleBackground(10f)
            updatePadding(vertical = 9.dp)
            isClickable = true
            isFocusable = true
            contentDescription = stringResource(
                R.string.home_recommend_filter_title
            )
            setOnClickListener { showHomeRecommendFilterDialog(anchor = it) }
        }
    ) {
        linearLayout(key = "PurifySettingsSections:3072",
            lparams = params { weight = 1f },
            init = { orientation = LinearLayout.VERTICAL }
        ) {
            textView(key = "PurifySettingsSections:3076", lparams = params(widthMatchParent = true)) {
                text = stringResource(R.string.home_recommend_filter_title)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            textView(key = "PurifySettingsSections:3081",
                lparams = params(widthMatchParent = true) {
                    topMargin = 4.dp
                }
            ) {
                homeRecommendFilterSummaryView = this
                alpha = 0.68f
                text = homeRecommendFilterSummary()
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
        }
        imageView(key = "PurifySettingsSections:3093", lparams = params(18.dp, 18.dp)) {
            setImageResource(R.drawable.ic_chevron_down)
            rotation = -90f
            alpha = 0.8f
            imageTintList = stateColorResource(R.color.colorTextGray)
        }
    }
    materialSwitch(key = "PurifySettingsSections:3100",
        lparams = params(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED, directToggle = true)
        settingsDestinations.bind(SettingsCatalog.ID_VIDEO_SEMANTIC_FILTER, this)
        text = stringResource(R.string.video_semantic_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = videoSemanticFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.VIDEO_SEMANTIC_FILTER_ENABLED, checked, button)
        }
    }
    semanticFilterDetails(this, SemanticSurface.VIDEO, SettingsCatalog.ID_VIDEO_SEMANTIC_RULES, R.string.video_semantic_filter_tip)
    materialSwitch(key = "PurifySettingsSections:3119",
        lparams = params(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED, directToggle = true)
        text = stringResource(R.string.home_recommend_title_filter)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = homeRecommendTitleFilterEnabled
        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_ENABLED, checked, button)
        }
    }
    textView(key = "PurifySettingsSections:3141",
        lparams = params(widthMatchParent = true)
    ) {
        bindSettingDestination(this, FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS)
        homeRecommendTitleSummaryView = this
        text = stringResource(R.string.home_recommend_title_rules) + "\n" +
            if (homeRecommendTitleKeywords.isBlank()) {
                stringResource(R.string.home_recommend_title_rules_empty)
            } else {
                stringResource(
                    R.string.home_recommend_title_rules_current,
                    homeRecommendTitleKeywords
                )
            }
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(5f, 1f)
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            showRuleEditorDialog(
                // 文案本来就与来源行相同（所以这处平移一直是好的），
                // 改成直接复用同一个 string，免得将来改文案时只改一边。
                R.string.home_recommend_title_rules,
                R.string.home_recommend_title_dialog_hint,
                homeRecommendTitleKeywords,
                anchor = it
            ) { value ->
                homeRecommendTitleKeywords = value
                prefs().edit {
                    putString(
                        FeaturePreferences.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS,
                        value
                    )
                }
                homeRecommendTitleSummaryView?.text =
                    stringResource(R.string.home_recommend_title_rules) + "\n" +
                        if (value.isBlank()) {
                            stringResource(R.string.home_recommend_title_rules_empty)
                        } else {
                            stringResource(
                                R.string.home_recommend_title_rules_current,
                                value
                            )
                        }
            }
        }
    }
    textView(key = "PurifySettingsSections:3193",
        lparams = params(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.home_recommend_purify_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}
