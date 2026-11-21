package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

class SettingsFavoritesRepositoryTest {
    private class ControlledExecutor : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun next() = pending.removeFirst().run()
    }

    fun queuedAddRemoveAndMoveFinishBeforeRecreatedReadIncludingSaveFailure() {
        data class Case(
            val name: String,
            val change: (SettingsFavoritesState) -> SettingsFavoritesState,
            val expected: List<String>
        )
        val initial = SettingsFavoritesState(listOf("future", "A", "removed", "B", "C"))
        val cases = listOf(
            Case("add", { SettingsFavoritesPolicy.add(it, "new.favorite")!! },
                initial.ids + "new.favorite"),
            Case("remove", { SettingsFavoritesPolicy.remove(it, "B")!! },
                listOf("future", "A", "removed", "C")),
            Case("move", { SettingsFavoritesPolicy.move(it, "C", 2)!! },
                listOf("future", "A", "C", "removed", "B"))
        )
        for (case in cases) for (saveSucceeds in listOf(true, false)) {
            val label = "${case.name}, saved=$saveSucceeds"
            val executor = ControlledExecutor()
            val uiExecutor = ControlledExecutor()
            val queue = SettingsFavoritesTaskQueue(executor)
            var disk = initial
            var newActivityState: SettingsFavoritesState? = null
            var returnedSaved: Boolean? = null
            var oldActivityDisposed = false
            var oldUiDeliveries = 0
            queue.execute {
                val candidate = case.change(disk)
                // Persistence fake: a rejected commit retains its previous data, as the store promises.
                returnedSaved = saveSucceeds
                if (saveSucceeds) disk = candidate
                uiExecutor.execute { if (!oldActivityDisposed) oldUiDeliveries++ }
            }
            // Old edit has not reached storage. Recreation enqueues its read on the shared queue.
            oldActivityDisposed = true
            queue.execute {
                val snapshot = disk
                uiExecutor.execute { newActivityState = snapshot }
            }
            assertEquals(label, 1, executor.pending.size)
            assertNull(label, newActivityState)
            executor.next()
            assertEquals(label, saveSucceeds, returnedSaved)
            assertEquals(label, 1, executor.pending.size)
            executor.next()
            assertNull(label, newActivityState)
            assertEquals(label, 2, uiExecutor.pending.size)
            // Deliver the old completion late, after the new Activity has already read.
            uiExecutor.next()
            assertEquals(label, 0, oldUiDeliveries)
            uiExecutor.next()
            assertEquals(label, if (saveSucceeds) case.expected else initial.ids, newActivityState!!.ids)
            assertEquals(label, 0, oldUiDeliveries)
            assertTrue(label, executor.pending.isEmpty())
            assertTrue(label, uiExecutor.pending.isEmpty())
        }
    }

    fun multipleRecreatedReadersCannotOvertakeAnEarlierMutation() {
        val executor = ControlledExecutor()
        val queue = SettingsFavoritesTaskQueue(executor)
        var version = 0
        val observed = mutableListOf<Int>()
        queue.execute { version = 1 }
        queue.execute { observed += version }
        queue.execute { version = 2 }
        queue.execute { observed += version }
        repeat(4) { executor.next() }
        assertEquals(listOf(1, 2), observed)
        assertTrue(executor.pending.isEmpty())
    }

    fun failedTaskDoesNotStrandTheNextRead() {
        val executor = ControlledExecutor()
        val queue = SettingsFavoritesTaskQueue(executor)
        var read = false
        queue.execute { error("simulated failure") }
        queue.execute { read = true }
        assertTrue(runCatching { executor.next() }.isFailure)
        executor.next()
        assertTrue(read)
    }

    fun repositoryQueuesApplicationContextAndOperationDataWithWeakUiDelivery() {
        val repository = SettingsUiSource.file("SettingsFavoritesRepository")
        val presenter = SettingsUiSource.file("SettingsHomePresenter")
        val dialog = SettingsUiSource.function("showSettingsFavoritesDialog")
        assertTrue(repository.contains("val application = context.applicationContext"))
        assertTrue(repository.contains("val recipient = WeakReference(observer)"))
        assertTrue(repository.contains("queue.execute"))
        assertTrue(repository.contains("recipient.get()?.onFavoritesResult(result)"))
        assertFalse(presenter.contains("newSingleThreadExecutor"))
        assertFalse(presenter.contains("SettingsFavoritesStore."))
        assertFalse(dialog.contains("SettingsFavoritesStore."))
        assertTrue(presenter.contains("afterEdit = null"))
        assertTrue(presenter.contains("if (disposed || activity.isFinishing || activity.isDestroyed) return"))
    }

    fun userGesturesCancelPendingRestoreAndStretchWaitsForHorizontalSettling() {
        val presenter = SettingsUiSource.file("SettingsHomePresenter")
        val scroll = SettingsUiSource.file("SettingsHomeScrollView")
        assertTrue(presenter.contains("pager.selectedPage == index && pager.isSettled"))
        assertTrue(presenter.contains("pager.onMotionStarted = { stretches.forEach(finishStretch) }"))
        assertTrue(presenter.contains("pager.onUserInteraction = { if (!revealingPage) userNavigated() }"))
        assertTrue(presenter.contains("{ cancelPageRestore(index); userNavigated() }"))
        assertTrue(presenter.contains("{ cancelPageRestore(index); navigationTouched() }"))
        assertTrue(presenter.contains("scrollRestore.consume(ticket)"))
        assertFalse(presenter.contains("userNavigationGeneration == 0L"))
        val down = scroll.after("MotionEvent.ACTION_DOWN ->").before("MotionEvent.ACTION_MOVE ->")
        assertFalse(down.contains("onUserScroll()"))
        val move = scroll.after("MotionEvent.ACTION_MOVE ->").before("MotionEvent.ACTION_POINTER_UP ->")
        assertTrue(move.contains("dy > slop && dy > dx * 1.2f"))
        assertTrue(move.contains("onUserScroll()"))
        assertTrue(scroll.contains("return super.dispatchTouchEvent(event)"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SettingsFavoritesRepositoryTest) -> Unit) {
            val s = SettingsFavoritesRepositoryTest()
            block(s)
        }
        runIsolated { s -> s.queuedAddRemoveAndMoveFinishBeforeRecreatedReadIncludingSaveFailure() }
        runIsolated { s -> s.multipleRecreatedReadersCannotOvertakeAnEarlierMutation() }
        runIsolated { s -> s.failedTaskDoesNotStrandTheNextRead() }
        runIsolated { s -> s.repositoryQueuesApplicationContextAndOperationDataWithWeakUiDelivery() }
        runIsolated { s -> s.userGesturesCancelPendingRestoreAndStretchWaitsForHorizontalSettling() }
    }
}
