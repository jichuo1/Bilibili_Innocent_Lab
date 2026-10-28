@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.AdvancedSettingsCategory

import com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz.LinearSettingsScope

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CommunicationCompatibilityStore
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.view.setPadding
import androidx.core.view.updateMargins
import androidx.core.view.updatePadding
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toast
import io.github.jichuo1.meguviz.android.extensions.updateMargins
import io.github.jichuo1.meguviz.android.extensions.updatePadding
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.RoamingCompatHook
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSource
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.FreeCopyConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.MaterialColorSpec
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.MaterialColorSpecStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.ModalBackdropBlurStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.PredictiveBack
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRepository
import android.widget.LinearLayout as NativeLinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.content.edit
import android.R as Android_R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.SettingsSearchSection

/** 设置备份入口卡片。 */
internal fun MainActivity.settingsBackupEntryCard(scope: LinearSettingsScope) = with(scope) {
    linearLayout(key = "SettingsCardSections:55",
        lparams = params(widthMatchParent = true) {
            updateMargins(horizontal = 15.dp)
        },
        init = {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = skinCardBackground(monetColors.surfaceVariant)
            foreground = selfRippleBackground(15f)
            updatePadding(horizontal = 15.dp, vertical = 14.dp)
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            settingsBackupEntryView = this
            contentDescription = buildString {
                append(stringResource(R.string.settings_backup_entry_title))
                append(". ")
                append(stringResource(R.string.settings_backup_entry_summary))
            }
            setOnClickListener {
                launchSettingsBackup()
            }
        }
    ) {
        imageView(key = "SettingsCardSections:79",
            lparams = params(22.dp, 22.dp) {
                marginEnd = 12.dp
            }
        ) {
            setImageResource(R.drawable.ic_backup_restore)
            imageTintList = stateColorResource(R.color.colorTextGray)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        linearLayout(key = "SettingsCardSections:88",
            lparams = params {
                weight = 1f
            },
            init = {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        ) {
            textView(key = "SettingsCardSections:98", lparams = params(widthMatchParent = true)) {
                settingsBackupEntryTitleView = this
                text = stringResource(R.string.settings_backup_entry_title)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                post {
                    val card = settingsBackupEntryView ?: return@post
                    val sourceWindow = findViewById<View>(Android_R.id.content)
                    SettingsBackupTransitionOriginRegistry.register(
                        card,
                        this,
                        sourceWindow
                    )
                }
            }
            textView(key = "SettingsCardSections:114",
                lparams = params(widthMatchParent = true) {
                    topMargin = 4.dp
                }
            ) {
                alpha = 0.68f
                text = stringResource(R.string.settings_backup_entry_summary)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
        }
        textView(key = "SettingsCardSections:125", lparams = params(28.dp, 40.dp)) {
            gravity = Gravity.CENTER
            text = "›"
            textColor = colorResource(R.color.colorTextGray)
            textSize = 24f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
}

/** 净化设置卡片；进阶子分区的分类标题在此注册 marker，顺序必须与 AdvancedSettingsCategory 一致。 */
internal fun MainActivity.purifySettingsCard(scope: LinearSettingsScope) = with(scope) {
    linearLayout(key = "SettingsCardSections:138",
        lparams = params(widthMatchParent = true) {
            updateMargins(horizontal = 15.dp)
        },
        init = {
            purificationSettingsRoot = this
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER or Gravity.START
            background = skinCardBackground(monetColors.surfaceVariant)
            updatePadding(left = 15.dp, top = 15.dp, right = 15.dp, bottom = 15.dp)
        }
    ) {
        // 大类主标题：净化
        linearLayout(key = "SettingsCardSections:151",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 12.dp
            },
            init = {
                gravity = Gravity.CENTER or Gravity.START
            }
        ) {
            imageView(key = "SettingsCardSections:159",
                lparams = params(15.dp, 15.dp) {
                    marginEnd = 10.dp
                }
            ) {
                setImageResource(R.drawable.ic_purify)
                imageTintList = stateColorResource(R.color.colorTextGray)
            }
            textView(key = "SettingsCardSections:167",
                lparams = params(widthMatchParent = true)
            ) {
                alpha = 0.85f
                isSingleLine = true
                text = stringResource(R.string.purify_settings)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 12f
            }
        }
        // 子项 1：视频提及游戏广告
        textView(key = "SettingsCardSections:178",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            alpha = 0.7f
            isSingleLine = true
            text = stringResource(R.string.gamecard_ad_settings)
            textColor = colorResource(R.color.colorTextGray)
            textSize = 11f
        }
        materialSwitch(key = "SettingsCardSections:189",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, HookEntry.PREF_GAMECARD_ENABLED, directToggle = true)
            text = stringResource(R.string.gamecard_ad_enable)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = gamecardAdEnabled
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(HookEntry.PREF_GAMECARD_ENABLED, checked, button)
        }
        }
        textView(key = "SettingsCardSections:209",
            lparams = params(widthMatchParent = true) {
                topMargin = 5.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.gamecard_ad_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        materialSwitch(key = "SettingsCardSections:220",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION, directToggle = true)
            text = stringResource(R.string.hide_video_detail_app_promotion)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideVideoDetailAppPromotion
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HIDE_VIDEO_DETAIL_APP_PROMOTION, checked, button)
        }
        }
        textView(key = "SettingsCardSections:250",
            lparams = params(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_video_detail_app_promotion_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        // 隐藏 UP主分享好物推广（简介区商品广告——归类到视频详细页广告下）
        materialSwitch(key = "SettingsCardSections:260",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, HookEntry.PREF_MERCH_ENABLED, directToggle = true)
            text = stringResource(R.string.merch_ad_enable)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = merchAdEnabled
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(HookEntry.PREF_MERCH_ENABLED, checked, button)
        }
        }
        textView(key = "SettingsCardSections:281",
            lparams = params(widthMatchParent = true) {
                topMargin = 0.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.merch_ad_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        // 视频暂停页广告（暂停视频后可能弹出的推广大卡；原实验性功能正式化）
        materialSwitch(key = "SettingsCardSections:293",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, HookEntry.PREF_ENABLED, directToggle = true)
            text = stringResource(R.string.paused_page_ad_enable)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = adskipEnabled
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(HookEntry.PREF_ENABLED, checked, button)
        }
        }
        textView(key = "SettingsCardSections:314",
            lparams = params(widthMatchParent = true) {
                topMargin = 0.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.paused_page_ad_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        // 功能区分隔线
        frameLayout(key = "SettingsCardSections:326",
            lparams = params(widthMatchParent = true, height = 1.dp) {
                topMargin = 14.dp
                bottomMargin = 14.dp
            },
            init = {
                setBackgroundColor(ColorUtils.setAlphaComponent(colorResource(R.color.colorTextGray), 0x40))
            }
        )
        // 子项 2：首页大卡轮播
        textView(key = "SettingsCardSections:336",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            alpha = 0.7f
            isSingleLine = true
            text = stringResource(R.string.banner_ad_settings)
            textColor = colorResource(R.color.colorTextGray)
            textSize = 11f
        }
        materialSwitch(key = "SettingsCardSections:347",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, HookEntry.PREF_BANNER_ENABLED, directToggle = true)
            text = stringResource(R.string.banner_ad_enable)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = bannerAdEnabled
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(HookEntry.PREF_BANNER_ENABLED, checked, button)
        }
        }
        textView(key = "SettingsCardSections:367",
            lparams = params(widthMatchParent = true) {
                topMargin = 5.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.banner_ad_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        linearLayout(key = "SettingsCardSections:378",
            lparams = params(widthMatchParent = true) {
                topMargin = 14.dp
            },
            init = {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER or Gravity.START
                background = skinCardBackground(monetColors.surface, 12f)
                updatePadding(
                    left = SettingsMenuSpacing.ADVANCED_SHELL_DP.dp,
                    top = 5.dp,
                    right = SettingsMenuSpacing.ADVANCED_SHELL_DP.dp,
                    bottom = 5.dp
                )
            }
        ) {
            linearLayout(key = "SettingsCardSections:394",
                lparams = params(widthMatchParent = true),
                init = {
                    gravity = Gravity.CENTER or Gravity.START
                    updatePadding(vertical = 10.dp)
                    setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.PURIFICATION_ADVANCED) }
                }
            ) {
                imageView(key = "SettingsCardSections:402",
                    lparams = params(15.dp, 15.dp) {
                        marginEnd = 10.dp
                    }
                ) {
                    setImageResource(R.drawable.ic_purify)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                }
                textView(key = "SettingsCardSections:410",
                    lparams = params { weight = 1f }
                ) {
                    alpha = 0.85f
                    maxLines = 2
                    text = stringResource(R.string.purification_advanced_settings)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 12f
                }
                imageView(key = "SettingsCardSections:419",
                    lparams = params(18.dp, 18.dp)
                ) {
                    purificationAdvancedChevron = this
                    setImageResource(R.drawable.ic_chevron_down)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                    alpha = 0.85f
                }
            }
            linearLayout(key = "SettingsCardSections:428",
                lparams = params(widthMatchParent = true),
                init = {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    purificationAdvancedContent = this
                    updatePadding(bottom = 10.dp)
                }
            ) {
                purifyHomeCategory(advancedCategoryScope(AdvancedSettingsCategory.PURIFY_HOME))
                purifyNavigationCategory(advancedCategoryScope(AdvancedSettingsCategory.PURIFY_NAVIGATION))
                purifySearchCategory(advancedCategoryScope(AdvancedSettingsCategory.PURIFY_SEARCH))
                purifyMineCategory(advancedCategoryScope(AdvancedSettingsCategory.PURIFY_MINE))
                purifyPlaybackCategory(advancedCategoryScope(AdvancedSettingsCategory.PURIFY_PLAYBACK))
                purifyCommentsCategory(advancedCategoryScope(AdvancedSettingsCategory.PURIFY_COMMENTS))
                purifyStartupCategory(advancedCategoryScope(AdvancedSettingsCategory.PURIFY_STARTUP))
                purifyShareCategory(advancedCategoryScope(AdvancedSettingsCategory.PURIFY_SHARE))
                purifySharedCategory(advancedCategoryScope(AdvancedSettingsCategory.PURIFY_SHARED))
            }
        }
    }
}

/** 增强设置卡片；进阶子分区的分类标题在此注册 marker，顺序必须与 AdvancedSettingsCategory 一致。 */
/**
 * @param uiSettings 只有播放能力那一组是**按动态 key 循环**建开关的，拿不到对应的镜像字段，
 *   所以要把启动快照传进来。刻意用参数而不是字段：快照只在 `onCreate` 构建期有效，
 *   写回偏好之后它就是过期的（见 AGENTS.md 里 `uiSettings` 那条红线）。
 */
internal fun MainActivity.enhancementSettingsCard(
    scope: LinearSettingsScope,
    uiSettings: ModuleUiSettings
) = with(scope) {
    linearLayout(key = "SettingsCardSections:462",
        lparams = params(widthMatchParent = true) {
            updateMargins(horizontal = 15.dp)
        },
        init = {
            enhancementSettingsRoot = this
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER or Gravity.START
            background = skinCardBackground(monetColors.surfaceVariant)
            updatePadding(left = 15.dp, top = 15.dp, right = 15.dp, bottom = 15.dp)
        }
    ) {
        linearLayout(key = "SettingsCardSections:474",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 12.dp
            },
            init = {
                gravity = Gravity.CENTER or Gravity.START
            }
        ) {
            imageView(key = "SettingsCardSections:482",
                lparams = params(15.dp, 15.dp) {
                    marginEnd = 10.dp
                }
            ) {
                setImageResource(R.drawable.ic_enhancement)
                imageTintList = stateColorResource(R.color.colorTextGray)
            }
            textView(key = "SettingsCardSections:490",
                lparams = params(widthMatchParent = true)
            ) {
                alpha = 0.85f
                isSingleLine = true
                text = stringResource(R.string.enhancement_settings)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 12f
            }
        }
        // 自由复制能力保留在增强栏，气泡外观由美化栏管理。
        textView(key = "SettingsCardSections:501",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 4.dp
            }
        ) {
            alpha = 0.7f
            isSingleLine = true
            text = stringResource(R.string.free_copy_title)
            textColor = colorResource(R.color.colorTextGray)
            textSize = 11f
        }
        materialSwitch(key = "SettingsCardSections:512",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, HookEntry.PREF_FREE_COPY_ENABLED, directToggle = true)
            text = stringResource(R.string.free_copy_enable)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = freeCopyEnabled
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(HookEntry.PREF_FREE_COPY_ENABLED, checked, button)
        }
        }
        textView(key = "SettingsCardSections:541",
            lparams = params(widthMatchParent = true) {
                topMargin = 5.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.free_copy_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        // 简介长按自由复制（与评论复制并列，共用同一气泡样式）
        materialSwitch(key = "SettingsCardSections:553",
            lparams = params(widthMatchParent = true) {
                topMargin = 5.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, HookEntry.PREF_FREE_COPY_DESC_ENABLED, directToggle = true)
            text = stringResource(R.string.free_copy_desc_enable)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = freeCopyDescEnabled
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(HookEntry.PREF_FREE_COPY_DESC_ENABLED, checked, button)
        }
        }
        textView(key = "SettingsCardSections:583",
            lparams = params(widthMatchParent = true) {
                topMargin = 0.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.free_copy_desc_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        linearLayout(key = "SettingsCardSections:594",
            lparams = params(widthMatchParent = true) {
                topMargin = 14.dp
            },
            init = {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER or Gravity.START
                background = skinCardBackground(monetColors.surface, 12f)
                updatePadding(
                    left = SettingsMenuSpacing.ADVANCED_SHELL_DP.dp,
                    top = 5.dp,
                    right = SettingsMenuSpacing.ADVANCED_SHELL_DP.dp,
                    bottom = 5.dp
                )
            }
        ) {
            linearLayout(key = "SettingsCardSections:610",
                lparams = params(widthMatchParent = true),
                init = {
                    gravity = Gravity.CENTER or Gravity.START
                    updatePadding(vertical = 10.dp)
                    setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.ENHANCEMENT_ADVANCED) }
                }
            ) {
                imageView(key = "SettingsCardSections:618",
                    lparams = params(15.dp, 15.dp) {
                        marginEnd = 10.dp
                    }
                ) {
                    setImageResource(R.drawable.ic_enhancement)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                }
                textView(key = "SettingsCardSections:626",
                    lparams = params { weight = 1f }
                ) {
                    alpha = 0.85f
                    maxLines = 2
                    text = stringResource(R.string.enhancement_advanced_settings)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 12f
                }
                imageView(key = "SettingsCardSections:635",
                    lparams = params(18.dp, 18.dp)
                ) {
                    enhancementAdvancedChevron = this
                    setImageResource(R.drawable.ic_chevron_down)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                    alpha = 0.85f
                }
            }
            linearLayout(key = "SettingsCardSections:644",
                lparams = params(widthMatchParent = true),
                init = {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    enhancementAdvancedContent = this
                    // 12dp 留白由共享分组构建器提供，此处不得重复叠加。
                    updatePadding(bottom = 10.dp)
                }
            ) {
                enhanceBrowsingCategory(advancedCategoryScope(AdvancedSettingsCategory.ENHANCE_BROWSING))
                enhancePlaybackCategory(advancedCategoryScope(AdvancedSettingsCategory.ENHANCE_PLAYBACK), uiSettings)
                enhanceLiveCategory(advancedCategoryScope(AdvancedSettingsCategory.ENHANCE_LIVE))
                enhanceCommentsCategory(advancedCategoryScope(AdvancedSettingsCategory.ENHANCE_COMMENTS))
                enhanceDisplayCategory(advancedCategoryScope(AdvancedSettingsCategory.ENHANCE_DISPLAY))
                enhanceSystemCategory(advancedCategoryScope(AdvancedSettingsCategory.ENHANCE_SYSTEM))
            }
        }
    }
}

