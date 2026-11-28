package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标题/简介/评论三表面长按自由复制统一手势后端（`freeCopyGesture`）、触摸派发、
 * 宿主按压状态保护与标题扩展接线契约。
 */
class FreeCopyGestureAndTouchContractTest {
    private val gestureFiles = HookEntrySource.freeCopyScopeFiles()
    private fun source() = HookEntrySource.freeCopyAll()
    private fun detector() = SourceContract.read("hook/feature/FreeCopyGestureDetector.kt")
    private fun policy() = SourceContract.read("hook/feature/FreeCopyPressPolicy.kt")
    private fun codeOnly(text: String): String = HookEntrySource.code(text)
    private fun dispatchWindow() = HookEntrySource.function("handleDispatchTouchEvent", gestureFiles)

    @Test
    fun unifiedGestureStrategyPolicyClocksAndStrictAnchorsHoldAcrossSurfaces() {
        val codeAll = codeOnly(source())
        for (name in listOf("claimAtUp", "TITLE_THRESHOLD_EXTRA_MS", "UP_CLAIM_MARGIN_MS")) {
            assertFalse("HookEntry/FreeCopy 分卷不得再出现 $name", codeAll.contains(name))
            assertFalse("检测器不得再出现 $name", codeOnly(detector()).contains(name))
            assertFalse("策略不得再出现 $name", codeOnly(policy()).contains(name))
        }

        val threshold = HookEntrySource.function("freeCopyLongPressThreshold", gestureFiles)
        assertTrue(threshold.contains("FreeCopyPressPolicy.thresholdMs("))
        assertTrue(threshold.contains("ViewConfiguration.getLongPressTimeout()"))
        assertEquals("评论与简介/标题两个 begin 调用点", 2, Regex("""freeCopyGesture\.begin\(""").findAll(codeAll).count())
        assertEquals(2, Regex("""freeCopyHoldSlop\(v\)""").findAll(codeAll).count())
        val gestureDispatchAndRetarget = dispatchWindow() + "\n" +
            HookEntrySource.function("beginOrRetargetCommentTouch", gestureFiles)
        assertEquals(2, Regex("""freeCopyLongPressThreshold\(\)""").findAll(codeOnly(gestureDispatchAndRetarget)).count())

        val due = HookEntrySource.function("freeCopyDueDelayMs", gestureFiles)
        assertTrue(due.contains("FreeCopyPressPolicy.dueDelayMs("))
        assertTrue(due.contains("freeCopyGesture.downTime, freeCopyGesture.observedAtMs"))
        assertEquals("定义 1 + 评论 1 + 简介/标题 1", 3, Regex("""freeCopyDueDelayMs\(\)""").findAll(codeAll).count())
        assertFalse(policy().contains("import android"))
        assertTrue(policy().contains("const val LONG_PRESS_SLOP_FACTOR"))
        assertTrue(policy().contains("const val MAX_INPUT_LAG_CREDIT_MS"))

        val det = detector()
        val verdict = det.after("fun resolveTerminal(").before("fun reset()")
        assertTrue(verdict.contains("if (alreadyHandled) return if (isUp) Terminal.CONSUME else Terminal.HAND_TO_HOST"))
        assertTrue(verdict.contains("if (isUp && claim(downTime, eventTime)) return Terminal.CLAIM_AND_CONSUME"))
        assertTrue(verdict.contains("return Terminal.HAND_TO_HOST"))
        val claim = det.after("fun claim(").before("/** 终止事件")
        assertTrue(claim.contains("eventTime - downTime < thresholdMs"))
        assertFalse(claim.contains("MARGIN"))
        assertEquals(1, Regex("""phase = Phase\.HANDLED""").findAll(det).count())

        val schedule = det.after("fun schedule(").before("/** 取消")
        assertTrue(schedule.indexOf("if (!poster.post(") in 0 until schedule.indexOf("scheduledSessionId = sessionId"))

        val dispatch = dispatchWindow()
        assertTrue("评论 UP 事件时钟复核", dispatch.contains("freeCopyGesture.resolveTerminal("))
        assertTrue(dispatch.contains("ev.downTime, ev.eventTime"))
        assertTrue(dispatch.contains("FreeCopyGestureDetector.Terminal.CLAIM_AND_CONSUME"))
        val finish = HookEntrySource.function("finishDescLikeTouch", gestureFiles)
        assertTrue("简介/标题 UP 事件时钟复核", finish.contains("freeCopyGesture.resolveTerminal(ev.downTime, ev.eventTime, isUp, handled)"))
        assertEquals(1, Regex("""\bval freeCopyGesture[:\s]""").findAll(source()).count())
        assertEquals(1, Regex("""\bval freeCopyLongPressRunnable\b""").findAll(source()).count())

        assertTrue("缺失锚点必须失败，不能回退成整份文件", runCatching { source().after("this anchor does not exist anywhere in HookEntry") }.isFailure)
        assertTrue(runCatching { source().before("this anchor does not exist anywhere in HookEntry") }.isFailure)
        assertTrue("HookEntrySource.function 缺失同样必须失败", runCatching { HookEntrySource.function("thisFunctionDoesNotExistAnywhereInFreeCopy", gestureFiles) }.isFailure)
    }

