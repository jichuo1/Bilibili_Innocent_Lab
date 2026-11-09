package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.textColor

internal fun hostCardMaterialLabel(material: HostCardMaterial) = when (material) {
    HostCardMaterial.ORIGINAL -> R.string.host_card_material_original
    HostCardMaterial.ACRYLIC -> R.string.host_card_material_acrylic
    HostCardMaterial.MICA -> R.string.host_card_material_mica
}

internal fun MainActivity.showHostCardMaterialDialog(anchor: View, onSaved: () -> Unit) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(TextView(this).apply {
        text = getString(R.string.host_card_material)
        textSize = 17f; textColor = getColor(R.color.colorTextDark)
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() })
    HostCardMaterial.entries.forEach { option ->
        val tip = when (option) {
            HostCardMaterial.ORIGINAL -> R.string.host_card_material_original_tip
            HostCardMaterial.ACRYLIC -> R.string.host_card_material_acrylic_tip
            HostCardMaterial.MICA -> R.string.host_card_material_mica_tip
        }
        container.addView(createGitHubMenuRow(getString(hostCardMaterialLabel(option)), getString(tip),
            hostCardMaterial == option) {
            runCatching { prefs().edit { putString(FeaturePreferences.HOST_CARD_MATERIAL, option.value) } }
                .onSuccess {
                    hostCardMaterial = option
                    onSaved()
                    dismissWithAnimation(dialog, container) {}
                }.onFailure { Log.e("BilibiliInnocentLab", "write host card material failed", it) }
        }, LinearLayout.LayoutParams(-1, -2))
    }
    container.addView(TextView(this).apply {
        text = getString(R.string.host_card_material_tip)
        textSize = 12f; textColor = getColor(R.color.colorTextGray)
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    container.addView(createPanelCloseRow {
        dismissWithAnimation(dialog, container) {}
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (22 * density).toInt() })
    presentModalDialog(dialog, container, anchor)
}
