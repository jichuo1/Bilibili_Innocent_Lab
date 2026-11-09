package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyVideoMetadataTest {
    class ModernArc(val aid: Long = 123, val cid: Long = 456, val title: String = "Selected video",
        val duration: Long = 199, val cover: String = "https://example.test/cover.jpg")
    class ModernOwner(val mid: Long = 7, val title: String = "Author name", val face: String = "https://example.test/avatar.jpg")
    class ModernReply(val arc: ModernArc = ModernArc(), val owner: ModernOwner = ModernOwner())
    class ArchiveAuthor(val mid: Long = 8, val name: String = "Archive author")
    class ArchiveArc(val aid: Long = 124, val firstCid: Long = 457, val title: String = "Archive video",
        val duration: Long = 200, val pic: String = "https://example.test/archive.jpg",
        val author: ArchiveAuthor = ArchiveAuthor())
    class ArchiveReply(val arc: ArchiveArc = ArchiveArc())
    private val host = PrivacyHostAccess(javaClass.classLoader!!)

    @Test fun modernOwnerTitleIsAnAuthorNameAndDurationUsesMilliseconds() {
        val video = PrivacyVideoMetadata.read(host, ModernReply())
        assertEquals(123L, video.aid)
        assertEquals(456L, video.cid)
        assertEquals("Selected video", video.title)
        assertEquals("Author name", video.authorName)
        assertEquals(7L, video.authorMid)
        assertEquals("https://example.test/avatar.jpg", video.authorFace)
        assertEquals(199_000L, video.durationMs)
        assertEquals(0L, video.positionMs)
        assertEquals(0L, video.updatedAtMs)
    }

    @Test fun archiveRepliesUseFirstCidAndArchiveAuthorWithoutInventingPlayback() {
        val video = PrivacyVideoMetadata.read(host, ArchiveReply())
        assertEquals(124L, video.aid)
        assertEquals(457L, video.cid)
        assertEquals("Archive author", video.authorName)
        assertEquals("", video.authorFace)
        assertEquals(200_000L, video.durationMs)
        assertEquals(0L, video.positionMs)
    }
}
