package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FishAccountMetadataTest {
    @Test fun `only explicit matching public profile counts earn a bonus`() {
        val accounts = FishAccountMetadata()
        accounts.observeSpaceResponse(1, space("""{"mid":1,"attention":0,"fans":0}"""), 0)
        accounts.observeSpaceResponse(2, space("""{"mid":9,"attention":0,"fans":0,"likes":{"like_num":0}}"""), 0)
        accounts.observeSpaceResponse(3, space("""{"mid":3,"attention":0,"fans":0,"likes":{}}"""), 0)
        accounts.observeSpaceResponse(4, space("""{"mid":4,"attention":0,"fans":0,"likes":{"like_num":0}}"""), 0)
        accounts.observeSpaceResponse(5, space("""{"mid":"5","attention":0,"fans":5,"user_like":{"like_num":20}}"""), 0)
        assertEquals(mapOf(4L to 8, 5L to 6), accounts.snapshot)

        accounts.observeSpaceResponse(4, space("""{"mid":4,"attention":1}"""), 0)
        accounts.observeSpaceResponse(
            5, space("""{"mid":5}""", ""","setting":{"disable_following":true}"""), 0
        )
        assertTrue(accounts.snapshot.isEmpty())
    }

    @Test fun `score snapshots stay bounded and expire independently of future requests`() {
        var now = 0L
        val accounts = FishAccountMetadata(nowNanos = { now })
        for (uid in 1L..65L) {
            accounts.observeSpaceResponse(uid, space("""{"mid":$uid,"attention":0,"fans":0,"likes":{"like_num":0}}"""), 0)
        }
        val snapshot = accounts.snapshot
        assertEquals(64, snapshot.size)
        assertNull(snapshot[1L])
        accounts.observeSpaceResponse(65, space("""{"mid":65,"attention":0}"""), 0)
        assertSame(snapshot, accounts.snapshot)
        now += TimeUnit.MINUTES.toNanos(31)
        assertTrue(accounts.snapshot.isEmpty())
        assertEquals(8, snapshot[65L])
    }

    @Test fun `stream forwards each read and only reports a complete sequential body`() {
        val bytes = "abc中文".encodeToByteArray()
        var completed: ByteArray? = null
        val stream = FishAccountMetadata.ObservedStream(ByteArrayInputStream(bytes)) { completed = it }
        assertEquals(bytes[0].toInt(), stream.read())
        assertNull(completed)
        assertArrayEquals(bytes.copyOfRange(1, bytes.size), stream.readBytes())
        assertArrayEquals(bytes, completed)

        completed = null
        val reordered = FishAccountMetadata.ObservedStream(ByteArrayInputStream(bytes)) { completed = it }
        reordered.mark(bytes.size)
        reordered.read()
        reordered.reset()
        assertArrayEquals(bytes, reordered.readBytes())
        assertNull(completed)

        val oversized = ByteArray(256 * 1024 + 1)
        val large = FishAccountMetadata.ObservedStream(ByteArrayInputStream(oversized)) { completed = it }
        assertArrayEquals(oversized, large.readBytes())
        assertNull(completed)
    }

    private fun space(card: String, extra: String = "") = """{"code":0,"data":{"card":$card$extra}}"""
}
