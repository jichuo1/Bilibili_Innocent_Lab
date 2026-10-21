package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test

class HostChromeAttachmentTest {
    @Test fun `late host layout retries before reporting actual application once`() {
        val queue = ArrayDeque<Runnable>()
        var attempts = 0
        var applied = 0
        var missing = 0
        val attachment = HostChromeAttachment({ _, task -> queue.addLast(task); true },
            { ++attempts == 3 }, { applied++ }, { missing++ }, { throw AssertionError(it) })
        attachment.start()
        assertEquals(0, applied)
        repeat(2) { queue.removeFirst().run(); assertEquals(0, applied) }
        val final = queue.removeFirst()
        final.run(); final.run()
        assertEquals(3, attempts)
        assertEquals(1, applied)
        assertEquals(0, missing)
        assertTrue(queue.isEmpty())
    }

    @Test fun `unsupported layout stops after a bounded number of attempts`() {
        val queue = ArrayDeque<Runnable>()
        var attempts = 0
        var missing = 0
        HostChromeAttachment({ _, task -> queue.addLast(task); true }, { attempts++; false },
            { fail("unsupported layout was marked applied") }, { missing++ }, { throw AssertionError(it) },
            maxAttempts = 3).start()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(3, attempts)
        assertEquals(1, missing)
    }

    @Test fun `asynchronous attachment exceptions report failure without retrying or escaping`() {
        val queue = ArrayDeque<Runnable>()
        val failure = IllegalStateException("changed host structure")
        var observed: Throwable? = null
        HostChromeAttachment({ _, task -> queue.addLast(task); true }, { throw failure },
            { fail("failed layout was marked applied") }, { fail("exception is not missing layout") },
            { observed = it }).start()
        queue.removeFirst().run()
        assertSame(failure, observed)
        assertTrue(queue.isEmpty())
    }

    @Test fun `throwing scheduler reports failure and cannot leave an active attempt`() {
        val failure = IllegalStateException("detached queue")
        var reported: Throwable? = null
        HostChromeAttachment({ _, _ -> throw failure }, { fail("task was not posted"); false },
            { fail("failed scheduling was marked applied") }, { fail("failed scheduling is not missing layout") },
            { reported = it }).start()
        assertSame(failure, reported)
    }

    @Test fun `rejected scheduling terminates without claiming application`() {
        var missing = 0
        HostChromeAttachment({ _, _ -> false }, { fail("rejected task ran"); false },
            { fail("rejected task was marked applied") }, { missing++ }, { throw AssertionError(it) }).start()
        assertEquals(1, missing)
    }
}
