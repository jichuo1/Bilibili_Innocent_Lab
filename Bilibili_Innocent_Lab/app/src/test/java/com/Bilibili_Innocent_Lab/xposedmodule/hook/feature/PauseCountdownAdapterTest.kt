package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseCountdownAdapterTest {
    private fun access(owner: Class<*>) = requireNotNull(PauseCountdownAdapter.resolve(owner,
        Service::class.java, Source::class.java, Reportable::class.java, Context::class.java,
        Active::class.java.getMethod("ensureActive", Context::class.java), Unit))

    fun `normal bar missing source failures and resumed coroutines keep the host behavior`() {
        val service = Service(SourceImpl(false))
        val receiver = OldCountdown(service)
        val guard = access(OldCountdown::class.java)
        assertFalse(guard.allows(receiver, Unit))
        service.source = null
        assertFalse(guard.allows(receiver, Unit))
        service.source = SourceImpl(true)
        assertTrue(guard.allows(receiver, Unit))
        assertSame(Unit, guard.emptyResult())
        receiver.label = 1
        assertFalse(guard.allows(receiver, Unit))
        receiver.label = 0
        assertFalse(guard.allows(receiver, IllegalStateException("host failure")))
        receiver.context.active = false
        assertFalse(guard.allows(receiver, Unit))
        receiver.context.active = true
        service.source = object : Source { override fun getHasAdContent(): Boolean = error("source failed") }
        assertFalse(guard.allows(receiver, Unit))
    }

    fun `new captured ad source wins over a different current service source`() {
        val receiver = NewCountdown(Service(SourceImpl(true)), SourceImpl(false))
        val guard = access(NewCountdown::class.java)
        assertFalse(guard.allows(receiver, Unit))
        receiver.captured = SourceImpl(true)
        assertTrue(guard.allows(receiver, Unit))
        receiver.captured = object : Reportable {}
        assertFalse(guard.allows(receiver, Unit))
        receiver.captured = null
        assertFalse(guard.allows(receiver, Unit))
    }

    fun `continuations and legacy helper aliases do not multiply request family coverage`() {
        assertEquals(setOf("ad-api", "theseus"), PauseCountdownAdapter.requestFamilies(listOf(
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$1",
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$2",
            "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService\$requestPausedPageData\$2",
            "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService\$requestPausedPageResult\$1")))
    }

    interface Reportable
    interface Source : Reportable { fun getHasAdContent(): Boolean }
    class SourceImpl(private val ad: Boolean) : Source { override fun getHasAdContent() = ad }
    class Service(@JvmField var source: Source?)
    class Context(var active: Boolean = true)
    class Active {
        companion object {
            @JvmStatic fun ensureActive(context: Context) {
                if (!context.active) throw CancellationException("host cancelled")
            }
        }
    }
    class OldCountdown(@JvmField val `this$0`: Service) {
        @JvmField var label = 0
        val context = Context()
        fun invokeSuspend(value: Any): Any = Unit
    }
    class NewCountdown(@JvmField val `this$0`: Service, @JvmField var captured: Reportable?) {
        @JvmField var label = 0
        val context = Context()
        fun invokeSuspend(value: Any): Any = Unit
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (PauseCountdownAdapterTest) -> Unit) {
            val s = PauseCountdownAdapterTest()
            block(s)
        }
        runIsolated { s -> s.`normal bar missing source failures and resumed coroutines keep the host behavior`() }
        runIsolated { s -> s.`new captured ad source wins over a different current service source`() }
        runIsolated { s -> s.`continuations and legacy helper aliases do not multiply request family coverage`() }
    
        PgcAutoActivityPopupPolicyCases().verifyAllCases()
        PlayerAiSubtitleFeatureInstallerCases().onlyValidOnDemandVideosAreEligible()
        PlayerBufferConfigCases().verifyAllCases()
        PlayerBufferDiskBudgetCases().verifyAllCases()
        PlayerBufferPolicyCases().verifyAllCases()
        PlayerBufferSessionsCases().verifyAllCases()
        PlayerCodecForcePolicyCases().verifyAllCases()
    }
}

private class PgcAutoActivityPopupPolicyCases {
    private class Popup
    private fun filter(values: Any?, size: Int = 9, index: Int = 7) =
        PgcAutoActivityPopupPolicy.filter(values, size, index, Popup::class.java)

    fun `copies once and preserves the original array and all unrelated identities`() {
        val original = Array<Any?>(9) { Any() }.apply { this[7] = Popup() }
        val filtered = filter(original) as PgcAutoActivityPopupPolicy.Decision.Filtered
        assertNotSame(original, filtered.values)
        assertNull(filtered.values[7])
        assertTrue(original[7] is Popup)
        original.indices.filter { it != 7 }.forEach { assertSame(original[it], filtered.values[it]) }
        assertSame(PgcAutoActivityPopupPolicy.Decision.Absent, filter(filtered.values))
    }

