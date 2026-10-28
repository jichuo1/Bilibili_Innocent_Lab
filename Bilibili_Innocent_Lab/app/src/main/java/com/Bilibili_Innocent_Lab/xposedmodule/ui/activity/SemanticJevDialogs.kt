package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import io.github.jichuo1.meguviz.android.extensions.childOrNull
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.JevBackend
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticBackend
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticConnectivity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticCustomRule
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticGuidance
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticRoute
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticPresets
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSensitivity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSettings
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.textToString
import io.github.jichuo1.meguviz.android.extensions.toast
import android.widget.CheckBox as NativeCheckBox
import android.widget.EditText as NativeEditText
import android.widget.FrameLayout as NativeFrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/** 某个来源的 Key（只判断有无，绝不回显到摘要）。 */
private fun MainActivity.semanticSourceKey(index: Int): String =
    prefs().getString(RemoteHookConfigContract.semanticApiKey(index), "").orEmpty()

/** 与宿主使用同一套来源校验；只有 Key、模型和地址都有效的来源才参与路由。 */
internal fun MainActivity.configuredSemanticSources(): List<Int> {
    val prefs = prefs()
    return (1..SemanticSource.MAX_SOURCES).filter { index ->
        val (providerKey, endpointKey, modelKey) = FeaturePreferences.semanticSourceKeys(index)
        SemanticSource.from(
            index, semanticSourceKey(index), prefs.getString(endpointKey, "").orEmpty(),
            prefs.getString(providerKey, SemanticBackend.JEV).orEmpty(), prefs.getString(modelKey, "").orEmpty()
        ) != null
    }
}

/** AI 语义判定当前配置的一行摘要；Key 只判断有无，绝不回显。 */
internal fun MainActivity.semanticJevSummaryText(): String {
    val prefs = prefs()
    val sources = configuredSemanticSources()
    if (sources.isEmpty()) return getString(R.string.semantic_jev_summary_unconfigured)
    val sensitivity = when (SemanticSensitivity.fromId(prefs.getString(FeaturePreferences.SEMANTIC_JEV_SENSITIVITY, null))) {
        SemanticSensitivity.LOW -> R.string.semantic_jev_sensitivity_low_short
        SemanticSensitivity.MEDIUM -> R.string.semantic_jev_sensitivity_medium_short
        SemanticSensitivity.HIGH -> R.string.semantic_jev_sensitivity_high_short
    }
    val mode = if (prefs.getBoolean(FeaturePreferences.SEMANTIC_JEV_WAIT_FIRST_SCREEN, false)) {
        R.string.semantic_jev_mode_wait_short
    } else {
        R.string.semantic_jev_mode_pass_short
    }
    val days = prefs.getInt(FeaturePreferences.SEMANTIC_JEV_CACHE_DAYS, SemanticSettings.DEFAULT_CACHE_DAYS)
    val timeout = SemanticSettings.normalizeTimeout(prefs.getInt(FeaturePreferences.SEMANTIC_JEV_TIMEOUT_MS, 0))
    return getString(R.string.semantic_jev_summary_configured, getString(sensitivity), getString(mode)) +
        (if (sources.size > 1) getString(R.string.semantic_summary_sources, sources.size) else "") +
        getString(R.string.semantic_jev_summary_cache_days, days) +
        (if (timeout > 0) getString(R.string.semantic_jev_summary_timeout, formatSeconds(timeout)) else "")
}

/** 毫秒 → 秒的显示文本：整数不带小数，其余保留一位（2500 → "2.5"）。 */
internal fun formatSeconds(ms: Int): String =
    if (ms % 1000 == 0) (ms / 1000).toString() else String.format(java.util.Locale.ROOT, "%.1f", ms / 1000.0)

/** 输入框文本 → 毫秒：空 = 0（自动）；非法或越界返回 null。 */
internal fun parseTimeoutSeconds(raw: String): Int? {
    val text = raw.trim().replace('，', '.').replace(',', '.')
    if (text.isEmpty()) return 0
    val seconds = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val ms = Math.round(seconds * 1000).toInt()
    return ms.takeIf { it in SemanticSettings.MIN_TIMEOUT_MS..SemanticSettings.MAX_TIMEOUT_MS }
}

/** 兼容区入口行：标题 + 摘要两行，与规则入口行同一结构。 */
internal fun MainActivity.semanticJevEntryText(): String =
    getString(R.string.semantic_jev_settings) + "\n" + semanticJevSummaryText()

private val semanticProviders = listOf(
    SemanticBackend.JEV to R.string.semantic_jev_provider_jev,
    SemanticBackend.OPENAI to R.string.semantic_jev_provider_openai,
    SemanticBackend.CLOUDFLARE to R.string.semantic_jev_provider_cloudflare
)

