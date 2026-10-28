@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz.LinearSettingsScope

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundImages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import io.github.jichuo1.meguviz.android.extensions.textColor

internal fun MainActivity.readHostBackground(): HostBackgroundConfig {
    val settings = com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings.read(prefs())
    return HostBackgroundConfig(HostBackgroundPreset.read(settings.string(FeaturePreferences.HOST_BACKGROUND_PRESET)),
        settings.int(FeaturePreferences.HOST_BACKGROUND_BLUR), settings.int(FeaturePreferences.HOST_BACKGROUND_SATURATION),
        settings.int(FeaturePreferences.HOST_BACKGROUND_VEIL), settings.string(FeaturePreferences.HOST_BACKGROUND_ASSET)).normalized()
}

internal fun MainActivity.backgroundLabel(preset: HostBackgroundPreset): String = getString(when (preset) {
    HostBackgroundPreset.OFF -> R.string.host_background_off
    HostBackgroundPreset.AURORA -> R.string.host_background_aurora
    HostBackgroundPreset.SAKURA -> R.string.host_background_sakura
    HostBackgroundPreset.OCEAN -> R.string.host_background_ocean
    HostBackgroundPreset.SUNSET -> R.string.host_background_sunset
    HostBackgroundPreset.MIST -> R.string.host_background_mist
    HostBackgroundPreset.STARRY -> R.string.host_background_starry
    HostBackgroundPreset.NEBULA -> R.string.host_background_nebula
    HostBackgroundPreset.METEOR -> R.string.host_background_meteor
    HostBackgroundPreset.CUSTOM -> R.string.host_background_custom
})

internal fun MainActivity.hostBackgroundAppearanceRows(performer: LinearSettingsScope) {
    with(performer) {
        textView(key = "HostBackgroundPresenter:57", lparams = params(widthMatchParent = true) { topMargin = 12.dp }) {
            bindSettingDestination(this, FeaturePreferences.HOST_BACKGROUND_PRESET)
            fun refresh() { text = getString(R.string.host_background_current, backgroundLabel(readHostBackground().preset)) }
            refresh()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            setPadding(0, 12.dp, 0, 12.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                hostBackgroundEditor?.close(animate = false)
                HostBackgroundEditor(this@hostBackgroundAppearanceRows, this) { refresh() }.also {
                    hostBackgroundEditor = it
                    it.show()
                }
            }
        }
        textView(key = "HostBackgroundPresenter:75", lparams = params(widthMatchParent = true)) {
            text = stringResource(R.string.host_background_tip)
            textColor = colorResource(R.color.colorTextDark)
            alpha = .6f
            textSize = 12f
        }
    }
}

