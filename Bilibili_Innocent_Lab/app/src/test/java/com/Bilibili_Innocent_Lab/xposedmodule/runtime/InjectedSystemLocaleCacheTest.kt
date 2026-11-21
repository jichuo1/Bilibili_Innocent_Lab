package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.LatestReceiptOutbox
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InjectedSystemLocaleCacheTest {
    fun `successful lookup is reused until system or module selection changes`() {
        val cache = InjectedSystemLocaleCache()
        var reads = 0
        assertEquals("zh-CN", cache.getOrLoad { reads++; "zh-CN" })
        assertEquals("zh-CN", cache.getOrLoad { reads++; "en" })
        assertEquals(1, reads)
        cache.invalidate()
        assertNull(cache.current)
        assertEquals("en", cache.getOrLoad { reads++; "en" })
        assertEquals(2, reads)
    }

    fun `failed platform read is not cached and can recover without a language event`() {
        val cache = InjectedSystemLocaleCache()
        assertNull(cache.getOrLoad { null })
        assertNull(cache.current)
        assertEquals("zh-Hant", cache.getOrLoad { "zh-Hant" })
    }

    fun `parallel readers share one successful platform read`() {
        val cache = InjectedSystemLocaleCache()
        val reads = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val readers = List(4) {
                pool.submit<String?> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    cache.getOrLoad { reads.incrementAndGet(); "en" }
                }
            }
            start.countDown()
            readers.forEach { assertEquals("en", it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, reads.get())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    fun `invalidation cannot be overwritten by an older in flight read`() {
        val cache = InjectedSystemLocaleCache()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidating = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val old = pool.submit<String?> {
                cache.getOrLoad {
                    reading.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    "en"
                }
            }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            val event = pool.submit {
                invalidating.countDown()
                cache.invalidate()
            }
            assertTrue(invalidating.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("en", old.get(5, TimeUnit.SECONDS))
            event.get(5, TimeUnit.SECONDS)
            assertNull(cache.current)
            assertEquals("zh-CN", cache.getOrLoad { "zh-CN" })
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (InjectedSystemLocaleCacheTest) -> Unit) {
            val s = InjectedSystemLocaleCacheTest()
            block(s)
        }
        runIsolated { s -> s.`successful lookup is reused until system or module selection changes`() }
        runIsolated { s -> s.`failed platform read is not cached and can recover without a language event`() }
        runIsolated { s -> s.`parallel readers share one successful platform read`() }
        runIsolated { s -> s.`invalidation cannot be overwritten by an older in flight read`() }
    
        InjectedUiLocaleCases().verifyAllCases()
        KavaMemberLookupFirstMatchCases().verifyAllCases()
        LatestValuePublisherCases().verifyAllCases()
        MineComponentSnapshotQueryContractCases().verifyAllCases()
        ReceiptEnvelopeSequencerCases().verifyAllCases()
    }
}

private class InjectedUiLocaleCases {

    fun normalizesOnlySupportedSelectionTags() {
        assertEquals(InjectedUiLocale.TAG_SYSTEM, InjectedUiLocale.normalizeSelectionTag(null))
        assertEquals(InjectedUiLocale.TAG_SYSTEM, InjectedUiLocale.normalizeSelectionTag("fr-FR"))
        assertEquals(InjectedUiLocale.TAG_ENGLISH, InjectedUiLocale.normalizeSelectionTag("en-US"))
        assertEquals(
            InjectedUiLocale.TAG_SIMPLIFIED_CHINESE,
            InjectedUiLocale.normalizeSelectionTag("zh-Hans-SG")
        )
    }

    fun mapsTraditionalScriptAndRegionsToOneStableTag() {
        listOf("zh-Hant", "zh-TW", "zh-HK", "zh-MO").forEach { rawTag ->
            assertEquals(
                InjectedUiLocale.TAG_TRADITIONAL_CHINESE,
                InjectedUiLocale.normalizeSelectionTag(rawTag)
            )
        }
    }

    fun returnsPrebuiltEnglishAndChineseSnapshotsWithoutContext() {
        assertEquals(
            "Trace",
            InjectedUiLocale.messages(
                context = null,
                explicitSelectionTag = InjectedUiLocale.TAG_ENGLISH
            ).replyTopologyEntryLabel
        )
        assertEquals(
            "脉络",
            InjectedUiLocale.messages(
                context = null,
                explicitSelectionTag = InjectedUiLocale.TAG_SIMPLIFIED_CHINESE
            ).replyTopologyEntryLabel
        )
        assertEquals(
            "脈絡",
            InjectedUiLocale.messages(
                context = null,
                explicitSelectionTag = InjectedUiLocale.TAG_TRADITIONAL_CHINESE
            ).replyTopologyEntryLabel
        )
    }

    fun sponsorReceiptAndShortUndoActionFollowTheSelectedLanguage() {
        val expected = listOf(
            Triple(InjectedUiLocale.TAG_ENGLISH, "Sponsor segment skipped", "Undo"),
            Triple(InjectedUiLocale.TAG_SIMPLIFIED_CHINESE, "已跳过商单", "撤销"),
            Triple(InjectedUiLocale.TAG_TRADITIONAL_CHINESE, "已略過商單", "復原")
        )
        expected.forEach { (tag, receipt, action) ->
            val messages = InjectedUiLocale.messages(context = null, explicitSelectionTag = tag)
            assertEquals(receipt, messages.sponsorSkipped)
            assertEquals(action, messages.sponsorUndoAction)
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (InjectedUiLocaleCases) -> Unit) {
            val s = InjectedUiLocaleCases()
            block(s)
        }
        runIsolated { s -> s.normalizesOnlySupportedSelectionTags() }
        runIsolated { s -> s.mapsTraditionalScriptAndRegionsToOneStableTag() }
        runIsolated { s -> s.returnsPrebuiltEnglishAndChineseSnapshotsWithoutContext() }
        runIsolated { s -> s.sponsorReceiptAndShortUndoActionFollowTheSelectedLanguage() }
    }
}

private class KavaMemberLookupFirstMatchCases {
    fun reset() { KavaMemberLookup.resetForTests() }

    private open class Base {
        @Suppress("unused") protected var inheritedValue = 1
        @Suppress("unused") fun baseCall(value: Int) = value
    }

    private class Sample : Base() {
        @Suppress("unused") var alpha = 1
        @Suppress("unused") var beta = ""
        @Suppress("unused") var gamma = 2L
        @Suppress("unused") fun read() = alpha
        @Suppress("unused") fun read(value: Int) = value
        @Suppress("unused") fun read(value: String) = value
        @Suppress("unused") fun write(value: Int) = value
        @Suppress("unused") private fun hidden() = 1
    }

    fun firstMethodIsTheFirstElementOfTheFullFilteredList() {
        val owners = listOf(Sample::class.java, Base::class.java, java.util.ArrayList::class.java, String::class.java, Object::class.java)
        val predicates = listOf<(Method) -> Boolean>(
            { true }, { it.name == "read" }, { it.parameterCount == 1 }, { it.name.startsWith("w") },
            { it.returnType == Integer.TYPE }, { it.name == "missing" }, { it.parameterCount == 2 }
        )
        for (owner in owners) for (inherit in listOf(false, true)) for (makeAccessible in listOf(false, true)) for (predicate in predicates) {
            val full = KavaMemberLookup.methods(owner, inherit, makeAccessible, predicate).firstOrNull()
            val first = KavaMemberLookup.firstMethodOrNull(owner, inherit, makeAccessible, predicate)
            assertEquals("${owner.simpleName} inherit=$inherit access=$makeAccessible", full, first)
        }
    }

    fun firstFieldIsTheFirstElementOfTheFullFilteredList() {
        val owners = listOf(Sample::class.java, Base::class.java, java.util.ArrayList::class.java)
        val predicates = listOf<(java.lang.reflect.Field) -> Boolean>(
            { true }, { it.type == String::class.java }, { it.name == "gamma" }, { it.name == "missing" }, { it.type == Integer.TYPE }
        )
        for (owner in owners) for (inherit in listOf(false, true)) for (makeAccessible in listOf(false, true)) for (predicate in predicates) {
            val full = KavaMemberLookup.fields(owner, inherit, makeAccessible, predicate).firstOrNull()
            val first = KavaMemberLookup.firstFieldOrNull(owner, inherit, makeAccessible, predicate)
            assertEquals("${owner.simpleName} inherit=$inherit access=$makeAccessible", full, first)
        }
        assertSame(KavaMemberLookup.declaredFields(Sample::class.java).first(), KavaMemberLookup.firstDeclaredFieldOrNull(Sample::class.java))
    }

    fun predicateStopsAtTheFirstMatchInsteadOfVisitingEveryMember() {
        val total = KavaMemberLookup.declaredMethods(Sample::class.java).size
        assertTrue("sample must have several methods: $total", total >= 6)
        var fullCalls = 0
        KavaMemberLookup.declaredMethods(Sample::class.java, predicate = { fullCalls++; true }).firstOrNull()
        var firstCalls = 0
        val first = KavaMemberLookup.firstDeclaredMethodOrNull(Sample::class.java) { firstCalls++; true }
        assertEquals("the legacy path evaluates every member", total, fullCalls)
        assertEquals("the first-match path stops at the first member", 1, firstCalls)
        assertEquals(KavaMemberLookup.declaredMethods(Sample::class.java).first(), first)
        // 没有命中时必须看完整个集合，结论一致。
        var missCalls = 0
        assertNull(KavaMemberLookup.firstDeclaredMethodOrNull(Sample::class.java) { missCalls++; false })
        assertEquals(total, missCalls)
    }

    fun inaccessibleLeadingCandidatesAreSkippedLikeBefore() {
        // makeAccessible 失败的候选被跳过，继续找下一项，而不是把失败当成「没有」。
        val accessible = KavaMemberLookup.firstDeclaredMethodOrNull(Sample::class.java, makeAccessible = true) { it.name == "hidden" }
        val legacy = KavaMemberLookup.declaredMethods(Sample::class.java, makeAccessible = true) { it.name == "hidden" }.firstOrNull()
        assertEquals(legacy, accessible)
        assertEquals(legacy != null, accessible != null)
    }

    fun collectionsAreCachedSoRepeatedFirstMatchLookupsDoNotRescan() {
        val before = KavaMemberLookup.diagnostics()
        repeat(50) { KavaMemberLookup.firstDeclaredMethodOrNull(Sample::class.java) { it.name == "write" } }
        val after = KavaMemberLookup.diagnostics()
        assertEquals("one collection resolve, then cache hits", before.cacheMisses + 1, after.cacheMisses)
        assertTrue(after.cacheHits >= before.cacheHits + 49)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (KavaMemberLookupFirstMatchCases) -> Unit) {
            val s = KavaMemberLookupFirstMatchCases()
            s.reset()
            block(s)
        }
        runIsolated { s -> s.firstMethodIsTheFirstElementOfTheFullFilteredList() }
        runIsolated { s -> s.firstFieldIsTheFirstElementOfTheFullFilteredList() }
        runIsolated { s -> s.predicateStopsAtTheFirstMatchInsteadOfVisitingEveryMember() }
        runIsolated { s -> s.inaccessibleLeadingCandidatesAreSkippedLikeBefore() }
        runIsolated { s -> s.collectionsAreCachedSoRepeatedFirstMatchLookupsDoNotRescan() }
    }
}

