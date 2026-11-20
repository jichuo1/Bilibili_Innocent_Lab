package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按自由复制识别策略（第三轮重设计）的源码契约。
 *
 * 钉住的是“为什么前两轮没修好”对应的几条不变量，不是实现细节：
 * 1. 标题与其他表面共用同一条判定线（系统长按线），没有按表面或按 UP 叠加的额外延时；
 * 2. 触摸清理路径不碰宿主的按压状态（不调 View.cancelLongPress()），标题登记不把控件改成可长按；
 * 3. UP/CANCEL 的裁决只有一个入口（finishDescLikeTouch / resolveTerminal），目标分支与祖先分支一致；
 * 4. 到期定时器与坡度都经 [FreeCopyPressPolicy] 计算，三表面同一套。
 * 锚点缺失一律失败（SourceContract.after/before），不允许单参数 substring 回退。
 */
class FreeCopyGestureStrategyContractTest {
    private fun source() = SourceContract.read("hook/HookEntry.kt")
    private fun detector() = SourceContract.read("hook/feature/FreeCopyGestureDetector.kt")
    private fun policy() = SourceContract.read("hook/feature/FreeCopyPressPolicy.kt")

    /** 去掉整行注释（// 与 KDoc 行）：契约只钉代码，不钉说明文字。 */
    private fun codeOnly(text: String): String = text.lineSequence().filterNot {
        val t = it.trimStart()
        t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }.joinToString("\n")

    private fun dispatchWindow() = source().after("// 简介触摸长按检测：").before("// 拦截官方「复制简介全文」实现")

    @Test fun noSurfaceOrUpSpecificDelayRemainsAnywhereInTheCode() {
        for (name in listOf("claimAtUp", "TITLE_THRESHOLD_EXTRA_MS", "UP_CLAIM_MARGIN_MS")) {
            assertFalse("HookEntry 不得再出现 $name", codeOnly(source()).contains(name))
            assertFalse("检测器不得再出现 $name", codeOnly(detector()).contains(name))
            assertFalse("策略不得再出现 $name", codeOnly(policy()).contains(name))
        }
        // 判定线只有一个来源：系统长按超时经策略取值，所有 begin() 调用点共用。
        val threshold = source().after("private fun freeCopyLongPressThreshold()").before("private fun freeCopyHoldSlop(")
        assertTrue(threshold.contains("FreeCopyPressPolicy.thresholdMs("))
        assertTrue(threshold.contains("ViewConfiguration.getLongPressTimeout()"))
        val begins = Regex("""freeCopyGesture\.begin\(""").findAll(codeOnly(source())).count()
        assertEquals("评论与简介/标题两个 begin 调用点", 2, begins)
        assertEquals(2, Regex("""freeCopyHoldSlop\(v\)""").findAll(codeOnly(source())).count())
        assertEquals(2, Regex("""freeCopyLongPressThreshold\(\)""").findAll(codeOnly(source().after("private fun freeCopyDueDelayMs()"))).count())
    }

    @Test fun dueDelayAndSlopComeFromThePolicyForEverySurface() {
        val due = source().after("private fun freeCopyDueDelayMs()").before("private fun revokePrematurePopupIfShortTap(")
        assertTrue(due.contains("FreeCopyPressPolicy.dueDelayMs("))
        val scheduleCalls = Regex("""freeCopyDueDelayMs\(\)""").findAll(codeOnly(source())).count()
        assertEquals("定义 1 + 评论 1 + 简介/标题 1", 3, scheduleCalls)
        // 策略本身：纯函数，不引用 Android 类型。
        assertFalse(policy().contains("import android"))
        assertTrue(policy().contains("const val LONG_PRESS_SLOP_FACTOR"))
        assertTrue(policy().contains("const val MAX_INPUT_LAG_CREDIT_MS"))
    }

    @Test fun terminalEventsHaveASingleVerdictEntryUsedByTargetAndAncestorBranches() {
        val dispatch = dispatchWindow()
        assertEquals("目标分支 + 祖先分支各一处", 2, Regex("""finishDescLikeTouch\(ev, """).findAll(codeOnly(dispatch)).count())
        // 祖先分支排在评论终止分支之前，且命中后直接返回，评论会话不会被 desc 裁决截断。
        val ancestor = dispatch.indexOf("finishDescLikeTouch(ev, null)")
        val commentTerminal = dispatch.indexOf("var handled = commentLongPressHandled")
        assertTrue(ancestor in 0 until commentTerminal)
        val ancestorBlock = dispatch.after("if ((descTouchedView != null || descLongPressHandled) &&")
            .before("if (!runtimeCommentFreeCopyEnabled) return@before")
        assertTrue(ancestorBlock.contains("if (finishDescLikeTouch(ev, null)) this.result = true"))
        assertTrue(ancestorBlock.contains("return@before"))

        val finish = source().after("private fun finishDescLikeTouch(").before("private var suppressOfficialUntilMs = 0L")
        // 交还宿主的条件都在这里：会话不匹配/目标不符 → 清理放行；UP 才可能消费。
        assertTrue(finish.contains("!freeCopyGesture.matches(ev.downTime) || (hitView != null && target !== hitView)"))
        assertTrue(finish.contains("return false"))
        assertTrue(finish.contains("return handled && isUp"))
        assertTrue(finish.indexOf("revokePrematurePopupIfShortTap(ev)") < finish.indexOf("resolveTerminal("))
        assertTrue(finish.indexOf("resolveTerminal(") < finish.indexOf("clearDescTouchSession(resetHandled = !handled)"))
        assertFalse("裁决函数不得碰宿主按压状态", codeOnly(finish).contains("cancelLongPress"))
    }

