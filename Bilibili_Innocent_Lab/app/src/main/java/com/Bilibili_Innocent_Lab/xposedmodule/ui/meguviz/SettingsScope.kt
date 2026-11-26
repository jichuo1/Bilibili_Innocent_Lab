package com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity.SkinnedActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
import com.lumen.coacervation.engine.widget.LumenSegmentScrubBar
import io.github.jichuo1.meguviz.android.extensions.textColor
import io.github.jichuo1.meguviz.android.extensions.toPx
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.ParentPolicy
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.UiDsl
import io.github.jichuo1.meguviz.ui.ViewFactory
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.android.AndroidUiBinding

internal typealias LinearSettingsScope = SettingsScope<LinearLayout, LinearLayout.LayoutParams>
internal typealias FrameSettingsScope = SettingsScope<FrameLayout, FrameLayout.LayoutParams>

/** Stateless factory descriptors can be shared. Views, contexts and keys are supplied by each mount. */
private class SettingsWidget<V : View>(clazz: Class<V>, val factory: ViewFactory<V>) {
    val type = ComponentType(clazz.name, clazz)
}

private val textWidget = SettingsWidget(TextView::class.java) { TextView(it.context) }
private val imageWidget = SettingsWidget(ImageView::class.java) { ImageView(it.context) }
private val spaceWidget = SettingsWidget(Space::class.java) { Space(it.context) }
private val viewWidget = SettingsWidget(View::class.java) { View(it.context) }
private val switchWidget = SettingsWidget(MaterialSwitch::class.java) { MaterialSwitch(it.context, null) }
private val scrubWidget = SettingsWidget(LumenSegmentScrubBar::class.java) { LumenSegmentScrubBar(it.context, null) }
private val seekBarWidget = SettingsWidget(SeekBar::class.java) { SeekBar(it.context) }
private val linearWidget = SettingsWidget(LinearLayout::class.java) { LinearLayout(it.context) }
private val frameWidget = SettingsWidget(FrameLayout::class.java) { FrameLayout(it.context) }
private val scrollWidget = SettingsWidget(NestedScrollView::class.java) { NestedScrollView(it.context) }

private object LinearPolicy : ParentPolicy<LinearLayout, LinearLayout.LayoutParams>
private object FramePolicy : ParentPolicy<FrameLayout, FrameLayout.LayoutParams>
private object ScrollPolicy : ParentPolicy<NestedScrollView, FrameLayout.LayoutParams>