private class LatestValuePublisherCases {
    fun `coalesces by key and confirms only successful values`() {
        val tasks = ArrayDeque<() -> Unit>()
        val writes = mutableListOf<String>()
        val queue = LatestValuePublisher<String, String>({ tasks.addLast(it) }) { key, value ->
            writes += "$key:$value"; true
        }
        queue.submit("home", "old"); queue.submit("home", "new"); queue.submit("mine", "mine")
        assertEquals(1, tasks.size)
        tasks.removeFirst().invoke()
        assertEquals(listOf("home:new", "mine:mine"), writes)
        queue.submit("home", "new")
        assertTrue(tasks.isEmpty())
    }

    fun `failed content retries once and can be resubmitted without being marked published`() {
        val tasks = ArrayDeque<() -> Unit>()
        var attempts = 0
        var healthy = false
        val queue = LatestValuePublisher<String, String>({ tasks.addLast(it) }) { _, _ ->
            attempts++; healthy
        }
        queue.submit("home", "same"); tasks.removeFirst().invoke()
        assertEquals(2, attempts); assertTrue(tasks.isEmpty())
        healthy = true
        queue.submit("home", "same"); tasks.removeFirst().invoke()
        assertEquals(3, attempts)
        queue.submit("home", "same"); assertTrue(tasks.isEmpty())
    }

