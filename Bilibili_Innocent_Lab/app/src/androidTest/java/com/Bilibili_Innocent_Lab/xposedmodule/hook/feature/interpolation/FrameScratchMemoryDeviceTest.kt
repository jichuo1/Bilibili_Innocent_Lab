package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameScratchMemoryDeviceTest {
    @Test fun managedScratchReleasesFailedSessionsAndKeepsInputsZero() {
        val size = 641 * 359 * 3
        lateinit var expired: FrameScratchArena
        assertThrows(IllegalArgumentException::class.java) {
            FrameScratchArena.withMemory(size, 4) { arena ->
                expired = arena
                arena.output(size).put(size - 1, 83)
                repeat(4) { index ->
                    val input = arena.input(index, size)
                    assertTrue(input.isDirect && input.isReadOnly)
                    assertEquals(0, input.get(size - 1).toInt())
                }
                throw IllegalArgumentException("interrupted probe")
            }
        }
        assertThrows(IllegalStateException::class.java) { expired.output(1) }
        FrameScratchArena.withMemory(size, 2) { arena ->
            assertEquals(0, arena.output(size).get(size - 1).toInt())
        }
    }
}