internal class HostBackgroundEditor(private val activity: MainActivity, private val anchor: View, private val onSaved: () -> Unit) {
    private val original = activity.readHostBackground()
    private var draft = original
    private val dialog = Dialog(activity)
    private val container = activity.createModalContainer()
    private val preview = FrameLayout(activity)
    private val selected = TextView(activity)
    private val options = mutableMapOf<HostBackgroundPreset, TextView>()
    private val presets = HostBackgroundPreset.entries.filter { it != HostBackgroundPreset.CUSTOM }
    private val selection = activity.createSkinSlidingSelection(12f)
    private var useSaved: View? = null
    private val customControls = mutableListOf<View>()
    private val previewCards = mutableListOf<Pair<GradientDrawable, TextView>>()
    private var rendered: Bitmap? = null
    private var generation = 0
    private var active = true
    private var closing = false
    private var importing = false
    private var rendering = false
    private var saved = false
    private var importedAsset: String? = null
    private val night get() = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 14f) = TextView(activity).apply {
        this.text = text; textSize = size; textColor = activity.getColor(R.color.colorTextDark)
    }

    fun show() {
        activity.installDialogElasticInteraction(dialog)
        container.addView(label(activity.getString(R.string.host_background_title), 18f))
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(content); isFillViewport = true }
        val available = (activity.resources.configuration.screenHeightDp - 200).coerceIn(120, 430)
        container.addView(scroll, LinearLayout.LayoutParams(-1, dp(available)).apply { topMargin = dp(12) })
        preview.clipToOutline = true
        preview.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
            }
        }
        val cards = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(20), dp(16), dp(20)) }
        repeat(2) { index ->
            cards.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val surface = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
                background = surface
                addView(View(activity).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        if (index == 0) intArrayOf(0xff729caa.toInt(), 0xffbec8dd.toInt()) else intArrayOf(0xffc9a6a8.toInt(), 0xffe3ccc1.toInt()))
                }, LinearLayout.LayoutParams(-1, dp(64)))
                val title = label(activity.getString(R.string.host_background_preview_card), 12f).apply { setPadding(dp(10), dp(12), dp(10), dp(12)) }
                addView(title)
                previewCards += surface to title
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0) marginEnd = dp(10) })
        }
        preview.addView(cards, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        content.addView(preview, LinearLayout.LayoutParams(-1, dp(180)))
        selected.textColor = activity.getColor(R.color.colorTextGray)
        selected.textSize = 12f
        content.addView(selected, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
        presets.forEach { preset ->
            val button = label(activity.backgroundLabel(preset)).apply {
                setPadding(dp(12), dp(13), dp(12), dp(13))
                background = activity.selfRippleBackground(12f)
                isFocusable = true
            }
            options[preset] = button
            selection.addOption(button)
        }
        selection.onSelect = { index ->
            if (active && !closing) { draft = draft.copy(preset = presets[index]); refresh() }
        }
        bindSelectionHighlight()
        content.addView(selection)
        content.addView(activity.createGitHubMenuRow(activity.getString(R.string.host_background_custom),
            activity.getString(R.string.host_background_choose), false) {
            if (!importing) activity.hostBackgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        })
        useSaved = activity.createGitHubMenuRow(
            activity.getString(R.string.host_background_use_saved), "", false) {
            draft = draft.copy(preset = HostBackgroundPreset.CUSTOM); refresh(); renderImage()
        }.also { content.addView(it) }
        slider(content, R.string.host_background_blur, draft.blur, 60, true) { draft = draft.copy(blur = it) }
        slider(content, R.string.host_background_saturation, draft.saturation, 200, true) { draft = draft.copy(saturation = it) }
        slider(content, R.string.host_background_veil, draft.veil, 90, false) { draft = draft.copy(veil = it) }
        content.addView(label(activity.getString(R.string.host_background_tip), 12f).apply { alpha = .65f; setPadding(0, dp(12), 0, dp(8)) })
        val buttons = LinearLayout(activity).apply { gravity = Gravity.END }
        listOf(R.string.dialog_cancel, R.string.dialog_confirm).forEach { res ->
            buttons.addView(label(activity.getString(res), 15f).apply {
                setPadding(dp(20), dp(16), dp(20), dp(16)); background = activity.selfRippleBackground(12f)
                isClickable = true; isFocusable = true
                setOnClickListener { if (res == R.string.dialog_confirm) save() else close() }
            })
        }
        container.addView(buttons)
        refresh()
        if (draft.preset == HostBackgroundPreset.CUSTOM) renderImage()
        activity.presentModalDialog(dialog, container, anchor, onDismissed = ::dispose)
    }

    private fun slider(parent: LinearLayout, title: Int, initial: Int, maxValue: Int, customOnly: Boolean, update: (Int) -> Unit) {
        val value = label("", 13f)
        val slider = SeekBar(activity).apply { max = maxValue; progress = initial; contentDescription = activity.getString(title) }
        fun showValue(progress: Int) { value.text = "${activity.getString(title)} · $progress${if (title == R.string.host_background_blur) "" else "%"}" }
        showValue(initial)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                showValue(progress); update(progress)
                if (!customOnly) refresh()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) { if (customOnly) renderImage() }
        })
        parent.addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        parent.addView(slider)
        if (customOnly) { customControls += value; customControls += slider }
    }

    private fun refresh() {
        useSaved?.visibility = if (draft.asset.isEmpty()) View.GONE else View.VISIBLE
        selected.text = activity.backgroundLabel(draft.preset)
        val previewNight = night || draft.preset.isCelestial
        previewCards.forEach { (surface, title) ->
            surface.setColor(if (previewNight) 0xff222631.toInt() else Color.WHITE)
            title.textColor = if (previewNight) 0xffe6e8ed.toInt() else activity.getColor(R.color.colorTextDark)
        }
        if (draft.preset.isCelestial) selected.append("\n" + activity.getString(R.string.host_background_night_theme_tip))
        preview.background = if (draft.enabled) HostBackgroundDrawable(draft, night, rendered) else GradientDrawable().apply { setColor(if (night) 0xff14151a.toInt() else 0xfff4f4f4.toInt()) }
        val index = presets.indexOf(draft.preset)
        val visibility = if (index >= 0) View.VISIBLE else View.INVISIBLE
        val modeChanged = selection.indicator.visibility != visibility
        selection.indicator.visibility = visibility
        if (index >= 0 && (selection.selectedIndex != index || !options.getValue(draft.preset).isSelected)) {
            selection.select(index)
        }
        if (modeChanged) bindSelectionHighlight()
        if (index < 0) options.values.forEach { view ->
            view.isSelected = false
            view.textColor = activity.getColor(R.color.colorTextDark)
        }
        customControls.forEach { it.isEnabled = draft.preset == HostBackgroundPreset.CUSTOM; it.alpha = if (it.isEnabled) 1f else .4f }
    }

    private fun bindSelectionHighlight() {
        selection.setOnHighlightListener { index, weight ->
            options.getValue(presets[index]).textColor = ColorUtils.blendARGB(
                activity.getColor(R.color.colorTextDark), activity.monetColors.primary,
                if (draft.preset == HostBackgroundPreset.CUSTOM) 0f else weight
            )
        }
    }

    fun importImage(uri: Uri) {
        if (!active || closing || importing) return
        importing = true
        selected.text = activity.getString(R.string.liquid_background_processing)
        val app = activity.applicationContext
        activity.liquidBackgroundWorker.execute {
            val result = runCatching { HostBackgroundImages.import(app, uri) }
            activity.runOnUiThread {
                importing = false
                result.onSuccess { asset ->
                    if (!active || closing) { HostBackgroundImages.file(app, asset).delete(); return@onSuccess }
                    importedAsset?.let { HostBackgroundImages.file(app, it).delete() }
                    importedAsset = asset
                    draft = draft.copy(preset = HostBackgroundPreset.CUSTOM, asset = asset)
                    refresh(); renderImage()
                }.onFailure {
                    if (active && !closing) { Toast.makeText(activity, R.string.host_background_import_failed, Toast.LENGTH_LONG).show(); refresh() }
                }
            }
        }
    }

    private fun renderImage() {
        if (!active || closing || draft.preset != HostBackgroundPreset.CUSTOM) return
        val ticket = ++generation
        rendering = true
        val config = draft.normalized()
        val app = activity.applicationContext
        selected.text = activity.getString(R.string.liquid_background_processing)
        activity.liquidBackgroundWorker.execute {
            val image = HostBackgroundImages.load(app, config)
            activity.runOnUiThread {
                if (!active || closing || ticket != generation) { image?.recycle(); return@runOnUiThread }
                rendering = false
                val previous = rendered
                rendered = image
                refresh()
                previous?.recycle()
                if (image == null) selected.text = activity.getString(R.string.host_background_missing)
            }
        }
    }

    private fun save() {
        if (importing || rendering || !active || closing) return
        val config = draft.normalized()
        if (config.preset == HostBackgroundPreset.CUSTOM && rendered == null) {
            Toast.makeText(activity, R.string.host_background_missing, Toast.LENGTH_LONG).show(); return
        }
        runCatching {
            HostBackgroundImages.grant(activity, config.asset)
            check(HostBackgroundSettingsStore.save(activity.prefs(), config))
        }.onSuccess {
            saved = true
            // 已提交的文件不再属于导入草稿；收尾失败也不能让后续取消删除它。
            importedAsset = null
            try {
                runCatching {
                    HostBackgroundImages.cleanup(activity, config.asset)
                    onSaved()
                    Toast.makeText(activity, R.string.host_background_saved, Toast.LENGTH_LONG).show()
                }.onFailure { android.util.Log.w("BilibiliInnocentLab", "host background finalization failed", it) }
            } finally { close() }
        }.onFailure { Toast.makeText(activity, R.string.host_background_save_failed, Toast.LENGTH_LONG).show() }
    }

    fun close(animate: Boolean = true) {
        if (!active || closing) return
        closing = true
        generation++
        // Keep the preview bitmap alive until the shared modal presenter finishes drawing it.
        if (animate && dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
            activity.dismissWithAnimation(dialog, container) {}
        } else {
            dialog.dismiss()
            dispose()
        }
    }
    private fun dispose() {
        if (!active) return
        active = false; generation++
        preview.background = null
        rendered?.recycle(); rendered = null
        if (!saved) importedAsset?.let { HostBackgroundImages.file(activity, it).delete() }
        if (activity.hostBackgroundEditor === this) activity.hostBackgroundEditor = null
    }
}