    fun `new value arriving during publication is always published after old value`() {
        val tasks = ArrayDeque<() -> Unit>()
        val writes = mutableListOf<String>()
        lateinit var queue: LatestValuePublisher<String, String>
        queue = LatestValuePublisher({ tasks.addLast(it) }) { _, value ->
            if (value == "old") queue.submit("home", "new")
            writes += value; true
        }
        queue.submit("home", "old"); tasks.removeFirst().invoke()
        assertEquals(listOf("old", "new"), writes)
        assertTrue(tasks.isEmpty())
    }

    fun `scheduler rejection does not discard a later retry`() {
        var reject = true
        val tasks = ArrayDeque<() -> Unit>()
        var writes = 0
        val queue = LatestValuePublisher<String, String>({ if (reject) error("rejected") else tasks.addLast(it) }) { _, _ -> writes++; true }
        assertFalse(queue.submit("home", "value"))
        reject = false
        assertTrue(queue.submit("home", "value")); tasks.removeFirst().invoke()
        assertEquals(1, writes)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (LatestValuePublisherCases) -> Unit) {
            val s = LatestValuePublisherCases()
            block(s)
        }
        runIsolated { s -> s.`coalesces by key and confirms only successful values`() }
        runIsolated { s -> s.`failed content retries once and can be resubmitted without being marked published`() }
        runIsolated { s -> s.`new value arriving during publication is always published after old value`() }
        runIsolated { s -> s.`scheduler rejection does not discard a later retry`() }
    }
}