/** 面板里的表单行：小标题、灰色说明、输入框，统一外观。 */
private class SemanticForm(
    private val activity: MainActivity,
    val body: NativeLinearLayout,
    /** 本面板在点击这一刻的可见表面矩形：子面板从行里长出来、展开端盖住本面板。 */
    val surface: () -> SettingsBackupMotionRect? = { null }
) {
    private val density = activity.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()

    fun label(textRes: Int, top: Int) = NativeTextView(activity).apply {
        text = activity.getString(textRes)
        textColor = activity.getColor(R.color.colorTextGray)
        textSize = 14f
    }.also { body.addView(it, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }) }

    fun help(text: CharSequence, top: Int = 4) = NativeTextView(activity).apply {
        this.text = text
        textColor = activity.getColor(R.color.colorTextGray)
        textSize = 12f
        alpha = 0.72f
        setLineSpacing(3 * density, 1f)
    }.also { body.addView(it, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }) }

    fun field(
        initial: String,
        hintRes: Int,
        secret: Boolean = false,
        numeric: Boolean = false,
        decimal: Boolean = false,
        multiLine: Boolean = false
    ) = NativeEditText(activity).apply {
        setText(initial)
        hint = activity.getString(hintRes)
        textColor = activity.getColor(R.color.colorTextDark)
        setHintTextColor(ColorUtils.setAlphaComponent(activity.getColor(R.color.colorTextGray), 0x99))
        textSize = 14f
        inputType = when {
            decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            numeric -> InputType.TYPE_CLASS_NUMBER
            secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        if (multiLine) {
            isSingleLine = false
            minLines = 2
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
        } else {
            isSingleLine = true
        }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(activity.monetColors.surfaceVariant)
            setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(activity.getColor(R.color.colorTextGray), 0x38))
        }
    }.also { body.addView(it, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
}

/**
 * AI 语义判定的表单弹窗（主面板、来源、判定来源、自定义类型共用）：标题 + 可滚动表单 + 底部按钮，
 * 与其它弹窗同一套皮肤容器与展示器。[actions] 拿到 `dismiss(after)`，按钮用它带动画关闭。
 */
private fun MainActivity.presentSemanticDialog(
    title: CharSequence,
    anchor: View?,
    /** 右上角 ⓘ 里的详细说明；null 时不显示 ⓘ。输入框下只留一行短提示，长说明都放这里。 */
    info: (() -> CharSequence)? = null,
    /**
     * 覆盖式展开（与 GitHub 面板的三级"更新渠道"同一套）：[origin] 是点击那一刻来源行的屏幕矩形，
     * [cover] 是父面板的可见表面。两个都给时本面板从那一行长出来、展开端正好盖住父面板，父面板不关闭，
     * 关掉本面板时再露出它；不给时退回从 [anchor] 形变的普通入场。
     */
    origin: SettingsBackupMotionRect? = null,
    cover: SettingsBackupMotionRect? = null,
    form: (SemanticForm) -> Unit,
    actions: (dismiss: (after: () -> Unit) -> Unit) -> List<Pair<String, () -> Unit>>
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    val titleRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    titleRow.addView(NativeTextView(this).apply {
        text = title
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(0, -2, 1f))
    if (info != null) {
        titleRow.addView(createSemanticInfoButton { source ->
            // 与匿名遥测的 ⓘ 同一套：子面板从 ⓘ 长出来、盖住本面板，关掉后露出本面板。
            // 两个矩形都要在点击这一刻取，形变一开始本面板就会被改 alpha / outline。
            showSemanticInfoDialog(
                title = getString(R.string.semantic_info_title, title),
                body = info(),
                origin = modalAnchorBounds(source),
                cover = modalSurfaceBounds(dialog, container)
            )
        }, NativeLinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()).apply {
            marginStart = (6 * density).toInt()
        })
    }
    container.addView(titleRow, NativeLinearLayout.LayoutParams(-1, -2))
    val body = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
    val builder = SemanticForm(this, body) { modalSurfaceBounds(dialog, container) }
    form(builder)
    val scrollCap = minOf(builder.dp(460), (resources.displayMetrics.heightPixels * 0.56f).toInt())
    container.addView(MaxHeightScrollView(this, scrollCap).apply {
        addView(body, NativeFrameLayout.LayoutParams(-1, -2))
    }, NativeLinearLayout.LayoutParams(-1, -2))
    val covering = origin != null && cover != null
    // 覆盖父面板时卡片被抬到父面板的高度：弹性占位把空档收到按钮行上面，按钮贴着卡片底边。
    // **只在覆盖时加**：顶层面板的高度约束是"最多到屏幕高"（AT_MOST），weight 占位会把剩余空间全吃掉，
    // 卡片被撑满整屏、表单区被截断（2026-09-30 用户截图）。
    if (covering) container.addView(View(this), NativeLinearLayout.LayoutParams(-1, 0, 1f))
    val dismiss: (() -> Unit) -> Unit = { after -> dismissWithAnimation(dialog, container) { after() } }
    semanticButtons(container, *actions(dismiss).toTypedArray())
    if (covering) {
        presentModalDialog(dialog, container, morphAnchorBounds = origin, coverBounds = cover)
    } else {
        presentModalDialog(dialog, container, anchor)
    }
}

/** 标题行右侧的 ⓘ：与匿名遥测面板的 ⓘ 同一外观。 */
private fun MainActivity.createSemanticInfoButton(onClick: (View) -> Unit): NativeTextView {
    val density = resources.displayMetrics.density
    return NativeTextView(this).apply {
        text = "ⓘ"
        contentDescription = getString(R.string.semantic_info_button)
        textColor = monetColors.primary
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
        background = selfRippleBackground(20f)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick(it) }
    }
}

/**
 * ⓘ 展开的说明子面板：从 ⓘ 形变长出、盖住父面板（父面板不关闭），关掉后露出父面板。
 * 正文可滚动，分节标题加粗。
 */
private fun MainActivity.showSemanticInfoDialog(
    title: CharSequence,
    body: CharSequence,
    origin: SettingsBackupMotionRect?,
    cover: SettingsBackupMotionRect?
) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = title
        textColor = getColor(R.color.colorTextDark)
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }, NativeLinearLayout.LayoutParams(-1, -2))
    val scrollCap = minOf((440 * density).toInt(), (resources.displayMetrics.heightPixels * 0.56f).toInt())
    container.addView(MaxHeightScrollView(this, scrollCap).apply {
        addView(NativeTextView(this@showSemanticInfoDialog).apply {
            text = body
            textColor = getColor(R.color.colorTextDark)
            textSize = 13f
            setLineSpacing(4 * density, 1f)
            setTextIsSelectable(true)
        }, NativeFrameLayout.LayoutParams(-1, -2))
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * density).toInt() })
    // 盖住父面板时卡片被抬到父面板的高度：弹性占位把空档收到关闭行上面，关闭行贴底。
    container.addView(View(this), NativeLinearLayout.LayoutParams(-1, 0, 1f))
    container.addView(NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(createPanelCloseButton { dismissWithAnimation(dialog, container) {} })
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    presentModalDialog(dialog, container, morphAnchorBounds = origin, coverBounds = cover)
}