/** 宿主外观统一入口；复用原控件、偏好键及收藏监听器。 */
internal fun MainActivity.beautificationSettingsCard(scope: LinearSettingsScope) = with(scope) {
    linearLayout(key = "SettingsCardSections:668",
        lparams = params(widthMatchParent = true) {
            updateMargins(horizontal = 15.dp)
        },
        init = {
            beautificationSettingsRoot = this
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER or Gravity.START
            background = skinCardBackground(monetColors.surfaceVariant)
            updatePadding(left = 15.dp, top = 15.dp, right = 15.dp, bottom = 15.dp)
        }
    ) {
        hostAppearanceHeading(this, R.string.host_appearance_top_bar)
        hostTopBarAppearanceRows(this)
        hostAppearanceHeading(this, R.string.host_appearance_bottom_bar)
        hostBottomBarAppearanceRows(this)
        hostAppearanceHeading(this, R.string.host_appearance_video_cards)
        hostVideoCardAppearanceRows(this)
        hostBackgroundAppearanceRows(this)
        hostAppearanceHeading(this, R.string.host_appearance_player)
        materialSwitch(key = "SettingsCardSections:688",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR, directToggle = true)
            text = stringResource(R.string.transparent_player_status_bar)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = transparentPlayerStatusBar
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.TRANSPARENT_PLAYER_STATUS_BAR, checked, button)
        }
        }
        textView(key = "SettingsCardSections:718",
            lparams = params(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.transparent_player_status_bar_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        hostAppearanceHeading(this, R.string.host_appearance_splash)
        materialSwitch(key = "SettingsCardSections:728", lparams = params(widthMatchParent = true) { topMargin = 12.dp; bottomMargin = 5.dp }) {
            bindFavoriteSwitch(this, FeaturePreferences.BRAND_SPLASH_CUSTOM, directToggle = true)
            text = stringResource(R.string.brand_splash_custom)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = brandSplashCustom
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.BRAND_SPLASH_CUSTOM, checked, button)
        }
        }
        textView(key = "SettingsCardSections:744", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.brand_splash_custom_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        materialSwitch(key = "SettingsCardSections:751",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.SPLASH_AUTO_NIGHT, directToggle = true)
            text = stringResource(R.string.splash_auto_night)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = splashAutoNight
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.SPLASH_AUTO_NIGHT, checked, button)
        }
        }
        textView(key = "SettingsCardSections:781",
            lparams = params(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.splash_auto_night_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        // Either copy feature can display the shared bubble.
        val copyOptions = DependentAppearanceOptions { freeCopyEnabled || freeCopyDescEnabled }
        refreshCopyAppearanceOptions = copyOptions::refresh
        // 复制气泡外观同时控制评论与简介。
        textView(key = "SettingsCardSections:794",
            lparams = params(widthMatchParent = true) {
                topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
                bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
            }
        ) {
            text = stringResource(R.string.free_copy_appearance_settings)
            applyAdvancedSubsectionStyle()
        }
        materialSwitch(key = "SettingsCardSections:803",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, HookEntry.PREF_FREE_COPY_AUTO_LIGHT, directToggle = true)
            text = stringResource(R.string.free_copy_auto_light)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = freeCopyAutoLight
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(HookEntry.PREF_FREE_COPY_AUTO_LIGHT, checked, button)
        }
            autoLightSwitch = this
            copyOptions.add(this)
        }
        textView(key = "SettingsCardSections:829",
            lparams = params(widthMatchParent = true) {
                topMargin = 0.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.free_copy_auto_light_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
            copyOptions.add(this)
        }
        materialSwitch(key = "SettingsCardSections:841",
            lparams = params(widthMatchParent = true) {
                topMargin = 5.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, HookEntry.PREF_FREE_COPY_LIGHT_MODE, directToggle = false)
            text = stringResource(R.string.free_copy_light_mode)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = freeCopyLightMode
            setOnCheckedChangeListener { button, checked -> applyModeledToggle(HookEntry.PREF_FREE_COPY_LIGHT_MODE, checked, button) }
            manualLightSwitch = this
            copyOptions.add(this)
        }
        textView(key = "SettingsCardSections:894",
            lparams = params(widthMatchParent = true) {
                topMargin = 0.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(
                if (freeCopyAutoLight) R.string.free_copy_light_mode_auto_tip
                else R.string.free_copy_light_mode_tip
            )
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
            lightModeTipView = this
            copyOptions.add(this)
        }
    }
}

