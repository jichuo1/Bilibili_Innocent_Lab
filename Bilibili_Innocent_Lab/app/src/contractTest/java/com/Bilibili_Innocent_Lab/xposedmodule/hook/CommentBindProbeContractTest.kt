package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P2-04「依据实际工作量分批绑定」：探针保持默认关闭；预算组件保留 LIFO、
 * 队列上限、generation 与脱离窗口检查。单项游标仍需真实宿主数据证明必要性。
 */
class CommentBindProbeContractTest {
    private val scheduler = HookEntrySource.file("hook/feature/freecopy/FreeCopyBindScheduler.kt")
    private val probe = SourceContract.read("hook/feature/CommentBindProbe.kt")
    private val drain = HookEntrySource.function("drainCommentBinds", HookEntrySource.freeCopyScopeFiles())

    @Test fun probeIsOffByDefaultAndOnlyTheInstallPathTurnsItOn() {
        assertTrue(probe.contains("var enabled: Boolean = false"))
        val totalAssignments = HookEntrySource.freeCopyScopeFiles().sumOf { (_, raw) ->
            Regex("""commentBindProbe\.enabled\s*=""").findAll(HookEntrySource.code(raw)).count()
        }
        assertEquals("exactly one production assignment", 1, totalAssignments)
        val install = HookEntrySource.function("installCommentFreeCopyHooks", HookEntrySource.freeCopyScopeFiles())
            .after("val isInitialAttempt = commentFreeCopyHooksInstalled.compareAndSet(false, true)")
            .before("try {")
        assertTrue(install.contains("Log.isLoggable(CommentBindProbe.LOG_TAG, Log.DEBUG)"))
        assertTrue("a failed lookup leaves it off", install.contains(".getOrDefault(false)"))
        assertTrue("tag fits Log.isLoggable's 23-character limit", Regex("""LOG_TAG = "(\w+)"""").find(probe)!!.groupValues[1].length <= 23)
    }

    @Test fun disabledProbeNeverReadsTheClockOrTakesTheLock() {
        // 每个入口先检查 volatile 开关再做事。
        for (entry in listOf("fun stamp()", "fun onEnqueue(", "fun onDeferred(", "fun beginDrain()", "fun noteStale()", "fun noteDetached()", "fun beginItem()")) {
            val body = probe.after(entry).before("\n    /**")
            assertTrue("$entry checks enabled first", body.contains("if (!enabled)") || body.contains("if (enabled)"))
            assertFalse("$entry reads the clock only after the check", body.indexOf("clock.nanoTime()") in 0 until body.indexOf("enabled"))
        }
        assertFalse("per-node counting takes no lock", probe.after("fun countNode()").before("}").contains("synchronized"))
    }

    @Test fun nodeCountingIsGuardedByAFlagReadOncePerTree() {
        val apply = HookEntrySource.function("applyFreeCopyListener", HookEntrySource.freeCopyScopeFiles())
        assertTrue(apply.contains("val probing = commentBindProbe.enabled"))
        assertEquals(1, Regex("""commentBindProbe\.countNode\(\)""").findAll(apply).count())
        assertTrue(apply.contains("if (probing) commentBindProbe.countNode()"))
        assertTrue(apply.contains("if (probing) commentBindProbe.noteBindable(views.size)"))
        // 仍是一次收集 + 共享监听器，没有为测量多走一遍子树。
        assertEquals(1, Regex("""\n\s+collect\(root\)""").findAll(apply).count())
        assertTrue(apply.contains("setLongClickListenerNoHook(v, FreeCopyGestureState.sharedFreeCopyListener)"))
    }

    @Test fun everyDeferralGateReportsItsReasonBeforeRetrying() {
        for ((reason, delay) in listOf("DEFER_SCROLL" to "scheduleCommentBindRetry(120L)", "DEFER_TOUCH" to "scheduleCommentBindRetry(80L)", "DEFER_QUIET" to "scheduleCommentBindRetry(120L - quiet + 20L)")) {
            val note = drain.indexOf("commentBindProbe.onDeferred(CommentBindProbe.$reason)")
            assertTrue("$reason noted", note >= 0)
            assertEquals("$reason sits right before its retry", delay, drain.substring(note).lines()[1].trim())
        }
    }

    @Test fun drainMeasurementWrapsOnlyTheWorkAfterAllGates() {
        val begin = drain.indexOf("commentBindProbe.beginDrain()")
        val gates = drain.indexOf("bindDrainScheduled = false")
        assertTrue("timing starts after every deferral gate", gates in 0 until begin)
        assertTrue(drain.indexOf("DEFER_QUIET") < begin)
        val end = drain.indexOf("commentBindProbe.endDrain(drainToken, queueBefore, batch.size, queueAfter)")
        assertTrue(end > drain.indexOf("CommentBindBudget.run("))
        // 汇总日志在计时区间之外，且逐 item 不打日志。
        val report = drain.indexOf("commentBindProbe.takeReportIfDue(")
        assertTrue(report > end)
        assertEquals(1, Regex("""frameworkLog\(""").findAll(drain).count())
        assertFalse(drain.contains("logInfo(") || drain.contains("logError("))
    }

    @Test fun itemTimingCoversTheTreeWorkAndDroppedItemsAreCountedNotTimed() {
        val loop = drain.after("CommentBindBudget.run(").before("var queueAfter = 0")
        val stale = loop.before("if (v.isAttachedToWindow)")
        assertTrue(stale.contains("commentBindProbe.noteStale()") && stale.contains("return@run"))
        assertTrue(loop.indexOf("commentBindProbe.beginItem()") < loop.indexOf("applyFreeCopyListener(v, raw, checkReply)"))
        assertTrue(loop.indexOf("applyFreeCopyListener(v, raw, checkReply)") < loop.indexOf("commentBindProbe.endItem(itemToken, enqueuedAtNanos, validComment)"))
        val detached = loop.after("} else {").after("commentBindProbe.noteDetached()")
        assertTrue(detached.trimStart().startsWith("unregisterCommentRoot(v, expectedGeneration = generation)"))
    }

    @Test fun enqueueStampsAndCountsInsideTheExistingQueueLock() {
        val enqueue = HookEntrySource.function("scheduleCommentBind", HookEntrySource.freeCopyScopeFiles())
        assertTrue(enqueue.contains("val enqueuedAtNanos = commentBindClock.nanoTime()"))
        assertTrue(enqueue.contains("PendingCommentBind(view, rawText, checkReply, rawTrusted, commentItem)"))
        assertTrue(enqueue.contains("replacedBinds = queued.replaced") && enqueue.contains("overflowBinds = queued.overflowDropped"))
        assertTrue("probe is notified after the queue lock is released",
            enqueue.indexOf("commentBindProbe.onEnqueue(replacedBinds, overflowBinds, queuedAfter)") > enqueue.indexOf("queuedAfter = pendingCommentBinds.size"))
    }

    @Test fun budgetIntegrationKeepsTheExistingBindProtections() {
        // 单次树收集、共享 listener、滚动暂停、LIFO、96 项上限、generation 与脱离窗口检查保持不变。
        assertTrue(drain.contains("if (isAnyRecyclerViewScrolling()) {"))
        assertTrue(scheduler.contains("private val pendingCommentBinds = CommentBindQueue<PendingCommentBind>()"))
        assertTrue(drain.contains("pendingCommentBinds.takeBatch(drainStartedNs, commentBindBudgetPolicy.starvationLimitNs)"))
        assertTrue(drain.contains("latestCommentBindingGeneration[v] == generation"))
        assertTrue(drain.contains("if (v.isAttachedToWindow) {"))
        assertFalse("no item cursor without measured evidence", drain.contains("uptimeMillis() - drainStart") || drain.contains("deadline") || drain.contains("cursor"))
    }
}
