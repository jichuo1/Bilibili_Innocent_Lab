package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputFilter
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.withTranslation
import io.github.jichuo1.meguviz.android.extensions.toast
import io.github.jichuo1.meguviz.android.extensions.textToString
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import java.util.Locale

internal data class SubtitleStyleLabels(
    val entry: String, val preview: String, val size: String, val font: String, val bold: String,
    val text: String, val background: String, val outline: String, val opacity: String,
    val width: String, val save: String, val cancel: String, val reset: String,
    val defaultFont: String, val invalidColor: String, val saved: String, val reopen: String, val failed: String
) {
    companion object {
        fun current(context: Context): SubtitleStyleLabels = when (InjectedUiLocale.resolveEffectiveTag(context)) {
            InjectedUiLocale.TAG_SIMPLIFIED_CHINESE -> SubtitleStyleLabels(
                "增强字幕样式", "字幕样式预览\nSubtitle preview", "字号比例", "字体", "粗体", "文字颜色", "遮罩颜色", "描边颜色", "不透明度", "描边宽度", "保存", "取消", "恢复官方样式", "系统默认", "请输入六位颜色，例如 FFFFFF", "已保存字幕样式", "已保存，重新打开视频后生效", "保存失败，请重试")
            InjectedUiLocale.TAG_TRADITIONAL_CHINESE -> SubtitleStyleLabels(
                "增強字幕樣式", "字幕樣式預覽\nSubtitle preview", "字級比例", "字體", "粗體", "文字顏色", "遮罩顏色", "描邊顏色", "不透明度", "描邊寬度", "儲存", "取消", "恢復官方樣式", "系統預設", "請輸入六位顏色，例如 FFFFFF", "已儲存字幕樣式", "已儲存，重新開啟影片後生效", "儲存失敗，請重試")
            else -> SubtitleStyleLabels("Enhanced subtitle style", "Subtitle preview\n字幕样式预览", "Font scale", "Font", "Bold", "Text color", "Background color", "Outline color", "Opacity", "Outline width", "Save", "Cancel", "Restore official style", "System default", "Enter six hex digits, e.g. FFFFFF", "Subtitle style saved", "Saved; reopen the video to apply", "Could not save. Please try again")
        }
    }
}