private class MineComponentSnapshotQueryContractCases {

    fun `digest is deterministic and rejects changed payload`() {
        val digest = MineComponentSnapshotQueryContract.sha256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest
        )
        assertTrue(MineComponentSnapshotQueryContract.digestMatches("abc", digest.uppercase()))
        assertFalse(MineComponentSnapshotQueryContract.digestMatches("abd", digest))
    }

    fun `nonce accepts uuid shape and rejects blank short or punctuation`() {
        assertTrue(
            MineComponentSnapshotQueryContract.isValidNonce(
                "123e4567-e89b-12d3-a456-426614174000"
            )
        )
        assertFalse(MineComponentSnapshotQueryContract.isValidNonce(""))
        assertFalse(MineComponentSnapshotQueryContract.isValidNonce("too-short"))
        assertFalse(MineComponentSnapshotQueryContract.isValidNonce("a".repeat(16) + ":"))
    }

    fun `source identity requires all version fields`() {
        assertTrue(MineComponentSnapshotSource(1L, 2L, 3L).isComplete)
        assertFalse(MineComponentSnapshotSource(0L, 2L, 3L).isComplete)
        assertFalse(MineComponentSnapshotSource(1L, 0L, 3L).isComplete)
        assertFalse(MineComponentSnapshotSource(1L, 2L, 0L).isComplete)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (MineComponentSnapshotQueryContractCases) -> Unit) {
            val s = MineComponentSnapshotQueryContractCases()
            block(s)
        }
        runIsolated { s -> s.`digest is deterministic and rejects changed payload`() }
        runIsolated { s -> s.`nonce accepts uuid shape and rejects blank short or punctuation`() }
        runIsolated { s -> s.`source identity requires all version fields`() }
    }
}

private class ReceiptEnvelopeSequencerCases {
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
    fun pausedOldProviderCannotOverwriteOrAcknowledgeNewBoundPublication() = delayedOldTransport("provider","bound")
    fun pausedOldBoundCannotOverwriteOrAcknowledgeNewProviderPublication() = delayedOldTransport("bound","provider")
    fun ackFromAnEarlierSuccessfulDeliveryDoesNotClearTheNextQueuedValue() {
        val sequencer=ReceiptEnvelopeSequencer();val registry=Delivery();val box=LatestReceiptOutbox<String>()
        val first=sequencer.capture { "old" };assertTrue(registry.receive("scan",first))
        val next=sequencer.capture { "new" };box.put("scan",next.sequence,3,next.value)
        box.ack("scan",first.sequence);assertEquals("new",box.snapshot()["scan"]?.value)
        assertTrue(registry.receive("scan",next));assertEquals("new",registry.stored["scan"])
    }
    fun globalPreparationOrderDoesNotCoupleDifferentChannels() {
        val sequencer=ReceiptEnvelopeSequencer();val registry=Delivery();val box=LatestReceiptOutbox<String>()
        val a=sequencer.capture { "A" };val b=sequencer.capture { "B" }
        box.put("a",a.sequence,1,a.value);box.put("b",b.sequence,1,b.value)
        assertTrue(registry.receive("b",b));assertTrue(registry.receive("a",a))
        box.ack("b",b.sequence);assertEquals("A",box.snapshot()["a"]?.value)
    }
    fun anUncapturedPublicationCannotConsumeOrReuseASequence() {
        val sequencer=ReceiptEnvelopeSequencer()
        try { sequencer.capture<String> { error("snapshot failed") }; fail() } catch (_: IllegalStateException) { }
        val one=sequencer.capture { "one" };val two=sequencer.capture { "two" }
        assertEquals(1L,one.sequence);assertEquals(2L,two.sequence)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (ReceiptEnvelopeSequencerCases) -> Unit) {
            val s = ReceiptEnvelopeSequencerCases()
            block(s)
        }
        runIsolated { s -> s.pausedOldProviderCannotOverwriteOrAcknowledgeNewBoundPublication() }
        runIsolated { s -> s.pausedOldBoundCannotOverwriteOrAcknowledgeNewProviderPublication() }
        runIsolated { s -> s.ackFromAnEarlierSuccessfulDeliveryDoesNotClearTheNextQueuedValue() }
        runIsolated { s -> s.globalPreparationOrderDoesNotCoupleDifferentChannels() }
        runIsolated { s -> s.anUncapturedPublicationCannotConsumeOrReuseASequence() }
    }
}
