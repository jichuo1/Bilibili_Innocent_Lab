package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FrameScratchArenaTest {
    private fun frameBytes(width: Int, height: Int) = width * height * 3

    @Test fun sessionAllocatesEachSlotOncePerCapacityHintAcrossAscendingSizes() {
        val arena = FrameScratchArena()
        val sizes = listOf(854 to 480, 640 to 360, 1280 to 720, 1920 to 1080, 2560 to 1440)
        val hint = sizes.maxOf { frameBytes(it.first, it.second) }
        for ((w, h) in sizes) {
            val bytes = frameBytes(w, h)
            arena.zeroed(0, bytes, hint); arena.zeroed(1, bytes, hint); arena.scratch(2, bytes, hint)
        }
        assertEquals(3, arena.allocations)
        assertEquals(3L * hint, arena.allocatedBytes)
        arena.zeroed(3, hint, hint); arena.scratch(4, hint, hint)
        assertEquals(5, arena.allocations)
        assertEquals(5L * hint, arena.allocatedBytes)
        assertEquals(5L * hint, arena.heldBytes)
    }

    @Test fun legacyPerCallAllocationsVersusOneSessionOfTheDefaultCapabilitySizes() {
        // 默认序列：WARM_SIZES + DEFAULT_CAPABILITY_SIZES 各 warm 一次（3 份），再对 3 个能力尺寸各测一档（5 份）。
        val warm = FrameInterpolationEngineHolder.WARM_SIZES + FrameInterpolationEngineHolder.DEFAULT_CAPABILITY_SIZES
        val capability = FrameInterpolationEngineHolder.DEFAULT_CAPABILITY_SIZES
        var legacyAllocations = 0
        var legacyBytes = 0L
        for ((w, h) in warm) { legacyAllocations += 3; legacyBytes += 3L * frameBytes(w, h) }
        for ((w, h) in capability) { legacyAllocations += 5; legacyBytes += 5L * frameBytes(w, h) }
        assertEquals(30, legacyAllocations)

        val arena = FrameScratchArena()
        val hint = warm.maxOf { frameBytes(it.first, it.second) }
        for ((w, h) in warm) {
            val bytes = frameBytes(w, h)
            arena.zeroed(0, bytes, hint); arena.zeroed(1, bytes, hint); arena.scratch(2, bytes, hint)
        }
        for ((w, h) in capability) {
            val bytes = frameBytes(w, h)
            repeat(4) { arena.zeroed(it, bytes, hint) }
            arena.scratch(4, bytes, hint)
        }
        assertEquals(5, arena.allocations)
        assertTrue("session bytes ${arena.allocatedBytes} < legacy $legacyBytes", arena.allocatedBytes < legacyBytes)
        assertEquals(5L * hint, arena.allocatedBytes)
    }

    @Test fun smallerRequestsReuseAndLargerRequestsReallocateOnlyThatSlot() {
        val arena = FrameScratchArena()
        val big = arena.scratch(0, 1000)
        val other = arena.scratch(1, 1000)
        assertEquals(2, arena.allocations)
        assertSame(big, arena.scratch(0, 10))
        assertEquals(2, arena.allocations)
        val grown = arena.scratch(0, 2000)
        assertNotSame(big, grown)
        assertEquals(3, arena.allocations)
        assertSame(other, arena.scratch(1, 1000))
        assertEquals(3000L, arena.heldBytes) // 旧的 1000 字节引用已被放掉
    }

    @Test fun slotsAreIndependentBuffers() {
        val arena = FrameScratchArena()
        val buffers = (0 until FrameScratchArena.MAX_SLOTS).map { arena.scratch(it, 64) }
        for (i in buffers.indices) for (j in i + 1 until buffers.size) assertNotSame(buffers[i], buffers[j])
        // 写一个槽不影响其它槽。
        buffers[0].put(0, 0x11); buffers[1].put(0, 0x22)
        assertEquals(0x11, buffers[0].get(0).toInt())
        assertEquals(0x22, buffers[1].get(0).toInt())
    }

    @Test fun reuseRestoresPositionLimitAndByteOrder() {
        val arena = FrameScratchArena()
        val first = arena.scratch(0, 100, 400)
        first.order(ByteOrder.LITTLE_ENDIAN)
        first.position(37)
        first.limit(80)
        val again = arena.scratch(0, 150)
        assertSame(first, again)
        assertTrue(again.isDirect)
        assertEquals(0, again.position())
        assertEquals(150, again.limit())
        assertEquals(400, again.capacity())
        assertEquals(ByteOrder.BIG_ENDIAN, again.order())
        // 后端要求 direct 且 position == 0 且 limit 足以容纳 (height-1)*stride + width*3。
        assertTrue(again.limit() >= 150)
    }

    @Test fun zeroedClearsStaleContentOfReusedBuffersButScratchDoesNot() {
        val arena = FrameScratchArena()
        val buffer = arena.scratch(0, 200_000)
        for (i in 0 until 200_000) buffer.put(i, 0x7f)
        val dirty = arena.scratch(0, 150_000)
        assertEquals(0x7f, dirty.get(149_999).toInt())
        val cleaned = arena.zeroed(0, 150_000)
        for (i in 0 until 150_000) assertEquals("byte $i", 0, cleaned.get(i).toInt())
        assertEquals(0, cleaned.position())
        assertEquals(150_000, cleaned.limit())
        // 范围之外不动。
        cleaned.limit(200_000)
        assertEquals(0x7f, cleaned.get(150_000).toInt())
        assertEquals(150_000L, arena.zeroedBytes)
    }

    @Test fun closeDropsEveryReferenceAndRejectsFurtherUse() {
        val arena = FrameScratchArena()
        repeat(FrameScratchArena.MAX_SLOTS) { arena.scratch(it, 1024) }
        assertTrue(arena.heldBytes > 0)
        arena.close()
        assertEquals(0L, arena.heldBytes)
        assertThrows(IllegalStateException::class.java) { arena.scratch(0, 16) }
    }

    @Test fun argumentsAndAllocatorAreValidated() {
        val arena = FrameScratchArena()
        assertThrows(IllegalArgumentException::class.java) { arena.scratch(-1, 16) }
        assertThrows(IllegalArgumentException::class.java) { arena.scratch(FrameScratchArena.MAX_SLOTS, 16) }
        assertThrows(IllegalArgumentException::class.java) { arena.scratch(0, 0) }
        assertThrows(IllegalArgumentException::class.java) { arena.scratch(0, FrameScratchArena.MAX_BYTES + 1) }
        assertFalse(FrameScratchArena.MAX_BYTES < 3840 * 2160 * 3)
        val heap = FrameScratchArena { ByteBuffer.allocate(it) }
        assertThrows(IllegalStateException::class.java) { heap.scratch(0, 16) }
        val tiny = FrameScratchArena { ByteBuffer.allocateDirect(it / 2) }
        assertThrows(IllegalStateException::class.java) { tiny.scratch(0, 16) }
    }
}