    fun `empty payload and missing half remain unchanged without a filtered result`() {
        assertSame(PgcAutoActivityPopupPolicy.Decision.Absent, filter(arrayOfNulls<Any>(9)))
        listOf(null, "not-array", emptyArray<Any>(), arrayOfNulls<Any>(8)).forEach {
            assertSame(PgcAutoActivityPopupPolicy.Decision.InvalidShape, filter(it))
        }
        assertSame(PgcAutoActivityPopupPolicy.Decision.InvalidShape, filter(arrayOfNulls<Any>(9), index = 9))
    }

    fun `wrong type is not removed and a shifted slot is supported`() {
        val wrong = arrayOf<Any?>(Any(), "not-a-popup")
        assertSame(PgcAutoActivityPopupPolicy.Decision.UnexpectedType, filter(wrong, 2, 1))
        assertEquals("not-a-popup", wrong[1])
        val shifted = arrayOf<Any?>(Popup(), Any())
        val filtered = filter(shifted, 2, 0) as PgcAutoActivityPopupPolicy.Decision.Filtered
        assertNull(filtered.values[0])
        assertSame(shifted[1], filtered.values[1])
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PgcAutoActivityPopupPolicyCases) -> Unit) {
            val s = PgcAutoActivityPopupPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`copies once and preserves the original array and all unrelated identities`() }
        runIsolated { s -> s.`empty payload and missing half remain unchanged without a filtered result`() }
        runIsolated { s -> s.`wrong type is not removed and a shifted slot is supported`() }
    }
}

private class PlayerAiSubtitleFeatureInstallerCases {
    fun onlyValidOnDemandVideosAreEligible() {
        assertTrue(PlayerAiSubtitleFeatureInstaller.eligible("ugc", 1, 2))
        assertTrue(PlayerAiSubtitleFeatureInstaller.eligible("pgc", 1, 2))
        assertFalse(PlayerAiSubtitleFeatureInstaller.eligible("live", 1, 2))
        assertFalse(PlayerAiSubtitleFeatureInstaller.eligible(null, 1, 2))
        assertFalse(PlayerAiSubtitleFeatureInstaller.eligible("ugc", 0, 2))
        assertFalse(PlayerAiSubtitleFeatureInstaller.eligible("ugc", 1, -1))
    }
}

private class PlayerBufferConfigCases {
    fun `unknown modes preserve host behavior`() {
        assertEquals(PlayerBufferMode.FOLLOW_HOST, PlayerBufferMode.fromValue(-1))
        assertEquals(PlayerBufferMode.FOLLOW_HOST, PlayerBufferMode.fromValue(Int.MAX_VALUE))
        assertEquals(setOf(0, 1, 2, 3), PlayerBufferConfig.supportedModes)
    }

    fun `native units use long and reject corrupt persisted values`() {
        assertEquals(600_000L, PlayerBufferConfig.secondsToMillis(600))
        assertEquals(268_435_456L, PlayerBufferConfig.memoryBytes(256))
        assertEquals(8_589_934_592L, PlayerBufferConfig.diskBytes(8_192))
        assertNull(PlayerBufferConfig.secondsToMillis(0))
        assertNull(PlayerBufferConfig.secondsToMillis(Int.MAX_VALUE))
        assertNull(PlayerBufferConfig.memoryBytes(15))
        assertNull(PlayerBufferConfig.diskBytes(Int.MAX_VALUE))
    }

    fun `ui normalization returns reviewed defaults rather than extreme clamps`() {
        assertEquals(30, PlayerBufferConfig.normalizeSeconds(-1))
        assertEquals(64, PlayerBufferConfig.normalizeMemoryMb(Int.MAX_VALUE))
        assertEquals(1_024, PlayerBufferConfig.normalizeDiskMb(0))
        assertEquals(1, PlayerBufferConfig.normalizeSeconds(1))
        assertEquals(256, PlayerBufferConfig.normalizeDiskMb(256))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PlayerBufferConfigCases) -> Unit) {
            val s = PlayerBufferConfigCases()
            block(s)
        }
        runIsolated { s -> s.`unknown modes preserve host behavior`() }
        runIsolated { s -> s.`native units use long and reject corrupt persisted values`() }
        runIsolated { s -> s.`ui normalization returns reviewed defaults rather than extreme clamps`() }
    }
}

