package com.Bilibili_Innocent_Lab.xposedmodule.ui.meguviz

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.LogSegmentScrubBar
import com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
import io.github.jichuo1.meguviz.android.extensions.toPx
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.ParentPolicy
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.UiDsl
import io.github.jichuo1.meguviz.ui.ViewFactory
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.ViewRef

internal typealias LinearSettingsScope = SettingsScope<LinearLayout, LinearLayout.LayoutParams>
internal typealias FrameSettingsScope = SettingsScope<FrameLayout, FrameLayout.LayoutParams>

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

    private fun <V : View> createRef(key: String, lparams: LP, type: Class<V>, factory: (Context) -> V, init: V.() -> Unit): ViewRef<V> =
        session.mount(parent, policy, ViewKey(key), ComponentType(type.name, type), lparams,
            ViewFactory { factory(it.context) }, init)

    private fun <V : View> create(key: String, lparams: LP, type: Class<V>, factory: (Context) -> V, init: V.() -> Unit): V =
        createRef(key, lparams, type, factory, init).get()

    private fun releaseFailed(ref: ViewRef<*>, failure: Throwable): Nothing {
        try { session.release(ref) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }

    fun textView(key: String, lparams: LP = params(), init: TextView.() -> Unit = {}): TextView =
        create(key, lparams, TextView::class.java, ::TextView, init)

    fun imageView(key: String, lparams: LP = params(), init: ImageView.() -> Unit = {}): ImageView =
        create(key, lparams, ImageView::class.java, ::ImageView, init)

    fun space(key: String, lparams: LP = params(), init: Space.() -> Unit = {}): Space =
        create(key, lparams, Space::class.java, ::Space, init)

    fun view(key: String, lparams: LP = params(), init: View.() -> Unit = {}): View =
        create(key, lparams, View::class.java, ::View, init)

    fun materialSwitch(key: String, lparams: LP = params(), init: MaterialSwitch.() -> Unit = {}): MaterialSwitch {
        val ref = createRef(key, lparams, MaterialSwitch::class.java, { MaterialSwitch(it, null) }, init)
        val view = ref.get()
        session.onRelease(ref) { view.unbindModel() }
        try { view.finishModelBinding() } catch (failure: Throwable) { releaseFailed(ref, failure) }
        return view
    }

    fun logSegmentScrubBar(key: String, lparams: LP = params(), init: LogSegmentScrubBar.() -> Unit = {}): LogSegmentScrubBar =
        create(key, lparams, LogSegmentScrubBar::class.java, { LogSegmentScrubBar(it, null) }, init)

    fun linearLayout(key: String, lparams: LP = params(), init: LinearLayout.() -> Unit = {}, content: LinearSettingsScope.() -> Unit = {}): LinearLayout {
        val ref = createRef(key, lparams, LinearLayout::class.java, ::LinearLayout, init)
        val view = ref.get()
        try {
            SettingsScope(session, view, LinearPolicy) { width, height -> LinearLayout.LayoutParams(width, height) }.content()
        } catch (failure: Throwable) { releaseFailed(ref, failure) }
        return view
    }

    fun frameLayout(key: String, lparams: LP = params(), init: FrameLayout.() -> Unit = {}, content: FrameSettingsScope.() -> Unit = {}): FrameLayout {
        val ref = createRef(key, lparams, FrameLayout::class.java, ::FrameLayout, init)
        val view = ref.get()
        try {
            SettingsScope(session, view, FramePolicy) { width, height -> FrameLayout.LayoutParams(width, height) }.content()
        } catch (failure: Throwable) { releaseFailed(ref, failure) }
        return view
    }

    fun nestedScrollView(key: String, lparams: LP = params(), init: NestedScrollView.() -> Unit = {}, content: SettingsScope<NestedScrollView, FrameLayout.LayoutParams>.() -> Unit = {}): NestedScrollView {
        val ref = createRef(key, lparams, NestedScrollView::class.java, ::NestedScrollView, init)
        val view = ref.get()
        try {
            SettingsScope(session, view, ScrollPolicy) { width, height -> FrameLayout.LayoutParams(width, height) }.content()
        } catch (failure: Throwable) { releaseFailed(ref, failure) }
        return view
    }

    fun component(content: SettingsScope<P, LP>.() -> Unit) = content()
}

internal fun settingsComponent(content: LinearSettingsScope.() -> Unit): LinearSettingsScope.() -> Unit = content

/** Uses the real window content parent; no synthetic inflater or intermediary parent is installed. */
internal fun Activity.setMeguvizContentView(content: FrameSettingsScope.() -> Unit): UiSession {
    val parent = findViewById<FrameLayout>(android.R.id.content)
    check(parent.childCount == 0) { "Close the previous content session before replacing its tree" }
    val session = UiSession(CreationEnvironment(this, resourceOwner = "bil-module"))
    try {
        SettingsScope(session, parent, FramePolicy) { width, height -> FrameLayout.LayoutParams(width, height) }.content()
        check(parent.childCount == 1) { "An Activity content session requires exactly one root" }
        val root = parent.getChildAt(0)
        val rootParams = root.layoutParams
        parent.removeView(root)
        setContentView(root, rootParams)
        check(root.parent === parent) { "The Activity changed its managed content parent during installation" }
    } catch (failure: Throwable) {
        try { session.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
    return session
}
