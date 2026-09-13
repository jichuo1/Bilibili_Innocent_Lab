package com.Bilibili_Innocent_Lab.xposedmodule.settings.remote
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test
class PublicationQueueTest {
    @Test fun aHungPhysicalWriterCannotAccumulateExpiredManualWork() {
        val queue=HostReceiptWire.executor("test-writer")
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            queue.execute { entered.countDown(); release.await() }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            var writes=0
            repeat(100) { i ->
                val attempt=RemotePublicationAttempt(i.toLong()+1,1,1,100,{1},{})
                val work=Runnable { if (attempt.isActive()) writes++ }
                attempt.onCancel { queue.remove(work) };queue.execute(work);attempt.cancel()
                assertEquals(0,queue.queue.size)
            }
            repeat(8) { queue.execute {} }
            try { queue.execute {};fail("must reject excess work") } catch (_: RejectedExecutionException) { }
            assertEquals(8,queue.queue.size);assertEquals(0,writes);assertEquals(1,queue.poolSize)
        } finally { release.countDown();queue.shutdown();assertTrue(queue.awaitTermination(2,TimeUnit.SECONDS)) }
    }
}