internal fun MainActivity.hostAppearanceHeading(
    scope: LinearSettingsScope,
    @StringRes title: Int
) = with(scope) {
    textView(key = "appearance-heading:$title",
        lparams = params(widthMatchParent = true) {
            topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
            bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
        }
    ) {
        text = stringResource(title)
        applyAdvancedSubsectionStyle()
    }
}

/** 实验性功能卡片（含外观、风格与配色等子区）。 */
internal fun MainActivity.experimentalFeaturesCard(
    scope: LinearSettingsScope,
    uiSettings: ModuleUiSettings
) = with(scope) {
    linearLayout(key = "SettingsCardSections:935",
        lparams = params(widthMatchParent = true) {
            updateMargins(horizontal = 15.dp)
        },
        init = {
            experimentalSettingsRoot = this
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER or Gravity.START
            updatePadding(vertical = 15.dp)
        }
    ) {
        linearLayout(key = "SettingsCardSections:946",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 12.dp
            },
            init = {
                gravity = Gravity.CENTER or Gravity.START
            }
        ) {
            imageView(key = "SettingsCardSections:954",
                lparams = params(15.dp, 15.dp) {
                    marginEnd = 10.dp
                }
            ) {
                setImageResource(R.drawable.ic_science)
                imageTintList = stateColorResource(R.color.colorTextGray)
            }
            textView(key = "SettingsCardSections:962",
                lparams = params(widthMatchParent = true)
            ) {
                alpha = 0.85f
                isSingleLine = true
                text = stringResource(R.string.experimental_features)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 12f
            }
        }
        linearLayout(key = "SettingsCardSections:972",
            lparams = params(widthMatchParent = true) {
                topMargin = 10.dp
            },
            init = {
                orientation = LinearLayout.VERTICAL
                background = skinCardBackground(monetColors.surface, 12f)
                updatePadding(horizontal = SettingsMenuSpacing.EXPERIMENTAL_SHELL_DP.dp, vertical = 4.dp)
            }
        ) {
            linearLayout(key = "SettingsCardSections:982",
                lparams = params(widthMatchParent = true),
                init = {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = 52.dp
                    updatePadding(vertical = 10.dp)
                    background = selfRippleBackground(10f)
                    isClickable = true
                    isFocusable = true
                    // 可展开标题参与全局长按弹性：长按后拖动时原生流会收到 CANCEL
                    // （涟漪退场、不触发展开），轻点仍在 UP 前恢复几何后正常展开，
                    // 与「净化/增强进阶设置」两个同型入口一致。
                    setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.APPEARANCE) }
                }
            ) {
                imageView(key = "SettingsCardSections:997",
                    lparams = params(20.dp, 20.dp) {
                        marginEnd = 10.dp
                    }
                ) {
                    setImageResource(R.drawable.ic_palette)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                linearLayout(key = "SettingsCardSections:1006",
                    lparams = params { weight = 1f },
                    init = { orientation = LinearLayout.VERTICAL }
                ) {
                    textView(key = "SettingsCardSections:1010", lparams = params(widthMatchParent = true)) {
                        text = stringResource(R.string.experimental_appearance)
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 14f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    textView(key = "SettingsCardSections:1016",
                        lparams = params(widthMatchParent = true) { topMargin = 4.dp }
                    ) {
                        text = stringResource(R.string.experimental_appearance_summary)
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                        alpha = 0.68f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                }
                imageView(key = "SettingsCardSections:1027",
                    lparams = params(18.dp, 18.dp) { marginStart = 8.dp }
                ) {
                    appearanceChevron = this
                    setImageResource(R.drawable.ic_chevron_down)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                    alpha = 0.85f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            }
            linearLayout(key = "SettingsCardSections:1037",
                lparams = params(widthMatchParent = true),
                init = {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    appearanceContent = this
                    // 所有行共用内容边距，避免入口容器与 Switch 各自增加水平缩进。
                    updatePadding(left = 4.dp, right = 4.dp, bottom = 10.dp)
                }
            ) {
                textView(key = "SettingsCardSections:1047",
                    lparams = params(widthMatchParent = true) {
                        topMargin = 10.dp
                        bottomMargin = 6.dp
                    }
                ) {
                    text = stringResource(R.string.appearance_style_and_color)
                    textColor = monetColors.primary
                    textSize = 12f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                materialSwitch(key = "SettingsCardSections:1058",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 5.dp
                    }
                ) {
                    settingsDestinations.bind(RuntimeSettingsNavigationCatalog.ADVANCED_MATERIAL, this)
                    updatePadding(horizontal = 0.dp)
                    text = stringResource(R.string.advanced_material_title)
                    isAllCaps = false
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    isChecked = SkinRepository.resolveRequestedSkin(applicationContext) == SkinId.LIQUID
                    setOnCheckedChangeListener { button, checked ->
                        if (advancedMaterialProgrammaticSwitch) {
                            return@setOnCheckedChangeListener
                        }
                        setAdvancedMaterial(button, checked)
                    }
                }
                textView(key = "SettingsCardSections:1076",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 10.dp
                    }
                ) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.advanced_material_summary)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                textView(key = "SettingsCardSections:1087",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 10.dp
                    }
                ) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.skin_setting_tip)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                linearLayout(key = "SettingsCardSections:1098",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 10.dp
                    },
                    init = {
                        orientation = LinearLayout.VERTICAL
                        background = selfRippleBackground(10f)
                        updatePadding(horizontal = 0.dp, vertical = 9.dp)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { showLiquidBackgroundDialog(it) }
                    }
                ) {
                    textView(key = "SettingsCardSections:1111",
                        lparams = params(widthMatchParent = true)
                    ) {
                        updatePadding(horizontal = 0.dp)
                        text = stringResource(R.string.liquid_background_setting_title)
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 15f
                    }
                    textView(key = "SettingsCardSections:1119",
                        lparams = params(widthMatchParent = true) {
                            topMargin = 4.dp
                        }
                    ) {
                        alpha = 0.72f
                        liquidBackgroundSummaryView = this
                        text = currentLiquidBackgroundSummary()
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                    }
                }
                materialSwitch(key = "SettingsCardSections:1131",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 5.dp
                    }
                ) {
                    settingsDestinations.bind(RuntimeSettingsNavigationCatalog.MATERIAL_COLOR_SPEC, this)
                    updatePadding(horizontal = 0.dp)
                    text = stringResource(R.string.material_color_spec_title)
                    isAllCaps = false
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    isChecked = MaterialColorSpecStore.read(applicationContext) ==
                        MaterialColorSpec.SPEC_2025
                    setOnCheckedChangeListener { button, checked ->
                        if (materialColorSpecProgrammaticSwitch) {
                            return@setOnCheckedChangeListener
                        }
                        val target = if (checked) {
                            MaterialColorSpec.SPEC_2025
                        } else {
                            MaterialColorSpec.SPEC_2021
                        }
                        if (MaterialColorSpecStore.write(applicationContext, target)) {
                            button.post {
                                if (!isFinishing && !isDestroyed) recreate()
                            }
                        } else {
                            materialColorSpecProgrammaticSwitch = true
                            button.isChecked = !checked
                            materialColorSpecProgrammaticSwitch = false
                            toast(stringResource(R.string.material_color_spec_save_failed))
                        }
                    }
                }
                textView(key = "SettingsCardSections:1164",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 10.dp
                    }
                ) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.material_color_spec_summary)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                materialSwitch(key = "SettingsCardSections:1175",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 5.dp
                    }
                ) {
                    settingsDestinations.bind(RuntimeSettingsNavigationCatalog.MODAL_BACKDROP_BLUR, this)
                    updatePadding(horizontal = 0.dp)
                    text = stringResource(R.string.panel_window_blur_title)
                    isAllCaps = false
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    // 未设置时默认关闭，见 ModalBackdropBlurStore.DEFAULT。
                    isChecked = ModalBackdropBlurStore.read(applicationContext)
                    setOnCheckedChangeListener { _, checked ->
                        prefs().edit {
                            putBoolean(ModalBackdropBlurStore.PREF_KEY, checked)
                        }
                    }
                }
                textView(key = "SettingsCardSections:1193",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 10.dp
                    }
                ) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.panel_window_blur_summary)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                textView(key = "SettingsCardSections:1204",
                    lparams = params(widthMatchParent = true) {
                        topMargin = 10.dp
                        bottomMargin = 6.dp
                    }
                ) {
                    text = stringResource(R.string.display_settings)
                    textColor = monetColors.primary
                    textSize = 12f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                linearLayout(key = "SettingsCardSections:1215",
                    lparams = params(widthMatchParent = true) {
                        topMargin = 8.dp
                    },
                    init = {
                        orientation = LinearLayout.VERTICAL
                        background = selfRippleBackground(10f)
                        updatePadding(horizontal = 0.dp, vertical = 9.dp)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { showAppLanguageDialog(it) }
                    }
                ) {
                    textView(key = "SettingsCardSections:1228",
                        lparams = params(widthMatchParent = true)
                    ) {
                        updatePadding(horizontal = 0.dp)
                        text = stringResource(R.string.app_language)
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 15f
                    }
                    textView(key = "SettingsCardSections:1236",
                        lparams = params(widthMatchParent = true) {
                            topMargin = 4.dp
                        }
                    ) {
                        alpha = 0.72f
                        text = currentAppLanguageSummary()
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                    }
                }
                textView(key = "SettingsCardSections:1247",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 5.dp
                    }
                ) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.app_language_tip)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                materialSwitch(key = "SettingsCardSections:1258",
                    lparams = params(widthMatchParent = true)
                ) {
                    settingsDestinations.bind(RuntimeSettingsNavigationCatalog.LAUNCHER_ICON, this)
                    updatePadding(horizontal = 0.dp)
                    text = stringResource(R.string.hide_app_icon_on_launcher)
                    isAllCaps = false
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    isChecked = !isLauncherIconShowing
                    setOnCheckedChangeListener { button, isChecked ->
                        if (button.isPressed) hideOrShowLauncherIcon(!isChecked)
                    }
                }
                textView(key = "SettingsCardSections:1271",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 10.dp
                    }
                ) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.hide_app_icon_on_launcher_tip)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                textView(key = "SettingsCardSections:1282",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 10.dp
                    }
                ) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.hide_app_icon_on_launcher_notice)
                    textColor = 0xFFFF5722.toInt()
                    textSize = 12f
                }
            }
        }
        linearLayout(key = "SettingsCardSections:1295",
            lparams = params(widthMatchParent = true) {
                topMargin = 10.dp
            },
            init = {
                orientation = LinearLayout.VERTICAL
                background = skinCardBackground(monetColors.surface, 12f)
                updatePadding(horizontal = SettingsMenuSpacing.EXPERIMENTAL_SHELL_DP.dp, vertical = 4.dp)
            }
        ) {
            linearLayout(key = "SettingsCardSections:1305",
                lparams = params(widthMatchParent = true),
                init = {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = 52.dp
                    updatePadding(vertical = 10.dp)
                    background = selfRippleBackground(10f)
                    isClickable = true
                    isFocusable = true
                    // 可展开标题参与全局长按弹性，同「外观」入口（见上）。
                    setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.COMPATIBILITY) }
                }
            ) {
                imageView(key = "SettingsCardSections:1318",
                    lparams = params(20.dp, 20.dp) {
                        marginEnd = 10.dp
                    }
                ) {
                    setImageResource(R.drawable.ic_extension)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                linearLayout(key = "SettingsCardSections:1327",
                    lparams = params { weight = 1f },
                    init = { orientation = LinearLayout.VERTICAL }
                ) {
                    textView(key = "SettingsCardSections:1331", lparams = params(widthMatchParent = true)) {
                        text = stringResource(R.string.experimental_compatibility)
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 14f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    }
                    textView(key = "SettingsCardSections:1337",
                        lparams = params(widthMatchParent = true) { topMargin = 4.dp }
                    ) {
                        text = stringResource(R.string.experimental_compatibility_summary)
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                        alpha = 0.68f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                }
                imageView(key = "SettingsCardSections:1348",
                    lparams = params(18.dp, 18.dp) { marginStart = 8.dp }
                ) {
                    compatibilityChevron = this
                    setImageResource(R.drawable.ic_chevron_down)
                    imageTintList = stateColorResource(R.color.colorTextGray)
                    alpha = 0.85f
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            }
            linearLayout(key = "SettingsCardSections:1358",
                lparams = params(widthMatchParent = true),
                init = {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    compatibilityContent = this
                    val contentPadding = SettingsMenuSpacing.matchingContentPaddingPx(
                        referenceShellPx = SettingsMenuSpacing.ADVANCED_SHELL_DP.dp,
                        ownShellPx = SettingsMenuSpacing.EXPERIMENTAL_SHELL_DP.dp,
                        density = resources.displayMetrics.density
                    )
                    updatePadding(left = contentPadding, right = contentPadding, bottom = 10.dp)
                }
            ) {
                materialSwitch(key = "SettingsCardSections:1372", lparams = params(widthMatchParent = true) { bottomMargin = 5.dp }) {
                    settingsDestinations.bind(RuntimeSettingsNavigationCatalog.COMMUNICATION_COMPATIBILITY, this)
                    text = stringResource(R.string.communication_compatibility_mode)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    isAllCaps = false
                    isChecked = uiSettings.bool(CommunicationCompatibilityStore.KEY) &&
                        CommunicationCompatibilityStore.warningAccepted(applicationContext)
                    compatibilityModeSwitch = this
                    setOnCheckedChangeListener { button, checked ->
                        if (compatibilityProgrammaticSwitch) return@setOnCheckedChangeListener
                        compatibilityProgrammaticSwitch = true
                        button.isChecked = CommunicationCompatibilityStore.isEnabled(applicationContext)
                        compatibilityProgrammaticSwitch = false
                        if (checked) showCommunicationCompatibilityConfirmDialog(anchor = button)
                        else setCommunicationCompatibilityEnabled(false)
                    }
                }
                textView(key = "SettingsCardSections:1389", lparams = params(widthMatchParent = true) { bottomMargin = 12.dp }) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.communication_compatibility_tip)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                materialSwitch(key = "SettingsCardSections:1396", lparams = params(widthMatchParent = true) { bottomMargin = 5.dp }) {
                    text = stringResource(R.string.bili_access_key_authorize)
                    settingsDestinations.bind(SettingsCatalog.ID_BILI_ACCESS_KEY_AUTHORIZED, this)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    isAllCaps = false
                    isChecked = biliAccessKeyAuthorized
                    biliAccessKeySwitch = this
                    setOnCheckedChangeListener { button, checked ->
                        if (biliAccessKeyProgrammaticSwitch) return@setOnCheckedChangeListener
                        // 打开必须先过风险确认：开关先弹回原状态，确认后才真正写入。
                        biliAccessKeyProgrammaticSwitch = true
                        button.isChecked = biliAccessKeyAuthorized
                        biliAccessKeyProgrammaticSwitch = false
                        if (checked) showBiliAccessKeyConfirmDialog(anchor = button)
                        else setBiliAccessKeyAuthorized(false)
                    }
                }
                textView(key = "SettingsCardSections:1414", lparams = params(widthMatchParent = true) { bottomMargin = 12.dp }) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.bili_access_key_authorize_tip)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                // JEV 语义判定配置：Key / 地址 / 灵敏度 / 首屏等待，都在同一个面板里保存。
                textView(key = "SettingsCardSections:1422", lparams = params(widthMatchParent = true) { bottomMargin = 5.dp }) {
                    semanticJevSummaryView = this
                    settingsDestinations.bind(SettingsCatalog.ID_SEMANTIC_JEV_ENDPOINT, this)
                    settingsDestinations.bind(SettingsCatalog.ID_SEMANTIC_JEV_SENSITIVITY, this)
                    settingsDestinations.bind(SettingsCatalog.ID_SEMANTIC_JEV_WAIT_FIRST_SCREEN, this)
                    settingsDestinations.bind(SettingsCatalog.ID_SEMANTIC_JEV_CACHE_DAYS, this)
                    settingsDestinations.bind(SettingsCatalog.ID_SEMANTIC_JEV_PROVIDER, this)
                    settingsDestinations.bind(SettingsCatalog.ID_SEMANTIC_JEV_MODEL, this)
                    settingsDestinations.bind(SettingsCatalog.ID_SEMANTIC_JEV_TIMEOUT_MS, this)
                    settingsDestinations.bind(SettingsCatalog.ID_SEMANTIC_JEV_GUIDANCE, this)
                    (2..SemanticSource.MAX_SOURCES).forEach { index ->
                        listOf("provider", "endpoint", "model").forEach { field ->
                            settingsDestinations.bind("compat.semantic_source.$index.$field", this)
                        }
                    }
                    text = semanticJevEntryText()
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    setLineSpacing(5f, 1f)
                    setPadding(12.dp, 10.dp, 12.dp, 10.dp)
                    background = selfRippleBackground(10f)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        showSemanticJevSettingsDialog(anchor = it) {
                            semanticJevSummaryView?.text = semanticJevEntryText()
                        }
                    }
                }
                textView(key = "SettingsCardSections:1451", lparams = params(widthMatchParent = true) { bottomMargin = 12.dp }) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.semantic_jev_tip)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                textView(key = "SettingsCardSections:1458", lparams = params(widthMatchParent = true) { bottomMargin = 12.dp }) {
                    text = stringResource(R.string.agent_title)
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    setPadding(12.dp, 10.dp, 12.dp, 10.dp)
                    background = selfRippleBackground(10f)
                    isClickable = true
                    isFocusable = true
                    bindSettingDestination(this, com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences.ENABLED)
                    setOnClickListener { showAgentDialog(anchor = it) }
                }
                materialSwitch(key = "SettingsCardSections:1469",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 5.dp
                    }
                ) {
                    settingsDestinations.bind(RuntimeSettingsNavigationCatalog.NO_ROOT_SUPPORT, this)
                    text = stringResource(R.string.no_root_support_enable)
                    isAllCaps = false
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    isChecked = noRootDesiredEnabled
                    isEnabled = noRootDesiredEnabled ||
                        (
                            AndroidVersion.isAtLeast(AndroidVersion.P) &&
                                noRootPrefsBridge != null
                            )
                    setOnCheckedChangeListener { _, isChecked ->
                        if (noRootProgrammaticSwitch) return@setOnCheckedChangeListener
                        noRootDesiredEnabled = isChecked
                        if (isChecked) enableAndSynchronizeNoRootSupport()
                        else disableNoRootSupport()
                    }
                    noRootSwitch = this
                }
                textView(key = "SettingsCardSections:1492",
                    lparams = params(widthMatchParent = true)
                ) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.no_root_support_tip)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                textView(key = "SettingsCardSections:1501",
                    lparams = params(widthMatchParent = true) {
                        topMargin = 4.dp
                        bottomMargin = 10.dp
                    }
                ) {
                    alpha = 0.8f
                    setLineSpacing(6f, 1f)
                    text = stringResource(noRootStatusText(currentNoRootDisplayState()))
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 12f
                    noRootStatusView = this
                }
                materialSwitch(key = "SettingsCardSections:1514",
                    lparams = params(widthMatchParent = true) {
                        bottomMargin = 5.dp
                    }
                ) {
                    bindFavoriteSwitch(this, HookEntry.PREF_ROAMING_COMPAT_ENABLED, directToggle = true)
                    text = stringResource(R.string.roaming_compat_enable)
                    isAllCaps = false
                    textColor = colorResource(R.color.colorTextGray)
                    textSize = 15f
                    isChecked = roamingCompatEnabled
                    setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(HookEntry.PREF_ROAMING_COMPAT_ENABLED, checked, button)
        }
                }
                textView(key = "SettingsCardSections:1545",
                    lparams = params(widthMatchParent = true) {
                        topMargin = 5.dp
                    }
                ) {
                    alpha = 0.6f
                    setLineSpacing(6f, 1f)
                    text = stringResource(R.string.roaming_compat_tip)
                    textColor = colorResource(R.color.colorTextDark)
                    textSize = 12f
                }
                // 预见式返回动画（Android 14+）：实验性开关，运行时切换 window 的
                // OnBackInvokedCallback 体系，返回时显示系统缩放预览动画。
                // Android 16（API 36）+ 系统对 targetSdk 36+ 的应用强制启用
                // 预测性返回（隐藏 API 的关闭调用被忽略），开关失去实际效果，
                // 此时整组隐藏；偏好键与备份 catalog 条目保留以兼容旧备份。
                if (!PredictiveBack.isSystemEnforced) {
                    materialSwitch(key = "SettingsCardSections:1562",
                        lparams = params(widthMatchParent = true) {
                            topMargin = 5.dp
                            bottomMargin = 5.dp
                        }
                    ) {
                        bindFavoriteSwitch(this, HookEntry.PREF_PREDICTIVE_BACK_ENABLED, directToggle = true)
                        text = stringResource(R.string.predictive_back_enable)
                        isAllCaps = false
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 15f
                        isChecked = predictiveBackEnabled
                        setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(HookEntry.PREF_PREDICTIVE_BACK_ENABLED, checked, button)
        }
                    }
                    textView(key = "SettingsCardSections:1585",
                        lparams = params(widthMatchParent = true) {
                            topMargin = 0.dp
                        }
                    ) {
                        alpha = 0.6f
                        setLineSpacing(6f, 1f)
                        text = stringResource(R.string.predictive_back_tip)
                        textColor = colorResource(R.color.colorTextDark)
                        textSize = 12f
                    }
                }
                // 手动重新适配：清除版本适配缓存，重启哔哩哔哩后自动重新定位 hook 点。
                // 风格与「实验性功能」分区标题行统一：图标 + 文字 + 水波纹点击反馈；
                // 点击弹出二级确认菜单（与「重启哔哩哔哩」确认弹窗同规格）
                linearLayout(key = "SettingsCardSections:1600",
                    lparams = params(widthMatchParent = true) {
                        topMargin = 12.dp
                        bottomMargin = 2.dp
                    },
                    init = {
                        gravity = Gravity.CENTER or Gravity.START
                        // 自绘涟漪（不解析主题属性 selectableItemBackground）：
                        // 客户设备上的全局主题模块（Monet-All 等）可能把该主题属性
                        // 解析为不透明实心 drawable，整行盖住内容 →「只剩空位但可点击」
                        background = selfRippleBackground(10f)
                        updatePadding(horizontal = 0.dp, vertical = 9.dp)
                        setOnClickListener { showAdaptConfirmDialog(it) }
                    }
                ) {
                    imageView(key = "SettingsCardSections:1615",
                        lparams = params(17.dp, 17.dp) {
                            marginEnd = 9.dp
                        }
                    ) {
                        setImageResource(R.drawable.ic_restart)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        alpha = 0.8f
                    }
                    textView(key = "SettingsCardSections:1624",
                        lparams = params {
                            weight = 1f
                        }
                    ) {
                        isSingleLine = true
                        text = stringResource(R.string.adapt_manual)
                        textColor = colorResource(R.color.colorTextGray)
                        textSize = 14f
                    }
                    imageView(key = "SettingsCardSections:1634",
                        lparams = params(16.dp, 16.dp)
                    ) {
                        alpha = 0.55f
                        setImageResource(R.drawable.ic_chevron_down)
                        imageTintList = stateColorResource(R.color.colorTextGray)
                        rotation = -90f
                    }
                }
            }
        }
    }
}

