package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P2-04「依据实际工作量分批绑定」：探针只测量，不改变 drainCommentBinds 的批大小、LIFO、
 * 队列上限、generation 与脱离窗口检查。没有真实宿主数据之前不引入 deadline 或游标。
 */
class CommentBindProbeContractTest {
    private val source = SourceContract.read("hook/HookEntry.kt")
    private val probe = SourceContract.read("hook/feature/CommentBindProbe.kt")
    private val drain = source.after("private fun drainCommentBinds() {").before("override fun onModuleLoaded(")

    @Test fun probeIsOffByDefaultAndOnlyTheInstallPathTurnsItOn() {
        assertTrue(probe.contains("var enabled: Boolean = false"))
        assertEquals("exactly one production assignment", 1, Regex("""commentBindProbe\.enabled\s*=""").findAll(source).count())
        val install = source.after("if (!commentFreeCopyHooksInstalled.compareAndSet(false, true)) return@installCommentHooks")
            .before("// 读版本适配缓存")
        assertTrue(install.contains("android.util.Log.isLoggable(CommentBindProbe.LOG_TAG, android.util.Log.DEBUG)"))
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
        val apply = source.after("private fun applyFreeCopyListener(").before("/** 缓存 RecyclerView.ViewHolder.itemView 字段")
        assertTrue(apply.contains("val probing = commentBindProbe.enabled"))
        assertEquals(1, Regex("""commentBindProbe\.countNode\(\)""").findAll(apply).count())
        assertTrue(apply.contains("if (probing) commentBindProbe.countNode()"))
        assertTrue(apply.contains("if (probing) commentBindProbe.noteBindable(views.size)"))
        // 仍是一次收集 + 共享监听器，没有为测量多走一遍子树。
        assertEquals(1, Regex("""\n\s+collect\(root\)""").findAll(apply).count())
        assertTrue(apply.contains("setLongClickListenerNoHook(v, sharedFreeCopyListener)"))
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
        assertTrue(end > drain.indexOf("for ((v, raw, checkReply, rawTrusted, commentItem, generation, enqueuedAtNanos) in batch)"))
        // 汇总日志在计时区间之外，且逐 item 不打日志。
        val report = drain.indexOf("commentBindProbe.takeReportIfDue(")
        assertTrue(report > end)
        assertEquals(1, Regex("""frameworkLog\(""").findAll(drain).count())
        assertFalse(drain.contains("logInfo(") || drain.contains("logError("))
    }

    @Test fun itemTimingCoversTheTreeWorkAndDroppedItemsAreCountedNotTimed() {
        val loop = drain.after("in batch) {").before("// 剩余排队")
        val stale = loop.before("// 仅绑定仍挂载的 view")
        assertTrue(stale.contains("commentBindProbe.noteStale()") && stale.contains("continue"))
        assertTrue(loop.indexOf("commentBindProbe.beginItem()") < loop.indexOf("applyFreeCopyListener(v, raw, checkReply)"))
        assertTrue(loop.indexOf("applyFreeCopyListener(v, raw, checkReply)") < loop.indexOf("commentBindProbe.endItem(itemToken, enqueuedAtNanos, validComment)"))
        val detached = loop.after("} else {\n                    commentBindProbe.noteDetached()")
        assertTrue(detached.trimStart().startsWith("unregisterCommentRoot(v, expectedGeneration = generation)"))
    }

    @Test fun enqueueStampsAndCountsInsideTheExistingQueueLock() {
        val enqueue = source.after("private fun scheduleCommentBind(").before("/** 主线程分帧批量绑定")
        assertTrue(enqueue.contains("val enqueuedAtNanos = commentBindProbe.stamp()"))
        assertTrue(enqueue.contains("view, rawText, checkReply, rawTrusted, commentItem, generation, enqueuedAtNanos"))
        assertTrue(enqueue.contains("replacedBinds++") && enqueue.contains("overflowBinds++"))
        assertTrue("probe is notified after the queue lock is released",
            enqueue.indexOf("commentBindProbe.onEnqueue(replacedBinds, overflowBinds, queuedAfter)") > enqueue.indexOf("queuedAfter = pendingCommentBinds.size"))
    }

    @Test fun measurementDidNotChangeTheExistingBindProtections() {
        // 单次树收集、共享 listener、滚动暂停、LIFO、96 项上限、generation 检查、批大小、脱离窗口检查。
        assertTrue(drain.contains("if (isAnyRecyclerViewScrolling()) {"))
        assertTrue(drain.contains("val take = minOf(pendingCommentBinds.size, 6)"))
        assertTrue(drain.contains("val start = pendingCommentBinds.size - take"))
        assertTrue(drain.contains("latestCommentBindingGeneration[v] == generation"))
        assertTrue(drain.contains("if (v.isAttachedToWindow) {"))
        assertTrue(source.contains("while (pendingCommentBinds.size > 96) {"))
        assertFalse("no deadline or cursor without measured evidence", drain.contains("uptimeMillis() - drainStart") || drain.contains("deadline") || drain.contains("cursor"))
    }
}