private class PlayerBufferDiskBudgetCases {
    fun `requests do not read space and repeated callbacks keep one queued sample`() {
        val fixture = Fixture()
        repeat(1_000) { fixture.budget.request(PATH_A) }
        assertEquals(1, fixture.jobs.size)
        assertTrue(fixture.directories.isEmpty())
        assertNull(fixture.budget.available(PATH_A))
        fixture.runOne()
        assertEquals(listOf(File(PATH_A).parent), fixture.directories)
        assertEquals(1_024L * MIB, fixture.budget.available(PATH_A))
        repeat(1_000) { fixture.budget.request(PATH_A) }
        assertTrue(fixture.jobs.isEmpty())
        assertEquals(1, fixture.directories.size)
    }

    fun `free space is halved with reserve and low space never creates a negative budget`() {
        val fixture = Fixture()
        for ((free, expected) in listOf(
            1_024L * MIB to 384L * MIB,
            512L * MIB to 128L * MIB,
            256L * MIB to 0L,
            1L to 0L,
            0L to 0L
        )) {
            fixture.free = free
            fixture.budget.request("/allocated/cache/$free.cache")
            fixture.runOne()
            assertEquals(expected, fixture.budget.available("/allocated/cache/$free.cache"))
        }
    }

    fun `long maximum space cannot overflow and configured limit remains authoritative`() {
        val fixture = Fixture(configuredBytes = Long.MAX_VALUE)
        fixture.free = Long.MAX_VALUE
        fixture.budget.request(PATH_A)
        fixture.runOne()
        assertEquals(Long.MAX_VALUE / 2L - 128L * MIB, fixture.budget.available(PATH_A))
        val capped = Fixture(configuredBytes = 256L * MIB)
        capped.free = Long.MAX_VALUE
        capped.budget.request(PATH_A)
        capped.runOne()
        assertEquals(256L * MIB, capped.budget.available(PATH_A))
    }

    fun `stale and future samples are unavailable until a new queued measurement`() {
        val fixture = Fixture()
        fixture.budget.request(PATH_A)
        fixture.runOne()
        fixture.time = 31_000L
        assertNotNull(fixture.budget.available(PATH_A))
        fixture.time++
        assertNull(fixture.budget.available(PATH_A))
        fixture.budget.request(PATH_A)
        assertEquals(1, fixture.jobs.size)
        fixture.runOne()
        assertNotNull(fixture.budget.available(PATH_A))
        fixture.time--
        assertNull(fixture.budget.available(PATH_A))
    }

    fun `failed filesystem sample fails open and backs off until its freshness window ends`() {
        val fixture = Fixture()
        fixture.read = { throw IllegalStateException("filesystem unavailable") }
        fixture.budget.request(PATH_A)
        fixture.runOne()
        assertNull(fixture.budget.available(PATH_A))
        repeat(1_000) { fixture.budget.request(PATH_A) }
        assertTrue(fixture.jobs.isEmpty())
        fixture.time = 31_001L
        fixture.read = { 1_024L * MIB }
        fixture.budget.request(PATH_A)
        fixture.runOne()
        assertEquals(384L * MIB, fixture.budget.available(PATH_A))
        fixture.time += 30_001L
        fixture.read = { -1L }
        fixture.budget.request(PATH_A)
        fixture.runOne()
        assertNull(fixture.budget.available(PATH_A))
    }

    fun `switches before execution coalesce to the latest path and do not reuse another snapshot`() {
        val fixture = Fixture()
        fixture.budget.request(PATH_A)
        fixture.budget.request(PATH_B)
        assertEquals(1, fixture.jobs.size)
        fixture.runOne()
        assertEquals(listOf(File(PATH_B).parent), fixture.directories)
        assertNull(fixture.budget.available(PATH_A))
        assertNotNull(fixture.budget.available(PATH_B))
        fixture.budget.request(PATH_A)
        assertNull(fixture.budget.available(PATH_A))
        assertNull(fixture.budget.available(PATH_B))
        fixture.runOne()
        assertNotNull(fixture.budget.available(PATH_A))
    }

    fun `path change during measurement schedules just one successor and cannot publish old path budget`() {
        val fixture = Fixture()
        fixture.read = {
            repeat(1_000) { fixture.budget.request(PATH_B) }
            assertTrue(fixture.jobs.isEmpty())
            4_096L * MIB
        }
        fixture.budget.request(PATH_A)
        fixture.runOne()
        assertEquals(1, fixture.jobs.size)
        assertNull(fixture.budget.available(PATH_A))
        assertNull(fixture.budget.available(PATH_B))
        fixture.read = { 1_024L * MIB }
        fixture.runOne()
        assertEquals(384L * MIB, fixture.budget.available(PATH_B))
        assertTrue(fixture.jobs.isEmpty())
    }

