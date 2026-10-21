@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Typeface
import android.util.Log
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.updatePadding
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.ImageView
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.android.widget.TextView
import com.highcapable.hikage.widget.com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
import com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ExactRuleSetCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TidBlocklistCodec
import android.widget.LinearLayout as NativeLinearLayout
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity.AdvancedSettingsCategory

/** 进阶增强 · ENHANCE_SYSTEM 分类；标题即 marker，必须是本组第一个子控件。 */
@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.enhanceSystemCategory(scope: Hikage.Performer<NativeLinearLayout.LayoutParams>) = with(scope) {
    TextView(
        lparams = LayoutParams(widthMatchParent = true) {
            bottomMargin = 4.dp
        }
    ) {
        advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_SYSTEM] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_enhance_system)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    MaterialSwitch(lparams = LayoutParams(widthMatchParent = true) { topMargin = 12.dp; bottomMargin = 5.dp }) {
        bindFavoriteSwitch(this, FeaturePreferences.BRAND_SPLASH_SKIP, directToggle = true)
        text = stringResource(R.string.brand_splash_skip)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = brandSplashSkip
        setOnCheckedChangeListener { _, checked ->
            runCatching { prefs().edit { putBoolean(FeaturePreferences.BRAND_SPLASH_SKIP, checked) } }
                .onSuccess { brandSplashSkip = checked }
                .onFailure { throwable ->
                    Log.e("BilibiliInnocentLab", "write brand splash skip prefs failed", throwable)
                    isChecked = brandSplashSkip
                }
        }
    }
    TextView(lparams = LayoutParams(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.brand_splash_skip_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION, directToggle = true)
        text = stringResource(R.string.system_media_notification)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = systemMediaNotification
        setOnCheckedChangeListener { _, checked ->
            systemMediaNotification = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.SYSTEM_MEDIA_NOTIFICATION,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write system media notification prefs failed",
                    throwable
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.system_media_notification_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.FORCE_EXTERNAL_BROWSER, directToggle = true)
        text = stringResource(R.string.force_external_browser)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = forceExternalBrowser
        setOnCheckedChangeListener { _, checked ->
            forceExternalBrowser = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.FORCE_EXTERNAL_BROWSER,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write external browser prefs failed",
                    throwable
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.force_external_browser_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶增强 · ENHANCE_DISPLAY 分类；标题即 marker，必须是本组第一个子控件。 */
@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.enhanceDisplayCategory(scope: Hikage.Performer<NativeLinearLayout.LayoutParams>) = with(scope) {
    TextView(
        lparams = LayoutParams(widthMatchParent = true) {
            bottomMargin = 4.dp
        }
    ) {
        advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_DISPLAY] = this
        alpha = 0.9f
        text = stringResource(R.string.number_display_settings)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.SHOW_FULL_NUMBERS, directToggle = true)
        text = stringResource(R.string.show_full_numbers)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = showFullNumbers
        setOnCheckedChangeListener { _, isChecked ->
            showFullNumbers = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.SHOW_FULL_NUMBERS,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write full number prefs failed",
                    t
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.show_full_numbers_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    // AV 号显示同样是"同一个数字换一种写法"，与完整播放量归在数字显示。
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.SHOW_BV_AS_AV, directToggle = true)
        text = stringResource(R.string.show_bv_as_av)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = showBvAsAv
        setOnCheckedChangeListener { _, checked ->
            showBvAsAv = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.SHOW_BV_AS_AV,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write bv to av prefs failed",
                    throwable
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.show_bv_as_av_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶增强 · ENHANCE_COMMENTS 分类；标题即 marker，必须是本组第一个子控件。 */
@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.enhanceCommentsCategory(scope: Hikage.Performer<NativeLinearLayout.LayoutParams>) = with(scope) {
    TextView(
        lparams = LayoutParams(widthMatchParent = true) {
            bottomMargin = 4.dp
        }
    ) {
        advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_COMMENTS] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_enhance_comments)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.COMMENT_CLASSIC_STYLE, directToggle = true)
        text = stringResource(R.string.comment_classic_style)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = commentClassicStyle
        setOnCheckedChangeListener { _, checked ->
            commentClassicStyle = checked
            prefs().edit { putBoolean(FeaturePreferences.COMMENT_CLASSIC_STYLE, checked) }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true) {
            bottomMargin = 12.dp
        }
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.comment_classic_style_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.REPLY_TOPOLOGY_ENABLED, directToggle = true)
        text = stringResource(R.string.reply_topology_enabled)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = replyTopologyEnabled
        setOnCheckedChangeListener { _, checked ->
            replyTopologyEnabled = checked
            prefs().edit {
                putBoolean(
                    FeaturePreferences.REPLY_TOPOLOGY_ENABLED,
                    checked
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.reply_topology_enabled_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY, directToggle = true)
        text = stringResource(R.string.block_comment_quick_reply)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = blockCommentQuickReply
        setOnCheckedChangeListener { _, isChecked ->
            blockCommentQuickReply = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.BLOCK_COMMENT_QUICK_REPLY,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write comment quick reply prefs failed",
                    t
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.block_comment_quick_reply_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶增强 · ENHANCE_LIVE 分类；标题即 marker，必须是本组第一个子控件。 */
@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.enhanceLiveCategory(scope: Hikage.Performer<NativeLinearLayout.LayoutParams>) = with(scope) {
    TextView(
        lparams = LayoutParams(widthMatchParent = true) {
            bottomMargin = 4.dp
        }
    ) {
        advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_LIVE] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_enhance_live)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH, directToggle = true)
        text = stringResource(R.string.block_live_room_switch)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = blockLiveRoomSwitch
        setOnCheckedChangeListener { _, checked ->
            blockLiveRoomSwitch = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.BLOCK_LIVE_ROOM_SWITCH,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write live room switch prefs failed",
                    throwable
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.block_live_room_switch_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE, directToggle = true)
        text = stringResource(R.string.live_room_double_tap_pause)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = liveRoomDoubleTapPause
        setOnCheckedChangeListener { _, checked ->
            liveRoomDoubleTapPause = checked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.LIVE_ROOM_DOUBLE_TAP_PAUSE,
                        checked
                    )
                }
            }.onFailure { throwable ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write live room double tap prefs failed",
                    throwable
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.live_room_double_tap_pause_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶增强 · ENHANCE_PLAYBACK 分类；标题即 marker，必须是本组第一个子控件。 */
@com.highcapable.hikage.annotation.Hikagable
/**
 * @param uiSettings 播放能力那一组是**按动态 key 循环**建开关的，拿不到对应的镜像字段，
 *   所以要把启动快照传进来。刻意用参数而不是字段：快照只在 `onCreate` 构建期有效，
 *   写回偏好之后它就是过期的（见 AGENTS.md 里 `uiSettings` 那条红线）。
 */
internal fun MainActivity.enhancePlaybackCategory(
    scope: Hikage.Performer<NativeLinearLayout.LayoutParams>,
    uiSettings: ModuleUiSettings
) = with(scope) {
    TextView(
        lparams = LayoutParams(widthMatchParent = true) {
            bottomMargin = 4.dp
        }
    ) {
        advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_PLAYBACK] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_enhance_playback)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    LinearLayout(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
        },
        init = {
            orientation = LinearLayout.VERTICAL
            background = selfRippleBackground(10f)
            updatePadding(horizontal = 0.dp, vertical = 9.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener { showPlayerQualityDialog(it) }
        }
    ) {
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            text = stringResource(R.string.player_default_quality)
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 5.dp
            }
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(
                R.string.player_default_quality_current,
                playerQualityLabel(playerDefaultQualityQn)
            )
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
            playerQualitySummaryView = this
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 4.dp
        }
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.player_default_quality_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    listOf(
        Triple(
            R.string.player_codec_preference,
            R.string.player_codec_preference_tip,
            { anchor: View -> showPlayerCodecPreferenceDialog(anchor) }
        ),
        Triple(
            R.string.player_decode_mode,
            R.string.player_decode_mode_tip,
            { anchor: View -> showPlayerDecodeModeDialog(anchor) }
        )
    ).forEachIndexed { index, (titleRes, tipRes, open) ->
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = if (index == 0) 12.dp else 8.dp
            },
            init = {
                orientation = LinearLayout.VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 0.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                setOnClickListener { open(this) }
            }
        ) {
            TextView(lparams = LayoutParams(widthMatchParent = true)) {
                text = stringResource(titleRes)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 5.dp }) {
                alpha = 0.6f
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                setLineSpacing(6f, 1f)
                if (index == 0) {
                    playerCodecPreferenceSummaryView = this
                    text = getString(
                        R.string.player_codec_preference_current,
                        playerCodecPreferenceLabel(playerCodecPreference)
                    )
                } else {
                    playerDecodeModeSummaryView = this
                    text = getString(
                        R.string.player_decode_mode_current,
                        playerDecodeModeLabel(playerDecodeMode)
                    )
                }
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(tipRes)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
    playerBufferSettingsRows(this)
    playerFrameInterpolationSettingsRows(this, uiSettings)
    TextView(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
            bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
        }
    ) {
        text = stringResource(R.string.player_capabilities_title)
        applyAdvancedSubsectionStyle()
    }
    listOf(
        FeaturePreferences.PLAYER_UNLOCK_BACKGROUND to R.string.player_unlock_background,
        FeaturePreferences.PLAYER_UNLOCK_SMALL_WINDOW to R.string.player_unlock_small_window,
        FeaturePreferences.PLAYER_UNLOCK_CAST to R.string.player_unlock_cast
    ).forEach { (key, label) ->
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, key)
            text = stringResource(label)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = uiSettings.bool(key)
            setOnCheckedChangeListener { _, checked ->
                runCatching { prefs().edit { putBoolean(key, checked) } }.onFailure {
                    Log.e("BilibiliInnocentLab", "write player capability prefs failed", it)
                }
            }
        }
    }
    TextView(lparams = LayoutParams(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.player_capabilities_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    TextView(lparams = LayoutParams(widthMatchParent = true) {
        topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
        bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
    }) {
        text = stringResource(R.string.player_subtitle_title)
        applyAdvancedSubsectionStyle()
    }
    listOf(
        Triple(FeaturePreferences.PLAYER_SUBTITLE_STYLE, R.string.player_subtitle_style, R.string.player_subtitle_style_tip),
        Triple(FeaturePreferences.PLAYER_AI_SUBTITLE, R.string.player_ai_subtitle, R.string.player_ai_subtitle_tip)
    ).forEach { (key, label, tip) ->
        MaterialSwitch(lparams = LayoutParams(widthMatchParent = true) { topMargin = 12.dp; bottomMargin = 5.dp }) {
            bindFavoriteSwitch(this, key)
            text = stringResource(label)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = uiSettings.bool(key)
            var savedChecked = isChecked
            setOnCheckedChangeListener { _, checked ->
                if (checked != savedChecked) {
                    runCatching { prefs().edit { putBoolean(key, checked) } }
                        .onSuccess { savedChecked = checked }
                        .onFailure {
                            Log.e("BilibiliInnocentLab", "write subtitle prefs failed", it)
                            isChecked = savedChecked
                        }
                }
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            text = stringResource(tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
            setLineSpacing(6f, 1f)
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
            bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
        }
    ) {
        text = stringResource(R.string.player_speed_title)
        applyAdvancedSubsectionStyle()
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, directToggle = true)
        text = stringResource(R.string.player_disable_long_press)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = playerDisableLongPress
        setOnCheckedChangeListener { _, checked ->
            playerDisableLongPress = checked
            runCatching {
                prefs().edit { putBoolean(FeaturePreferences.PLAYER_DISABLE_LONG_PRESS, checked) }
            }.onFailure {
                Log.e("BilibiliInnocentLab", "write player long press prefs failed", it)
            }
            updatePlayerSpeedSummaries()
        }
    }
    listOf(true, false).forEach { longPress ->
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) { topMargin = 8.dp },
            init = {
                orientation = LinearLayout.VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 0.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                setOnClickListener { showPlayerSpeedDialog(longPress, it) }
                bindSettingDestination(this, if (longPress) FeaturePreferences.PLAYER_LONG_PRESS_SPEED_PERCENT
                    else FeaturePreferences.PLAYER_DEFAULT_SPEED_PERCENT)
            }
        ) {
            TextView(lparams = LayoutParams(widthMatchParent = true)) {
                text = stringResource(if (longPress) R.string.player_long_press_speed else R.string.player_default_speed)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 5.dp }) {
                alpha = 0.6f
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                setLineSpacing(6f, 1f)
                if (longPress) playerLongPressSpeedSummary = this else playerDefaultSpeedSummary = this
                updatePlayerSpeedSummaries()
            }
        }
    }
    LinearLayout(
        lparams = LayoutParams(widthMatchParent = true) { topMargin = 8.dp },
        init = {
            orientation = LinearLayout.VERTICAL
            background = selfRippleBackground(10f)
            updatePadding(horizontal = 0.dp, vertical = 9.dp)
            isClickable = true
            isFocusable = true
            setOnClickListener { showPlayerSpeedMenuDialog(it) }
            settingsDestinations.bind(SettingsCatalog.ID_PLAYER_SPEED_MENU, this)
        }
    ) {
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            text = stringResource(R.string.player_speed_menu)
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
        }
        TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 5.dp }) {
            alpha = 0.6f
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
            setLineSpacing(6f, 1f)
            playerSpeedMenuSummary = this
            updatePlayerSpeedSummaries()
        }
    }
    TextView(lparams = LayoutParams(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.player_speed_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    TextView(lparams = LayoutParams(widthMatchParent = true) {
        topMargin = AdvancedSubsectionStyle.TOP_MARGIN_DP.dp
        bottomMargin = AdvancedSubsectionStyle.BOTTOM_MARGIN_DP.dp
    }) {
        text = stringResource(R.string.sponsorblock_title)
        applyAdvancedSubsectionStyle()
    }
    listOf(FeaturePreferences.SPONSORBLOCK_ENABLED to R.string.sponsorblock_enabled,
        FeaturePreferences.SPONSORBLOCK_AUTOMATIC to R.string.sponsorblock_automatic).forEach { (key, label) ->
        MaterialSwitch(lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }) {
            bindFavoriteSwitch(this, key)
            text = stringResource(label)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = uiSettings.bool(key)
            setOnCheckedChangeListener { _, checked ->
                runCatching { prefs().edit { putBoolean(key, checked) } }.onFailure {
                    Log.e("BilibiliInnocentLab", "write sponsorblock prefs failed", it)
                }
            }
        }
    }
    TextView(lparams = LayoutParams(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.sponsorblock_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}

/** 进阶增强 · ENHANCE_BROWSING 分类；标题即 marker，必须是本组第一个子控件。 */
@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.enhanceBrowsingCategory(scope: Hikage.Performer<NativeLinearLayout.LayoutParams>) = with(scope) {
    TextView(
        lparams = LayoutParams(widthMatchParent = true) {
            bottomMargin = 4.dp
        }
    ) {
        advancedCategoryMarkers[AdvancedSettingsCategory.ENHANCE_BROWSING] = this
        alpha = 0.9f
        text = stringResource(R.string.advanced_enhance_browsing)
        textColor = monetColors.primary
        textSize = 12f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    MaterialSwitch(lparams = LayoutParams(widthMatchParent = true) {
        topMargin = 12.dp
        bottomMargin = 5.dp
    }) {
        bindFavoriteSwitch(this, FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED, directToggle = true)
        text = stringResource(R.string.home_recommend_section_pick)
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = homeRecommendSectionPickEnabled
        setOnCheckedChangeListener { _, checked ->
            homeRecommendSectionPickEnabled = checked
            prefs().edit {
                putBoolean(
                    FeaturePreferences.HOME_RECOMMEND_SECTION_PICK_ENABLED,
                    checked
                )
            }
        }
    }
    TextView(lparams = LayoutParams(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.home_recommend_section_pick_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true) { topMargin = 12.dp }
    ) {
        homeRecommendBlockedTidsSummaryView = this
        text = ruleEntryText(
            R.string.home_recommend_blocked_tids,
            R.string.home_recommend_blocked_tids_empty,
            R.string.home_recommend_blocked_tids_current,
            recommendationRuleCount(homeRecommendBlockedTids, tags = true)
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
                R.string.home_recommend_blocked_tids,
                R.string.home_recommend_blocked_tids_hint,
                prefs().getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, "").orEmpty(),
                anchor = it
            ) { value ->
                // 落库前先规范化：手填的非法项与重复项在这里就清掉，
                // 免得摘要显示一堆过滤链根本不认的内容。
                // 用 normalize 而不是 encode(parse(...))——后者只留数字，
                // 用户填的标签名会在按确定的瞬间被静默吃掉。
                val normalized = TidBlocklistCodec.normalize(value)
                homeRecommendBlockedTids = normalized
                prefs().edit {
                    putString(
                        FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS,
                        normalized
                    )
                }
                homeRecommendBlockedTidsSummaryView?.text = ruleEntryText(
                    R.string.home_recommend_blocked_tids,
                    R.string.home_recommend_blocked_tids_empty,
                    R.string.home_recommend_blocked_tids_current,
                    recommendationRuleCount(normalized, tags = true)
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.home_recommend_blocked_tids_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true) { topMargin = 12.dp }
    ) {
        homeRecommendBlockedAuthorsSummaryView = this
        text = ruleEntryText(
            R.string.home_recommend_blocked_authors,
            R.string.home_recommend_blocked_authors_empty,
            R.string.home_recommend_blocked_authors_current,
            recommendationRuleCount(homeRecommendBlockedAuthors, tags = false)
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
                R.string.home_recommend_blocked_authors,
                R.string.home_recommend_blocked_authors_hint,
                prefs().getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, "").orEmpty(),
                anchor = it
            ) { value ->
                val normalized = ExactRuleSetCodec.encode(ExactRuleSetCodec.parse(value))
                homeRecommendBlockedAuthors = normalized
                prefs().edit {
                    putString(
                        FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS,
                        normalized
                    )
                }
                homeRecommendBlockedAuthorsSummaryView?.text = ruleEntryText(
                    R.string.home_recommend_blocked_authors,
                    R.string.home_recommend_blocked_authors_empty,
                    R.string.home_recommend_blocked_authors_current,
                    recommendationRuleCount(normalized, tags = false)
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.home_recommend_blocked_authors_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 12.dp }) {
        text = stringResource(R.string.recommendation_blocklist_manage)
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        setPadding(12.dp, 10.dp, 12.dp, 10.dp)
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        // 「自动确认新增屏蔽标签」的开关本体在这个弹窗里，设置树上没有它自己的行；
        // 公告导航落到这一行入口，点进去再打开面板。少了这句，那条 highlight 的
        // 跳转会走 revealHighlightDestination 的 target==null 分支，只弹一句"不可用"。
        settingsDestinations.bind("home.recommend.feedback_auto_confirm", this)
        setOnClickListener {
            showRecommendationBlocklistDialog(anchor = it) { refreshRecommendationBlocklistSummaries() }
        }
    }
    TextView(lparams = LayoutParams(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.recommendation_blocklist_manage_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    // 强力模式是总开关下面的二级勾选面板入口，总开关关着时置灰（宿主侧它只在总开关开着时生效）。
    // 面板里两项独立：屏蔽发布者；获取 access_key（推荐预检，实际生效还要通用授权）。
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS, directToggle = true)
        text = stringResource(R.string.block_ai_declared_videos)
        settingsDestinations.bind(SettingsCatalog.ID_AI_DECLARED_VIDEOS_BLOCKED, this)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = blockAiDeclaredVideos
        setOnCheckedChangeListener { _, isChecked ->
            blockAiDeclaredVideos = isChecked
            updateAiStrongModeEntry()
            runCatching {
                prefs().edit {
                    putBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS, isChecked)
                }
            }.onFailure { t ->
                Log.e("BilibiliInnocentLab", "write ai declared videos prefs failed", t)
            }
        }
    }
    TextView(lparams = LayoutParams(widthMatchParent = true)) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.block_ai_declared_videos_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    LinearLayout(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        },
        init = {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = selfRippleBackground(10f)
            updatePadding(horizontal = 4.dp, vertical = 9.dp)
            isClickable = true
            isFocusable = true
            contentDescription = stringResource(R.string.block_ai_declared_videos_strong_mode)
            settingsDestinations.bind(SettingsCatalog.ID_AI_DECLARED_VIDEOS_STRONG_MODE, this)
            settingsDestinations.bind(SettingsCatalog.ID_AI_DECLARED_VIDEOS_PRECHECK, this)
            setOnClickListener { showAiStrongModeDialog(it) }
            aiStrongModeEntry = this
            isEnabled = blockAiDeclaredVideos
            alpha = if (blockAiDeclaredVideos) 1f else 0.5f
        }
    ) {
        LinearLayout(
            lparams = LayoutParams { weight = 1f },
            init = { orientation = LinearLayout.VERTICAL }
        ) {
            TextView(lparams = LayoutParams(widthMatchParent = true)) {
                text = stringResource(R.string.block_ai_declared_videos_strong_mode)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 4.dp }) {
                aiStrongModeSummaryView = this
                alpha = 0.68f
                text = aiStrongModeSummary()
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
            // 供设置搜索命中面板里的两项。
            TextView(lparams = LayoutParams(widthMatchParent = true)) {
                visibility = View.GONE
                text = listOf(R.string.ai_declared_block_author, R.string.ai_declared_precheck)
                    .joinToString(" · ") { stringResource(it) }
            }
        }
        ImageView(lparams = LayoutParams(18.dp, 18.dp)) {
            setImageResource(R.drawable.ic_chevron_down)
            rotation = -90f
            alpha = 0.8f
            imageTintList = stateColorResource(R.color.colorTextGray)
        }
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 12.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL, directToggle = true)
        text = stringResource(R.string.home_vertical_open_detail)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = homeVerticalOpenDetail
        setOnCheckedChangeListener { _, isChecked ->
            homeVerticalOpenDetail = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.HOME_VERTICAL_OPEN_DETAIL,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write home vertical detail prefs failed",
                    t
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.home_vertical_open_detail_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
    MaterialSwitch(
        lparams = LayoutParams(widthMatchParent = true) {
            topMargin = 8.dp
            bottomMargin = 5.dp
        }
    ) {
        bindFavoriteSwitch(this, FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB, directToggle = true)
        text = stringResource(R.string.prefer_dynamic_video_tab)
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        isChecked = preferDynamicVideoTab
        setOnCheckedChangeListener { _, isChecked ->
            preferDynamicVideoTab = isChecked
            runCatching {
                prefs().edit {
                    putBoolean(
                        FeaturePreferences.PREFER_DYNAMIC_VIDEO_TAB,
                        isChecked
                    )
                }
            }.onFailure { t ->
                Log.e(
                    "BilibiliInnocentLab",
                    "write preferred dynamic video prefs failed",
                    t
                )
            }
        }
    }
    TextView(
        lparams = LayoutParams(widthMatchParent = true)
    ) {
        alpha = 0.6f
        setLineSpacing(6f, 1f)
        text = stringResource(R.string.prefer_dynamic_video_tab_tip)
        textColor = colorResource(R.color.colorTextDark)
        textSize = 12f
    }
}