/** 日志设置卡片。 */
internal fun MainActivity.logSettingsCard(scope: LinearSettingsScope) = with(scope) {
    linearLayout(key = "SettingsCardSections:1651",
        lparams = params(widthMatchParent = true) {
            updateMargins(horizontal = 15.dp)
        },
        init = {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER or Gravity.START
            background = skinCardBackground(monetColors.surfaceVariant)
            updatePadding(left = 15.dp, top = 15.dp, right = 15.dp, bottom = 15.dp)
            // 日志档位滑块长按拖动时会溢出自身边界（按压缩放约 4dp + 弹性位移 4dp），
            // 默认 clipToPadding=true 会把溢出圆角裁成竖直断框；上界小于 15dp 内边距，装得下。
            clipChildren = false
            clipToPadding = false
        }
    ) {
        linearLayout(key = "SettingsCardSections:1666",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 10.dp
            },
            init = {
                gravity = Gravity.CENTER or Gravity.START
            }
        ) {
            imageView(key = "SettingsCardSections:1674",
                lparams = params(15.dp, 15.dp) {
                    marginEnd = 10.dp
                }
            ) {
                setImageResource(R.drawable.ic_article)
                imageTintList = stateColorResource(R.color.colorTextGray)
            }
            textView(key = "SettingsCardSections:1682",
                lparams = params(widthMatchParent = true)
            ) {
                alpha = 0.85f
                isSingleLine = true
                text = stringResource(R.string.log_settings)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 12f
            }
        }
        materialSwitch(key = "SettingsCardSections:1692",
            lparams = params(widthMatchParent = true) {
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, HookEntry.PREF_LOG_ENABLED, directToggle = true)
            text = stringResource(R.string.log_capture_enable)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = logEnabled
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(HookEntry.PREF_LOG_ENABLED, checked, button)
        }
        }
        textView(key = "SettingsCardSections:1712",
            lparams = params(widthMatchParent = true) {
                topMargin = 5.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.log_capture_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        // 详细度档位选择器（精简 / 完整）
        textView(key = "SettingsCardSections:1724",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 6.dp
            }
        ) {
            alpha = 0.85f
            isSingleLine = true
            text = stringResource(R.string.log_level_label)
            textColor = colorResource(R.color.colorTextGray)
            textSize = 13f
        }
        // 详细度档位选择器：底栏同款 scrub 控件（长按拖动切换 + 触点高光 + 弹簧回弹）。
        // 不走 skinSelectionControl：它会把这里整份替换成 surface 系表面，
        // 轨道与滑块就都成了灰白系，滑块的 primary 填充随之丢失。
        logSegmentScrubBar(key = "SettingsCardSections:1739",
            lparams = params(widthMatchParent = true),
            init = {
                bindSettingDestination(this, HookEntry.PREF_LOG_LEVEL)
                // 控件整体接管触摸（轻点/拖动/回弹），与全局长按弹性手势互斥，
                // 打上排除标记（底栏 dock 同款处理）。
                tag = com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController.EXCLUDED_TAG
                configure(
                    labels = listOf(
                        stringResource(R.string.log_level_minimal),
                        stringResource(R.string.log_level_complete)
                    ),
                    colors = ModernNavigationColors(
                        text = colorResource(R.color.colorTextGray),
                        selectedText = monetColors.onPrimary,
                        highlight = monetColors.primary
                    ),
                    thumbBackground = logLevelThumbBg(),
                    trackBackground = GradientDrawable().apply {
                        cornerRadius = resources.displayMetrics.density * 10f
                        setColor(monetColors.background)
                    },
                    selectedIndex = if (logVerbose) 1 else 0,
                    onSelect = ::commitLogLevel
                )
            }
        )
        // 档位描述（随选中项动态更新）
        textView(key = "SettingsCardSections:1767",
            lparams = params(widthMatchParent = true) {
                topMargin = 6.dp
            }
        ) {
            logLevelDesc = this
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(if (logVerbose) R.string.log_level_complete_desc else R.string.log_level_minimal_desc)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}
