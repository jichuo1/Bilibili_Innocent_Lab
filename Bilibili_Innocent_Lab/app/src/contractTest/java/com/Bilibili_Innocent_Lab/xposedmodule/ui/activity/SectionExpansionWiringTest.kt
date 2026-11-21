package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

/**
 * 手风琴分节动画的接线护栏（源码断言，与 AdaptiveGlowRenderGuardTest 同款思路）。
 *
 * 拦的是会**静默退化回旧观感**的写法：展开/收起绕开进度驱动器、
 * 动画帧不喂 Liquid 位移通知（位移表面会折射滞后底图）、收尾时 offsets 复位
 * 早于 `GONE` 布局落地（下层控件先回弹再被顶回——一帧跳变）。
 */
class SectionExpansionWiringTest {

    private fun source(relative: String): String= com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource.read(relative)

    fun sectionToggleDelegatesToTheProgressDrivenController() {
        val activity = source("ui/activity/MainActivity.kt")
        val body = activity.after("private fun animateSecondarySection")
            .before("override fun onStart")
        assertTrue("animateSecondarySection 必须委托 SectionExpansionController",
            body.contains("SectionExpansionController"))
        assertTrue("controller 必须吃到 Liquid 位移通知入口",
            body.contains("notifyPreparedSkinPositionChanged"))
    }

