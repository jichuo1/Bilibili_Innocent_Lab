package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页打断：按下不冻结进行中的动画（竖滑/轻点时动画照常走完），只有横向接手那一刻才从动画当前位置
 * 交给手指；文字链式联动只改绘制矩阵，不碰弹性交互与手风琴使用的 translation 属性。
 */
class SettingsPageInterruptionContractTest {
    private val pager = SourceContract.read("ui/activity/SettingsPagePager.kt")
    private val chain = SourceContract.read("ui/activity/SettingsPageTextChain.kt")

    @Test fun downDoesNotFreezeTheRunningAnimation() {
        val begin = pager.after("private fun beginGesture(event: MotionEvent) {").before("private fun protectedChildAt(")
        assertFalse(begin.contains("stopMotion()"))
        assertFalse(pager.contains("resumePausedMotion"))
        assertFalse(pager.contains("motionPaused"))
    }

    @Test fun horizontalTakeoverStartsFromTheCurrentAnimatedPosition() {
        val own = pager.after("private fun tryOwnGesture(event: MotionEvent) {").before("override fun onTouchEvent(")
        val takeover = own.after("gestureOwned = true")
        assertTrue(takeover.indexOf("stopMotion()") < takeover.indexOf("dragStart = position"))
        assertTrue(takeover.contains("downX = event.getX(pointer)"))
        assertTrue(takeover.contains("motionAnchorY = downY"))
    }

    @Test fun settleUsesVelocityMatchedHandoff() {
        val settle = pager.after("private fun settle(").before("private fun stopMotion()")
        assertTrue(settle.contains("SettingsPageMotionPolicy.handoffDuration(base, position, target.toFloat(), velocity)"))
    }

    @Test fun textChainOnlyTouchesTheDrawMatrixAndSkipsSurfacedControls() {
        assertFalse(chain.contains(".translationX ="))
        assertFalse(chain.contains(".translationY ="))
        assertTrue(chain.contains("view.animationMatrix = matrix"))
        assertTrue(chain.contains("if (view is EditText || (view is TextView && view !is CompoundButton && view.background != null)) return"))
        assertTrue(chain.contains("ElasticInteractionController.EXCLUDED_TAG"))
        val dispose = chain.after("fun dispose() {")
        assertTrue(dispose.contains("removeFrameCallback(frame)"))
        val home = SourceContract.read("ui/activity/SettingsHomePresenter.kt")
        assertTrue(home.contains("textChain?.onPositionChanged()"))
        assertTrue(home.after("fun dispose() {").contains("textChain?.dispose()"))
    }

    /** 文字不被行矩形、内边距或卡片边缘切断：放开中间容器与内边距裁剪，幅度按到卡片边缘的余量收紧，结束后恢复。 */
    @Test fun textNeverGetsClippedByRowsPaddingOrCardEdges() {
        assertTrue(chain.contains("val room = if (direction >= 0f) item.roomRight else item.roomLeft"))
        assertTrue(chain.contains("val goal = direction * minOf(room, limit) * share * bump"))
        assertTrue(chain.contains("private fun isClipBoundary(view: View): Boolean = view.clipToOutline"))
        assertTrue(chain.contains("if (it is ScrollView || it is NestedScrollView) return@forEach"))
        assertTrue(chain.contains("if (it.clipToPadding) relaxPadding += it"))
        assertTrue(chain.contains("if (boundary !== page && boundary is ViewGroup && boundary.clipToPadding) relaxPadding += boundary"))
        assertTrue(chain.contains("relax.remove(page as? ViewGroup)"))
        assertTrue(chain.contains("relaxPadding.remove(page as? ViewGroup)"))
        val release = chain.after("private fun release(index: Int) {").before("private fun isClipBoundary(")
        assertTrue(release.contains("relaxed[index]?.forEach { it.clipChildren = true }"))
        assertTrue(release.contains("paddingRelaxed[index]?.forEach { it.clipToPadding = true }"))
        assertTrue(chain.after("fun dispose() {").contains("release(index)"))
    }

    /**
     * 偏移由本段翻页进度决定：sin² 鼓包起止斜率为 0、到达目标页时归零（与切页同步）；链式顺序用进度相位错开，
     * 远近按离锚点最远的屏幕边缘归一。
     */
    @Test fun chainIsAProgressBumpPhasedByDistanceFromTheGesture() {
        assertTrue(chain.contains("val anchor = pager.motionAnchorY.takeIf { it.isFinite() } ?: height"))
        assertTrue(chain.contains("val span = maxOf(anchor, height - anchor, height * REACH_SPAN_MIN)"))
        assertTrue(chain.contains("val phase = progress.pow(skew)"))
        assertTrue(chain.contains("val bump = sin(PI.toFloat() * phase).let { it * it }"))
        assertTrue(chain.contains("if (!motionFrom.isFinite()) motionFrom = position.roundToInt().toFloat()"))
        assertFalse(chain.contains("velocity"))
        assertTrue(chain.contains("val icon = view is ImageView && view.background == null"))
    }
}
