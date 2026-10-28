package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz.LinearSettingsScope

import android.widget.LinearLayout as NativeLinearLayout
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferMode
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.updatePadding

internal fun MainActivity.playerBufferModeLabel(value: Int): String = getString(when (PlayerBufferMode.fromValue(value)) {
    PlayerBufferMode.FOLLOW_HOST -> R.string.player_buffer_follow_host
    PlayerBufferMode.LOW -> R.string.player_buffer_low
    PlayerBufferMode.CUSTOM -> R.string.player_buffer_custom
    PlayerBufferMode.WHOLE -> R.string.player_buffer_whole
})

internal fun MainActivity.updatePlayerBufferSummary() {
    playerBufferSummaryView?.text = if (PlayerBufferMode.fromValue(playerBufferMode) == PlayerBufferMode.CUSTOM) {
        getString(R.string.player_buffer_custom_current, playerBufferSeconds, playerBufferMemoryMb)
    } else getString(R.string.player_buffer_current, playerBufferModeLabel(playerBufferMode))
}

/** 共用一行导航入口，草稿和生命周期仍由当前 MainActivity 的模态底座持有。 */
internal fun MainActivity.playerBufferSettingsRows(performer: LinearSettingsScope) {
    with(performer) {
        linearLayout(key = "PlayerBufferPresenter:31",
            lparams = params(widthMatchParent = true) { topMargin = 12.dp },
            init = {
                orientation = NativeLinearLayout.VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 0.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                setOnClickListener { showPlayerBufferDialog(anchor = it) }
                listOf(
                    FeaturePreferences.PLAYER_BUFFER_MODE, FeaturePreferences.PLAYER_BUFFER_SECONDS,
                    FeaturePreferences.PLAYER_BUFFER_MEMORY_MB, FeaturePreferences.PLAYER_BUFFER_DISK_MB,
                    FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY
                ).forEach { key -> bindSettingDestination(this, key) }
            }
        ) {
            textView(key = "PlayerBufferPresenter:47", lparams = params(widthMatchParent = true)) {
                text = stringResource(R.string.player_buffer_strategy)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            textView(key = "PlayerBufferPresenter:52", lparams = params(widthMatchParent = true) { topMargin = 5.dp }) {
                alpha = 0.6f
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                playerBufferSummaryView = this
                updatePlayerBufferSummary()
            }
        }
        textView(key = "PlayerBufferPresenter:60", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.player_buffer_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}