/** 说明正文：每节「加粗小标题 + 段落」，节间空一行。 */
private fun MainActivity.semanticInfo(vararg sections: Pair<Int, CharSequence>): CharSequence {
    val out = android.text.SpannableStringBuilder()
    sections.forEachIndexed { index, (titleRes, text) ->
        if (index > 0) out.append("\n\n")
        val start = out.length
        out.append(getString(titleRes))
        out.setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append('\n').append(text)
    }
    return out
}

/** 底部按钮行：从左到右依次排列，最后一个是主按钮。 */
private fun MainActivity.semanticButtons(
    container: NativeLinearLayout,
    vararg actions: Pair<String, () -> Unit>
) {
    val density = resources.displayMetrics.density
    val buttons = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL; gravity = Gravity.END }
    actions.forEachIndexed { index, (label, action) ->
        buttons.addView(
            createTermsActionButton(label, filled = index == actions.lastIndex) { action() },
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index > 0) marginStart = (8 * density).toInt()
            }
        )
    }
    container.addView(buttons, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = (16 * density).toInt() })
}

/** 来源行的副标题：「类型 · 模型」或「未配置」。 */
internal fun MainActivity.semanticSourceRowSummary(index: Int): String {
    if (semanticSourceKey(index).isBlank()) return getString(R.string.semantic_source_row_empty)
    val (providerKey, _, modelKey) = FeaturePreferences.semanticSourceKeys(index)
    val provider = prefs().getString(providerKey, SemanticBackend.JEV)?.takeIf { it in SemanticBackend.IDS } ?: SemanticBackend.JEV
    val model = prefs().getString(modelKey, "").orEmpty().ifBlank {
        if (provider == SemanticBackend.JEV) JevBackend.DEFAULT_MODEL else "—"
    }
    return getString(R.string.semantic_source_row_summary, getString(semanticProviders.first { it.first == provider }.second), model)
}

/**
 * 来源列表的一行：标题「来源 N」+ 单行副标题（过长省略号收尾）。以前"来源 N：类型 · 模型"挤在一行标题里，
 * 模型名一长就在任意位置折行（2026-09-30 用户截图）。返回行与刷新函数。
 */
private fun MainActivity.createSemanticSourceRow(index: Int, onClick: (View) -> Unit): Pair<NativeLinearLayout, () -> Unit> {
    lateinit var row: NativeLinearLayout
    row = createGitHubMenuRow(getString(R.string.semantic_source_row_title, index), semanticSourceRowSummary(index), highlight = false) {
        onClick(row)
    }
    row.childOrNull<NativeTextView>(1)?.apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    return row to { row.childOrNull<NativeTextView>(1)?.text = semanticSourceRowSummary(index) }
}

/** 来源列表里的一行：「来源 N：类型 · 模型」或「未配置」（判定来源单选用的一行文字）。 */
internal fun MainActivity.semanticSourceRowText(index: Int): String {
    if (semanticSourceKey(index).isBlank()) return getString(R.string.semantic_source_row_unconfigured, index)
    val (providerKey, _, modelKey) = FeaturePreferences.semanticSourceKeys(index)
    val provider = prefs().getString(providerKey, SemanticBackend.JEV)?.takeIf { it in SemanticBackend.IDS } ?: SemanticBackend.JEV
    val providerName = getString(semanticProviders.first { it.first == provider }.second)
    val model = prefs().getString(modelKey, "").orEmpty().ifBlank {
        if (provider == SemanticBackend.JEV) JevBackend.DEFAULT_MODEL else "—"
    }
    return getString(R.string.semantic_source_row_configured, index, providerName, model)
}

/**
 * AI 语义判定面板（实验性功能 → 兼容）：来源列表（点开逐个编辑）、灵敏度、首屏等待、等待上限、保存天数、判定说明。
 *
 * - 打开时实时读偏好，保存时一次写入；来源在各自的编辑框里单独保存。
 * - 判定说明只改"怎么判"；防注入前缀与回答格式由模块固定（见 SemanticGuidance）。
 */
