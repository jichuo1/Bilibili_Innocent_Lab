package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoadedModuleArtifactTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun recordsTheActualLoadedArtifactContent() {
        val file = folder.newFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LoadedModuleArtifact.capture(file.path)?.digest())
    }

    @Test fun replacementAndRemovalInvalidateTheCapturedArtifact() {
        val file = folder.newFile().apply { writeText("abc") }
        val initial = requireNotNull(LoadedModuleArtifact.capture(file.path))
        file.writeText("abd")
        assertTrue(file.setLastModified(initial.modified + 2_000))
        assertNull(initial.digest())
        val replaced = requireNotNull(LoadedModuleArtifact.capture(file.path))
        file.writeText("longer")
        assertNull(replaced.digest())
        assertTrue(file.delete()); assertNull(replaced.digest())
    }
}