/** BIL constructors and typed parent parameters; business and visual ownership stay in the app. */
@UiDsl
internal class SettingsScope<P : ViewGroup, LP : ViewGroup.LayoutParams>(
    private val session: UiSession,
    private val parent: P,
    private val policy: ParentPolicy<P, LP>,
    private val createParams: (Int, Int) -> LP
) {
    val context: Context get() = session.environment.context
    val Int.dp: Int get() = toPx(context)
    val Float.dp: Float get() = toPx(context)
    fun stringResource(id: Int, vararg arguments: Any): String =
        if (arguments.isEmpty()) context.getString(id) else context.getString(id, *arguments)
    fun colorResource(id: Int): Int = context.getColor(id)
    fun stateColorResource(id: Int): ColorStateList = context.getColorStateList(id)

    fun params(
        width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        matchParent: Boolean = false,
        widthMatchParent: Boolean = false,
        heightMatchParent: Boolean = false,
        configure: LP.() -> Unit = {}
    ): LP = createParams(
        if (matchParent || widthMatchParent) ViewGroup.LayoutParams.MATCH_PARENT else width,
        if (matchParent || heightMatchParent) ViewGroup.LayoutParams.MATCH_PARENT else height
    ).apply(configure)

    private fun <V : View> createRef(key: String, lparams: LP, widget: SettingsWidget<V>, init: V.() -> Unit): ViewRef<V> =
        session.mount(parent, policy, ViewKey(key), widget.type, lparams, widget.factory, init)

    private fun <V : View> create(key: String, lparams: LP, widget: SettingsWidget<V>, init: V.() -> Unit): V =
        createRef(key, lparams, widget, init).get()

    private fun releaseFailed(ref: ViewRef<*>, failure: Throwable): Nothing {
        try { session.release(ref) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }

    fun textView(key: String, lparams: LP = params(), init: TextView.() -> Unit = {}): TextView =
        create(key, lparams, textWidget, init)

    fun textViewAction(
        key: String,
        lparams: LP,
        init: TextView.() -> Unit,
        action: (ViewRef<TextView>, TextView) -> Unit
    ) {
        val ref = createRef(key, lparams, textWidget, init)
        try { session.click(ref) { action(ref, it) } }
        catch (failure: Throwable) { releaseFailed(ref, failure) }
    }

    fun imageView(key: String, lparams: LP = params(), init: ImageView.() -> Unit = {}): ImageView =
        create(key, lparams, imageWidget, init)

    fun space(key: String, lparams: LP = params(), init: Space.() -> Unit = {}): Space =
        create(key, lparams, spaceWidget, init)

    fun view(key: String, lparams: LP = params(), init: View.() -> Unit = {}): View =
        create(key, lparams, viewWidget, init)

    fun materialSwitch(key: String, lparams: LP = params(), init: MaterialSwitch.() -> Unit = {}): MaterialSwitch {
        val ref = createRef(key, lparams, switchWidget, init)
        val view = ref.get()
        session.onRelease(ref) { view.unbindModel() }
        try { view.finishModelBinding() } catch (failure: Throwable) { releaseFailed(ref, failure) }
        return view
    }

    fun logSegmentScrubBar(key: String, lparams: LP = params(), init: LumenSegmentScrubBar.() -> Unit = {}): LumenSegmentScrubBar =
        create(key, lparams, scrubWidget, init)

    fun seekBar(key: String, lparams: LP = params(), init: SeekBar.() -> Unit = {}): SeekBar =
        create(key, lparams, seekBarWidget, init)

    fun linearLayout(key: String, lparams: LP = params(), init: LinearLayout.() -> Unit = {}, content: LinearSettingsScope.() -> Unit = {}): LinearLayout {
        val ref = createRef(key, lparams, linearWidget, init)
        val view = ref.get()
        try {
            SettingsScope(session, view, LinearPolicy) { width, height -> LinearLayout.LayoutParams(width, height) }.content()
        } catch (failure: Throwable) { releaseFailed(ref, failure) }
        return view
    }

    fun frameLayout(key: String, lparams: LP = params(), init: FrameLayout.() -> Unit = {}, content: FrameSettingsScope.() -> Unit = {}): FrameLayout {
        val ref = createRef(key, lparams, frameWidget, init)
        val view = ref.get()
        try {
            SettingsScope(session, view, FramePolicy) { width, height -> FrameLayout.LayoutParams(width, height) }.content()
        } catch (failure: Throwable) { releaseFailed(ref, failure) }
        return view
    }

    fun nestedScrollView(key: String, lparams: LP = params(), init: NestedScrollView.() -> Unit = {}, content: SettingsScope<NestedScrollView, FrameLayout.LayoutParams>.() -> Unit = {}): NestedScrollView {
        val ref = createRef(key, lparams, scrollWidget, init)
        val view = ref.get()
        try {
            SettingsScope(session, view, ScrollPolicy) { width, height -> FrameLayout.LayoutParams(width, height) }.content()
        } catch (failure: Throwable) { releaseFailed(ref, failure) }
        return view
    }

    fun component(content: SettingsScope<P, LP>.() -> Unit) = content()
}

/** 声明式说明文本行，复用设置页 12sp 次级文字与 6f 行距排版。 */
internal fun LinearSettingsScope.tipText(
    key: String,
    text: CharSequence,
    lparams: LinearLayout.LayoutParams = params(widthMatchParent = true),
    init: TextView.() -> Unit = {}
): TextView = textView(key = key, lparams = lparams) {
    alpha = 0.6f
    setLineSpacing(6f, 1f)
    this.text = text
    textColor = colorResource(R.color.colorTextDark)
    textSize = 12f
    init()
}

/** 声明式开关行及其可选说明文本，消除 `*Sections.kt` 重复样板。 */
internal fun LinearSettingsScope.switchRow(
    key: String,
    title: CharSequence,
    summary: CharSequence? = null,
    lparams: LinearLayout.LayoutParams = params(widthMatchParent = true) {
        topMargin = 12.dp
        bottomMargin = 5.dp
    },
    summaryTopMarginDp: Int = 0,
    onTip: (TextView) -> Unit = {},
    bind: MaterialSwitch.() -> Unit
): MaterialSwitch {
    val toggle = materialSwitch(key = key, lparams = lparams) {
        text = title
        isAllCaps = false
        textColor = colorResource(R.color.colorTextGray)
        textSize = 15f
        bind()
    }
    if (summary != null) {
        tipText(
            key = "$key:tip",
            text = summary,
            lparams = params(widthMatchParent = true) {
                if (summaryTopMarginDp != 0) topMargin = summaryTopMarginDp.dp
            },
            init = onTip
        )
    }
    return toggle
}

/** 声明式可点操作行（带可选副标题与点击事件）。 */
internal fun LinearSettingsScope.actionRow(
    key: String,
    title: CharSequence,
    summary: CharSequence? = null,
    lparams: LinearLayout.LayoutParams = params(widthMatchParent = true) {
        topMargin = 12.dp
    },
    backgroundDrawable: Drawable? = null,
    init: TextView.() -> Unit = {},
    onClick: (View) -> Unit
): TextView = textView(key = key, lparams = lparams) {
    text = if (summary.isNullOrEmpty()) title else "$title\n$summary"
    textColor = colorResource(R.color.colorTextGray)
    textSize = 15f
    setPadding(12.dp, 10.dp, 12.dp, 10.dp)
    if (backgroundDrawable != null) background = backgroundDrawable
    isClickable = true
    isFocusable = true
    init()
    setOnClickListener(onClick)
}

/** 声明式双行摘要可点行与下方辅助说明行。 */
internal fun LinearSettingsScope.summaryRow(
    key: String,
    title: CharSequence,
    summary: CharSequence,
    helperText: CharSequence? = null,
    lparams: LinearLayout.LayoutParams = params(widthMatchParent = true) {
        topMargin = 12.dp
    },
    backgroundDrawable: Drawable? = null,
    helperInit: TextView.() -> Unit = {},
    init: TextView.() -> Unit = {},
    onClick: (View) -> Unit
): TextView {
    val view = actionRow(
        key = key,
        title = title,
        summary = summary,
        lparams = lparams,
        backgroundDrawable = backgroundDrawable,
        init = {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(5f, 1f)
            init()
        },
        onClick = onClick
    )
    if (!helperText.isNullOrEmpty()) {
        tipText(key = "$key:tip", text = helperText, init = helperInit)
    }
    return view
}

/** 声明式分段滑动选择器行（可选标题、弹性防冲突 scrub bar 与下方档位说明）。 */
internal fun LinearSettingsScope.segmentScrubRow(
    key: String,
    label: CharSequence? = null,
    description: CharSequence? = null,
    descriptionInit: TextView.() -> Unit = {},
    init: LumenSegmentScrubBar.() -> Unit
): LumenSegmentScrubBar {
    if (!label.isNullOrEmpty()) {
        textView(
            key = "$key:label",
            lparams = params(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 6.dp
            }
        ) {
            alpha = 0.85f
            isSingleLine = true
            text = label
            textColor = colorResource(R.color.colorTextGray)
            textSize = 13f
        }
    }
    val bar = logSegmentScrubBar(key = key, lparams = params(widthMatchParent = true)) {
        tag = ElasticInteractionController.EXCLUDED_TAG
        init()
    }
    if (!description.isNullOrEmpty()) {
        tipText(
            key = "$key:desc",
            text = description,
            lparams = params(widthMatchParent = true) {
                topMargin = 6.dp
            },
            init = descriptionInit
        )
    }
    return bar
}

/** 声明式滑块行（标题、SeekBar 与可选数值变化回调）。 */
internal fun LinearSettingsScope.sliderRow(
    key: String,
    title: CharSequence,
    max: Int,
    progress: Int,
    lparams: LinearLayout.LayoutParams = params(widthMatchParent = true) {
        topMargin = 12.dp
    },
    seekBarInit: SeekBar.() -> Unit = {},
    onProgressChanged: (SeekBar, Int, Boolean) -> Unit = { _, _, _ -> }
): SeekBar {
    textView(
        key = "$key:title",
        lparams = params(widthMatchParent = true) {
            bottomMargin = 4.dp
        }
    ) {
        text = title
        textColor = colorResource(R.color.colorTextGray)
        textSize = 14f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }
    return seekBar(key = key, lparams = lparams) {
        this.max = max
        this.progress = progress
        contentDescription = title
        seekBarInit()
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) =
                onProgressChanged(seekBar, progress, fromUser)

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }
}

internal fun settingsComponent(content: LinearSettingsScope.() -> Unit): LinearSettingsScope.() -> Unit = content

/** Uses the real window content parent; no synthetic inflater or intermediary parent is installed. */
internal fun SkinnedActivity.setMeguvizContentView(content: FrameSettingsScope.() -> Unit): UiSession {
    val parent = findViewById<FrameLayout>(android.R.id.content)
    check(parent.childCount == 0) { "Close the previous content session before replacing its tree" }
    val binding = AndroidUiBinding.open(moduleWindowSession(), parent)
    val session = binding.ui
    try {
        SettingsScope(session, parent, FramePolicy) { width, height -> FrameLayout.LayoutParams(width, height) }.content()
        check(parent.childCount == 1) { "An Activity content session requires exactly one root" }
        val root = parent.getChildAt(0)
        val rootParams = root.layoutParams
        parent.removeView(root)
        setContentView(root, rootParams)
        check(root.parent === parent) { "The Activity changed its managed content parent during installation" }
    } catch (failure: Throwable) {
        try { binding.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
        throw failure
    }
    return session
}
