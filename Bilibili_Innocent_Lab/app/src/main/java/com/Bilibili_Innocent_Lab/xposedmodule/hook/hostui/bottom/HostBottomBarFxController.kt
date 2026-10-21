package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeArtwork
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGlowView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostTouchGlowBinding

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostChromeAttachments
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import android.graphics.Outline
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** 宿主底栏装配与原生图标适配；材质交给 HostSurfaceScope，交互交给 DockLayer。 */
internal object HostBottomBarFxController {

    private val attachments = HostChromeAttachments()
    private val attachedHosts = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())

    fun attach(tabHost: ViewGroup, config: HostBottomBarFxConfig,
        onApplied: () -> Unit = {}, onMissing: () -> Unit = {},
        onError: (Throwable) -> Unit = { ModernHookLog.error("[BIL] 底栏装配失败", it) }) {
        if (!config.liquidGlass && !config.touchGlow) return
        attachments.post(tabHost, { attachInternal(tabHost, config) }, onApplied, onMissing, onError)
    }

    private fun attachInternal(tabHost: ViewGroup, config: HostBottomBarFxConfig): Boolean {
        if (tabHost in attachedHosts) return true
        if (!tabHost.isAttachedToWindow || tabHost.width <= 0 || tabHost.height <= 0) return false

        val context = tabHost.context
        val density = tabHost.resources.displayMetrics.density
        val theme = HostChromeTheme(context)
        val colors = theme.read()
        val isDark = colors.dark
        val palette = colors.palette()

        // 1. 查找底栏内原有构件
        val bgId = context.resources.getIdentifier("tab_background", "id", context.packageName)
        val divId = context.resources.getIdentifier("bottom_tab_divider", "id", context.packageName)
        val containerId = context.resources.getIdentifier("container", "id", context.packageName)

        val container = (if (containerId != 0) tabHost.findViewById<ViewGroup>(containerId) else null)
            ?: (0 until tabHost.childCount).map { tabHost.getChildAt(it) }
                .filterIsInstance<ViewGroup>().firstOrNull()

        // 小底栏和无字模式仅调整模块胶囊，不能把胶囊几何套到官方底栏。
        if (!config.liquidGlass) {
            if (config.touchGlow) HostTouchGlowBinding.attach(tabHost)
            attachedHosts.add(tabHost)
            return true
        }

        if (container == null || container.childCount == 0) return false
        val barHeight = (config.heightDp * density).roundToInt()
        val marginH = horizontalMarginPx(tabHost, container, config, density)
        val marginB = (12f * density).roundToInt()
        val inset = HostNavigationMotion.INSET_DP * density

        // 2. 悬浮胶囊几何形态与 Liquid Glass 外壳背景
        val backdrop = if (config.liquidGlass) HostSurfaceScope(tabHost, colors, appearance = config.appearance) else null
        if (config.liquidGlass) {
            val lp = tabHost.layoutParams
            if (lp != null) {
                lp.height = barHeight
                if ((config.liquidGlass || config.iconOnly) && lp is ViewGroup.MarginLayoutParams) {
                    lp.leftMargin = marginH
                    lp.rightMargin = marginH
                    if (config.liquidGlass) lp.bottomMargin = marginB
                }
                if (lp is FrameLayout.LayoutParams) {
                    lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                } else if (lp is CoordinatorLayout.LayoutParams) {
                    lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                tabHost.layoutParams = lp
            }
        }
        if (config.liquidGlass) {
            prepareGlassContainer(tabHost)

            // 应用模块浮动底栏原版 Liquid Glass 材质：常量色罩 + 实时透镜采样（模糊/折射底下的内容）
            backdrop?.floating(tabHost, isDark, config.heightDp / 2f)
        }

        val insetH = inset.roundToInt()

        // 3. 清除宿主分割线/背景、对齐内容：宿主会在按压、切页、重建 tab 时把官方背景重新挂回来，
        //    所以保留整树清理，但改成"按需触发 + 合并到一帧一次"。逐帧路径只剩 pre-draw 里的
        //    O(items) 空判与对齐（见 HostBottomBarDockLayer.preDrawListener），不再每帧全树递归。
        var sanitizePosted = false
        val sanitizeRunnable = Runnable {
            sanitizePosted = false
            if (!tabHost.isAttachedToWindow) return@Runnable
            if (config.liquidGlass) stripAllHostArtifacts(tabHost, isRoot = true)
            alignTabContent(tabHost, container, density, insetH, config, theme.read())
            // 冷启动时底栏还没有尺寸、找不到采样源；布局稳定后每次清理都顺手补一次定位（命中即 O(1)）。
            backdrop?.revalidate()
        }
        val requestSanitization = {
            if (!sanitizePosted && tabHost.isAttachedToWindow) {
                sanitizePosted = true
                tabHost.postOnAnimation(sanitizeRunnable)
            }
        }
        requestSanitization()

        tabHost.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = requestSanitization()
            override fun onViewDetachedFromWindow(v: View) {
                sanitizePosted = false
                tabHost.removeCallbacks(sanitizeRunnable)
            }
        })
        tabHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestSanitization() }


        // 4. 插入专属硬件加速指示滑块、柔光图层与实时透镜 (放在最底层)
        val dockLayer = HostBottomBarDockLayer(
            context, config, tabHost, container, palette, isDark, backdrop, requestSanitization,
            theme = theme
        )
        tabHost.addView(
            dockLayer,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // 5. 挂接统一全域触控手势
        tabHost.setOnTouchListener { _, event ->
            dockLayer.handleTouch(event, -1)
        }
        dockLayer.setOnTouchListener { _, event ->
            dockLayer.handleTouch(event, -1)
        }

        container?.let { c ->
            c.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestSanitization() }

            fun hookTabTouch() {
                requestSanitization()
                for (i in 0 until c.childCount) {
                    val tabItem = c.getChildAt(i)
                    tabItem.background = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        tabItem.foreground = null
                    }
                    (tabItem as? FrameLayout)?.foreground = null
                    tabItem.setOnTouchListener { _, event ->
                        dockLayer.handleTouch(event, i)
                    }
                }
            }
            hookTabTouch()
            c.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    // 宿主重建 tab 时内容层可能整体被替换：重找实时透镜的采样源。
                    backdrop?.revalidate()
                    hookTabTouch()
                }

                override fun onChildViewRemoved(parent: View?, child: View?) {
                    backdrop?.revalidate()
                    hookTabTouch()
                }
            })
        }
        attachedHosts.add(tabHost)
        return true
    }

    /** 材质接管时建立完整合成层；之后的宿主显示/隐藏动画仍由宿主管理。 */
    internal fun prepareGlassContainer(tabHost: View) {
        // 真机宿主容器可遗留 alpha=0.98。整层透明会重新混入清晰原图，
        // 即使模糊输入和 backdropOpacity 都为 1；透明感应由玻璃材质自身合成。
        tabHost.alpha = 1f
        tabHost.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
            }
        }
        tabHost.clipToOutline = true
        // 外侧软阴影由父级 overlay 绘制；平台 elevation 会从半透明玻璃内部透出灰边。
        tabHost.elevation = 0f
    }

    /**
     * id → 资源名缓存。
     *
     * `getResourceEntryName` 走 AssetManager 的资源表（加锁、每次返回新建 String），而同一 id 在进程内
     * 的名字恒定。宿主底栏的逐帧路径曾经每个节点查一次，是快速切页掉帧的来源之一；清理与对齐现在都
     * 按需触发，这里再做一层缓存，让偶发的整树扫描也不产生这批查询。
     */
    private val resourceNames = HashMap<Int, String>()
    private data class IconSize(var width: Int, var height: Int, var appliedWidth: Int = -1, var appliedHeight: Int = -1)
    private val iconSizes = WeakHashMap<View, IconSize>()

    private fun fitGarbIcons(group: ViewGroup, density: Float, config: HostBottomBarFxConfig, personalized: Boolean) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val name = resourceName(child, child.id)
            if (name == "tab_svg" || name == "tab_icon") {
                val lp = child.layoutParams ?: continue
                if (!personalized) {
                    val old = iconSizes.remove(child) ?: continue
                    if (lp.width == old.appliedWidth && lp.height == old.appliedHeight) {
                        lp.width = old.width; lp.height = old.height; child.layoutParams = lp
                    }
                    continue
                }
                if (lp.width <= 0 || lp.height <= 0) continue
                val size = iconSizes.getOrPut(child) { IconSize(lp.width, lp.height) }
                if (lp.width != size.appliedWidth || lp.height != size.appliedHeight) {
                    size.width = lp.width; size.height = lp.height
                }
                val maxHeight = minOf(if (config.iconOnly) 36f else 32f,
                    config.heightDp - if (config.iconOnly) 12f else 26f) * density
                val scale = minOf(1f, maxHeight / size.height, 48f * density / size.width)
                val width = (size.width * scale).roundToInt().coerceAtLeast(1)
                val height = (size.height * scale).roundToInt().coerceAtLeast(1)
                size.appliedWidth = width; size.appliedHeight = height
                if (lp.width != width || lp.height != height) {
                    lp.width = width; lp.height = height; child.layoutParams = lp
                }
            } else if (child is ViewGroup) fitGarbIcons(child, density, config, personalized)
        }
    }

    private fun resourceName(view: View, id: Int): String {
        if (id == 0 || id == View.NO_ID) return ""
        resourceNames[id]?.let { return it }
        val name = try {
            view.resources.getResourceEntryName(id)
        } catch (_: Exception) {
            ""
        }
        resourceNames[id] = name
        return name
    }

    /**
     * 垂直居中对齐底栏内容 (图标或图文组合居中于当前高度的底栏内)。
     * 消除文字过度靠下或图标独占居中的视觉失衡，完全还原模块导航栏的人机工程布局。
     */
    fun alignTabContent(
        tabHost: ViewGroup,
        container: ViewGroup?,
        density: Float,
        insetH: Int,
        config: HostBottomBarFxConfig = HostBottomBarFxConfig(),
        colors: HostChromeColors? = null
    ) {
        if (container == null || !config.liquidGlass) return
        if (config.iconOnly) {
            val lp = tabHost.layoutParams as? ViewGroup.MarginLayoutParams
            val marginH = horizontalMarginPx(tabHost, container, config, density)
            if (lp != null && (lp.leftMargin != marginH || lp.rightMargin != marginH)) {
                lp.leftMargin = marginH
                lp.rightMargin = marginH
                tabHost.layoutParams = lp
            }
        }
        val barHeight = tabHost.height.takeIf { it > 0 } ?: (config.heightDp * density).roundToInt()

        // 1. 宿主中间层包装容器 (例如包裹 divider 与 container 的 LinearLayout) 铺满并居中
        val contentParent = container.parent as? ViewGroup
        if (contentParent != null && contentParent != tabHost) {
            val clp = contentParent.layoutParams
            if (clp != null) {
                var changed = false
                if (clp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    clp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    changed = true
                }
                if (clp is FrameLayout.LayoutParams && clp.gravity != Gravity.CENTER) {
                    clp.gravity = Gravity.CENTER
                    clp.topMargin = 0
                    clp.bottomMargin = 0
                    changed = true
                }
                if (changed) contentParent.layoutParams = clp
            }
            contentParent.setPadding(0, 0, 0, 0)
            if (tabHost.width > 0 && barHeight > 0) {
                if (contentParent.top != 0 || contentParent.bottom != barHeight) {
                    contentParent.layout(0, 0, tabHost.width, barHeight)
                }
            }
        }

        // 2. container 左右内嵌 insetH，上下内边距清零，高度 MATCH_PARENT
        val lp = container.layoutParams
        if (lp != null) {
            var changed = false
            if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                changed = true
            }
            if (lp is ViewGroup.MarginLayoutParams && (lp.topMargin != 0 || lp.bottomMargin != 0)) {
                lp.topMargin = 0
                lp.bottomMargin = 0
                changed = true
            }
            if (changed) container.layoutParams = lp
        }
        container.setPadding(insetH, 0, insetH, 0)
        container.clipToPadding = false
        if (tabHost.width > 0 && barHeight > 0) {
            if (container.top != 0 || container.bottom != barHeight) {
                container.layout(0, 0, tabHost.width, barHeight)
            }
        }

        // 3. 遍历每一个 Tab 项，确保 tab 高度铺满，且内部 normal_ll (包含图标与文字) 整体垂直居中
        for (i in 0 until container.childCount) {
            val tab = container.getChildAt(i) as? ViewGroup ?: continue
            if (tab.visibility == View.GONE) continue
            if (config.liquidGlass) {
                if (tab.background != null) tab.background = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && tab.foreground != null) tab.foreground = null
                if (tab is FrameLayout && tab.foreground != null) tab.foreground = null
                if (tab.isPressed) tab.isPressed = false
            }
            tab.setPadding(0, 0, 0, 0)

            val tlp = tab.layoutParams
            if (tlp != null && tlp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                tlp.height = ViewGroup.LayoutParams.MATCH_PARENT
                tab.layoutParams = tlp
            }
            if (barHeight > 0 && (tab.top != 0 || tab.bottom != barHeight)) {
                tab.layout(tab.left, 0, tab.right, barHeight)
            }

            // 对齐 tab 内部承载图标和文字的布局
            for (j in 0 until tab.childCount) {
                val child = tab.getChildAt(j)
                val cId = child.id
                val name = resourceName(child, cId)

                if (name.contains("normal") || child is ConstraintLayout) {
                    if (child is ViewGroup) fitGarbIcons(child, density, config, colors?.garbAccent != null)
                    if (config.iconOnly && child is ViewGroup) {
                        hideTabLabels(child)
                        child.setPadding(0, 0, 0, 0)
                        if (child is LinearLayout) child.gravity = Gravity.CENTER
                    }
                    // normal_ll 必须是 WRAP_CONTENT，由内容自然决定高度
                    val clp = child.layoutParams
                    if (clp is FrameLayout.LayoutParams) {
                        var nlpChanged = false
                        if (clp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                            clp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            nlpChanged = true
                        }
                        if (clp.gravity != Gravity.CENTER) {
                            clp.gravity = Gravity.CENTER
                            nlpChanged = true
                        }
                        if (clp.topMargin != 0 || clp.bottomMargin != 0) {
                            clp.topMargin = 0
                            clp.bottomMargin = 0
                            nlpChanged = true
                        }
                        if (nlpChanged) child.layoutParams = clp
                    }
                    // 切页绑定会重新显示文字。pre-draw 中隐藏它以后，旧 measuredHeight
                    // 仍包含文字；必须当帧重测，否则图标先按图文高度上移，下一帧才居中。
                    val remeasured = (config.iconOnly || colors?.garbAccent != null) && child.isLayoutRequested && barHeight > 0
                    if (remeasured) {
                        val margins = clp as? ViewGroup.MarginLayoutParams
                        val parentWidth = tab.width.takeIf { it > 0 } ?: tab.measuredWidth
                        child.measure(
                            ViewGroup.getChildMeasureSpec(
                                View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
                                tab.paddingLeft + tab.paddingRight + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0),
                                clp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
                            ),
                            ViewGroup.getChildMeasureSpec(
                                View.MeasureSpec.makeMeasureSpec(barHeight, View.MeasureSpec.EXACTLY),
                                tab.paddingTop + tab.paddingBottom + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0),
                                clp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        )
                    }
                    val ch = child.measuredHeight.takeIf { it > 0 } ?: child.height
                    if (barHeight > 0 && ch > 0) {
                        val targetTop = ((barHeight - ch) / 2f).roundToInt()
                        if (remeasured || child.top != targetTop || child.bottom != targetTop + ch) {
                            child.layout(child.left, targetTop, child.right, targetTop + ch)
                        }
                    }

                    // 优化文字属性，去除字体上下溢出内边距并完全居中
                    if (child is ViewGroup) {
                        for (k in 0 until child.childCount) {
                            val subChild = child.getChildAt(k)
                            if (subChild is TextView) {
                                subChild.includeFontPadding = false
                                subChild.gravity = Gravity.CENTER
                                if (colors?.garbAccent != null) {
                                    val palette = colors.palette()
                                    val foreground = if (tab.isSelected) palette.primary else palette.textPrimary
                                    if (subChild.currentTextColor != foreground) subChild.setTextColor(foreground)
                                }
                            }
                        }
                    }
                } else if (name.contains("publish") || child.javaClass.simpleName.contains("Publish")) {
                    val plp = child.layoutParams as? FrameLayout.LayoutParams
                    if (plp != null && plp.gravity != Gravity.CENTER) {
                        plp.gravity = Gravity.CENTER
                        child.layoutParams = plp
                    }
                    val ch = child.measuredHeight.takeIf { it > 0 } ?: child.height
                    if (barHeight > 0 && ch > 0) {
                        val targetTop = ((barHeight - ch) / 2f).roundToInt()
                        if (child.top != targetTop || child.bottom != targetTop + ch) {
                            child.layout(child.left, targetTop, child.right, targetTop + ch)
                        }
                    }
                }
            }
        }
    }

    private fun horizontalMarginPx(
        tabHost: ViewGroup,
        container: ViewGroup?,
        config: HostBottomBarFxConfig,
        density: Float
    ): Int {
        val parent = tabHost.parent as? ViewGroup
        val parentWidth = parent?.width?.takeIf { it > 0 } ?: tabHost.resources.displayMetrics.widthPixels
        val availableWidth = parentWidth - (parent?.paddingLeft ?: 0) - (parent?.paddingRight ?: 0)
        val tabCount = container?.let { c ->
            (0 until c.childCount).count { c.getChildAt(it).visibility != View.GONE }
        } ?: 5
        return (config.horizontalMarginDp(availableWidth / density, tabCount) * density).roundToInt()
    }

    /** Only hide labels inside normal tab content; badge and publish overlays remain intact. */
    private fun hideTabLabels(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val name = resourceName(child, child.id)
            if (name.contains("badge") || name.contains("red") || name.contains("notify")) continue
            if (child is TextView) {
                if (child.visibility != View.GONE) child.visibility = View.GONE
            } else if (child is ViewGroup) {
                hideTabLabels(child)
            }
            if (child !is TextView) {
                val lp = child.layoutParams
                if (lp is ViewGroup.MarginLayoutParams && (lp.topMargin != 0 || lp.bottomMargin != 0)) {
                    lp.topMargin = 0
                    lp.bottomMargin = 0
                    child.layoutParams = lp
                }
                if (lp is ConstraintLayout.LayoutParams &&
                    (lp.topToTop != ConstraintLayout.LayoutParams.PARENT_ID ||
                        lp.bottomToBottom != ConstraintLayout.LayoutParams.PARENT_ID ||
                        lp.topToBottom != ConstraintLayout.LayoutParams.UNSET ||
                        lp.bottomToTop != ConstraintLayout.LayoutParams.UNSET)) {
                    lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    lp.topToBottom = ConstraintLayout.LayoutParams.UNSET
                    lp.bottomToTop = ConstraintLayout.LayoutParams.UNSET
                    lp.verticalBias = 0.5f
                    child.layoutParams = lp
                }
            }
        }
    }
    /** 彻底剥离宿主所有官方背景、分割线、并清除按压阴影残余 */
    fun stripAllHostArtifacts(view: View, isRoot: Boolean = true) {
        if (view is HostBottomBarDockLayer || view is HostGlowView) return

        val resName = resourceName(view, view.id)
        // 发布按钮整棵子树交给宿主管理：日夜底色、白色加号、远程皮肤和 SVGA 动画
        // 使用同一套原版换肤逻辑，不能只给普通加号重染或清掉它的 GradientDrawable。
        if (resName == "home_publish_icon" || resName == "publish_root" ||
            view.javaClass.simpleName == "HomeTabPublishView") return

        // 保留宿主 TabHost 的玻璃背景，清除其余子 View 的官方底色。
        if (!isRoot && view.background != null) {
            view.background = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && view.foreground != null) {
            view.foreground = null
        }
        (view as? FrameLayout)?.foreground = null
        if (view.isPressed) {
            view.isPressed = false
        }

        // 彻底移除官方分割线
        if (resName.contains("divider") || (view !is ViewGroup && (view.height in 1..4 || view.layoutParams?.height in 1..4))) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            if (view.alpha != 0f) view.alpha = 0f
            if (view.layoutParams?.height != 0) view.layoutParams?.height = 0
        }

        // 彻底隐藏官方底栏底图与非 icon 纯色遮罩
        if (HostChromeArtwork.isBackdrop(resName) || resName.contains("bg") && view !is ViewGroup) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            if (view.alpha != 0f) view.alpha = 0f
            (view as? android.widget.ImageView)?.setImageDrawable(null)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                stripAllHostArtifacts(view.getChildAt(i), isRoot = false)
            }
        }
    }
}
