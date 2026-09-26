package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowBackdropTarget
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowFloatingChrome
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowLegibilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowScrollEdge
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowScrollEdgePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch
import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticInteractionController
import com.highcapable.betterandroid.ui.extension.view.child
import com.highcapable.betterandroid.ui.extension.view.firstChildOrNull
import com.highcapable.betterandroid.ui.extension.view.removeSelf
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.textToString
import com.highcapable.betterandroid.ui.extension.view.toast
import kotlin.math.roundToInt

/** Owns navigation and personal arrangement only; original controls remain the settings authority. */
internal class SettingsHomePresenter(
    private val activity: MainActivity,
    private val shell: LinearLayout,
    private val originalScroll: NestedScrollView,
    private val originalContent: ViewGroup,
    private val purification: View?,
    private val enhancement: View?,
    private val activation: View?,
    private val floatingToolbar: View?,
    private val savedState: Bundle?,
    private val installStretch: (View, () -> Boolean) -> View?,
    private val finishStretch: (View?) -> Unit,
    private val skinPositionChanged: () -> Unit,
    private val skinContentSource: (View) -> Unit,
    private val navigationChanged: () -> Unit,
    private val navigationTouched: () -> Unit
) : SettingsFavoritesRepository.Observer {
    internal data class Entry(val id: String, val source: MaterialSwitch) {
        val title: String get() = source.textToString()
    }

    private val density = activity.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val pager = SettingsPagePager(activity)
    // pager 的直接容器：只装会从悬浮栏下穿过的内容与滚动边缘溶解层，两栏是它的兄弟。
    private val backdropTarget = GlowBackdropTarget(activity)
    private var floatingChrome: GlowFloatingChrome? = null
    private var navigation: ModernNavigationBar? = null
    private val contents = List(4) { LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL } }
    private val scrolls = List(4) { SettingsHomeScrollView(activity, ::userNavigated, navigationTouched).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        // The last row must be reachable above the floating chrome, while content can pass behind it.
        setPadding(0, dp(8), 0, dp(96))
    } }
    private val stretches = mutableListOf<View?>()
    private val peerDisposers = mutableListOf<() -> Unit>()
    private var afterEdit: ((Boolean) -> Unit)? = null
    private var disposed = false
    private var revealingPage = false
    private var userNavigationGeneration = 0L
    private var previousPage = 0
    private var manageButton: View? = null
    // 顶部悬浮栏与底部悬浮栏各自的高度（px）；滚动页用它们做内缩，
    // 静止时内容从两栏下沿开始，滚动时从栏背后穿过被盖住。
    private var headerInset = 0
    private var dockInset = dp(96)
    /**
     * 状态栏（含刘海）高度。页面层铺到窗口顶端，内容从状态栏背后滑过（2026-09-23 用户要求：
     * 状态栏任何时刻都透出内容，而不只是长按弹性临时放开祖先裁剪的那一下）；由顶部胶囊的
     * 外边距与滚动页内缩让开它，而不是让根布局整体下移。
     */
    private var systemTopInset = 0
    private var header: View? = null
    private val favoriteRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    internal var state: SettingsFavoritesState? = null
        private set
    internal var editing = false
        private set
    internal var entries: List<Entry> = emptyList()
        private set
    val searchRoots: List<ViewGroup> get() = contents.drop(1)

    fun install() {
        pager.onPositionChanged = {
            navigation?.setPageProgress(pager.pagePosition, notifyPositionChanged = false)
            skinPositionChanged()
            floatingChrome?.onContentMoved()
        }
        fun collect(view: View) {
            if (view is MaterialSwitch && view.isVisible) {
                view.settingId?.let { id -> entries = entries + Entry(id, view) }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.child(i))
        }
        collect(originalContent)
        val duplicateIds = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            android.util.Log.e("BilibiliInnocentLab", "Duplicate favorite control IDs: $duplicateIds")
            activity.toast(activity.getString(R.string.settings_favorites_unavailable), Toast.LENGTH_LONG)
            entries = entries.filterNot { it.id in duplicateIds }
        }
        val originalCards: List<View> = List(originalContent.childCount) { originalContent.child(it) }
        originalContent.removeAllViews()
        originalCards.forEach { card ->
            val index = when (card) { purification -> 1; enhancement -> 2; else -> 3 }
            contents[index].addView(card)
        }
        if (activation != null) {
            activation.removeSelf()
            contents[3].addView(activation, 0)
        }
        shell.removeView(originalScroll)

        val titleIds = intArrayOf(R.string.settings_home_favorites, R.string.settings_home_purify,
            R.string.settings_home_enhance, R.string.settings_home_module)
        val descriptionIds = intArrayOf(R.string.settings_home_favorites_hint, R.string.settings_home_purify_hint,
            R.string.settings_home_enhance_hint, R.string.settings_home_module_hint)
        val manage = TextView(activity).apply {
            text = activity.getString(R.string.settings_favorites_manage)
            textSize = 14f
            textColor = activity.getColor(R.color.colorTextDark)
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = activity.skinFloatingBackground(activity.monetColors.surface, 24f)
            foreground = activity.selfRippleBackground(24f)
            isFocusable = true
            isEnabled = false
            setOnClickListener { activity.showSettingsFavoritesDialog(anchor = it) }
        }
        manageButton = manage
        contents.forEachIndexed { index, content ->
            val heading = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(9), dp(22), dp(18))
                val title = TextView(activity).apply {
                    text = activity.getString(titleIds[index]); textSize = 29f
                    setTypeface(typeface, Typeface.BOLD)
                    textColor = activity.getColor(R.color.colorTextDark)
                    androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                }
                addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                    if (index == 0) addView(manage, LinearLayout.LayoutParams(-2, -2).apply {
                        marginStart = dp(12)
                    })
                })
                addView(TextView(activity).apply {
                    text = activity.getString(descriptionIds[index]); textSize = 13f
                    textColor = activity.getColor(R.color.colorTextGray)
                    setPadding(0, dp(6), 0, 0)
                })
            }
            content.addView(heading, 0)
            scrolls[index].addView(content, ViewGroup.LayoutParams(-1, -2))
            pager.addView(scrolls[index], FrameLayout.LayoutParams(-1, -1))
            stretches += installStretch(scrolls[index]) { pager.selectedPage == index && pager.isSettled }
        }
        contents[0].addView(favoriteRows, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(15), 0, dp(15), 0)
        })
        val pageLayer = object : FrameLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = (View.MeasureSpec.getSize(widthMeasureSpec) - dp(64)).coerceAtLeast(0)
                navigation?.layoutParams?.width = minOf(available, dp(480))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            clipChildren = false
            clipToPadding = false
            backdropTarget.addView(pager, FrameLayout.LayoutParams(-1, -1))
            addView(backdropTarget, FrameLayout.LayoutParams(-1, -1))
        }
        shell.addView(pageLayer, LinearLayout.LayoutParams(-1, 0, 1f))
        // 底栏与顶部胶囊都是 pager 的兄弟，皮肤层可以安全地抓 pager 做透镜采样。
        skinContentSource(pager)
        val icons = intArrayOf(R.drawable.ic_favorites, R.drawable.ic_purify, R.drawable.ic_enhancement, R.drawable.ic_science)
        val dock = ModernNavigationBar(
            context = activity,
            titles = titleIds.map(activity::getString),
            icons = icons,
            colors = ModernNavigationColors(
                text = activity.getColor(R.color.colorTextGray),
                selectedText = activity.monetColors.primary,
                highlight = activity.monetColors.primary
            ),
            backgroundFactory = { surface, radius ->
                when (surface) {
                    ModernNavigationSurface.BAR -> activity.skinFloatingBackground(activity.monetColors.surface, radius)
                    ModernNavigationSurface.SELECTION -> activity.skinChromeOverlayBackground(activity.monetColors.surface, radius, selected = true)
                }
            },
            onSelect = { pager.selectPage(it) },
            onUserInteraction = ::userNavigated,
            onVisualMovement = skinPositionChanged
        )
        navigation = dock
        dock.tag = ElasticInteractionController.EXCLUDED_TAG
        pageLayer.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(32), 0, dp(32), dp(16))
        })
        dock.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                dockInset = bottom - top + dp(32)
                applyScrollInsets()
            }
        }
        // 顶部悬浮栏：长胶囊形，左右包住三枚按钮、上下收薄，与底部悬浮栏同款
        // 磨砂表面。内容上滚时从胶囊背后穿过被盖住，而不是在它下沿被截断。
        // 工具栏行保持透明，磨砂背景加在这层外壳上——弹性手势按"有表面的控件"
        // 提升形变组，背景若直接加在行上，三枚图标会被并成一个组、失去各自回弹。
        // 胶囊自身 isClickable：弹性控制器只把"能消费 DOWN 的命中控件"当作手势
        // 目标，长按拖动落在按钮以外的磨砂区域时，整条胶囊（含三枚按钮）一起
        // 形变位移；单击空白处无 listener，行为与之前透传给滚动页一致。
        // 按在某一枚按钮上时，命中在更深的子视图，该按钮仍是自己的形变组，
        // 各自的回弹不受影响。胶囊自身不参与朗读（子按钮自带描述）。
        val chrome = GlowFloatingChrome(backdropTarget, { activity.glowEngine }, ::edgeCoverage)
        floatingChrome = chrome
        chrome.attach(dock, GlowScrollEdge.BOTTOM, activity.getColor(R.color.colorTextGray)) { boost ->
            dock.setLegibility(boost, activity.monetColors.surface)
        }
        floatingToolbar?.let { toolbar ->
            toolbar.removeSelf()
            val header = FrameLayout(activity).apply {
                clipChildren = false
                clipToPadding = false
                // 58dp 高（48dp 按钮 + 上下各 5dp）配 29dp 圆角 = 正胶囊端头。
                background = activity.skinFloatingBackground(activity.monetColors.surface, 29f)
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(toolbar, FrameLayout.LayoutParams(-1, -2))
            }
            pageLayer.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
                setMargins(dp(10), dp(4) + systemTopInset, dp(10), 0)
            })
            this.header = header
            header.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    headerInset = bottom - top + dp(8)
                    applyScrollInsets()
                }
            }
            val icons = ArrayList<ImageView>()
            fun collectIcons(view: View) {
                if (view is ImageView) icons += view
                if (view is ViewGroup) for (i in 0 until view.childCount) collectIcons(view.child(i))
            }
            collectIcons(toolbar)
            // 胶囊本体上没有文字，图标都在三枚圆按钮里：补偿只加在圆按钮上，本体与底栏一样保持清透。
            // 加厚本体时亮背景下补偿封顶，整条胶囊被洗成一层灰（2026-09-27 真机：顶栏 luma 0.635 → boost 1.0）。
            chrome.attach(
                header, GlowScrollEdge.TOP, activity.getColor(R.color.colorTextGray),
                companions = icons, thickenHost = false, onForeground = toolbarForeground(icons)
            )
        }
        pager.onPageSelected = { index ->
            stretches.getOrNull(previousPage)?.let(finishStretch)
            previousPage = index
            dock.setSelectedPage(index)
        }
        pager.onMotionStarted = { stretches.forEach(finishStretch) }
        pager.onUserInteraction = { if (!revealingPage) userNavigated() }
        val restored = savedState?.getInt("settings_home_page", 0)?.coerceIn(0, 3) ?: 0
        revealingPage = true
        try { pager.selectPage(restored, false) } finally { revealingPage = false }
        previousPage = restored
        dock.setSelectedPage(restored)
        dock.setPageProgress(pager.pagePosition)
        scrolls.forEachIndexed { index, scroll ->
            val y = savedState?.getInt("settings_home_scroll_$index", 0) ?: 0
            if (y > 0 && index != 0) scroll.doOnNextLayout {
                if (!disposed && userNavigationGeneration == 0L) scroll.scrollTo(0, y)
            }
        }
        renderFavorites()
        SettingsFavoritesRepository.read(activity.applicationContext, this)
        installTopInset()
    }

    /**
     * 接管根布局的顶部安全边距（BetterAndroid 默认把 systemBars + 刘海整体写成根 padding，
     * 根的 clipToPadding 把内容裁在状态栏下沿）。左、右、下照旧缩进，底栏与导航栏行为不变。
     */
    private fun installTopInset() {
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safe.left, 0, safe.right, safe.bottom)
            if (systemTopInset != safe.top) {
                systemTopInset = safe.top
                (header?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    params.topMargin = dp(4) + systemTopInset
                    header?.layoutParams = params
                }
                applyScrollInsets()
            }
            insets
        }
        ViewCompat.requestApplyInsets(shell)
    }

    /** 两栏悬浮 chrome 的高度折算成滚动页内缩；任一侧变化只重设一次 padding。 */
    private fun applyScrollInsets() {
        val top = (if (headerInset > 0) headerInset else dp(8)) + systemTopInset
        scrolls.forEach { scroll ->
            if (scroll.paddingTop != top || scroll.paddingBottom != dockInset) {
                scroll.setPadding(0, top, 0, dockInset)
            }
        }
    }

    /**
     * 滚动边缘溶解的覆盖度：当前页（翻页途中按页位置插值）有多少内容已经滚进栏下。
     * 淡入行程取栏占用的内缩高度——静止时内容恰好从栏下沿开始，滚过一个栏高就满覆盖。
     */
    private fun edgeCoverage(edge: GlowScrollEdge): Float =
        GlowScrollEdgePolicy.pagerCoverage(pager.pagePosition, scrolls.size) { index ->
            val scroll = scrolls[index]
            when (edge) {
                GlowScrollEdge.TOP -> GlowScrollEdgePolicy.topCoverage(scroll.scrollY, headerInset.toFloat())
                GlowScrollEdge.BOTTOM -> {
                    val child = scroll.firstChildOrNull<View>()
                    val range = if (child == null) 0 else
                        (child.height - (scroll.height - scroll.paddingTop - scroll.paddingBottom)).coerceAtLeast(0)
                    GlowScrollEdgePolicy.bottomCoverage(scroll.scrollY, range, dockInset.toFloat())
                }
            }
        }

    /** 顶栏三枚图标的前景加强：原色取自安装时的 tint，0 档还原原对象。 */
    private fun toolbarForeground(icons: List<ImageView>): (Float) -> Unit {
        val originals = icons.map { it.imageTintList }
        var applied = 0
        return { boost ->
            val step = (boost.coerceIn(0f, 1f) * 32f).roundToInt()
            if (step != applied) {
                applied = step
                icons.forEachIndexed { index, icon ->
                    val original = originals[index]
                    icon.imageTintList = if (step == 0 || original == null) original
                        else ColorStateList.valueOf(GlowLegibilityPolicy.foreground(original.defaultColor, step / 32f))
                }
            }
        }
    }

    /** 顶部悬浮栏当前高度（px）；搜索定位要避开它，未布置或回退布局为 0。 */
    /** 滚动页顶端到顶部悬浮栏下沿（+8dp）的距离；页面层铺到窗口顶端，含状态栏高度。 */
    internal val currentHeaderInset: Int get() = headerInset + systemTopInset

    internal fun edit(operation: SettingsFavoritesRepository.Edit, after: (Boolean) -> Unit) {
        if (disposed || editing || state?.canWrite != true) return
        editing = true
        manageButton?.isEnabled = false
        afterEdit = after
        SettingsFavoritesRepository.edit(activity.applicationContext, operation, this)
    }

    override fun onFavoritesResult(result: SettingsFavoritesRepository.Result) {
        if (disposed || activity.isFinishing || activity.isDestroyed) return
        state = result.state
        val success = result.saved
        if (success == null) {
            renderFavorites()
            val y = savedState?.getInt("settings_home_scroll_0", 0) ?: 0
            if (y > 0) scrolls[0].doOnNextLayout {
                if (!disposed && userNavigationGeneration == 0L) scrolls[0].scrollTo(0, y)
            }
        } else {
            editing = false
            renderFavorites()
            val after = afterEdit
            afterEdit = null
            after?.invoke(success)
            if (!success) activity.toast(activity.getString(R.string.settings_favorites_save_failed))
        }
    }

    private fun userNavigated() {
        userNavigationGeneration++
        navigationChanged()
    }

    private fun renderFavorites() {
        peerDisposers.forEach { it() }; peerDisposers.clear()
        favoriteRows.removeAllViews()
        val snapshot = state
        manageButton?.isEnabled = snapshot != null && !editing
        val ids = snapshot?.ids.orEmpty()
        if (snapshot == null || !snapshot.canWrite || ids.isEmpty()) {
            favoriteRows.addView(TextView(activity).apply {
                text = activity.getString(when {
                    snapshot == null -> R.string.settings_favorites_loading
                    !snapshot.canWrite -> R.string.settings_favorites_unavailable
                    else -> R.string.settings_favorites_empty
                })
                textSize = 15f; textColor = activity.getColor(R.color.colorTextGray)
                setPadding(dp(18), dp(28), dp(18), dp(28))
            })
            return
        }
        ids.forEach { id ->
            val entry = entries.singleOrNull { it.id == id }
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = activity.skinCardBackground(activity.monetColors.surfaceVariant, 18f)
            }
            if (entry != null && entry.source.supportsFavoriteToggle) {
                val source = entry.source
                val peer = MaterialSwitch(activity, null).apply {
                    text = entry.title; textSize = 15f; isAllCaps = false
                    isSingleLine = false; maxLines = 3
                    minHeight = dp(48)
                    textColor = activity.getColor(R.color.colorTextGray)
                }
                var syncing = false
                fun sync() {
                    syncing = true
                    try { peer.isChecked = source.isChecked; peer.isEnabled = source.isEnabled }
                    finally { syncing = false }
                }
                sync()
                peerDisposers += source.observeState(::sync)
                peer.setOnCheckedChangeListener { _, checked ->
                    if (!syncing) { source.isChecked = checked; sync() }
                }
                card.addView(peer, LinearLayout.LayoutParams(-1, -2))
            } else {
                card.addView(TextView(activity).apply {
                    text = entry?.title ?: activity.getString(R.string.settings_favorites_missing, id)
                    textSize = 15f; textColor = activity.getColor(R.color.colorTextGray)
                    minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
                    if (entry != null) {
                        isFocusable = true
                        setOnClickListener {
                            activity.collectSettingsSearchTargets().firstOrNull { it.view === entry.source }
                                ?.let { activity.revealSettingsSearchTarget(it) }
                        }
                    }
                })
            }
            favoriteRows.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        activity.styleHomeControls(favoriteRows)
    }

    fun revealPageFor(view: View): NestedScrollView? {
        val index = contents.indexOfFirst { root ->
            var ancestor: View? = view
            while (ancestor != null && ancestor !== root) ancestor = ancestor.parent as? View
            ancestor === root
        }
        if (index < 0) return null
        revealingPage = true
        try { pager.selectPage(index, false) } finally { revealingPage = false }
        return scrolls[index]
    }

    fun saveState(out: Bundle) {
        out.putInt("settings_home_page", pager.selectedPage)
        scrolls.forEachIndexed { index, scroll -> out.putInt("settings_home_scroll_$index", scroll.scrollY) }
    }

    fun dispose() {
        disposed = true
        floatingChrome?.dispose()
        floatingChrome = null
        peerDisposers.forEach { it() }; peerDisposers.clear()
        stretches.forEach(finishStretch); stretches.clear()
        pager.onPageSelected = {}
        pager.onPositionChanged = {}
        navigation?.dispose()
        navigation = null
        pager.onMotionStarted = {}
        pager.onUserInteraction = {}
        afterEdit = null
    }
}
