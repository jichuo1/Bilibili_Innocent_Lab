package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P2-04「依据实际工作量分批绑定」：生产 drain 复用经过伪时钟验证的预算与归还逻辑，
 * 探针保持默认关闭，队列维持 LIFO、96 项上限、generation 与脱离窗口检查。
 */
class FreeCopyBindBudgetAndProbeContractTest {
    private val scheduler get() = HookEntrySource.file("hook/feature/freecopy/FreeCopyBindScheduler.kt")
    private val probe get() = SourceContract.read("hook/feature/CommentBindProbe.kt")
    private val drain get() = HookEntrySource.function("drainCommentBinds", HookEntrySource.freeCopyScopeFiles())

    @Test
    fun bindQueueBudgetStarvationSelectionAndProtectionsRemainWired() {
        assertTrue(scheduler.contains("private val pendingCommentBinds = CommentBindQueue<PendingCommentBind>()"))
        assertTrue(scheduler.contains("pendingCommentBinds.enqueue("))
        assertFalse(scheduler.contains("private val pendingCommentBinds = java.util.ArrayList<PendingCommentBind>()"))

        assertTrue(drain.contains("if (isAnyRecyclerViewScrolling()) {"))
        assertTrue(drain.contains("pendingCommentBinds.takeBatch(drainStartedNs, commentBindBudgetPolicy.starvationLimitNs)"))
        assertTrue(drain.contains("CommentBindBudget.run("))
        assertTrue(drain.contains("pendingCommentBinds.giveBack(outcome.unprocessed)"))
        assertTrue(drain.contains("latestCommentBindingGeneration[v] == generation"))
        assertTrue(drain.contains("if (v.isAttachedToWindow) {"))
        assertFalse(
            "no item cursor without measured evidence",
            drain.contains("uptimeMillis() - drainStart") || drain.contains("deadline") || drain.contains("cursor")
        )

        val budget = SourceContract.read("hook/feature/CommentBindBudget.kt")
        val prioritized = budget.after("fun takeBatch(nowNanos: Long, starvationLimitNanos: Long)")
        assertTrue(prioritized.contains("entries.indexOfFirst"))
        assertTrue(prioritized.contains("entries.removeAt(starvedIndex)"))
        assertTrue(prioritized.contains("if (starvedIndex < 0) return takeBatch()"))
    }

    @Test
    fun bindProbeStaysOffByDefaultGuardsHotPathsAndIsolatesTiming() {
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
        assertTrue(
            "tag fits Log.isLoggable's 23-character limit",
            Regex("""LOG_TAG = "(\w+)"""").find(probe)!!.groupValues[1].length <= 23
        )

        for (entry in listOf(
            "fun stamp()", "fun onEnqueue(", "fun onDeferred(", "fun beginDrain()",
            "fun noteStale()", "fun noteDetached()", "fun beginItem()"
        )) {
            val body = probe.after(entry).before("\n    /**")
            assertTrue("$entry checks enabled first", body.contains("if (!enabled)") || body.contains("if (enabled)"))
            assertFalse("$entry reads the clock only after the check", body.indexOf("clock.nanoTime()") in 0 until body.indexOf("enabled"))
        }
        assertFalse("per-node counting takes no lock", probe.after("fun countNode()").before("}").contains("synchronized"))

        val apply = HookEntrySource.function("applyFreeCopyListener", HookEntrySource.freeCopyScopeFiles())
        assertTrue(apply.contains("val probing = commentBindProbe.enabled"))
        assertEquals(1, Regex("""commentBindProbe\.countNode\(\)""").findAll(apply).count())
        assertTrue(apply.contains("if (probing) commentBindProbe.countNode()"))
        assertTrue(apply.contains("if (probing) commentBindProbe.noteBindable(views.size)"))
        assertEquals(1, Regex("""\n\s+collect\(root\)""").findAll(apply).count())
        assertTrue(apply.contains("setLongClickListenerNoHook(v, FreeCopyGestureState.sharedFreeCopyListener)"))

        for ((reason, delay) in listOf(
            "DEFER_SCROLL" to "scheduleCommentBindRetry(120L)",
            "DEFER_TOUCH" to "scheduleCommentBindRetry(80L)",
            "DEFER_QUIET" to "scheduleCommentBindRetry(120L - quiet + 20L)"
        )) {
            val afterNote = drain.after("commentBindProbe.onDeferred(CommentBindProbe.$reason)")
            assertEquals("$reason sits right before its retry", delay, afterNote.lines()[1].trim())
        }

        val begin = drain.indexOf("commentBindProbe.beginDrain()")
        val gates = drain.indexOf("bindDrainScheduled = false")
        assertTrue("timing starts after every deferral gate", gates in 0 until begin)
        assertTrue(drain.indexOf("DEFER_QUIET") < begin)
        val end = drain.indexOf("commentBindProbe.endDrain(drainToken, queueBefore, batch.size, queueAfter)")
        assertTrue(end > drain.indexOf("CommentBindBudget.run("))
        val report = drain.indexOf("commentBindProbe.takeReportIfDue(")
        assertTrue(report > end)
        assertEquals(1, Regex("""frameworkLog\(""").findAll(drain).count())
        assertFalse(drain.contains("logInfo(") || drain.contains("logError("))

        val loop = drain.after("CommentBindBudget.run(").before("var queueAfter = 0")
        val stale = loop.before("if (v.isAttachedToWindow)")
        assertTrue(stale.contains("commentBindProbe.noteStale()") && stale.contains("return@run"))
        assertTrue(loop.indexOf("commentBindProbe.beginItem()") < loop.indexOf("applyFreeCopyListener(v, raw, checkReply)"))
        assertTrue(
            loop.indexOf("applyFreeCopyListener(v, raw, checkReply)") <
                loop.indexOf("commentBindProbe.endItem(itemToken, enqueuedAtNanos, validComment)")
        )
        val detached = loop.after("} else {").after("commentBindProbe.noteDetached()")
        assertTrue(detached.trimStart().startsWith("unregisterCommentRoot(v, expectedGeneration = generation)"))

        val enqueue = HookEntrySource.function("scheduleCommentBind", HookEntrySource.freeCopyScopeFiles())
        assertTrue(enqueue.contains("val enqueuedAtNanos = commentBindClock.nanoTime()"))
        assertTrue(enqueue.contains("PendingCommentBind(view, rawText, checkReply, rawTrusted, commentItem)"))
        assertTrue(enqueue.contains("replacedBinds = queued.replaced") && enqueue.contains("overflowBinds = queued.overflowDropped"))
        assertTrue(
            "probe is notified after the queue lock is released",
            enqueue.indexOf("commentBindProbe.onEnqueue(replacedBinds, overflowBinds, queuedAfter)") >
                enqueue.indexOf("queuedAfter = pendingCommentBinds.size")
        )
    }
}