    fun `scheduler rejection or exception releases the in-flight claim for a later request`() {
        val fixture = Fixture()
        fixture.accept = false
        fixture.budget.request(PATH_A)
        assertTrue(fixture.jobs.isEmpty())
        assertNull(fixture.budget.available(PATH_A))
        fixture.accept = true
        fixture.schedulerThrows = true
        fixture.budget.request(PATH_A)
        assertNull(fixture.budget.available(PATH_A))
        fixture.schedulerThrows = false
        fixture.budget.request(PATH_A)
        assertEquals(1, fixture.jobs.size)
        fixture.runOne()
        assertNotNull(fixture.budget.available(PATH_A))
    }

    fun `invalid configured budgets and paths never produce usable space`() {
        for (configured in listOf(-1L, 0L)) {
            val fixture = Fixture(configured)
            fixture.budget.request(PATH_A)
            assertTrue(fixture.jobs.isEmpty())
            assertNull(fixture.budget.available(PATH_A))
        }
        val fixture = Fixture()
        fixture.budget.request(" ")
        assertTrue(fixture.jobs.isEmpty())
        fixture.budget.request("filename-without-parent")
        fixture.runOne()
        assertTrue(fixture.directories.isEmpty())
        assertNull(fixture.budget.available("filename-without-parent"))
    }

    fun `concurrent callers cannot create duplicate queued jobs`() {
        val queued = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val budget = PlayerBufferDiskBudget(1_024L * MIB, schedule = {
            queued.incrementAndGet()
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            true
        }, availableBytes = { error("sampling must remain queued") }, nowMs = { 1_000L })
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            runCatching { budget.request(PATH_A) }.onFailure(failure::set)
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(1_000) { budget.request(PATH_B) }
            assertEquals(1, queued.get())
            assertNull(budget.available(PATH_B))
        } finally {
            release.countDown()
            worker.join(5_000L)
        }
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertEquals(1, queued.get())
    }

    private class Fixture(configuredBytes: Long = 1_024L * MIB) {
        var time = 1_000L
        var free = 4_096L * MIB
        var accept = true
        var schedulerThrows = false
        var read: (String) -> Long = { free }
        val jobs = ArrayDeque<() -> Unit>()
        val directories = mutableListOf<String>()
        val budget = PlayerBufferDiskBudget(configuredBytes, schedule = { work ->
            if (schedulerThrows) throw IllegalStateException("scheduler unavailable")
            if (accept) jobs.addLast(work)
            accept
        }, availableBytes = { path ->
            directories += path
            read(path)
        }, nowMs = { time })

        fun runOne() = jobs.removeFirst().invoke()
    }

    companion object {
        private const val MIB = 1_048_576L
        private const val PATH_A = "/allocated/a/video.cache"
        private const val PATH_B = "/allocated/b/video.cache"
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PlayerBufferDiskBudgetCases) -> Unit) {
            val s = PlayerBufferDiskBudgetCases()
            block(s)
        }
        runIsolated { s -> s.`requests do not read space and repeated callbacks keep one queued sample`() }
        runIsolated { s -> s.`free space is halved with reserve and low space never creates a negative budget`() }
        runIsolated { s -> s.`long maximum space cannot overflow and configured limit remains authoritative`() }
        runIsolated { s -> s.`stale and future samples are unavailable until a new queued measurement`() }
        runIsolated { s -> s.`failed filesystem sample fails open and backs off until its freshness window ends`() }
        runIsolated { s -> s.`switches before execution coalesce to the latest path and do not reuse another snapshot`() }
        runIsolated { s -> s.`path change during measurement schedules just one successor and cannot publish old path budget`() }
        runIsolated { s -> s.`scheduler rejection or exception releases the in-flight claim for a later request`() }
        runIsolated { s -> s.`invalid configured budgets and paths never produce usable space`() }
        runIsolated { s -> s.`concurrent callers cannot create duplicate queued jobs`() }
    }
}

private class PlayerBufferPolicyCases {
    private val settings = PlayerBufferPolicy.Settings(
        mode = PlayerBufferMode.CUSTOM,
        seconds = 60,
        memoryMb = 64,
        diskMb = 1_024,
        wifiOnly = true
    )
    private val media = PlayerBufferPolicy.Media(
        current = true,
        eligible = true,
        progressive = true,
        fileSize = 128L * PlayerBufferConfig.MIB,
        durationMs = 60_000L
    )
    private val diskBytes = 512L * PlayerBufferConfig.MIB

    fun `follow host makes no native changes even with corrupt ancillary settings`() {
        val decision = decide(settings.copy(
            mode = PlayerBufferMode.FOLLOW_HOST,
            seconds = Int.MAX_VALUE,
            memoryMb = -1,
            diskMb = Int.MIN_VALUE
        ))
        assertDisabled(decision, "follow-host")
    }

