package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.highcapable.betterandroid.ui.extension.view.toast

internal fun MainActivity.showRecommendationBlocklistDialog(anchor: View? = null, onSaved: () -> Unit) {
    val preferences = prefs()
    val snapshots = listOf(
        MineComponentSnapshotCodec.SURFACE_SECTION_PICKS,
        MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS
    ).mapNotNull { MineComponentSnapshotStore.read(this, it) }
    val draft = RecommendationBlocklistDraft(
        preferences.getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, "").orEmpty(),
        preferences.getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, "").orEmpty(),
        snapshots,
        preferences.getString(RecommendationBlocklistDraft.REVIEWED_EVENTS_KEY, "").orEmpty()
    )
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val dialog = Dialog(this)
    val container = createModalContainer()
    container.addView(TextView(this).apply {
        text = getString(R.string.recommendation_blocklist_manage)
        setTextColor(getColor(R.color.colorTextDark))
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(TextView(this).apply {
        text = getString(R.string.recommendation_blocklist_description)
        setTextColor(getColor(R.color.colorTextGray))
        textSize = 12f
        setLineSpacing(dp(4).toFloat(), 1f)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
    })
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (draft.rows.isEmpty()) {
        rows.addView(TextView(this).apply {
            text = getString(R.string.recommendation_blocklist_empty)
            setTextColor(getColor(R.color.colorTextGray))
            textSize = 14f
            setPadding(0, dp(16), 0, dp(16))
        })
    }
    RecommendationBlockKind.entries.forEach { kind ->
        val group = draft.rows.filter { it.rule.kind == kind }
        if (group.isEmpty()) return@forEach
        rows.addView(TextView(this).apply {
            text = getString(if (kind == RecommendationBlockKind.TAG) R.string.recommendation_blocklist_tags
                else R.string.recommendation_blocklist_authors)
            setTextColor(monetColors.primary)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(6))
        })
        group.forEach { row ->
            rows.addView(CheckBox(this).apply {
                text = if (row.pending) getString(R.string.recommendation_blocklist_pending, row.label) else row.label
                setTextColor(getColor(R.color.colorTextDark))
                textSize = 14f
                minimumHeight = dp(48)
                isChecked = draft.isSelected(row.rule)
                setOnCheckedChangeListener { _, checked -> draft.setSelected(row.rule, checked) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    val listHeight = if (draft.rows.isEmpty()) dp(80) else minOf(
        dp(320), (resources.displayMetrics.heightPixels * 0.38f).toInt(),
        dp(draft.rows.size.coerceAtMost(6) * 56 + 80)
    )
    container.addView(ScrollView(this).apply { addView(rows) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight))
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    })
    buttons.addView(createTermsActionButton(getString(R.string.dialog_confirm), filled = true) {
        if (!draft.save(preferences)) {
            toast(getString(R.string.recommendation_blocklist_save_failed))
            return@createTermsActionButton
        }
        onSaved()
        toast(getString(R.string.recommendation_blocklist_saved))
        dismissWithAnimation(dialog, container) {}
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(8)
    })
    container.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12)
    })
    presentModalDialog(dialog, container, anchor)
}
