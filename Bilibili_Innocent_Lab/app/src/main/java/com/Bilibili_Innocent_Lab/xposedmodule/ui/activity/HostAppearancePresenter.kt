@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz.LinearSettingsScope

import android.util.Log
import android.widget.LinearLayout as NativeLinearLayout
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import io.github.jichuo1.meguviz.android.extensions.textColor

// 宿主美化设置的界面分卷；状态和收藏身份仍由当前 MainActivity 管理。

internal fun MainActivity.loadHostCardSettings(settings: com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings) {
    hostVideoCards = settings.bool(FeaturePreferences.HOST_VIDEO_CARDS)
    hostCardMaterial = HostCardMaterial.parse(settings.string(FeaturePreferences.HOST_CARD_MATERIAL))
    hostHomeCardHorizontalDp = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec.normalize(
        settings.int(FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP))
    hostHomeCardVerticalDp = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec.normalize(
        settings.int(FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP))
    hostVideoCardRadiusDp = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyleSpec.normalizeRadius(
        settings.int(FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP))
}

internal fun MainActivity.hostBottomBarAppearanceRows(
    performer: LinearSettingsScope
) {
    with(performer) {
        var refreshGlassSettings: () -> Unit = {}
        val capsuleOptions = DependentAppearanceOptions { hostBottomBarLiquidGlass }
        materialSwitch(key = "HostAppearancePresenter:38",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS, directToggle = true)
            text = stringResource(R.string.host_bottom_bar_liquid_glass)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostBottomBarLiquidGlass
            setOnCheckedChangeListener { button, checked -> applyModeledToggle(FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS, checked, button) }
            onModelStateRendered = { refreshGlassSettings(); capsuleOptions.refresh() }
        }
        textView(key = "HostAppearancePresenter:72",
            lparams = params(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_bottom_bar_liquid_glass_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        refreshGlassSettings = hostGlassConfigurationRows(this, bottom = true)
        // 步骤：胶囊底栏选项与触摸光效；紧随玻璃设置行，保持原构建顺序
        deferStep {
            materialSwitch(key = "HostAppearancePresenter:82",
                lparams = params(widthMatchParent = true) {
                    marginStart = 16.dp
                    topMargin = 12.dp
                    bottomMargin = 5.dp
                }
            ) {
                capsuleOptions.add(this)
                bindFavoriteSwitch(this, FeaturePreferences.HOST_BOTTOM_BAR_COMPACT, directToggle = true)
                text = stringResource(R.string.host_bottom_bar_compact)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = hostBottomBarCompact
                setOnCheckedChangeListener { button, checked ->
                applyModeledToggle(FeaturePreferences.HOST_BOTTOM_BAR_COMPACT, checked, button)
            }
            }
            textView(key = "HostAppearancePresenter:118", lparams = params(widthMatchParent = true) { marginStart = 16.dp }) {
                alpha = 0.6f
                text = stringResource(R.string.host_bottom_bar_compact_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                capsuleOptions.add(this)
            }
            materialSwitch(key = "HostAppearancePresenter:125",
                lparams = params(widthMatchParent = true) {
                    marginStart = 16.dp
                    topMargin = 12.dp
                    bottomMargin = 5.dp
                }
            ) {
                capsuleOptions.add(this)
                bindFavoriteSwitch(this, FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY, directToggle = true)
                text = stringResource(R.string.host_bottom_bar_icon_only)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = hostBottomBarIconOnly
                setOnCheckedChangeListener { button, checked ->
                applyModeledToggle(FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY, checked, button)
            }
            }
            textView(key = "HostAppearancePresenter:161", lparams = params(widthMatchParent = true) { marginStart = 16.dp }) {
                alpha = 0.6f
                text = stringResource(R.string.host_bottom_bar_icon_only_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                capsuleOptions.add(this)
            }
            materialSwitch(key = "HostAppearancePresenter:168",
                lparams = params(widthMatchParent = true) {
                    marginStart = 16.dp
                    topMargin = 12.dp
                    bottomMargin = 5.dp
                }
            ) {
                capsuleOptions.add(this)
                bindFavoriteSwitch(this, FeaturePreferences.HOST_MINE_GARB_DIM, directToggle = true)
                bindSettingDestination(this, FeaturePreferences.HOST_MINE_GARB_DIM)
                text = stringResource(R.string.host_mine_garb_dim)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = hostMineGarbDim
                setOnCheckedChangeListener { button, checked ->
                applyModeledToggle(FeaturePreferences.HOST_MINE_GARB_DIM, checked, button)
            }
            }
            textView(key = "HostAppearancePresenter:196", lparams = params(widthMatchParent = true) { marginStart = 16.dp }) {
                alpha = 0.6f
                text = stringResource(R.string.host_mine_garb_dim_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                capsuleOptions.add(this)
            }
            capsuleOptions.refresh()
            materialSwitch(key = "HostAppearancePresenter:204",
                lparams = params(widthMatchParent = true) {
                    topMargin = 12.dp
                    bottomMargin = 5.dp
                }
            ) {
                bindFavoriteSwitch(this, FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW, directToggle = true)
                text = stringResource(R.string.host_bottom_bar_touch_glow)
                isAllCaps = false
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
                isChecked = hostBottomBarTouchGlow
                setOnCheckedChangeListener { button, checked ->
                applyModeledToggle(FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW, checked, button)
            }
            }
            textView(key = "HostAppearancePresenter:234",
                lparams = params(widthMatchParent = true)
            ) {
                alpha = 0.6f
                setLineSpacing(6f, 1f)
                text = stringResource(R.string.host_bottom_bar_touch_glow_tip)
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
            }
        }
    }
}

internal fun MainActivity.hostTopBarAppearanceRows(
    performer: LinearSettingsScope
) {
    with(performer) {
        var refreshGlassSettings: () -> Unit = {}
        materialSwitch(key = "HostAppearancePresenter:252",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS, directToggle = true)
            text = stringResource(R.string.host_top_bar_liquid_glass)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostTopBarLiquidGlass
            setOnCheckedChangeListener { button, checked -> applyModeledToggle(FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS, checked, button) }
            onModelStateRendered = { refreshGlassSettings() }
        }
        textView(key = "HostAppearancePresenter:285",
            lparams = params(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_top_bar_liquid_glass_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        refreshGlassSettings = hostGlassConfigurationRows(this, bottom = false)
        materialSwitch(key = "HostAppearancePresenter:295",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW, directToggle = true)
            text = stringResource(R.string.host_top_bar_touch_glow)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostTopBarTouchGlow
            setOnCheckedChangeListener { button, checked ->
            applyModeledToggle(FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW, checked, button)
        }
        }
        textView(key = "HostAppearancePresenter:325",
            lparams = params(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_top_bar_touch_glow_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}

internal fun MainActivity.hostVideoCardAppearanceRows(
    performer: LinearSettingsScope
) {
    with(performer) {
        val cardOptions = DependentAppearanceOptions { hostVideoCards }
        materialSwitch(key = "HostAppearancePresenter:343",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_VIDEO_CARDS, directToggle = true)
            text = stringResource(R.string.host_video_cards)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostVideoCards
            setOnCheckedChangeListener { button, checked -> applyModeledToggle(FeaturePreferences.HOST_VIDEO_CARDS, checked, button) }
            onModelStateRendered = { cardOptions.refresh() }
        }
        textView(key = "HostAppearancePresenter:362", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_video_cards_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        textView(key = "HostAppearancePresenter:369", lparams = params(widthMatchParent = true) { topMargin = 12.dp }) {
            bindSettingDestination(this, FeaturePreferences.HOST_CARD_MATERIAL)
            fun refresh() {
                text = getString(R.string.host_card_material_current, getString(hostCardMaterialLabel(hostCardMaterial)))
            }
            refresh()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            setPadding(0, 12.dp, 0, 12.dp)
            background = selfRippleBackground(10f)
            isClickable = true; isFocusable = true
            setOnClickListener { if (hostVideoCards) showHostCardMaterialDialog(this, ::refresh) }
            cardOptions.add(this)
        }
        textView(key = "HostAppearancePresenter:383", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_card_material_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
            cardOptions.add(this)
        }
        textView(key = "HostAppearancePresenter:391", lparams = params(widthMatchParent = true) { topMargin = 12.dp }) {
            bindSettingDestination(this, FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP)
            fun refresh() {
                text = getString(R.string.host_video_card_radius_current,
                    if (hostVideoCardRadiusDp < 0) getString(R.string.host_video_card_radius_default)
                    else "${hostVideoCardRadiusDp} dp")
            }
            refresh()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            setPadding(0, 12.dp, 0, 12.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (hostVideoCards) showHostVideoCardRadiusDialog(this) { refresh() } }
            cardOptions.add(this)
        }
        textView(key = "HostAppearancePresenter:408", lparams = params(widthMatchParent = true) { topMargin = 4.dp }) {
            bindSettingDestination(this, FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP)
            bindSettingDestination(this, FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP)
            fun refresh() {
                fun value(dp: Int) = if (dp < 0) getString(R.string.host_video_card_radius_default) else "$dp dp"
                text = getString(R.string.host_home_card_spacing_current,
                    value(hostHomeCardHorizontalDp), value(hostHomeCardVerticalDp))
            }
            refresh()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            setPadding(0, 12.dp, 0, 12.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener { showHostHomeCardSpacingDialog(this) { refresh() } }
        }
    }
}