    fun `unknown persisted modes resolve to zero native changes`() {
        val decision = decide(settings.copy(mode = PlayerBufferMode.fromValue(Int.MAX_VALUE)))
        assertDisabled(decision, "follow-host")
    }

    fun `unconfirmed current item and unrelated routes never expand prefetch`() {
        for (mode in listOf(PlayerBufferMode.LOW, PlayerBufferMode.CUSTOM, PlayerBufferMode.WHOLE)) {
            assertDisabled(decide(settings.copy(mode = mode), media.copy(current = false)), "not-current")
            assertDisabled(decide(settings.copy(mode = mode), media.copy(eligible = false)), "not-applicable")
        }
    }

    fun `low mode keeps one media second and divides the memory budget`() {
        val decision = decide(settings.copy(mode = PlayerBufferMode.LOW))
        assertTrue(decision.active)
        assertFalse(decision.fullPreload)
        assertEquals(1_000L, decision.itemTimeMs)
        assertEquals(32L * PlayerBufferConfig.MIB, decision.itemBytes)
        assertEquals(32L * PlayerBufferConfig.MIB, decision.consumerBytes)
        assertNull(decision.forwardBytes)
        assertNull(decision.diskCapacity)
    }

    fun `custom duration uses media milliseconds at both supported endpoints`() {
        assertEquals(1_000L, decide(settings.copy(seconds = 1)).itemTimeMs)
        assertEquals(600_000L, decide(settings.copy(seconds = 600)).itemTimeMs)
        assertEquals(60_000L, decide().itemTimeMs)
    }

    fun `every supported memory budget is shared by two bounded queues`() {
        for (memoryMb in PlayerBufferConfig.MIN_MEMORY_MB..PlayerBufferConfig.MAX_MEMORY_MB) {
            val decision = decide(settings.copy(memoryMb = memoryMb))
            val total = memoryMb * PlayerBufferConfig.MIB
            assertTrue(decision.itemBytes!! > 0L)
            assertTrue(decision.consumerBytes!! > 0L)
            assertEquals(total, decision.itemBytes + decision.consumerBytes)
            assertTrue(decision.itemBytes < total)
            assertTrue(decision.consumerBytes < total)
        }
    }

    fun `corrupt persisted numeric values fail open without partial native updates`() {
        val invalidSettings = listOf(
            settings.copy(seconds = 0), settings.copy(seconds = -1), settings.copy(seconds = Int.MAX_VALUE),
            settings.copy(memoryMb = 15), settings.copy(memoryMb = 257), settings.copy(memoryMb = Int.MIN_VALUE),
            settings.copy(diskMb = 255), settings.copy(diskMb = 8_193), settings.copy(diskMb = Int.MAX_VALUE)
        )
        for (mode in listOf(PlayerBufferMode.LOW, PlayerBufferMode.CUSTOM, PlayerBufferMode.WHOLE)) {
            invalidSettings.forEach { assertDisabled(decide(it.copy(mode = mode)), "invalid-settings") }
        }
    }

    fun `whole prefetch keeps packet memory finite and forwards only the actual file size`() {
        val decision = decide(settings.copy(mode = PlayerBufferMode.WHOLE))
        assertTrue(decision.active)
        assertTrue(decision.fullPreload)
        assertEquals(PlayerBufferConfig.DEFAULT_SECONDS * 1_000L, decision.itemTimeMs)
        assertEquals(32L * PlayerBufferConfig.MIB, decision.itemBytes)
        assertEquals(32L * PlayerBufferConfig.MIB, decision.consumerBytes)
        assertEquals(media.fileSize, decision.forwardBytes)
        assertEquals(diskBytes, decision.diskCapacity)
        assertTrue(decision.itemTimeMs!! < media.durationMs)
        assertTrue(decision.itemBytes!! < decision.diskCapacity!!)
    }

    fun `whole prefetch is capped by both measured capacity and configured capacity`() {
        val whole = settings.copy(mode = PlayerBufferMode.WHOLE, diskMb = 256)
        assertEquals(256L * PlayerBufferConfig.MIB, decide(whole, verifiedDiskBytes = Long.MAX_VALUE).diskCapacity)
        val measured = 200L * PlayerBufferConfig.MIB
        assertEquals(measured, decide(whole, verifiedDiskBytes = measured).diskCapacity)
    }

    fun `unverified disk budgets retain only finite phase one settings`() {
        for (budget in listOf(null, 0L, -1L, Long.MIN_VALUE)) {
            assertWholeFallback(decide(settings.copy(mode = PlayerBufferMode.WHOLE), verifiedDiskBytes = budget), "disk-budget-unverified")
        }
    }

