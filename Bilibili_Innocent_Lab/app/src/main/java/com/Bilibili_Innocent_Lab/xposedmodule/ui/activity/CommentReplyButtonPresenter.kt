package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz.LinearSettingsScope

import android.util.Log
import android.widget.LinearLayout as NativeLinearLayout
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.textColor

internal fun MainActivity.commentReplyButtonSettingsRows(
    performer: LinearSettingsScope,
    checked: Boolean,
    onChanged: (Boolean) -> Unit
) {
    with(performer) {
        materialSwitch(key = "CommentReplyButtonPresenter:22",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, directToggle = true)
            bindSettingDestination(this, FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON)
            text = stringResource(R.string.remove_comment_reply_button)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = checked
            setOnCheckedChangeListener { button, value ->
                applyModeledToggle(FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, value, button)
                onChanged(button.isChecked)
            }
        }
        textView(key = "CommentReplyButtonPresenter:44", lparams = params(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_comment_reply_button_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}