// These labels are localized through InjectedUiLocale; the host cannot resolve module resource IDs.
@android.annotation.SuppressLint("SetTextI18n")
internal object SubtitleStyleDialog {
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BIL-SubtitleSave").apply { isDaemon = true }
    }
    fun show(context: Context, store: SubtitleStyleStore, refresh: () -> Boolean): AlertDialog {
        val labels = SubtitleStyleLabels.current(context)
        var draft = store.read() ?: PlayerSubtitleStyle()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val preview = SubtitleStylePreview(context).apply { sample = labels.preview; style = draft }
        content.addView(preview, LinearLayout.LayoutParams(-1, -2))
        fun changed(style: PlayerSubtitleStyle) { draft = style; preview.style = style }
        fun label(text: String) = TextView(context).apply {
            this.text = text
            textSize = 14f
            setPadding(0, dp(12), 0, dp(4))
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        fun slider(title: String, initial: Int, min: Int, max: Int, unit: (Int) -> String, update: (Int) -> Unit) {
            val titleView = label("$title: ${unit(initial)}")
            content.addView(SeekBar(context).apply {
                this.max = max - min
                progress = initial - min
                contentDescription = title
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(view: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val value = progress + min
                        titleView.text = "$title: ${unit(value)}"
                        update(value)
                    }
                    override fun onStartTrackingTouch(view: SeekBar?) = Unit
                    override fun onStopTrackingTouch(view: SeekBar?) = Unit
                })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        fun color(title: String, initial: Int, update: (Int) -> Unit) {
            val button = Button(context).apply {
                text = "$title: #${hex(initial)}"
                isAllCaps = false
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
            var selected = initial
            button.setOnClickListener {
                val editor = EditText(context).apply {
                    setSingleLine(true)
                    filters = arrayOf(InputFilter.LengthFilter(7))
                    setText(hex(selected))
                    selectAll()
                    contentDescription = title
                }
                val dialog = AlertDialog.Builder(context).setTitle(title).setView(editor)
                    .setPositiveButton(labels.save, null).setNegativeButton(labels.cancel, null).create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = editor.textToString().trim().removePrefix("#")
                        if (!Regex("[0-9A-Fa-f]{6}").matches(value)) { editor.error = labels.invalidColor; return@setOnClickListener }
                        selected = value.toInt(16)
                        button.text = "$title: #${hex(selected)}"
                        update(selected)
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
        }
        slider(labels.size, draft.sizePercent, 50, 250, { "$it%" }) { changed(draft.copy(sizePercent = it)) }
        content.addView(Button(context).apply {
            isAllCaps = false
            fun fontLabel() { text = "${labels.font}: ${if (draft.font == "sans-serif") labels.defaultFont else draft.font}" }
            fontLabel()
            setOnClickListener {
                val names = listOf("sans-serif") + SubtitleSystemFonts.files.map { it.name }
                val titles = names.map { if (it == "sans-serif") labels.defaultFont else it }
                AlertDialog.Builder(context).setTitle(labels.font)
                    .setSingleChoiceItems(titles.toTypedArray(), names.indexOf(draft.font)) { dialog, index ->
                        changed(draft.copy(font = names[index])); fontLabel(); dialog.dismiss()
                    }.setNegativeButton(labels.cancel, null).show()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(CheckBox(context).apply {
            text = labels.bold; isChecked = draft.bold
            setOnCheckedChangeListener { _, checked -> changed(draft.copy(bold = checked)) }
        }, LinearLayout.LayoutParams(-1, -2))
        color(labels.text, draft.textColor) { changed(draft.copy(textColor = it)) }
        slider("${labels.text} · ${labels.opacity}", draft.textOpacity, 0, 100, { "$it%" }) { changed(draft.copy(textOpacity = it)) }
        color(labels.background, draft.backgroundColor) { changed(draft.copy(backgroundColor = it)) }
        slider("${labels.background} · ${labels.opacity}", draft.backgroundOpacity, 0, 100, { "$it%" }) { changed(draft.copy(backgroundOpacity = it)) }
        color(labels.outline, draft.outlineColor) { changed(draft.copy(outlineColor = it)) }
        slider("${labels.outline} · ${labels.opacity}", draft.outlineOpacity, 0, 100, { "$it%" }) { changed(draft.copy(outlineOpacity = it)) }
        slider(labels.width, draft.outlineWidth, 0, 60, { String.format(Locale.ROOT, "%.1f", it / 10f) }) { changed(draft.copy(outlineWidth = it)) }
        val scroll = ScrollView(context).apply { addView(content) }
        val dialog = AlertDialog.Builder(context).setTitle(labels.entry).setView(scroll)
            .setPositiveButton(labels.save, null).setNegativeButton(labels.cancel, null)
            .setNeutralButton(labels.reset, null).create()
        var saving = false
        fun save(style: PlayerSubtitleStyle?) {
            if (saving) return
            saving = true
            dialog.setCancelable(false)
            for (button in listOf(-1, -2, -3)) dialog.getButton(button).isEnabled = false
            io.execute {
                val outcome = runCatching { store.save(style) }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!dialog.isShowing) return@post
                    outcome.onSuccess { mirrored ->
                        val refreshed = mirrored > 0 && runCatching(refresh).getOrDefault(false)
                        context.toast(if (refreshed) labels.saved else labels.reopen)
                        dialog.dismiss()
                    }.onFailure {
                        saving = false
                        dialog.setCancelable(true)
                        for (button in listOf(-1, -2, -3)) dialog.getButton(button).isEnabled = true
                        context.toast(labels.failed)
                    }
                }
            }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save(draft) }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { save(null) }
        scroll.layoutParams = scroll.layoutParams.apply {
            height = minOf(context.resources.displayMetrics.heightPixels / 2, content.dp(420))
        }
        return dialog
    }

    private fun hex(color: Int): String = String.format(Locale.ROOT, "%06X", color)
}

internal class SubtitleStylePreview(context: Context) : View(context) {
    var style = PlayerSubtitleStyle()
        set(value) { field = value; requestLayout(); invalidate() }
    var sample = "Subtitle preview"
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var textLayout: StaticLayout? = null
    private var lastFont: String? = null
    private var font: Typeface = Typeface.DEFAULT

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(dp(40))
        if (lastFont != style.font) {
            font = runCatching { SubtitleSystemFonts.resolve(style.font)?.let { Typeface.createFromFile(it) } }.getOrNull() ?: Typeface.DEFAULT
            lastFont = style.font
        }
        paint.typeface = Typeface.create(font, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
        paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 18f * style.sizePercent / 100f, resources.displayMetrics)
        val available = (width - dp(24)).coerceAtLeast(1)
        val textWidth = sample.lines().maxOf { paint.measureText(it) }.toInt().coerceIn(1, available)
        textLayout = StaticLayout.Builder.obtain(sample, 0, sample.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build()
        setMeasuredDimension(width, resolveSize((textLayout?.height ?: 0) + dp(24), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val layout = textLayout ?: return
        canvas.drawColor(Color.DKGRAY)
        val left = (width - layout.width) / 2f
        val top = (height - layout.height) / 2f
        paint.style = Paint.Style.FILL
        paint.color = PlayerSubtitleStyle.argb(style.backgroundColor, style.backgroundOpacity)
        canvas.drawRoundRect(left - dp(4), top, left + layout.width + dp(4), top + layout.height, dp(3).toFloat(), dp(3).toFloat(), paint)
        canvas.withTranslation(left, top) {
            if (style.outlineWidth > 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = style.outlineWidth / 10f * resources.displayMetrics.density
                paint.color = PlayerSubtitleStyle.argb(style.outlineColor, style.outlineOpacity)
                layout.draw(canvas)
            }
            paint.style = Paint.Style.FILL
            paint.color = PlayerSubtitleStyle.argb(style.textColor, style.textOpacity)
            layout.draw(canvas)
        }
    }
}

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