    fun `DASH unknown size and unknown duration cannot become whole disk preload`() {
        val whole = settings.copy(mode = PlayerBufferMode.WHOLE)
        assertWholeFallback(decide(whole, media.copy(progressive = false)), "unsupported-disk-route")
        for (size in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertWholeFallback(decide(whole, media.copy(fileSize = size)), "unknown-media-size")
        }
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertWholeFallback(decide(whole, media.copy(durationMs = duration)), "unknown-media-duration")
        }
    }

    fun `whole capacity admission reserves headroom and rejects exact capacity or overflow`() {
        val whole = settings.copy(mode = PlayerBufferMode.WHOLE)
        val headroom = PlayerBufferPolicy.HEADROOM_BYTES
        val accepted = decide(whole, media.copy(fileSize = diskBytes - headroom))
        assertTrue(accepted.fullPreload)
        assertEquals(diskBytes - headroom, accepted.forwardBytes)
        for (size in listOf(diskBytes - headroom + 1L, diskBytes, Long.MAX_VALUE)) {
            assertWholeFallback(decide(whole, media.copy(fileSize = size)), "insufficient-disk-budget")
        }
        for (budget in listOf(headroom - 1L, headroom)) {
            assertWholeFallback(decide(whole, media.copy(fileSize = 1L), budget), "insufficient-disk-budget")
        }
    }

    fun `WiFi admission applies only to disk preload and respects explicit opt out`() {
        val whole = settings.copy(mode = PlayerBufferMode.WHOLE)
        assertWholeFallback(decide(whole, wifi = false), "wifi-required")
        assertTrue(decide(whole.copy(wifiOnly = false), wifi = false).fullPreload)
        assertTrue(decide(whole, wifi = true).fullPreload)
        assertTrue(decide(settings.copy(mode = PlayerBufferMode.LOW), wifi = false).active)
        assertTrue(decide(settings, wifi = false).active)
    }

    private fun decide(
        settings: PlayerBufferPolicy.Settings = this.settings,
        media: PlayerBufferPolicy.Media = this.media,
        verifiedDiskBytes: Long? = diskBytes,
        wifi: Boolean = true
    ) = PlayerBufferPolicy.decide(settings, media, verifiedDiskBytes, wifi)

    private fun assertDisabled(decision: PlayerBufferPolicy.Decision, reason: String) {
        assertFalse(decision.active)
        assertFalse(decision.fullPreload)
        assertNull(decision.itemTimeMs)
        assertNull(decision.itemBytes)
        assertNull(decision.consumerBytes)
        assertNull(decision.forwardBytes)
        assertNull(decision.diskCapacity)
        assertEquals(reason, decision.reason)
    }

    private fun assertWholeFallback(decision: PlayerBufferPolicy.Decision, reason: String) {
        assertTrue(decision.active)
        assertFalse(decision.fullPreload)
        assertEquals(PlayerBufferConfig.DEFAULT_SECONDS * 1_000L, decision.itemTimeMs)
        assertEquals(settings.memoryMb * PlayerBufferConfig.MIB, decision.itemBytes!! + decision.consumerBytes!!)
        assertNull(decision.forwardBytes)
        assertNull(decision.diskCapacity)
        assertEquals(reason, decision.reason)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PlayerBufferPolicyCases) -> Unit) {
            val s = PlayerBufferPolicyCases()
            block(s)
        }
        runIsolated { s -> s.`follow host makes no native changes even with corrupt ancillary settings`() }
        runIsolated { s -> s.`unknown persisted modes resolve to zero native changes`() }
        runIsolated { s -> s.`unconfirmed current item and unrelated routes never expand prefetch`() }
        runIsolated { s -> s.`low mode keeps one media second and divides the memory budget`() }
        runIsolated { s -> s.`custom duration uses media milliseconds at both supported endpoints`() }
        runIsolated { s -> s.`every supported memory budget is shared by two bounded queues`() }
        runIsolated { s -> s.`corrupt persisted numeric values fail open without partial native updates`() }
        runIsolated { s -> s.`whole prefetch keeps packet memory finite and forwards only the actual file size`() }
        runIsolated { s -> s.`whole prefetch is capped by both measured capacity and configured capacity`() }
        runIsolated { s -> s.`unverified disk budgets retain only finite phase one settings`() }
        runIsolated { s -> s.`DASH unknown size and unknown duration cannot become whole disk preload`() }
        runIsolated { s -> s.`whole capacity admission reserves headroom and rejects exact capacity or overflow`() }
        runIsolated { s -> s.`WiFi admission applies only to disk preload and respects explicit opt out`() }
    }
}

