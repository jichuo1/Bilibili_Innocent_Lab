package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.LatestReceiptOutbox
import org.junit.Assert.*
import org.junit.Test

class ReceiptEnvelopeSequencerTest {
    private class Delivery {
        val sequences=hashMapOf<String,Long>()
        val stored=hashMapOf<String,String>()
        fun receive(channel: String, message: ReceiptEnvelopeSequencer.Stamped<String>): Boolean {
            if (!ReceiptSessionGate.isNewerSequence(sequences[channel] ?: 0L,message.sequence)) return false
            stored[channel]=message.value;sequences[channel]=message.sequence
            return true
        }
    }
    private fun delayedOldTransport(oldTransport: String, newTransport: String) {
        val sequencer=ReceiptEnvelopeSequencer();val registry=Delivery();val box=LatestReceiptOutbox<String>()
        var current="old"
        // 旧通道在生产排序点捕获不可变发布后暂停，此时网络/调度延迟任意长。
        val paused=sequencer.capture { current }
        current="new"
        val newer=sequencer.capture { current }
        box.put("scan",newer.sequence,3,newer.value)
        assertTrue(newTransport,registry.receive("scan",newer))
        assertFalse(oldTransport,registry.receive("scan",paused))
        assertEquals("new",registry.stored["scan"])
        // 迟到的旧 ACK 也不能移除尚未收到自身 ACK 的新发布。
        box.ack("scan",paused.sequence)
        assertEquals("new",box.snapshot()["scan"]?.value)
        box.ack("scan",newer.sequence);assertTrue(box.snapshot().isEmpty())
    }
    @Test fun pausedOldProviderCannotOverwriteOrAcknowledgeNewBoundPublication() = delayedOldTransport("provider","bound")
    @Test fun pausedOldBoundCannotOverwriteOrAcknowledgeNewProviderPublication() = delayedOldTransport("bound","provider")
    @Test fun ackFromAnEarlierSuccessfulDeliveryDoesNotClearTheNextQueuedValue() {
        val sequencer=ReceiptEnvelopeSequencer();val registry=Delivery();val box=LatestReceiptOutbox<String>()
        val first=sequencer.capture { "old" };assertTrue(registry.receive("scan",first))
        val next=sequencer.capture { "new" };box.put("scan",next.sequence,3,next.value)
        box.ack("scan",first.sequence);assertEquals("new",box.snapshot()["scan"]?.value)
        assertTrue(registry.receive("scan",next));assertEquals("new",registry.stored["scan"])
    }
    @Test fun globalPreparationOrderDoesNotCoupleDifferentChannels() {
        val sequencer=ReceiptEnvelopeSequencer();val registry=Delivery();val box=LatestReceiptOutbox<String>()
        val a=sequencer.capture { "A" };val b=sequencer.capture { "B" }
        box.put("a",a.sequence,1,a.value);box.put("b",b.sequence,1,b.value)
        assertTrue(registry.receive("b",b));assertTrue(registry.receive("a",a))
        box.ack("b",b.sequence);assertEquals("A",box.snapshot()["a"]?.value)
    }
    @Test fun anUncapturedPublicationCannotConsumeOrReuseASequence() {
        val sequencer=ReceiptEnvelopeSequencer()
        try { sequencer.capture<String> { error("snapshot failed") }; fail() } catch (_: IllegalStateException) { }
        val one=sequencer.capture { "one" };val two=sequencer.capture { "two" }
        assertEquals(1L,one.sequence);assertEquals(2L,two.sequence)
    }
}