    @Test
    fun commentAndDescriptionTouchDispatchCancellationAndBubbleClaimPreserveHostSemantics() {
        val dispatch = dispatchWindow()
        val down = dispatch.after("if (!isDescLikeTarget) {").after("if (action == MotionEvent.ACTION_DOWN) {")
            .before("root?.let { beginOrRetargetCommentTouch(v, it, ev) }")
        val cleanup = down.indexOf("clearCommentTouchSession(resetHandled = true)")
        val empty = down.indexOf("commentRootRefs.isEmpty()")
        val walk = down.indexOf("while (cur != null)")
        assertTrue(cleanup in 0 until empty)
        assertTrue(
            "the emptiness check is made under the root lock",
            down.contains("synchronized(FreeCopyBindScheduler.commentRootLock)") && down.contains("FreeCopyBindScheduler.commentRootRefs.isEmpty()")
        )
        assertTrue(empty in 0 until walk)
        assertTrue(down.substring(empty, walk).contains("return false"))

        val nonDownNonDesc = dispatch
            .after("if (!isDescLikeTarget) {")
            .after("root?.let { beginOrRetargetCommentTouch(v, it, ev) }")
            .after("return false")
            .before("MotionEvent.ACTION_DOWN -> {")
        assertFalse("MOVE path must not walk ancestors", nonDownNonDesc.contains("while (cur != null)"))
        assertFalse("MOVE path must not take commentRootLock", nonDownNonDesc.contains("commentRootLock"))
        assertTrue(dispatch.contains("commentMoreButtonPassthroughDownTime = ev.downTime"))
        assertTrue(
            dispatch.contains("FreeCopyTextExtractor.isCommentMoreButton(v) ||") &&
                dispatch.contains("FreeCopyTextExtractor.isCommentActionsContainer(v)")
        )

        val retarget = HookEntrySource.function("beginOrRetargetCommentTouch", gestureFiles)
        assertTrue(retarget.contains("freeCopyGesture.schedule(freeCopyGesture.sessionId, freeCopyDueDelayMs(), freeCopyPoster(handler))"))
        assertFalse("no per-level removeCallbacks/postDelayed of the long-press runnable", retarget.contains("removeCallbacks("))
        assertFalse(retarget.contains("postDelayed("))
        assertTrue(retarget.contains("commentTouchTargetRef = WeakReference(v)"))
        assertTrue(retarget.contains("commentTouchRootRef = WeakReference(root)"))

        val clearComment = HookEntrySource.function("clearCommentTouchSession", gestureFiles)
        assertTrue(clearComment.contains("freeCopyGesture.cancelSchedule("))
        assertFalse(clearComment.contains("removeCallbacks(commentLongPressRunnable)"))
        val runnable = HookEntrySource.property("freeCopyLongPressRunnable", gestureFiles)
        assertTrue(runnable.indexOf("freeCopyGesture.onFired()") in 0 until runnable.indexOf("val sessionId = freeCopyGesture.sessionId"))
        val totalDirectPosts = gestureFiles.sumOf { (_, raw) ->
            Regex("""postDelayed\(freeCopyLongPressRunnable""").findAll(raw).count()
        }
        assertEquals("the long-press runnable is only ever posted by the detector scheduler", 0, totalDirectPosts)

        val clearDesc = HookEntrySource.function("clearDescTouchSession", gestureFiles)
        assertTrue(clearDesc.contains("if (resetHandled)"))
        assertTrue(clearDesc.contains("freeCopyGesture.cancelSchedule("))
        assertFalse("清理路径不得取消宿主/框架的按压状态", clearDesc.contains("cancelLongPress"))
        val cancelDesc = HookEntrySource.function("cancelDescLongPressCandidate", gestureFiles)
        assertTrue(cancelDesc.contains("freeCopyGesture.cancelSchedule("))
        assertTrue(cancelDesc.contains("if (!FreeCopyTextExtractor.isTitleView(view)) runCatching { view.cancelLongPress() }"))
        assertEquals("取消候选里只有这一处 cancelLongPress", 1, Regex("""cancelLongPress\(\)""").findAll(cancelDesc).count())
        assertFalse(cancelDesc.contains("freeCopyGesture.reset()"))
        val gestureState = codeOnly(HookEntrySource.file("hook/feature/freecopy/FreeCopyGestureState.kt"))
        assertEquals(
            "自由复制会话只有这一处 cancelLongPress（简介让出给滑动）",
            1,
            Regex("""\.cancelLongPress\(\)""").findAll(gestureState).count()
        )
        val popup = HookEntrySource.function("showFreeCopyPopup", gestureFiles) { it.contains("rawContent: FreeCopyContent") }
        assertFalse(codeOnly(popup).contains("cancelLongPress"))

        val observer = HookEntrySource.function("observeFreeCopyTouch", gestureFiles)
        assertTrue(observer.contains("ACTION_POINTER_DOWN"))
        assertTrue(observer.contains("ACTION_POINTER_UP"))
        assertTrue(observer.contains("ACTION_CANCEL"))
        assertTrue(observer.contains("getHistoricalX(index, history)"))
        assertTrue(observer.contains("getHistoricalY(index, history)"))
        assertTrue(observer.contains("findPointerIndex(freeCopyGesture.pointerId)"))
        assertFalse(observer.contains("requestDisallowInterceptTouchEvent"))
        assertFalse(observer.contains("dispatchTouchEvent"))

        val listener = HookEntrySource.property("sharedFreeCopyListener", gestureFiles)
        assertTrue(listener.contains("!view.isAttachedToWindow || !view.isShown"))
        assertTrue(listener.contains("view.windowVisibility != View.VISIBLE"))
        assertTrue(listener.contains("freeCopyGesture.claim("))
        assertTrue("守卫 A 对 HANDLED 放行", listener.contains("descTouchedView !== view || (!freeCopyGesture.isPending && !freeCopyGesture.isHandled)"))
        assertTrue("守卫 B 已认领跳过二次 claim", listener.contains("!freeCopyGesture.isHandled &&"))
        assertTrue(runnable.contains("!freeCopyGesture.isPending"))
        assertTrue(runnable.contains("freeCopyGesture.claim("))
        assertTrue(runnable.contains("sharedFreeCopyListener.onLongClick(descTarget)"))

        assertTrue(dispatch.indexOf("observeFreeCopyTouch(ev)") < dispatch.indexOf("if (!isDescLikeTarget) {"))
        val description = dispatch.after("MotionEvent.ACTION_DOWN ->").after("freeCopyGesture.begin(")
        assertTrue(description.contains("freeCopyHoldSlop(v)"))
        assertTrue(description.contains("freeCopyLongPressThreshold()"))
        assertTrue(dispatch.contains("finishDescLikeTouch(ev, v)"))
        assertFalse(dispatch.contains("60f"))
        assertFalse(dispatch.contains("requestDisallowInterceptTouchEvent"))
        val slop = HookEntrySource.function("freeCopyHoldSlop", gestureFiles)
        assertTrue(slop.contains("scaledTouchSlop.toFloat()"))
        assertTrue(slop.contains("FreeCopyPressPolicy.holdSlop("))

        assertEquals("目标分支 + 祖先分支各一处", 2, Regex("""finishDescLikeTouch\(ev, """).findAll(codeOnly(dispatch)).count())
        val ancestor = dispatch.indexOf("finishDescLikeTouch(ev, null)")
        val commentTerminal = dispatch.indexOf("var handled = commentLongPressHandled")
        assertTrue(ancestor in 0 until commentTerminal)
        val descTerminal = dispatch.after("if ((descTouchedView != null || descLongPressHandled) &&")
            .before("if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled) return false")
        assertTrue("desc 终止清理按会话归属门控", descTerminal.contains("freeCopyGesture.matches(ev.downTime)"))
        assertTrue("仅终止事件走 desc 裁决", descTerminal.contains("ACTION_UP") && descTerminal.contains("ACTION_CANCEL"))
        assertTrue("祖先/容器收到终止事件走同一裁决", descTerminal.contains("return finishDescLikeTouch(ev, null)"))

        val installer = HookEntrySource.function("installDescriptionFreeCopyHooks", gestureFiles)
        assertTrue(
            "已认领的 UP 在祖先分支同样被消费",
            installer.contains("if (FreeCopyGestureState.handleDispatchTouchEvent(v, ev)) {\n                            this.result = true")
        )

        val finish = HookEntrySource.function("finishDescLikeTouch", gestureFiles)
        assertTrue(finish.contains("!freeCopyGesture.matches(ev.downTime) || (hitView != null && target !== hitView)"))
        assertTrue(finish.contains("return false"))
        assertTrue(finish.contains("return handled && isUp"))
        assertTrue(finish.indexOf("revokePrematurePopupIfShortTap(ev)") < finish.indexOf("resolveTerminal("))
        assertTrue(finish.indexOf("resolveTerminal(") < finish.indexOf("clearDescTouchSession(resetHandled = !handled)"))
        assertFalse("裁决函数不得碰宿主按压状态", codeOnly(finish).contains("cancelLongPress"))
        assertEquals("简介/标题（目标与祖先）经同一个裁决函数接入短按安全网", 1, Regex("""revokePrematurePopupIfShortTap\(ev\)""").findAll(finish).count())
        assertEquals("评论 UP 仍单独接入短按安全网", 1, Regex("""revokePrematurePopupIfShortTap\(ev\)""").findAll(dispatch).count())
        val revoke = HookEntrySource.function("revokePrematurePopupIfShortTap", gestureFiles)
        assertTrue("事件时长不足阈值才撤销", revoke.contains("ev.eventTime - ev.downTime >= freeCopyGesture.thresholdMs"))
        assertTrue("仅撤销仍在显示的自家气泡", revoke.contains("isOurBubbleShowing()"))

        val finishBubble = HookEntrySource.function("finishBubbleSession", gestureFiles)
        assertTrue(finishBubble.indexOf("activeBubbleSessionId != sessionId") < finishBubble.indexOf("freeCopyGesture.finishHandled()"))
        assertFalse(finishBubble.contains("freeCopyGesture.reset()"))
    }