internal fun MainActivity.showSemanticJevSettingsDialog(anchor: View? = null, onSaved: () -> Unit) {
    val prefs = prefs()
    lateinit var sensitivityRef: () -> SemanticSensitivity
    lateinit var waitSwitch: com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
    lateinit var timeoutField: NativeEditText
    lateinit var daysField: NativeEditText
    lateinit var guidanceField: NativeEditText
    presentSemanticDialog(getString(R.string.semantic_jev_settings), anchor, info = {
        semanticInfo(
            R.string.semantic_sources_title to getString(R.string.semantic_sources_help),
            R.string.semantic_jev_wait_first_screen to getString(R.string.semantic_jev_wait_first_screen_tip),
            R.string.semantic_jev_timeout to getString(R.string.semantic_jev_timeout_help),
            R.string.semantic_jev_cache_days to getString(R.string.semantic_jev_cache_days_help),
            R.string.semantic_jev_guidance to getString(R.string.semantic_jev_guidance_help, SemanticGuidance.DEFAULT_CRITERIA)
        )
    }, form = { form ->
        form.label(R.string.semantic_sources_title, 14)
        (1..SemanticSource.MAX_SOURCES).forEach { index ->
            lateinit var refresh: () -> Unit
            val (row, refreshRow) = createSemanticSourceRow(index) { source ->
                // 两张矩形都在点击这一刻取：形变一开始主面板就会被改 alpha / outline。
                showSemanticSourceDialog(index, origin = modalAnchorBounds(source), cover = form.surface()) {
                    refresh()
                    onSaved()
                }
            }
            refresh = refreshRow
            form.body.addView(row, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = form.dp(4) })
        }
        form.help(getString(R.string.semantic_short_sources))

        form.label(R.string.semantic_jev_sensitivity, 14)
        var sensitivity = SemanticSensitivity.fromId(prefs.getString(FeaturePreferences.SEMANTIC_JEV_SENSITIVITY, null))
        val options = listOf(
            SemanticSensitivity.LOW to R.string.semantic_jev_sensitivity_low,
            SemanticSensitivity.MEDIUM to R.string.semantic_jev_sensitivity_medium,
            SemanticSensitivity.HIGH to R.string.semantic_jev_sensitivity_high
        )
        form.body.addView(
            createSlidingChoice(
                labels = options.map { getString(it.second) },
                selectedIndex = options.indexOfFirst { it.first == sensitivity }
            ) { index -> sensitivity = options[index].first },
            NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = form.dp(4) }
        )
        sensitivityRef = { sensitivity }

        waitSwitch = com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch(this, null).apply {
            text = getString(R.string.semantic_jev_wait_first_screen)
            textColor = getColor(R.color.colorTextDark)
            textSize = 14f
            isChecked = prefs.getBoolean(FeaturePreferences.SEMANTIC_JEV_WAIT_FIRST_SCREEN, false)
        }
        form.body.addView(waitSwitch, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = form.dp(12) })
        form.help(getString(R.string.semantic_short_wait), top = 2)

        form.label(R.string.semantic_jev_timeout, 14)
        timeoutField = form.field(
            SemanticSettings.normalizeTimeout(prefs.getInt(FeaturePreferences.SEMANTIC_JEV_TIMEOUT_MS, 0))
                .takeIf { it > 0 }?.let(::formatSeconds).orEmpty(),
            R.string.semantic_jev_timeout_hint,
            decimal = true
        )
        form.help(getString(R.string.semantic_short_timeout))

        form.label(R.string.semantic_jev_cache_days, 14)
        daysField = form.field(
            prefs.getInt(FeaturePreferences.SEMANTIC_JEV_CACHE_DAYS, SemanticSettings.DEFAULT_CACHE_DAYS).toString(),
            R.string.semantic_jev_cache_days_hint,
            numeric = true
        )
        form.help(getString(R.string.semantic_short_cache))

        form.label(R.string.semantic_jev_guidance, 14)
        guidanceField = form.field(
            prefs.getString(FeaturePreferences.SEMANTIC_JEV_GUIDANCE, "").orEmpty(),
            R.string.semantic_jev_guidance_hint,
            multiLine = true
        )
        form.help(getString(R.string.semantic_short_guidance))
    }) { dismiss -> listOf(
        getString(R.string.dialog_cancel) to { dismiss {} },
        getString(R.string.semantic_jev_save) to save@{
            val days = daysField.textToString().trim().toIntOrNull()
            if (days == null || days !in SemanticSettings.MIN_CACHE_DAYS..SemanticSettings.MAX_CACHE_DAYS) {
                toast(getString(R.string.semantic_jev_cache_days_invalid))
                return@save
            }
            val timeoutMs = parseTimeoutSeconds(timeoutField.textToString())
            if (timeoutMs == null) {
                toast(getString(R.string.semantic_jev_timeout_invalid))
                return@save
            }
            runCatching {
                prefs.edit {
                    putString(FeaturePreferences.SEMANTIC_JEV_SENSITIVITY, sensitivityRef().id)
                    putBoolean(FeaturePreferences.SEMANTIC_JEV_WAIT_FIRST_SCREEN, waitSwitch.isChecked)
                    putInt(FeaturePreferences.SEMANTIC_JEV_CACHE_DAYS, days)
                    putInt(FeaturePreferences.SEMANTIC_JEV_TIMEOUT_MS, timeoutMs)
                    putString(FeaturePreferences.SEMANTIC_JEV_GUIDANCE, SemanticGuidance.normalize(guidanceField.textToString()))
                }
            }.onFailure { Log.e("BilibiliInnocentLab", "write semantic jev prefs failed", it) }
            dismiss {
                toast(getString(R.string.semantic_jev_saved))
                onSaved()
            }
        }
    ) }
}

/**
 * 编辑一个判定来源：接口类型、Key（密码框，不进备份）、地址、模型。地址或模型不合格时不保存、不关闭，
 * 避免把 Key 发到错误的地方。「清除」把这个来源的 Key 置空（其余字段保留，方便以后再填 Key）。
 */
