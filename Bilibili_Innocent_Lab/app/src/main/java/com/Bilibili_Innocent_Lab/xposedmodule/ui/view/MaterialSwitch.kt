@file:Suppress("SameParameterValue")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.view

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.CompoundButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.SettingState
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity.SkinnedActivity
import com.lumen.coacervation.engine.liquid.LiquidChoiceDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.ModernPalette

/**
 * 优先使用主题里的轻量开关样式：它继承默认开关样式，只去掉 thumb/track 及其着色。
 * 那两块默认资源在构造时解析成本最高，而挂载时 [applyMonetStyle]（或皮肤遍历）一定会整体替换，
 * 从未被画出。主题未声明该属性时（例如独立透明窗口）退回 AppCompat 默认开关样式。
 */
private fun switchStyleAttr(context: Context): Int =
    if (context.theme.resolveAttribute(R.attr.bilMaterialSwitchStyle, TypedValue(), true)) R.attr.bilMaterialSwitchStyle
    else androidx.appcompat.R.attr.switchStyle

class MaterialSwitch(context: Context, attrs: AttributeSet?) : SwitchCompat(context, attrs, switchStyleAttr(context)) {

    /** Explicit catalog identity; never inferred from localized text. */
    internal var settingId: String? = null
    internal var supportsFavoriteToggle: Boolean = false
    private var stateObservers: MutableList<() -> Unit>? = null
    private var monetStyleApplied = false
    private var modelStore: SettingStore? = null
    private var modelDisposer: (() -> Unit)? = null
    private var modelReady = false
    private var modelRendering = false
    private var renderedModelState: SettingState? = null
    internal var onModelStateRendered: (() -> Unit)? = null

    internal fun bindModel(store: SettingStore, id: String) {
        unbindModel()
        modelStore = store
        settingId = id
    }

    internal fun finishModelBinding() {
        val store = modelStore ?: return
        val id = settingId ?: return
        store.confirm(id, store.state(id).copy(enabled = store.state(id).enabled && isEnabled))
        modelReady = true
        modelDisposer = store.observe(id, ::renderModelState)
        renderModelState(store.state(id))
    }

    internal fun renderModelState(state: SettingState) {
        val changed = renderedModelState != state
        renderedModelState = state
        modelRendering = true
        try { isChecked = state.checked; isEnabled = state.enabled }
        finally { modelRendering = false }
        if (changed) onModelStateRendered?.invoke()
    }

    internal fun unbindModel() {
        modelDisposer?.invoke()
        modelDisposer = null
        modelStore = null
        modelReady = false
        renderedModelState = null
        onModelStateRendered = null
    }

    override fun setOnCheckedChangeListener(listener: CompoundButton.OnCheckedChangeListener?) {
        super.setOnCheckedChangeListener(if (listener == null) null else CompoundButton.OnCheckedChangeListener { button, value ->
            if (!modelRendering) listener.onCheckedChanged(button, value)
        })
    }

    private fun publishModelState() {
        if (!modelReady || modelRendering) return
        val id = settingId ?: return
        modelStore?.confirm(id, SettingState(isChecked, isEnabled))
    }

    internal fun observeState(observer: () -> Unit): () -> Unit {
        val observers = stateObservers ?: mutableListOf<() -> Unit>().also { stateObservers = it }
        observers += observer
        return { observers.remove(observer) }
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        publishModelState()
        // The existing listener may reject/revert a request. Peers see the final authoritative state.
        stateObservers?.toList()?.forEach { it() }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        publishModelState()
        stateObservers?.toList()?.forEach { it() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 弹窗（Dialog）内的控件不在 Activity 根视图树里，收不到 stylePreparedSkinControls
        // 的遍历替换，只能停留在构造期硬编码的灰色样式上。这里在挂载时自取 Monet 配色兜底，
        // 保证任何容器里的开关都是 Material You 取色样式；同时附带整行按压涟漪——否则
        // 按压反馈只剩轨道那一小条的状态变化，没有覆盖整个控件的按压光晕。
        if (!monetStyleApplied) {
            monetStyleApplied = true
            applyMonetStyle()
        }
    }

    private fun applyMonetStyle() {
        val activity = generateSequence(context) { c -> (c as? ContextWrapper)?.baseContext }
            .filterIsInstance<SkinnedActivity>().firstOrNull()
        val palette = activity?.monetColors ?: ModernPalette.resolve(context)
        val density = resources.displayMetrics.density
        val thumbSize = (20 * density).toInt()
        fun choice(width: Int, height: Int, thumb: Boolean) = LiquidChoiceDrawable(
            width, height, density, palette.surface, palette.primary,
            palette.onPrimary, context.getColor(R.color.colorTextGray), false, thumb)
        thumbTintList = null
        trackTintList = null
        thumbDrawable = choice(thumbSize, thumbSize, thumb = true)
        trackDrawable = choice(thumbSize * 2, thumbSize, thumb = false)
        splitTrack = false
        // 新装的 drawable 起始状态为空，`setThumbDrawable`/`setTrackDrawable` 都不推状态；
        // 不显式刷新就要等下一次 drawableStateChanged()——换皮肤的 recreate() 发生在已获焦
        // 的窗口里，那一次根本不会来，已开启的开关于是停在未选中的灰色配色。
        refreshDrawableState()
        // 整行按压涟漪：primary 低透明度 + 圆角 mask，按压时光晕铺满整个控件而非只有轨道。
        val rippleMask = GradientDrawable().apply {
            cornerRadius = 10f * density
            setColor(Color.WHITE)
        }
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 0x33)),
            ColorDrawable(Color.TRANSPARENT),
            rippleMask
        )
    }

    init {
        // 构造期不再搭建占位 thumb/track：挂载时 applyMonetStyle（以及皮肤遍历）必定以
        // 20dp 的 LiquidChoiceDrawable 整体替换，旧占位从未上屏；皮肤遍历按 20dp 下限取尺寸，结果不变。
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
}
