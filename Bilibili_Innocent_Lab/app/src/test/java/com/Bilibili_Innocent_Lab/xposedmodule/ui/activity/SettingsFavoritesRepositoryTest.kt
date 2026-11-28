package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFavoritesRepositoryTest {

    private class ControlledExecutor : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun next() = pending.removeFirst().run()
    }

    @Test
    fun taskQueueOrdersQueuedMutationsReadersAndRecoveryAfterFailure() {
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

        // Multiple recreated readers cannot overtake an earlier mutation.
        val readerExecutor = ControlledExecutor()
        val readerQueue = SettingsFavoritesTaskQueue(readerExecutor)
        var version = 0
        val observed = mutableListOf<Int>()
        readerQueue.execute { version = 1 }
        readerQueue.execute { observed += version }
        readerQueue.execute { version = 2 }
        readerQueue.execute { observed += version }
        repeat(4) { readerExecutor.next() }
        assertEquals(listOf(1, 2), observed)
        assertTrue(readerExecutor.pending.isEmpty())

        // Failed task does not strand the next read.
        val failureExecutor = ControlledExecutor()
        val failureQueue = SettingsFavoritesTaskQueue(failureExecutor)
        var read = false
        failureQueue.execute { error("simulated failure") }
        failureQueue.execute { read = true }
        assertTrue(runCatching { failureExecutor.next() }.isFailure)
        failureExecutor.next()
        assertTrue(read)
    }
}