internal fun MainActivity.showSemanticSourceDialog(
    index: Int,
    anchor: View? = null,
    origin: SettingsBackupMotionRect? = null,
    cover: SettingsBackupMotionRect? = null,
    onSaved: () -> Unit
) {
    val prefs = prefs()
    val (providerKey, endpointKey, modelKey) = FeaturePreferences.semanticSourceKeys(index)
    var provider = prefs.getString(providerKey, null)?.takeIf { it in SemanticBackend.IDS } ?: SemanticBackend.JEV
    lateinit var keyField: NativeEditText
    lateinit var endpointField: NativeEditText
    lateinit var modelField: NativeEditText
    val configuredBefore = semanticSourceKey(index).isNotBlank()
    presentSemanticDialog(getString(R.string.semantic_source_edit_title, index), anchor, origin = origin, cover = cover, info = {
        semanticInfo(
            R.string.semantic_jev_provider to getString(R.string.semantic_jev_provider_help),
            R.string.semantic_jev_api_key to getString(R.string.semantic_jev_api_key_help),
            R.string.semantic_jev_endpoint to getString(R.string.semantic_jev_endpoint_help),
            R.string.semantic_jev_model to getString(R.string.semantic_jev_model_help),
            R.string.semantic_sources_title to getString(R.string.semantic_info_sites)
        )
    }, form = { form ->
        form.label(R.string.semantic_jev_provider, 10)
        form.body.addView(
            createSlidingChoice(
                labels = semanticProviders.map { getString(it.second) },
                selectedIndex = semanticProviders.indexOfFirst { it.first == provider }
            ) { choice -> provider = semanticProviders[choice].first },
            NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = form.dp(4) }
        )
        form.help(getString(R.string.semantic_short_provider))
        form.label(R.string.semantic_jev_api_key, 14)
        keyField = form.field(semanticSourceKey(index), R.string.semantic_jev_api_key_hint_short, secret = true)
        form.help(getString(R.string.semantic_short_key))
        form.label(R.string.semantic_jev_endpoint, 12)
        endpointField = form.field(prefs.getString(endpointKey, "").orEmpty(), R.string.semantic_jev_endpoint_hint_short)
        form.help(getString(R.string.semantic_short_endpoint))
        form.label(R.string.semantic_jev_model, 12)
        modelField = form.field(prefs.getString(modelKey, "").orEmpty(), R.string.semantic_jev_model_hint)
        form.help(getString(R.string.semantic_short_model))
        // 连通性测试：用框里当前的值（不必先保存）真发一条判定请求，走与宿主相同的写法回退。
        form.body.addView(createSemanticConnectivityTester(
            provider = { provider },
            key = { keyField.textToString().trim() },
            endpoint = { endpointField.textToString().trim() },
            model = { modelField.textToString().trim() }
        ), NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = form.dp(14) })
        form.help(getString(R.string.semantic_test_note), top = 2)
    }) { dismiss -> buildList {
        if (configuredBefore) {
            add(getString(R.string.semantic_source_clear) to {
                runCatching { prefs.edit { putString(RemoteHookConfigContract.semanticApiKey(index), "") } }
                    .onFailure { Log.e("BilibiliInnocentLab", "clear semantic source failed", it) }
                dismiss {
                    toast(getString(R.string.semantic_source_cleared))
                    onSaved()
                }
            })
        }
        add(getString(R.string.dialog_cancel) to { dismiss {} })
        add(getString(R.string.semantic_jev_save) to save@{
            val endpoint = endpointField.textToString().trim()
            val model = modelField.textToString().trim().take(SemanticBackend.MAX_MODEL_LENGTH)
            val backend = SemanticBackend.of(provider, model)
            if (backend == null) {
                toast(getString(R.string.semantic_jev_model_required))
                return@save
            }
            if (backend.resolveEndpoint(endpoint) == null) {
                toast(getString(R.string.semantic_jev_endpoint_invalid))
                return@save
            }
            val key = keyField.textToString().trim().take(RemoteHookConfigContract.MAX_SEMANTIC_JEV_API_KEY_LENGTH)
            runCatching {
                prefs.edit {
                    putString(RemoteHookConfigContract.semanticApiKey(index), key)
                    putString(providerKey, provider)
                    putString(endpointKey, endpoint)
                    putString(modelKey, model)
                }
            }.onFailure { Log.e("BilibiliInnocentLab", "write semantic source failed", it) }
            dismiss {
                toast(getString(R.string.semantic_jev_saved))
                onSaved()
            }
        })
    } }
}

/**
 * 「测试连通性」一行：左边是按钮，右边是结果（绿色 ✓ / 红色 !）。在后台线程里用一个临时判定器发一条请求，
 * 结果回到主线程；面板关掉后迟到的结果直接丢弃。同一时间只跑一个测试。
 */
private fun MainActivity.createSemanticConnectivityTester(
    provider: () -> String,
    key: () -> String,
    endpoint: () -> String,
    model: () -> String
): View {
    val density = resources.displayMetrics.density
    val dark = ColorUtils.calculateLuminance(monetColors.surface) < 0.5
    val row = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val status = NativeTextView(this).apply {
        textSize = 13f
        setLineSpacing(2 * density, 1f)
    }
    var running = false
    val button = NativeTextView(this).apply {
        text = getString(R.string.semantic_test_connect)
        textColor = monetColors.primary
        textSize = 14f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding((12 * density).toInt(), (9 * density).toInt(), (12 * density).toInt(), (9 * density).toInt())
        background = selfRippleBackground(12f)
        isClickable = true
        isFocusable = true
    }
    fun show(ok: Boolean?, message: String) {
        status.text = when (ok) {
            true -> "✓ $message"
            false -> "! $message"
            null -> message
        }
        status.textColor = when (ok) {
            true -> DiagnosticStatusPalette.color(DiagnosticStatusTone.OK, dark)
            false -> DiagnosticStatusPalette.color(DiagnosticStatusTone.ACTION_REQUIRED, dark)
            null -> getColor(R.color.colorTextGray)
        }
    }
    button.setOnClickListener {
        if (running) return@setOnClickListener
        val source = SemanticSource.from(1, key(), endpoint(), provider(), model())
        if (source == null) {
            show(false, getString(R.string.semantic_test_fail_config))
            return@setOnClickListener
        }
        running = true
        show(null, getString(R.string.semantic_test_running))
        Thread({
            val result = SemanticConnectivity.probe(source)
            runOnUiThread {
                running = false
                if (isFinishing || isDestroyed || !row.isAttachedToWindow) return@runOnUiThread
                show(result.ok, semanticTestMessage(result))
            }
        }, "BIL-SemanticTest").apply { isDaemon = true }.start()
    }
    row.addView(button, NativeLinearLayout.LayoutParams(-2, -2))
    row.addView(status, NativeLinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (8 * density).toInt() })
    return row
}

private fun MainActivity.semanticTestMessage(result: SemanticConnectivity.Result): String {
    if (result.ok) {
        return getString(R.string.semantic_test_ok, formatSeconds(result.elapsedMs.toInt().coerceAtLeast(100)), result.variant)
    }
    val status = result.outcome.removePrefix("http-").toIntOrNull()
    return when {
        status == 401 || status == 402 || status == 403 -> getString(R.string.semantic_test_fail_auth, status)
        status == 404 || status == 405 -> getString(R.string.semantic_test_fail_address, status)
        status == 429 -> getString(R.string.semantic_test_fail_rate)
        status != null -> getString(R.string.semantic_test_fail_http, status)
        result.outcome == "network" -> getString(R.string.semantic_test_fail_network)
        result.outcome == "parse" -> getString(R.string.semantic_test_fail_parse)
        result.outcome == "deadline" -> getString(R.string.semantic_test_fail_timeout)
        else -> getString(R.string.semantic_test_fail_other, result.outcome)
    }
}

/** 过滤面的「判定来源」入口文字：标题 + 当前选择。 */
internal fun MainActivity.semanticRouteEntryText(surface: SemanticSurface): String {
    val configured = configuredSemanticSources()
    val raw = prefs().getString(FeaturePreferences.semanticRouteKey(surface), SemanticRoute.AUTO)
    val fixed = raw?.toIntOrNull()?.takeIf { it in configured }
    val summary = when {
        configured.isEmpty() -> getString(R.string.semantic_jev_summary_unconfigured)
        fixed != null -> getString(R.string.semantic_route_fixed, fixed)
        else -> getString(R.string.semantic_route_entry_auto, configured.size)
    }
    return getString(R.string.semantic_route_title) + "\n" + summary
}