    @Test fun detectorVerdictConsumesOnlyAnUpAndNeverACancel() {
        val verdict = detector().after("fun resolveTerminal(").before("fun reset()")
        assertTrue(verdict.contains("if (alreadyHandled) return if (isUp) Terminal.CONSUME else Terminal.HAND_TO_HOST"))
        assertTrue(verdict.contains("if (isUp && claim(downTime, eventTime)) return Terminal.CLAIM_AND_CONSUME"))
        assertTrue(verdict.contains("return Terminal.HAND_TO_HOST"))
        // 认领只有一处实现，且仍以事件时钟比较阈值。
        val claim = detector().after("fun claim(").before("/** 终止事件")
        assertTrue(claim.contains("eventTime - downTime < thresholdMs"))
        assertEquals(1, Regex("""phase = Phase\.HANDLED""").findAll(detector()).count())
    }

    @Test fun terminalCleanupNeverTouchesTheHostPressStateAndTitlesAreNeverCancelledThroughIt() {
        val src = codeOnly(source())
        // 整个文件里 cancelLongPress() 的调用点：评论树的宿主交互不在此列，这里只数自由复制会话相关函数。
        val clear = codeOnly(source().after("private fun clearDescTouchSession(").before("* 让出给滑动时取消模块长按"))
        assertFalse(clear.contains("cancelLongPress"))
        val cancel = codeOnly(source().after("private fun cancelDescLongPressCandidate()").before("private fun observeFreeCopyTouch("))
        assertTrue(cancel.contains("if (!isTitleView(view)) runCatching { view.cancelLongPress() }"))
        assertEquals("自由复制会话只有这一处 cancelLongPress（简介让出给滑动）", 1,
            Regex("""\.cancelLongPress\(\)""").findAll(src).count())
        // 弹泡自身清理会话时（resetHandled=false）同样不碰按压状态。
        val popup = source().after("private fun showFreeCopyPopup(anchor: View, rawContent: FreeCopyContent)")
            .before("/** 评论日期文本特征")
        assertFalse(codeOnly(popup).contains("cancelLongPress"))
    }

    @Test fun titleKeepsTheHostTouchSemanticsBecauseTheModuleOnlyRecognisesLongPressItself() {
        // 标题登记不挂监听、不改可点击性：宿主容器点击展开（9.19：setEnableTouchToggle(false) + 容器 setOnClickListener）
        // 与标题自身点击监听（旧版本）都保持原样。
        val register = codeOnly(source().after("private fun onTitleCandidateTextSet(").before("private fun applyTitleFreeCopyListener("))
        assertTrue(register.contains("titleViews[v] = true"))
        for (forbidden in listOf("setLongClickListenerNoHook", "setOnLongClickListener", "isLongClickable", "isClickable", "v.post")) {
            assertFalse("标题登记不得出现 $forbidden", register.contains(forbidden))
        }
        // 夺回只发生在宿主自己给标题设了长按监听之后（官方 setOnLongClickListener 钩子）。
        val steal = source().after("\"setOnLongClickListener\",").before("// 简介触摸长按检测：")
        assertTrue(steal.contains("if (runtimeTitleFreeCopyEnabled && isTitleView(v))"))
        // 长按本身由 dispatchTouchEvent 钩子 + 到期 Runnable 识别，目标是标题时照常工作。
        val runnable = source().after("private val freeCopyLongPressRunnable: Runnable =").before("private fun freeCopyPoster(")
        assertTrue(runnable.contains("sharedFreeCopyListener.onLongClick(descTarget)"))
    }

    @Test fun anchorsAreStrictSoARelocatedBlockFailsInsteadOfWideningTheWindow() {
        val missing = runCatching { source().after("this anchor does not exist anywhere in HookEntry") }
        assertTrue("缺失锚点必须失败，不能回退成整份文件", missing.isFailure)
        val missingBefore = runCatching { source().before("this anchor does not exist anywhere in HookEntry") }
        assertTrue(missingBefore.isFailure)
    }
}
