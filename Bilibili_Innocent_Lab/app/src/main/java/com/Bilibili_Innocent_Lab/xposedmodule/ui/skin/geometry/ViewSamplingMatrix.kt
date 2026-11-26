package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry

import android.graphics.Matrix
import android.view.View
import androidx.annotation.MainThread
import java.util.IdentityHashMap

/**
 * Reusable local/screen/backdrop coordinate bridge, including ancestor matrices and window offsets.
 * Own one instance per renderer. It retains no Views and allocates nothing per call.
 * Uses public APIs available on API 27; View.transformMatrixToGlobal itself only became public in 29.
 */
@MainThread
internal class ViewSamplingMatrix {
    private val sourceGlobal = FloatArray(9)
    private val targetGlobal = FloatArray(9)
    private val result = FloatArray(9)
    private val inverse = FloatArray(9)
    private val step = FloatArray(9)
    private val screenOrigin = IntArray(2)

    /**
     * 祖先备忘：[withAncestorMemo] 期间按 View 记下全局矩阵，同一条祖先链上的每一层只算一次。
     *
     * 柔光透镜采集时一次录制要给底栏下方 6–8 张卡片各算一次静态磨砂映射，每次都从卡片一路走到根
     * （十几层 `getMatrix` + `getValues`，2026-09-23 打点每次 25–54µs），而这些卡片共享绝大部分
     * 祖先。备忘后每张卡只剩自己到最近已算祖先的那几层。
     */
    private var memoActive = false
    private val memo = IdentityHashMap<View, FloatArray>()
    private val memoPool = ArrayList<FloatArray>()
    private val chain = ArrayList<View>()

    /**
     * 在 [block] 期间启用祖先备忘。**只能包住期间没有任何 View 移动的区间**（例如透镜采集的一次
     * pre-draw 录制）；区间结束即清空，不跨帧保留任何 View。
     *
     * 与逐次计算的差别只在矩阵乘法的结合顺序（先乘好祖先前缀再乘后代）：静止时各层都是整数平移，
     * 结果逐位相同；动画中的小数平移至多差最后一位（约 1e-5 像素）。
     */
    fun <T> withAncestorMemo(block: () -> T): T {
        if (memoActive) return block()
        memoActive = true
        try {
            return block()
        } finally {
            memoActive = false
            memoPool.addAll(memo.values)
            memo.clear()
            chain.clear()
        }
    }

    fun localToScreen(view: View, out: Matrix): Boolean {
        if (!localToScreen(view, result)) return false
        out.setValues(result)
        return true
    }

    /** Float output is also suitable for a retained surface's complete transform footprint. */
    fun localToScreen(view: View, out: FloatArray): Boolean {
        if (!view.isAttachedToWindow) return false
        if (memoActive) return memoizedLocalToScreen(view, out)
        SamplingMatrixMath.identity(out)
        var current = view
        var depth = 0
        while (depth++ < 128) {
            // View.matrix includes translation, scale, rotation and their actual pivot.
            if (current.matrix.isIdentity) {
                SamplingMatrixMath.identity(step)
            } else {
                current.matrix.getValues(step)
                if (!SamplingMatrixMath.isFinite(step)) return false
            }
            val parent = current.parent as? View
            SamplingMatrixMath.translateAfter(step,
                (current.left.toLong() - (parent?.scrollX ?: 0)).toFloat(),
                (current.top.toLong() - (parent?.scrollY ?: 0)).toFloat())
            SamplingMatrixMath.multiply(step, out, out)
            if (parent == null) {
                if (!current.isAttachedToWindow) return false
                current.getLocationOnScreen(screenOrigin)
                return SamplingMatrixMath.alignRootToScreen(out, step, screenOrigin[0], screenOrigin[1])
            }
            current = parent
        }
        return false
    }

    /**
     * 自上而下：global(根) = 根对齐 · step(根)，global(子) = global(父) · step(子)。与
     * [localToScreen] 自下而上的累乘在数学上相同（`alignRootToScreen` 就是左乘一个平移）。
     */
    private fun memoizedLocalToScreen(view: View, out: FloatArray): Boolean {
        memo[view]?.let { cached ->
            cached.copyInto(out)
            return true
        }
        chain.clear()
        var anchor: FloatArray? = null
        var current: View = view
        while (true) {
            if (chain.size >= 128) return false
            val cached = memo[current]
            if (cached != null) {
                anchor = cached
                break
            }
            chain += current
            current = current.parent as? View ?: break
        }
        for (index in chain.indices.reversed()) {
            val node = chain[index]
            if (node.matrix.isIdentity) {
                SamplingMatrixMath.identity(step)
            } else {
                node.matrix.getValues(step)
                if (!SamplingMatrixMath.isFinite(step)) return false
            }
            val parent = node.parent as? View
            SamplingMatrixMath.translateAfter(step,
                (node.left.toLong() - (parent?.scrollX ?: 0)).toFloat(),
                (node.top.toLong() - (parent?.scrollY ?: 0)).toFloat())
            val global = if (memoPool.isEmpty()) FloatArray(9) else memoPool.removeAt(memoPool.lastIndex)
            if (parent == null) {
                if (!node.isAttachedToWindow) return false
                node.getLocationOnScreen(screenOrigin)
                step.copyInto(global)
                if (!SamplingMatrixMath.alignRootToScreen(global, step, screenOrigin[0], screenOrigin[1])) {
                    memoPool += global
                    return false
                }
            } else {
                val parentGlobal = if (index == chain.lastIndex) anchor else memo[parent]
                if (parentGlobal == null) {
                    memoPool += global
                    return false
                }
                SamplingMatrixMath.multiply(parentGlobal, step, global)
            }
            memo[node] = global
        }
        val resolved = memo[view] ?: return false
        resolved.copyInto(out)
        return true
    }

    fun sourceToTarget(source: View, target: View, out: Matrix): Boolean =
        compose(source, target, 1f, 1f, out)

    /**
     * 同一个源对多个目标：调用方先用 [localToScreen] 取一次源的全局矩阵，逐个目标只再走目标
     * 自己那条链。公式与 [sourceToTarget] 相同，结果逐位一致。
     */
    fun sourceToTarget(sourceGlobal: FloatArray, target: View, out: Matrix): Boolean {
        if (!localToScreen(target, targetGlobal) ||
            !SamplingMatrixMath.bitmapToTarget(sourceGlobal, targetGlobal, 1f, 1f, result, inverse)) return false
        out.setValues(result)
        return true
    }

    fun bitmapToTarget(sourceRoot: View, target: View, bitmapWidth: Int, bitmapHeight: Int, out: Matrix): Boolean {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || sourceRoot.width <= 0 || sourceRoot.height <= 0) return false
        return compose(sourceRoot, target, sourceRoot.width.toFloat() / bitmapWidth,
            sourceRoot.height.toFloat() / bitmapHeight, out)
    }

    private fun compose(source: View, target: View, scaleX: Float, scaleY: Float, out: Matrix): Boolean {
        if (!localToScreen(source, sourceGlobal) || !localToScreen(target, targetGlobal) ||
            !SamplingMatrixMath.bitmapToTarget(sourceGlobal, targetGlobal, scaleX, scaleY, result, inverse)) return false
        out.setValues(result)
        return true
    }
}