/** 选择过滤面的判定来源：自动分流或某个已配置的来源。 */
internal fun MainActivity.showSemanticRouteDialog(surface: SemanticSurface, anchor: View? = null, onSaved: () -> Unit) {
    val configured = configuredSemanticSources()
    if (configured.isEmpty()) {
        toast(getString(R.string.semantic_route_none))
        return
    }
    val routeKey = FeaturePreferences.semanticRouteKey(surface)
    val options = listOf(SemanticRoute.AUTO to getString(R.string.semantic_route_auto)) +
        configured.map { it.toString() to semanticSourceRowText(it) }
    val stored = prefs().getString(routeKey, SemanticRoute.AUTO)
    var choice = options.indexOfFirst { it.first == stored }.takeIf { it >= 0 } ?: 0
    presentSemanticDialog(getString(R.string.semantic_route_title), anchor, info = {
        semanticInfo(R.string.semantic_route_title to getString(R.string.semantic_route_dialog_tip))
    }, form = { form ->
        form.help(getString(R.string.semantic_short_route), top = 6)
        form.body.addView(
            createSlidingChoice(labels = options.map { it.second }, selectedIndex = choice) { choice = it },
            NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = form.dp(8) }
        )
    }) { dismiss -> listOf(
        getString(R.string.dialog_cancel) to { dismiss {} },
        getString(R.string.semantic_jev_save) to {
            runCatching { prefs().edit { putString(routeKey, options[choice].first) } }
                .onFailure { Log.e("BilibiliInnocentLab", "write semantic route failed", it) }
            dismiss {
                toast(getString(R.string.semantic_jev_saved))
                onSaved()
            }
        }
    ) }
}

/**
 * 单选列表，选中框是一块独立的皮肤选中面：点新选项时它从旧位置连续滑到新位置（位置与高度一起插值），
 * 标题颜色同步渐变。首帧布局完成前不做动画，直接落在初始选中项上；动画可被下一次点击打断并从当前位置接续。
 */
private fun MainActivity.createSlidingChoice(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
): View {
    val frame = createSkinSlidingSelection()
    val selectedColor = monetColors.primary
    val normalColor = getColor(R.color.colorTextGray)
    labels.forEach { label ->
        frame.addOption(createGitHubMenuRow(label, "", highlight = false) {})
    }
    frame.onSelect = onSelected
    frame.setOnHighlightListener { index, weight ->
        (frame.options.getChildAt(index) as? ViewGroup)?.getChildAt(0)?.let { title ->
            (title as? NativeTextView)?.textColor = ColorUtils.blendARGB(normalColor, selectedColor, weight)
        }
    }
    frame.select(selectedIndex, animate = false)
    return frame
}

/** 各过滤面的开关键与勾选键。 */
internal fun semanticRulesKey(surface: SemanticSurface): String = when (surface) {
    SemanticSurface.DYNAMIC -> FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_RULES
    SemanticSurface.DANMAKU -> FeaturePreferences.DANMAKU_SEMANTIC_FILTER_RULES
    SemanticSurface.COMMENT -> FeaturePreferences.COMMENT_SEMANTIC_FILTER_RULES
    SemanticSurface.VIDEO -> FeaturePreferences.VIDEO_SEMANTIC_FILTER_RULES
}

/** 当前勾选的预设（实时读偏好；从未设置过时为默认勾选）。 */
internal fun MainActivity.semanticSelectedIds(surface: SemanticSurface): Set<String> {
    val raw = prefs().getString(semanticRulesKey(surface), null) ?: SemanticPresets.defaultSelection(surface)
    return SemanticPresets.selected(surface, raw).mapTo(linkedSetOf()) { it.id }
}

/** 当前的自定义类型（实时读偏好）。 */
internal fun MainActivity.semanticCustomRules(surface: SemanticSurface): List<SemanticCustomRule> =
    SemanticCustomRule.parse(prefs().getString(FeaturePreferences.semanticCustomRulesKey(surface), ""))

/** 入口行文字：「屏蔽类型」+ 已选数量（预设 + 启用的自定义类型）。 */
internal fun MainActivity.semanticRulesEntryText(surface: SemanticSurface): String {
    val count = semanticSelectedIds(surface).size + semanticCustomRules(surface).count { it.enabled }
    val summary = if (count == 0) getString(R.string.semantic_rules_summary_none)
    else getString(R.string.semantic_rules_summary, count)
    return getString(R.string.semantic_rules_title) + "\n" + summary
}

/**
 * 屏蔽类型勾选面板：先列该过滤面的全部预设（每项带一行说明），再列自定义类型（可勾选、可编辑、可新增）。
 * 点「保存」一次写入预设勾选与自定义类型。规则集合进入宿主缓存键，改动后旧判定自然作废（重启哔哩哔哩生效）。
 */
