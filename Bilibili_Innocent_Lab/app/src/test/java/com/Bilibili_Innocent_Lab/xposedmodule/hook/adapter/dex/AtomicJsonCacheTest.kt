package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtomicJsonCacheTest {

    fun `valid payload replaces cache`() {
        withTempDirectory { directory ->
            val target = File(directory, "adapter.json").apply { writeText("old") }

            assertTrue(AtomicJsonCache.write(target, "new") { it == "new" })
            assertEquals("new", target.readText())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".adapter.json.tmp-") })
        }
    }

    fun `rejected payload preserves previous cache`() {
        withTempDirectory { directory ->
            val target = File(directory, "adapter.json").apply { writeText("old") }

            assertFalse(AtomicJsonCache.write(target, "broken") { false })
            assertEquals("old", target.readText())
            assertTrue(directory.listFiles().orEmpty().none { it.name.startsWith(".adapter.json.tmp-") })
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("atomic-json-cache").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (AtomicJsonCacheTest) -> Unit) {
            val s = AtomicJsonCacheTest()
            block(s)
        }
        runIsolated { s -> s.`valid payload replaces cache`() }
        runIsolated { s -> s.`rejected payload preserves previous cache`() }
    }
}
