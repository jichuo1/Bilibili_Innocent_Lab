package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SponsorSegmentStoreTest {
    val folder = TemporaryFolder()
    private var time = 1_000_000L
    private val video = SponsorVideoId("BV1pVWu6ZEzn", 43486676261)
    private val segments = listOf(SponsorSegment("a6447b58b813db92", 74_636, 103_813, 566_000),
        SponsorSegment("b-2_x", 200_000, 210_000, 0))
    private fun store(root: File = folder.root, maxEntries: Int = SponsorSegmentStore.MAX_ENTRIES) =
        SponsorSegmentStore(root, maxEntries = maxEntries) { time }
    private fun directory() = File(folder.root, SponsorSegmentStore.DIRECTORY)
    private fun entries() = directory().listFiles().orEmpty().filter { it.name.endsWith(".json") }

    fun aWrittenEntryReadsBackWithItsSavedTime() {
        store().write(video, segments)
        val entry = store().read(video)!!
        assertEquals(segments.sortedBy { it.startMs }, entry.segments)
        assertEquals(1_000_000L, entry.savedAtMs)
    }

    fun theDiskNeverShowsTheVideoIdentityAndEachPartHasItsOwnFile() {
        store().write(video, segments); store().write(video.copy(cid = 2), segments)
        val files = entries()
        assertEquals(2, files.size)
        for (file in files) {
            assertFalse(file.name.contains(video.bvid)); assertFalse(file.readText().contains(video.bvid))
            assertTrue(file.name.matches(Regex("[0-9a-f]{32}\\.json")))
        }
        assertNull(store().read(video.copy(cid = 3)))
    }

    fun emptyOrOversizedSegmentListsAreNeverStored() {
        store().write(video, emptyList())
        assertFalse(directory().exists())
        store().write(video, (0..SponsorSegmentStore.MAX_SEGMENTS).map { SponsorSegment("s$it", it * 10L, it * 10L + 5, 0) })
        assertTrue(entries().isEmpty())
    }

    fun corruptWrongKeyOversizedAndInvalidFilesAreRejectedAndRemoved() {
        val key = SponsorSegmentStore.keyOf(video)
        val file = File(directory(), "$key.json")
        val good = SponsorSegmentStore.encode(key, 1_000_000L, segments)
        val bad = listOf(
            "not json", "",
            good.replace("\"k\":\"$key\"", "\"k\":\"${"0".repeat(32)}\""),         // 换名复制：校验键对不上
            good.replace("\"v\":1", "\"v\":2"),                                    // 未知版本
            good.replace("103813", "74000"),                                       // start >= end
            good.replace("a6447b58b813db92", "bad uuid!"),                         // 非法 UUID
            SponsorSegmentStore.encode(key, 1_000_000L + 2 * 86_400_000L, segments), // 写于未来
            "x".repeat((SponsorSegmentStore.MAX_FILE_BYTES + 1).toInt()))
        for (content in bad) {
            directory().mkdirs(); file.writeText(content)
            assertNull(content.take(40), store().read(video))
            assertFalse("corrupt file must be deleted", file.exists())
        }
    }

    fun theEntryLimitEvictsTheOldestWrites() {
        assertEquals(256, SponsorSegmentStore.MAX_ENTRIES)
        val limit = 8
        val total = limit + 5
        val limitedStore = store(maxEntries = limit)
        repeat(total) { index ->
            time = 1_000_000L + index * 1_000L
            limitedStore.write(SponsorVideoId("BV" + "%010d".format(index), 1), segments)
        }
        assertEquals(limit, entries().size)
        assertNull(limitedStore.read(SponsorVideoId("BV" + "%010d".format(0), 1)))
        assertNotNull(limitedStore.read(SponsorVideoId("BV" + "%010d".format(total - 1), 1)))
        assertTrue(directory().listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    fun staleTemporaryFilesFromACrashedWriteAreCleanedUp() {
        directory().mkdirs()
        val stale = File(directory(), "dead.1.tmp").apply { writeText("x"); setLastModified(1) }
        store().write(video, segments)
        assertFalse(stale.exists())
    }

    fun purgeRemovesOnlyTheCacheDirectoryContent() {
        store().write(video, segments)
        val sibling = File(folder.root, "keep.txt").apply { writeText("keep") }
        store().purge()
        assertFalse(directory().exists()); assertTrue(sibling.exists())
        store().purge() // 目录不存在时也不抛
    }

    fun anUnwritableRootIsASilentNoOp() {
        val notADirectory = folder.newFile("occupied")
        val broken = store(notADirectory)
        broken.write(video, segments)
        assertNull(broken.read(video))
        broken.delete(video); broken.purge()
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SponsorSegmentStoreTest) -> Unit) {
            val s = SponsorSegmentStoreTest()
            s.folder.create()
            try {
                block(s)
            } finally {
                s.folder.delete()
            }
        }
        runIsolated { s -> s.aWrittenEntryReadsBackWithItsSavedTime() }
        runIsolated { s -> s.theDiskNeverShowsTheVideoIdentityAndEachPartHasItsOwnFile() }
        runIsolated { s -> s.emptyOrOversizedSegmentListsAreNeverStored() }
        runIsolated { s -> s.corruptWrongKeyOversizedAndInvalidFilesAreRejectedAndRemoved() }
        runIsolated { s -> s.theEntryLimitEvictsTheOldestWrites() }
        runIsolated { s -> s.staleTemporaryFilesFromACrashedWriteAreCleanedUp() }
        runIsolated { s -> s.purgeRemovesOnlyTheCacheDirectoryContent() }
        runIsolated { s -> s.anUnwritableRootIsASilentNoOp() }
    }
}