private class PlayerBufferSessionsCases {
    private class SameHash {
        override fun equals(other: Any?) = other is SameHash
        override fun hashCode() = 1
    }
    fun `host equality cannot approve another item`() {
        val store = PlayerBufferSessions()
        val first = SameHash()
        val other = SameHash()
        val ticket = PlayerBufferSessions.Ticket()
        val entry = store.register(first, ticket, null, null)
        assertTrue(store.hasPlayIntent(entry))
        assertFalse(store.current(entry))
        assertNull(store.get(other))
        assertFalse(store.commit(ticket, other))
        assertFalse(store.hasPlayIntent(entry))
    }
    fun `only committed current item is admitted for disk preload`() {
        val store = PlayerBufferSessions()
        val first = Any()
        val firstTicket = PlayerBufferSessions.Ticket()
        val firstEntry = store.register(first, firstTicket, null, null)
        val player = Any()
        assertFalse(store.current(firstEntry))
        store.select(player, first)
        assertTrue(store.commit(firstTicket, first))
        assertTrue(store.current(firstEntry))
        val next = Any()
        val nextTicket = PlayerBufferSessions.Ticket()
        val nextEntry = store.register(next, nextTicket, null, null)
        store.select(player, next)
        assertTrue(store.commit(nextTicket, next))
        assertFalse(store.current(firstEntry))
        assertFalse(store.hasPlayIntent(firstEntry))
        assertTrue(store.current(nextEntry))
        store.remove(next)
        assertFalse(store.current(nextEntry))
        assertFalse(store.hasPlayIntent(nextEntry))
    }
    fun `cancelled or rejected preparation cannot commit later`() {
        val store = PlayerBufferSessions()
        val item = Any()
        val ticket = PlayerBufferSessions.Ticket()
        val entry = store.register(item, ticket, null, null)
        store.cancel(ticket)
        assertFalse(store.commit(ticket, item))
        assertFalse(store.current(entry))
        assertFalse(store.hasPlayIntent(entry))
    }
    fun `two creations sharing one ticket cannot authorize the non current sibling`() {
        val store = PlayerBufferSessions()
        val first = Any()
        val sibling = Any()
        val ticket = PlayerBufferSessions.Ticket()
        val firstEntry = store.register(first, ticket, null, null)
        val siblingEntry = store.register(sibling, ticket, null, null)
        val player = Any()
        store.select(player, first)
        assertTrue(store.commit(ticket, first))
        assertTrue(store.current(firstEntry))
        assertFalse(store.current(siblingEntry))
        assertFalse(store.hasPlayIntent(siblingEntry))
        store.remove(sibling)
        assertTrue(store.current(firstEntry))
        store.select(player, null)
        assertFalse(store.current(firstEntry))
    }
    fun `business current readback without native selection cannot authorize full preload`() {
        val store = PlayerBufferSessions()
        val item = Any()
        val ticket = PlayerBufferSessions.Ticket()
        val entry = store.register(item, ticket, null, null)
        assertFalse(store.commit(ticket, item))
        assertFalse(store.current(entry))
    }
    fun `bounded weak registry evicts stale preparation even when host retains items`() {
        val store = PlayerBufferSessions()
        val retained = List(17) { Any() }
        val tickets = List(17) { PlayerBufferSessions.Ticket() }
        retained.forEachIndexed { i, item -> store.register(item, tickets[i], null, null) }
        assertNull(store.get(retained[0]))
        assertFalse(store.commit(tickets[0], retained[0]))
        assertNotNull(store.get(retained.last()))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PlayerBufferSessionsCases) -> Unit) {
            val s = PlayerBufferSessionsCases()
            block(s)
        }
        runIsolated { s -> s.`host equality cannot approve another item`() }
        runIsolated { s -> s.`only committed current item is admitted for disk preload`() }
        runIsolated { s -> s.`cancelled or rejected preparation cannot commit later`() }
        runIsolated { s -> s.`two creations sharing one ticket cannot authorize the non current sibling`() }
        runIsolated { s -> s.`business current readback without native selection cannot authorize full preload`() }
        runIsolated { s -> s.`bounded weak registry evicts stale preparation even when host retains items`() }
    }
}

private class PlayerCodecForcePolicyCases {
    fun `follow host leaves fnval and options untouched`() {
        assertNull(PlayerCodecForcePolicy.expectedFnval(2048L, PlayerCodecPreference.FOLLOW_HOST))
        assertNull(PlayerCodecForcePolicy.optionValue("decoder_type", PlayerDecodeMode.FOLLOW_HOST))
    }

