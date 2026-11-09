package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import io.github.jichuo1.meguviz.android.extensions.AndroidVersion
import io.github.jichuo1.meguviz.android.extensions.parentOrNull
import kotlin.math.max
import kotlin.math.sqrt

/** 悬浮栏共用的窗口判定。 */
internal object HostDockWindow {
    /** 窗口宽度达到中等宽度档（平板、折叠屏展开、横屏）即视为超宽，胶囊不再铺满。 */
    const val WIDE_WINDOW_DP = 600f
    const val SIDE_GAP_DP = 4f
}

/**
 * 悬浮底栏要避让的窗口区域，单位 dp，均以底栏所在容器为基准量出。
 *
 * [reservedBelowDp] 是容器下方已经被让出的窗口空间：系统导航栏背景或宿主自己留的实色条。
 * 宿主 khome 底栏的 Compose 内容不消费导航栏 inset（9.16.11 dexq 核对），所以白条只能在 View 层量。
 */
internal data class HostDockClearance(
    val reservedBelowDp: Float = 0f,
    val navigationDp: Float = 0f,
    val tappableDp: Float = 0f,
    val sideLeftDp: Float = 0f,
    val sideRightDp: Float = 0f
)

/**
 * 从真实窗口读避让区域；只读 insets 与位置，不注册、不替换宿主的 insets 监听。
 * 调用方多在宿主的预绘制与布局回调里，读取失败按没有避让处理，不能让宿主线程崩溃。
 */
internal object HostDockClearanceReader {
    /** 只在主线程读取；复用以免逐帧分配。 */
    private val origin = IntArray(2)
    private val sides = IntArray(2)

    /**
     * [container] 是底栏布局所在的容器（View 底栏的父布局、Compose 底栏的 ComposeView）。
     * [liftDp] 给出胶囊最终离容器底部的距离，圆角侵入量按胶囊下沿的高度取样。
     */
    fun read(container: View, liftDp: (HostDockClearance) -> Float): HostDockClearance =
        HostThreadGuard.call("host.dock.clearance", HostDockClearance()) { measure(container, liftDp) }

    /**
     * 顶栏用：刘海与侧边导航栏在 [container] 左右的侵入量（dp，取两侧较大者），扣除容器已让出的空间。
     * 顶栏胶囊在状态栏之下，顶部圆角在该高度几乎不侵入，不计。
     */
    fun sideDp(container: View): Float = HostThreadGuard.call("host.dock.side", 0f) {
        val insets = ViewCompat.getRootWindowInsets(container) ?: return@call 0f
        layoutOrigin(container, origin)
        readSides(insets, sides)
        val side = max(sides[0] - reservedLeft(container), sides[1] - reservedRight(container)).coerceAtLeast(0)
        side / container.resources.displayMetrics.density
    }

    /**
     * 逐帧入口用的几何指纹，零分配：指纹不变就不必重读。
     * ViewRootImpl 在 insets 变化前一直返回同一个实例，所以比较实例身份即可。
     */
    fun signature(container: View): Long {
        layoutOrigin(container, origin)
        val root = container.rootView
        var value = System.identityHashCode(container.rootWindowInsets).toLong()
        value = value * 31 + origin[0]
        value = value * 31 + origin[1]
        value = value * 31 + container.width
        value = value * 31 + container.height
        value = value * 31 + container.paddingLeft
        value = value * 31 + container.paddingRight
        value = value * 31 + container.paddingBottom
        value = value * 31 + root.width
        return value * 31 + root.height
    }

    private fun measure(container: View, liftDp: (HostDockClearance) -> Float): HostDockClearance {
        val insets = ViewCompat.getRootWindowInsets(container) ?: return HostDockClearance()
        val density = container.resources.displayMetrics.density
        layoutOrigin(container, origin)
        // 容器自身的底部内边距同样把底栏抬离窗口底边（宿主用 padding 让导航栏时）。
        val containerBottom = origin[1] + container.height - container.paddingBottom
        val reserved = (container.rootView.height - containerBottom).coerceAtLeast(0)
        val vertical = HostDockClearance(reserved / density,
            insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom / density,
            insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom / density)
        readSides(insets, sides)
        if (AndroidVersion.isAtLeast(AndroidVersion.S)) {
            HostDockCornerApi31.bottomIntrusion(container, containerBottom - liftDp(vertical) * density, sides)
        }
        return vertical.copy(sideLeftDp = (sides[0] - reservedLeft(container)).coerceAtLeast(0) / density,
            sideRightDp = (sides[1] - reservedRight(container)).coerceAtLeast(0) / density)
    }

    /** 刘海与侧边导航栏（横屏三键导航）的左右侵入量，像素。 */
    private fun readSides(insets: WindowInsetsCompat, out: IntArray) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        out[0] = max(cutout.left, bars.left)
        out[1] = max(cutout.right, bars.right)
    }

    /** 容器自己已让出的左右空间（宿主 fitsSystemWindows 时），不能再叠加一次。依赖 [layoutOrigin] 已写入 [origin]。 */
    private fun reservedLeft(container: View): Int = (origin[0] + container.paddingLeft).coerceAtLeast(0)

    private fun reservedRight(container: View): Int =
        (container.rootView.width - (origin[0] + container.width - container.paddingRight)).coerceAtLeast(0)

    /**
     * 容器在窗口中的布局原点。只累加 left/top 与滚动、不含 translation：宿主滑动隐藏底栏
     * 的位移动画不能被当成白条，否则底距会随动画逐帧跳动。
     */
    private fun layoutOrigin(container: View, out: IntArray) {
        var x = 0
        var y = 0
        var view = container
        while (true) {
            val parent = view.parentOrNull() ?: break
            x += view.left - parent.scrollX
            y += view.top - parent.scrollY
            view = parent
        }
        out[0] = x; out[1] = y
    }
}

internal object HostDockCorner {
    /** 圆心以下 [y] 处，圆弧距屏幕侧边的水平距离；圆心以上为 0。 */
    fun intrusion(radius: Float, centerY: Float, y: Float): Float {
        if (radius <= 0f || y <= centerY) return 0f
        val dy = (y - centerY).coerceAtMost(radius)
        return radius - sqrt(radius * radius - dy * dy)
    }
}