    @Test
    fun titleFreeCopyStaysStartupOnlyPreservesHostClickAndSharesUnifiedChain() {
        val init = HookEntrySource.hookEntry()
            .after("val initialDescriptionFreeCopyEnabled =").before("// Modern API")
        assertTrue(init.contains("val initialTitleFreeCopyEnabled ="))
        assertTrue(init.contains("hookConfig.getBoolean(FeaturePreferences.FREE_COPY_TITLE_ENABLED, false)"))
        assertTrue(
            HookEntrySource.function("configureStartup", gestureFiles)
                .contains("runtimeTitleFreeCopyEnabled = titleEnabled")
        )
        val live = HookEntrySource.hookEntry().after("NPatchLiveConfig.registerHost(").before("frameworkLog(\"[BIL] 热应用端点不可用")
        assertFalse(live.contains("FREE_COPY_TITLE"))
        assertFalse(live.contains("runtimeTitleFreeCopyEnabled"))
        val liveConfig = HookEntrySource.function("updateLiveConfig", gestureFiles) {
            !it.contains("FreeCopyRuntimeConfig.updateLiveConfig(")
        }
        assertFalse(liveConfig.contains("runtimeTitleFreeCopyEnabled"))

        val descHooks = HookEntrySource.function("installDescriptionFreeCopyHooks", gestureFiles)
        val hooks = descHooks.after("val descTextHook:").before("sharedEnv.registrar.exact(")
        assertTrue(hooks.contains("FreeCopyBindScheduler.onDetailTextSet(v)"))
        assertEquals(2, hooks.split("block = descTextHook").size - 1)
        assertTrue(hooks.contains("for (cn in TitleFreeCopyPolicy.WIDGET_CLASS_NAMES)"))
        assertTrue(hooks.contains("if (!FreeCopyRuntimeConfig.descLikeFreeCopyEnabled) return@after"))
        val entry = HookEntrySource.function("onDetailTextSet", gestureFiles)
        assertTrue(
            entry.indexOf("runCatching { FreeCopyTextExtractor.onTitleCandidateTextSet(v) }") in
                0 until entry.indexOf("if (!FreeCopyRuntimeConfig.runtimeDescriptionFreeCopyEnabled) return")
        )
        assertTrue(entry.contains("applyFreeCopyListener(v, null, false)"))

        val register = HookEntrySource.function("onTitleCandidateTextSet", gestureFiles)
        assertTrue(register.contains("if (v.id != titleViewId) return"))
        assertTrue(register.contains("findViewById<View>(titleArrowId)"))
        assertTrue(register.contains("TitleFreeCopyPolicy.isTitleWidget(true, classChain, hasArrowSibling)"))
        assertTrue(register.contains("titleViews[v] = true"))
        val registerCode = codeOnly(register)
        for (forbidden in listOf(
            "applyTitleFreeCopyListener(",
            "setLongClickListenerNoHook",
            "setOnLongClickListener",
            "isLongClickable",
            "isClickable",
            "v.post",
            ".post {"
        )) {
            assertFalse("标题登记不得出现 $forbidden", registerCode.contains(forbidden))
        }
        val gate = HookEntrySource.function("isTitleView", gestureFiles)
        assertTrue(gate.indexOf("v.id == id") < gate.indexOf("titleViews.containsKey(v)"))
        val ids = HookEntrySource.function("resolveTitleViewIds", gestureFiles)
        assertTrue(ids.contains("if (title != 0 && arrow != 0)"))
        assertTrue(ids.indexOf("titleArrowId = arrow") < ids.indexOf("titleViewId = title"))

        val listener = HookEntrySource.property("sharedFreeCopyListener", gestureFiles)
        assertTrue(listener.contains("val isTitle = !isDescOnly && FreeCopyTextExtractor.isTitleView(view)"))
        assertTrue(listener.contains("val isDesc = isDescOnly || isTitle"))
        assertTrue(listener.contains("if (isTitle && !FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled) return@OnLongClickListener false"))
        assertTrue(listener.contains("FreeCopyContent(FreeCopyTextExtractor.extractTitleText(view))"))
        assertTrue(listener.contains("\"free_copy_title_enabled\""))
        val dispatch = dispatchWindow()
        assertTrue(dispatch.contains("val isTitleTarget = FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled && FreeCopyTextExtractor.isTitleView(v)"))
        val steal = descHooks.after("\"free-copy:shared:set-on-long-click\"").before("\"free-copy:shared:dispatch-touch\"")
        assertTrue(steal.contains("FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled &&") && steal.contains("FreeCopyTextExtractor.isTitleView(v)"))
        assertTrue(steal.contains("FreeCopyBindScheduler.applyTitleFreeCopyListener(v)"))
        assertEquals(1, Regex("""FreeCopyBindScheduler\.applyTitleFreeCopyListener\(v\)""").findAll(codeOnly(source())).count())

        val installerFile = HookEntrySource.file("hook/feature/freecopy/FreeCopyFeatureInstaller.kt")
        val install = HookEntrySource.function("install", listOf("FreeCopyFeatureInstaller.kt" to installerFile))
        assertTrue(install.contains("FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled ||\n            FreeCopyRuntimeConfig.descLikeFreeCopyEnabled"))
        assertTrue(install.contains("!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled &&\n                !FreeCopyRuntimeConfig.descLikeFreeCopyEnabled ->"))
        assertTrue(descHooks.contains("!FreeCopyRuntimeConfig.descLikeFreeCopyEnabled &&\n                        !FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled"))
        assertTrue(descHooks.contains("!FreeCopyRuntimeConfig.descLikeFreeCopyEnabled &&\n                            !FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled"))
        val publish = HookEntrySource.function("publishFreeCopyCapabilities", gestureFiles)
        assertTrue(publish.contains("if (FreeCopyRuntimeConfig.runtimeTitleFreeCopyEnabled)"))
        assertTrue(publish.contains("CAPABILITY_TITLE"))
        assertTrue(installerFile.contains("const val CAPABILITY_TITLE = \"free_copy_title_enabled\""))
        assertTrue(descHooks.after("free-copy:haptic:").before("free-copy:popup:").contains("descLikeFreeCopyEnabled &&"))

        val extract = HookEntrySource.function("extractTitleText", gestureFiles)
        assertTrue(extract.contains("titleOriginField(v.javaClass)"))
        assertTrue(extract.contains("stripSpanPlaceholderChars(it)"))
        assertTrue(extract.contains("TitleFreeCopyPolicy.choose(origin, visible)"))
        assertTrue(extract.contains("runCatching"))
    }
}