    fun controllerNotifiesTheRendererOnEveryFrame() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        val frameBody = controller.after("override fun onPreDraw(): Boolean")
            .before("private fun applyScrollFollow()")
        assertTrue("先落地本帧位移，再通知皮肤读取位置",
            frameBody.contains("for (notify in positionNotifications) notify()") &&
                frameBody.indexOf("offsets.commit()") <
                frameBody.indexOf("for (notify in positionNotifications) notify()"))
        assertTrue("同帧退休分节也要提交最后一次位置，不能只通知仍在运行的分节",
            frameBody.contains("for (controller in retired) positionNotifications.add(controller.notifyPositionChanged)"))
    }

    fun collapseDefersOffsetResetUntilLayoutLands() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        val finishBody = controller.after("private fun finish(")
            .before("/** 无动画直达")
        assertTrue("收尾必须把 content 置回 GONE",
            finishBody.contains("View.GONE"))
        assertTrue("GONE 触发的重布局要等布局生效帧——offsets 复位必须挂在 preDraw 上，" +
            "同帧清零会让兄弟控件先回弹再被布局顶回（2026-09-23 真机实证的下层跳变）",
            controller.contains("observer.addOnPreDrawListener(this)") && finishBody.contains("retiring = true"))
        assertTrue("反转撤回退休，旧收尾不能清掉新一轮共享贡献",
            controller.after("fun setExpanded(").before("private fun beginExpandSetup")
                .contains("retiring = false"))
        val preDraw = controller.after("override fun onPreDraw(): Boolean")
            .before("private fun applyScrollFollow()")
        assertTrue("必须先移除退休贡献，再读取剩余控制器的布局高度",
            preDraw.indexOf("controller.resetOffsets()") < preDraw.indexOf("val measuredHeight = controller.content.height"))
    }

    fun controllerDrivesCardClipAndSiblingGlideFromOneProgress() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        assertTrue("卡片下边缘必须由逐帧圆角 outline 驱动（矩形 clipBounds 会切成直边截断）",
            controller.contains("clipToOutline") && controller.contains("invalidateOutline"))
        assertTrue("兄弟控件必须走 translationY 滑行（而非逐帧 relayout）",
            controller.contains("offsets.follow(sibling.view, -controller.shrinkThrough(controller.ownShrink, sibling.rowLevels))") && controller.contains("view.translationY = state.originalY"))
        assertTrue("文字行必须由揭示沿级联显影",
            controller.contains("ExpansionMotionPolicy.rowReveal"))
        assertTrue("文字行必须随进度折叠位移（原地淡出不算跟动）",
            controller.contains("NestedExpansionPolicy.rowTop"))
        assertTrue("箭头转角必须由同一进度驱动",
            controller.contains("chevron.rotation"))
    }

    /**
     * 会变矮的**每一层**都要跟着收（2026-09-22 真机逐帧实证）。
     *
     * 兄弟控件靠 translationY 补偿，但祖先卡片自己的背景/描边画在布局矩形上：
     * 只裁分节卡时，外层卡片全程停在展开尺寸，收尾那一帧被布局一次性收掉——
     * 实测「净化进阶设置」收起动画第 1064 帧已静止，第 1069 帧单帧全屏差分 0.52
     * （与动画中段同量级），热区 y2666–3168 全在外层「净化」大卡的下边缘上。
     */
    fun everyShrinkingAncestorIsClippedAlongWithTheCard() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        val capture = controller.after("private fun captureActors()")
            .substringBefore("private fun clipLayer(", "MISSING")
        assertTrue("captureActors 必须能取到（函数名或结构变了就要同步这条护栏）",
            capture != "MISSING")
        assertTrue("祖先链上自己画东西的层必须进裁剪层列表",
            capture.contains("parent.background != null") && capture.contains("clipLayer(parent, rowModels.size)"))
        assertTrue("高度不是 wrap 的层不会变矮，必须在加它之前断链——裁它等于凭空切内容",
            capture.indexOf("ViewGroup.LayoutParams.WRAP_CONTENT") <
                capture.indexOf("if (parent !== card && parent.background != null)"))
        assertTrue("兄弟收集要从 content 起步，卡内位于 content 之后的控件同样要滑行",
            capture.contains("var node: View? = content"))

        val applyClip = controller.after("private fun applyClip(")
            .substringBefore("private fun applyScrollFollow(", "MISSING")
        assertTrue(applyClip != "MISSING")
        assertTrue("所有层共用同一个收缩量（内容高度的未完成部分），否则各层不同速",
            applyClip.contains("val shrink = ownShrink"))
        assertTrue("每层的收缩量必须登记到按 View 共用的接管权上",
            applyClip.contains("layer.takeover.press(layer.view, this, shrinkThrough(shrink, layer.rowLevels))"))
    }

    /**
     * 接管前的 outline 原值必须**按 View 只记一次**（2026-09-23 真机 logcat 实证）。
     *
     * 一张「进阶设置」大卡既是它自己那个分节的 card，又是它内部每个二级分节的 wrap
     * 祖先；两个相邻二级分节还共用它和页面大卡。原先每个控制器各存一份原值，后起步
     * 的那个就把**前一个控制器还挂着的 ClipLayer provider** 当成原值记下来，收尾时
     * 原样装回去——实测「增强进阶设置」卡真实高 4806 被还原成 `vis=2447` 的 provider
     * 裁住，页面大卡 6517 被裁到 4158，二级条目展开后直接从下部截断。而且自锁：
     * 下一轮 capture 读到的"原值"仍是这个假 provider，重启进程前每次展开都重演。
     */
    fun theOutlineOriginalIsRecordedOncePerViewAndRestoredByTheLastOwner() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        assertTrue("原值必须落在按 View 共用的登记表里，不能每个控制器各存一份",
            controller.contains("WeakHashMap<View, ClipTakeover>"))
        val takeover = controller.after("private class ClipTakeover(")
            .substringBefore("private var clipLayers", "MISSING")
        assertTrue(takeover != "MISSING")
        assertTrue("原值只在接管权创建时取一次",
            takeover.contains("OutlineState(view.outlineProvider, view.clipToOutline)"))
        assertTrue("各控制器分别登记自己的收缩量，合计后才是可见下沿——相加而不是互相覆盖",
            takeover.contains("shrinkByOwner") && takeover.contains("fullHeight - shrink"))
        assertTrue("只有最后一个占用者离开才还原原值",
            takeover.contains("if (shrinkByOwner.isEmpty())") &&
                takeover.contains("view.outlineProvider = original.provider"))
        assertTrue("新占用者加入时必须重取封顶高，否则按'它不存在'时的旧高度封顶",
            takeover.contains("fullHeight = view.height"))
        val clear = controller.after("private fun clearClip()")
            .substringBefore("private fun finish(", "MISSING")
        assertTrue(clear != "MISSING")
        assertTrue("交还时才把登记表项摘掉，且只摘无人占用的那一项",
            clear.contains("takeover.release(layer.view, this)) takeovers.remove(layer.view)"))
    }

    /**
     * 裁剪层的表面 Drawable 必须逐帧定界（2026-09-22 真机像素实测）。
     *
     * 只裁不定界时，玻璃表面仍按展开态矩形算边缘：下缘描边与菲涅尔/镜面 rim 全落在
     * 裁剪线之外，整段动画没有下缘光，落定重新定界才一次性出现——就是"边缘光效
     * 跳变加载"。实测 x=700 处底缘由平坦 26 变成 28→30→32 的描边梯度，左缘 44→40。
     * 与 2026-09-21（十一）承载层那条同源：正在被画的矩形必须交给 drawable。
     */
    fun clippedSurfacesAreReboundedToTheRectBeingDrawn() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        val takeover = controller.after("private class ClipTakeover(")
            .substringBefore("private var clipLayers", "MISSING")
        assertTrue(takeover != "MISSING")
        assertTrue("逐帧必须把裁剪矩形交给背景 drawable，否则整段动画缺下缘光",
            takeover.contains("view.background?.setBounds(0, 0, view.width, visibleBottom.roundToInt())"))
        assertTrue("还原时必须把定界交回布局矩形",
            takeover.contains("view.background?.setBounds(0, 0, view.width, view.height)"))
    }

    /**
     * 收起会缩短滚动范围，落定那一帧容器把 scrollY 钳回新上限——视角"停在原地、
     * 动画放完被瞬间拉上去"（2026-09-22 用户报告）。钳制必须提前摊到整条动画上。
     */
    fun theViewportRidesTheCollapseInsteadOfBeingClampedAtTheEnd() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        val follow = controller.after("private class ScrollFollow(")
            .substringBefore("private companion object", "MISSING")
        assertTrue(follow != "MISSING")
        assertTrue("共享视口终点按合成后的收缩量计算，不能重复扣子级",
            follow.contains("maximum - target") && controller.contains("target += controller.shrinkThrough(controller.targetShrink, Int.MAX_VALUE)"))
        assertTrue("滚动在同一个 preDraw 内统一写回，用户滚动后整组永久让位",
            follow.contains("SCROLL_YIELD_PX") && follow.contains("yielded = true"))
    }

    /**
     * 行显影沿取内容自己的已揭示高度，不能拿卡片裁剪线反算——两者差一个
     * `card.paddingBottom`，p=0 时第一行会残留一条半透明薄片（真机实测约 15px、
     * alpha≈0.23），收尾 GONE 时"啪"地消失。
     */
    fun theRevealEdgeIsMeasuredOnTheContentItself() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        val apply = controller.after("private fun apply(offsets: MotionOffsets)")
            .substringBefore("private fun applyClip(", "MISSING")
        assertTrue(apply != "MISSING")
        assertTrue("揭示沿必须扣除后代收缩，且不包含卡片内边距",
            apply.contains("val clipY = revealedHeight") &&
                controller.contains("height - nested - controller.ownShrink"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SectionExpansionWiringTest) -> Unit) {
            val s = SectionExpansionWiringTest()
            block(s)
        }
        runIsolated { s -> s.sectionToggleDelegatesToTheProgressDrivenController() }
        runIsolated { s -> s.controllerNotifiesTheRendererOnEveryFrame() }
        runIsolated { s -> s.collapseDefersOffsetResetUntilLayoutLands() }
        runIsolated { s -> s.controllerDrivesCardClipAndSiblingGlideFromOneProgress() }
        runIsolated { s -> s.everyShrinkingAncestorIsClippedAlongWithTheCard() }
        runIsolated { s -> s.theOutlineOriginalIsRecordedOncePerViewAndRestoredByTheLastOwner() }
        runIsolated { s -> s.clippedSurfacesAreReboundedToTheRectBeingDrawn() }
        runIsolated { s -> s.theViewportRidesTheCollapseInsteadOfBeingClampedAtTheEnd() }
        runIsolated { s -> s.theRevealEdgeIsMeasuredOnTheContentItself() }
    }
}
