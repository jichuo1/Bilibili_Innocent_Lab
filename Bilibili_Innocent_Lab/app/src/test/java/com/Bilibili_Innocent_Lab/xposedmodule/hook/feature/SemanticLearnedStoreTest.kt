package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SemanticLearnedStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `updates during an active save keep one consumer and persist the newest snapshot`() {
        val tasks = ArrayDeque<Runnable>()
        val file = File(temporary.root, "learned.txt")
        var snapshot = "old"
        val writes = mutableListOf<String>()
        lateinit var store: SemanticLearnedStore
        store = SemanticLearnedStore({ tasks.addLast(it); true }, { snapshot }) { target, text ->
            writes += text
            if (text == "old") {
                snapshot = "new"
                assertTrue(store.save())
                // Force the precise interleaving that formerly scheduled a second file writer.
                assertTrue(tasks.isEmpty())
            }
            SemanticLearnedStore.replaceAtomically(target, text)
        }
        assertTrue(store.attach(file) { true })
        assertTrue(store.save())
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(listOf("old", "new"), writes)
        assertEquals("new", file.readText())
        assertTrue(tasks.isEmpty())
    }

    @Test fun `failed writes retry within a bound and remain retryable on a later update`() {
        val tasks = ArrayDeque<Runnable>()
        val file = File(temporary.root, "learned.txt").apply { writeText("previous") }
        var healthy = false
        var attempts = 0
        val store = SemanticLearnedStore({ tasks.addLast(it); true }, { "latest" }) { target, text ->
            attempts++
            healthy && SemanticLearnedStore.replaceAtomically(target, text)
        }
        assertTrue(store.attach(file) { true })
        store.save()
        tasks.removeFirst().run()
        assertEquals(2, attempts)
        assertEquals("previous", file.readText())
        assertTrue(tasks.isEmpty())
        healthy = true
        store.save()
        tasks.removeFirst().run()
        assertEquals(3, attempts)
        assertEquals("latest", file.readText())
    }

    @Test fun `updates before and during loading wait for all disk sections to merge before saving`() {
        val tasks = ArrayDeque<Runnable>()
        val file = File(temporary.root, "learned.txt").apply { writeText("v old\nb old\nt old\n") }
        val memory = linkedSetOf("v new")
        val store = SemanticLearnedStore({ tasks.addLast(it); true }, { memory.joinToString("\n") })
        assertTrue(store.save())
        assertTrue(tasks.isEmpty())
        var reads = 0
        assertTrue(store.attach(file) { target ->
            reads++
            val disk = target.readLines()
            memory += disk[0]
            memory += "b new"
            assertTrue(store.save())
            assertTrue(tasks.isEmpty())
            // A repeated attach during the load must not start a second disk reader.
            assertFalse(store.attach(target) { reads++; true })
            assertEquals(1, reads)
            memory += disk.drop(1)
            true
        })
        assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        assertEquals(setOf("v new", "b new", "v old", "b old", "t old"), file.readLines().toSet())
        assertTrue(store.attach(file) { reads++; true })
        assertEquals(1, reads)
        assertTrue(tasks.isEmpty())
        val other = File(temporary.root, "other.txt")
        assertFalse(store.attach(other) { reads++; true })
        assertEquals(1, reads)
        assertFalse(other.exists())
    }

    @Test fun `attaching without new learning does not rewrite an existing file`() {
        val tasks = ArrayDeque<Runnable>()
        val file = File(temporary.root, "learned.txt").apply { writeText("old") }
        val store = SemanticLearnedStore({ tasks.addLast(it); true }, { "replacement" })
        var reads = 0
        assertTrue(store.attach(file) { reads++; true })
        assertTrue(store.attach(file) { reads++; true })
        assertEquals(1, reads)
        assertTrue(tasks.isEmpty())
        assertEquals("old", file.readText())
    }

    @Test fun `failed initialization preserves disk and pending updates until a successful retry`() {
        val tasks = ArrayDeque<Runnable>()
        val file = File(temporary.root, "learned.txt").apply { writeText("old") }
        var memory = "new"
        val store = SemanticLearnedStore({ tasks.addLast(it); true }, { memory })
        store.save()
        assertFalse(store.attach(file) { throw IOException("read failure") })
        assertTrue(tasks.isEmpty())
        assertEquals("old", file.readText())
        assertTrue(store.save())
        assertTrue(tasks.isEmpty())
        var wrongRootReads = 0
        assertFalse(store.attach(File(temporary.root, "other.txt")) { wrongRootReads++; true })
        assertEquals(0, wrongRootReads)
        assertTrue(tasks.isEmpty())
        assertTrue(store.attach(file) { memory += ":" + it.readText(); true })
        tasks.removeFirst().run()
        assertEquals("new:old", file.readText())
    }

    @Test fun `rejected save after loading is retried by repeated attach without rereading disk`() {
        val tasks = ArrayDeque<Runnable>()
        var reject = true
        val file = File(temporary.root, "learned.txt").apply { writeText("old") }
        val store = SemanticLearnedStore({ if (reject) false else { tasks.addLast(it); true } }, { "merged" })
        store.save()
        var reads = 0
        assertTrue(store.attach(file) { reads++; true })
        assertTrue(tasks.isEmpty())
        assertEquals("old", file.readText())
        reject = false
        assertTrue(store.attach(file) { reads++; true })
        assertEquals(1, reads)
        tasks.removeFirst().run()
        assertEquals("merged", file.readText())
    }

    @Test fun `atomic replacement updates an existing file and cleans temporary files`() {
        val file = File(temporary.root, "learned.txt").apply { writeText("old") }
        assertTrue(SemanticLearnedStore.replaceAtomically(file, "new"))
        assertEquals("new", file.readText())
        assertEquals(listOf("learned.txt"), temporary.root.list()?.toList())
    }

    @Test fun `failed final move preserves the previous file and cleans the incomplete replacement`() {
        val file = File(temporary.root, "learned.txt").apply { writeText("old") }
        assertFalse(SemanticLearnedStore.replaceAtomically(file, "new") { _, _ -> throw IOException("move failed") })
        assertEquals("old", file.readText())
        assertEquals(listOf("learned.txt"), temporary.root.list()?.toList())
    }
}