internal fun MainActivity.showSemanticRulesDialog(
    surface: SemanticSurface,
    anchor: View? = null,
    onSaved: () -> Unit
) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.semantic_rules_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.semantic_rules_dialog_tip)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        alpha = 0.72f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })

    fun description(text: CharSequence) = NativeTextView(this).apply {
        this.text = text
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        alpha = 0.72f
        setLineSpacing(3 * density, 1f)
    }

    val selected = semanticSelectedIds(surface)
    val list = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
    val boxes = SemanticPresets.of(surface).mapNotNull { rule ->
        val (labelRes, descRes) = SemanticRuleLabels.of(surface, rule.id) ?: return@mapNotNull null
        val box = NativeCheckBox(this).apply {
            text = getString(labelRes)
            textSize = 14f
            textColor = getColor(R.color.colorTextDark)
            isChecked = rule.id in selected
            isFocusable = true
        }
        list.addView(box, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        list.addView(description(getString(descRes)), NativeLinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(12)
            marginEnd = dp(8)
            bottomMargin = dp(2)
        })
        rule.id to box
    }

    // ---- 自定义类型：内存里改，点「保存」才写入 ----
    val customs = semanticCustomRules(surface).toMutableList()
    list.addView(NativeTextView(this).apply {
        text = getString(R.string.semantic_custom_rules_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 15f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    list.addView(description(getString(R.string.semantic_short_custom)), NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
    val customList = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
    list.addView(customList, NativeLinearLayout.LayoutParams(-1, -2))
    val customBoxes = HashMap<String, NativeCheckBox>()

    fun renderCustoms() {
        // 重绘前先把勾选状态收回列表，编辑/新增后不丢勾选。
        customBoxes.forEach { (id, box) ->
            val position = customs.indexOfFirst { it.id == id }
            if (position >= 0) customs[position] = customs[position].copy(enabled = box.isChecked)
        }
        customBoxes.clear()
        customList.removeAllViews()
        customs.forEach { rule ->
            val row = NativeLinearLayout(this).apply {
                orientation = NativeLinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val box = NativeCheckBox(this).apply {
                text = rule.type
                textSize = 14f
                textColor = getColor(R.color.colorTextDark)
                isChecked = rule.enabled
                isFocusable = true
            }
            row.addView(box, NativeLinearLayout.LayoutParams(0, -2, 1f))
            row.addView(NativeTextView(this).apply {
                text = getString(R.string.semantic_custom_edit)
                textColor = monetColors.primary
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = selfRippleBackground(10f)
                isClickable = true
                isFocusable = true
                setOnClickListener { view ->
                    customBoxes[rule.id]?.let { current ->
                        val position = customs.indexOfFirst { it.id == rule.id }
                        if (position >= 0) customs[position] = customs[position].copy(enabled = current.isChecked)
                    }
                    showSemanticCustomRuleDialog(
                        customs.firstOrNull { it.id == rule.id }, rule.id,
                        origin = modalAnchorBounds(row), cover = modalSurfaceBounds(dialog, container)
                    ) { edited ->
                        val position = customs.indexOfFirst { it.id == rule.id }
                        if (position >= 0) {
                            if (edited == null) customs.removeAt(position) else customs[position] = edited
                        }
                        customBoxes.remove(rule.id)
                        renderCustoms()
                    }
                }
            }, NativeLinearLayout.LayoutParams(-2, -2))
            customList.addView(row, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            customList.addView(description(rule.covers), NativeLinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(12)
                marginEnd = dp(8)
                bottomMargin = dp(2)
            })
            customBoxes[rule.id] = box
        }
    }
    renderCustoms()
    list.addView(NativeTextView(this).apply {
        text = getString(R.string.semantic_custom_add)
        textColor = monetColors.primary
        textSize = 14f
        setPadding(dp(8), dp(10), dp(8), dp(10))
        background = selfRippleBackground(10f)
        isClickable = true
        isFocusable = true
        setOnClickListener { view ->
            val id = SemanticCustomRule.nextId(customs)
            if (id == null) {
                toast(getString(R.string.semantic_custom_full))
                return@setOnClickListener
            }
            showSemanticCustomRuleDialog(null, id, origin = modalAnchorBounds(view), cover = modalSurfaceBounds(dialog, container)) { created ->
                if (created != null) {
                    customs += created
                    renderCustoms()
                }
            }
        }
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val listCap = minOf(dp(420), (resources.displayMetrics.heightPixels * 0.52f).toInt())
    container.addView(MaxHeightScrollView(this, listCap).apply {
        addView(list, NativeFrameLayout.LayoutParams(-1, -2))
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    semanticButtons(
        container,
        getString(R.string.dialog_cancel) to { dismissWithAnimation(dialog, container) {} },
        getString(R.string.semantic_jev_save) to {
            val ids = boxes.filter { it.second.isChecked }.mapTo(hashSetOf()) { it.first }
            val finalCustoms = customs.map { rule -> customBoxes[rule.id]?.let { rule.copy(enabled = it.isChecked) } ?: rule }
            runCatching {
                prefs().edit {
                    putString(semanticRulesKey(surface), SemanticPresets.encode(surface, ids))
                    putString(FeaturePreferences.semanticCustomRulesKey(surface), SemanticCustomRule.encode(finalCustoms))
                }
            }.onFailure { Log.e("BilibiliInnocentLab", "write semantic rules failed", it) }
            dismissWithAnimation(dialog, container) {
                toast(getString(R.string.semantic_jev_saved))
                onSaved()
            }
        }
    )
    presentModalDialog(dialog, container, anchor)
}

/**
 * 新增 / 编辑一个自定义屏蔽类型。[onDone] 收到 null 表示删除（新增时点取消不回调）。
 * 只在内存里改，由屏蔽类型面板的「保存」统一写入。
 */
private fun MainActivity.showSemanticCustomRuleDialog(
    existing: SemanticCustomRule?,
    id: String,
    origin: SettingsBackupMotionRect? = null,
    cover: SettingsBackupMotionRect? = null,
    onDone: (SemanticCustomRule?) -> Unit
) {
    lateinit var name: NativeEditText
    lateinit var covers: NativeEditText
    lateinit var notFor: NativeEditText
    lateinit var examples: NativeEditText
    lateinit var keepExamples: NativeEditText
    presentSemanticDialog(getString(R.string.semantic_custom_edit_title), null, origin = origin, cover = cover, info = {
        semanticInfo(R.string.semantic_custom_rules_title to getString(R.string.semantic_custom_help))
    }, form = { form ->
        form.label(R.string.semantic_custom_name, 10)
        name = form.field(existing?.type.orEmpty(), R.string.semantic_custom_name_hint, multiLine = false)
        form.label(R.string.semantic_custom_covers, 12)
        covers = form.field(existing?.covers.orEmpty(), R.string.semantic_custom_covers_hint, multiLine = true)
        form.label(R.string.semantic_custom_not_for, 12)
        notFor = form.field(existing?.notFor.orEmpty(), R.string.semantic_custom_not_for_hint, multiLine = true)
        form.label(R.string.semantic_custom_examples, 12)
        examples = form.field(existing?.examples.orEmpty().joinToString("\n"), R.string.semantic_custom_examples_hint, multiLine = true)
        form.label(R.string.semantic_custom_keep_examples, 12)
        keepExamples = form.field(existing?.keepExamples.orEmpty().joinToString("\n"), R.string.semantic_custom_examples_hint, multiLine = true)
        listOf(name, covers, notFor).forEach { it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        name.isSingleLine = true
    }) { dismiss -> buildList {
        if (existing != null) {
            add(getString(R.string.semantic_custom_delete) to { dismiss { onDone(null) } })
        }
        add(getString(R.string.dialog_cancel) to { dismiss {} })
        add(getString(R.string.semantic_jev_save) to save@{
            val rule = SemanticCustomRule.sanitize(
                id = id,
                enabled = existing?.enabled ?: true,
                type = name.textToString(),
                covers = covers.textToString(),
                notFor = notFor.textToString(),
                examples = examples.textToString().lines(),
                keepExamples = keepExamples.textToString().lines()
            )
            if (rule == null) {
                toast(getString(R.string.semantic_custom_required))
                return@save
            }
            dismiss { onDone(rule) }
        })
    } }
}

/** 预设 id → (名称, 说明) 文案；显式对照表，不用 getIdentifier（资源收缩安全、可被 lint 检查）。 */
internal object SemanticRuleLabels {
    private val table: Map<SemanticSurface, Map<String, Pair<Int, Int>>> = mapOf(
        SemanticSurface.DYNAMIC to mapOf(
            "lottery" to (R.string.semantic_rule_dynamic_lottery to R.string.semantic_rule_dynamic_lottery_desc),
            "goods" to (R.string.semantic_rule_dynamic_goods to R.string.semantic_rule_dynamic_goods_desc),
            "sponsored" to (R.string.semantic_rule_dynamic_sponsored to R.string.semantic_rule_dynamic_sponsored_desc),
            "traffic" to (R.string.semantic_rule_dynamic_traffic to R.string.semantic_rule_dynamic_traffic_desc),
            "flame" to (R.string.semantic_rule_dynamic_flame to R.string.semantic_rule_dynamic_flame_desc),
            "abuse" to (R.string.semantic_rule_dynamic_abuse to R.string.semantic_rule_dynamic_abuse_desc),
            "bait" to (R.string.semantic_rule_dynamic_bait to R.string.semantic_rule_dynamic_bait_desc),
            "marketing" to (R.string.semantic_rule_dynamic_marketing to R.string.semantic_rule_dynamic_marketing_desc)
        ),
        SemanticSurface.DANMAKU to mapOf(
            "spoiler" to (R.string.semantic_rule_danmaku_spoiler to R.string.semantic_rule_danmaku_spoiler_desc),
            "flood" to (R.string.semantic_rule_danmaku_flood to R.string.semantic_rule_danmaku_flood_desc),
            "flame" to (R.string.semantic_rule_danmaku_flame to R.string.semantic_rule_danmaku_flame_desc),
            "abuse" to (R.string.semantic_rule_danmaku_abuse to R.string.semantic_rule_danmaku_abuse_desc),
            "promotion" to (R.string.semantic_rule_danmaku_promotion to R.string.semantic_rule_danmaku_promotion_desc),
            "checkin" to (R.string.semantic_rule_danmaku_checkin to R.string.semantic_rule_danmaku_checkin_desc),
            "offtopic" to (R.string.semantic_rule_danmaku_offtopic to R.string.semantic_rule_danmaku_offtopic_desc),
            "warning" to (R.string.semantic_rule_danmaku_warning to R.string.semantic_rule_danmaku_warning_desc)
        ),
        SemanticSurface.COMMENT to mapOf(
            "flame" to (R.string.semantic_rule_comment_flame to R.string.semantic_rule_comment_flame_desc),
            "abuse" to (R.string.semantic_rule_comment_abuse to R.string.semantic_rule_comment_abuse_desc),
            "sarcasm" to (R.string.semantic_rule_comment_sarcasm to R.string.semantic_rule_comment_sarcasm_desc),
            "fandom" to (R.string.semantic_rule_comment_fandom to R.string.semantic_rule_comment_fandom_desc),
            "polarize" to (R.string.semantic_rule_comment_polarize to R.string.semantic_rule_comment_polarize_desc),
            "promotion" to (R.string.semantic_rule_comment_promotion to R.string.semantic_rule_comment_promotion_desc),
            "spoiler" to (R.string.semantic_rule_comment_spoiler to R.string.semantic_rule_comment_spoiler_desc),
            "checkin" to (R.string.semantic_rule_comment_checkin to R.string.semantic_rule_comment_checkin_desc),
            "fishing" to (R.string.semantic_rule_comment_fishing to R.string.semantic_rule_comment_fishing_desc)
        ),
        SemanticSurface.VIDEO to mapOf(
            "clickbait" to (R.string.semantic_rule_video_clickbait to R.string.semantic_rule_video_clickbait_desc),
            "marketing" to (R.string.semantic_rule_video_marketing to R.string.semantic_rule_video_marketing_desc),
            "borderline" to (R.string.semantic_rule_video_borderline to R.string.semantic_rule_video_borderline_desc),
            "outrage" to (R.string.semantic_rule_video_outrage to R.string.semantic_rule_video_outrage_desc),
            "anxiety" to (R.string.semantic_rule_video_anxiety to R.string.semantic_rule_video_anxiety_desc),
            "repost" to (R.string.semantic_rule_video_repost to R.string.semantic_rule_video_repost_desc)
        )
    )

    fun of(surface: SemanticSurface, id: String): Pair<Int, Int>? = table[surface]?.get(id)

    /** 每个预设都要有文案，单测据此钉住"加了预设忘了加名称"。 */
    fun covers(surface: SemanticSurface): Boolean =
        SemanticPresets.of(surface).all { table[surface]?.containsKey(it.id) == true }
}