    fun `av1 sets the av1 fnval bit while h264 and h265 clear it`() {
        assertEquals(2048L, PlayerCodecForcePolicy.expectedFnval(0L, PlayerCodecPreference.AV1))
        assertEquals(0L, PlayerCodecForcePolicy.expectedFnval(2048L, PlayerCodecPreference.H264))
        assertEquals(16L, PlayerCodecForcePolicy.expectedFnval(2064L, PlayerCodecPreference.H265))
    }

    fun `decode policy changes all known option families`() {
        assertEquals(1L, PlayerCodecForcePolicy.optionValue("mediacodec-hevc", PlayerDecodeMode.FORCE_HARDWARE))
        assertEquals(0L, PlayerCodecForcePolicy.optionValue("mediacodec-all-videos", PlayerDecodeMode.FORCE_SOFTWARE))
        assertEquals(1L, PlayerCodecForcePolicy.optionValue("decoder_type", PlayerDecodeMode.FORCE_SOFTWARE))
        assertNull(PlayerCodecForcePolicy.optionValue("unknown-option", PlayerDecodeMode.FORCE_SOFTWARE))
    }

    fun `decode policy keeps unknown option value types and values`() {
        assertEquals(1L, PlayerCodecForcePolicy.recognizedOptionValue("mediacodec", 1))
        assertEquals(0L, PlayerCodecForcePolicy.recognizedOptionValue("mediacodec", "0"))
        assertNull(PlayerCodecForcePolicy.recognizedOptionValue("mediacodec", 2.5))
        assertNull(PlayerCodecForcePolicy.recognizedOptionValue("mediacodec", "future-mode"))
        assertNull(PlayerCodecForcePolicy.recognizedOptionValue("decoder_type", 2L))
    }

    /** 方案硬约束 R4：AV1 × 强制软解不可用，一律降级 H.264；其他组合原样。 */
    fun `av1 with forced software decoding is downgraded to h264`() {
        assertEquals(PlayerCodecPreference.H264,
            PlayerCodecForcePolicy.effectivePreference(PlayerCodecPreference.AV1, PlayerDecodeMode.FORCE_SOFTWARE))
        assertEquals(PlayerCodecPreference.AV1,
            PlayerCodecForcePolicy.effectivePreference(PlayerCodecPreference.AV1, PlayerDecodeMode.FORCE_HARDWARE))
        assertEquals(PlayerCodecPreference.AV1,
            PlayerCodecForcePolicy.effectivePreference(PlayerCodecPreference.AV1, PlayerDecodeMode.FOLLOW_HOST))
        assertEquals(PlayerCodecPreference.H265,
            PlayerCodecForcePolicy.effectivePreference(PlayerCodecPreference.H265, PlayerDecodeMode.FORCE_SOFTWARE))
        // 降级后的请求改写确实把 AV1 位清掉。
        assertEquals(16L, PlayerCodecForcePolicy.expectedFnval(16L or PlayerCodecForcePolicy.AV1_FNVAL,
            PlayerCodecForcePolicy.effectivePreference(PlayerCodecPreference.AV1, PlayerDecodeMode.FORCE_SOFTWARE)))
    }

    /** 跟随宿主编码 + 强制软解：不改偏好，只让服务端不下发 AV1 流。 */
    fun `follow host with forced software decoding only strips the av1 capability`() {
        assertEquals(true, PlayerCodecForcePolicy.stripsAv1Only(PlayerCodecPreference.FOLLOW_HOST, PlayerDecodeMode.FORCE_SOFTWARE))
        assertEquals(false, PlayerCodecForcePolicy.stripsAv1Only(PlayerCodecPreference.FOLLOW_HOST, PlayerDecodeMode.FORCE_HARDWARE))
        assertEquals(false, PlayerCodecForcePolicy.stripsAv1Only(PlayerCodecPreference.H264, PlayerDecodeMode.FORCE_SOFTWARE))
        assertEquals(4048L - 2048L, PlayerCodecForcePolicy.withoutAv1(4048L))
        assertEquals(16L, PlayerCodecForcePolicy.withoutAv1(16L))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (PlayerCodecForcePolicyCases) -> Unit) {
            val s = PlayerCodecForcePolicyCases()
            block(s)
        }
        runIsolated { s -> s.`follow host leaves fnval and options untouched`() }
        runIsolated { s -> s.`av1 sets the av1 fnval bit while h264 and h265 clear it`() }
        runIsolated { s -> s.`decode policy changes all known option families`() }
        runIsolated { s -> s.`decode policy keeps unknown option value types and values`() }
        runIsolated { s -> s.`av1 with forced software decoding is downgraded to h264`() }
        runIsolated { s -> s.`follow host with forced software decoding only strips the av1 capability`() }
    }
}
