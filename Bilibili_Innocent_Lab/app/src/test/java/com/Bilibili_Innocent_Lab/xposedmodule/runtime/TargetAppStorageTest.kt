package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.FreeCopyImportJournal
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.FreeCopyJournalRead
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.PendingFreeCopyMirror
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAppStorageTest {

    fun `maps Android uid to its user id`() {
        assertEquals(0, TargetAppStorage.userIdFromUid(10_251))
        assertEquals(10, TargetAppStorage.userIdFromUid(1_010_251))
    }

    fun `builds a work profile cache path`() {
        assertEquals(
            "/data/user/10/tv.danmaku.bili/cache/innocent_lab_adapt.json",
            TargetAppStorage.cachePath("innocent_lab_adapt.json", 10)
        )
    }

    fun `rejects a cache file path rather than a file name`() {
        assertThrows(IllegalArgumentException::class.java) {
            TargetAppStorage.cachePath("nested/cache.json", 0)
        }
    }

    fun `atomic file stores recover from bak files without short-circuiting on baseFile`() {
        val tempDir = Files.createTempDirectory("stage-a-atomic").toFile()
        try {
            // 1. NoRootSupportStore
            val payloadJson = JSONObject().put("schema", 1).toString()
            assertEquals(
                payloadJson,
                NoRootSupportStore.readAtomicSnapshotPayload { ByteArrayInputStream(payloadJson.toByteArray()) }
            )
            assertNull(NoRootSupportStore.readAtomicSnapshotPayload { throw FileNotFoundException("missing") })

            // 2. FreeCopyConfigStore
            val validFreeCopyBytes = ByteArrayOutputStream().apply {
                DataOutputStream(this).use { out ->
                    out.writeInt(0x42494C46)
                    out.writeInt(1)
                    out.writeLong(77L)
                    out.writeBoolean(true)
                    out.writeBoolean(false)
                }
            }.toByteArray()
            val snapshot = FreeCopyConfigStore.readFrom { ByteArrayInputStream(validFreeCopyBytes) }
            assertEquals(FreeCopyConfigStore.Snapshot(commentEnabled = true, descriptionEnabled = false, revision = 77L), snapshot)
            assertNull(FreeCopyConfigStore.readFrom { throw FileNotFoundException("missing") })

            // 3. FreeCopyImportJournal: baseFile 缺失仅 .bak 存在时 exists 与 readFrom 仍可恢复
            val journalBase = File(tempDir, "settings_import_free_copy_journal.bin")
            val journalBak = File("${journalBase.path}.bak")
            assertFalse(FreeCopyImportJournal.hasAtomicFile(journalBase))
            assertEquals(
                FreeCopyJournalRead.Missing,
                FreeCopyImportJournal.readFrom(journalBase, { throw FileNotFoundException("absent") })
            )

            assertTrue(journalBak.writeBytesCompat(ByteArrayOutputStream().apply {
                DataOutputStream(this).use { out ->
                    out.writeInt(0x42494C4A)
                    out.writeInt(1)
                    out.writeLong(99L)
                    out.writeBoolean(false)
                    out.writeBoolean(true)
                }
            }.toByteArray()))
            assertFalse(journalBase.exists())
            assertTrue(FreeCopyImportJournal.hasAtomicFile(journalBase))
            assertEquals(
                FreeCopyJournalRead.Ready(PendingFreeCopyMirror(commentEnabled = false, descriptionEnabled = true, revision = 99L)),
                FreeCopyImportJournal.readFrom(journalBase, { journalBak.inputStream() })
            )
            // 文件确实存在但因权限/短暂 I/O 失败时返回 Unreadable，不得销毁前滚意图。
            assertEquals(
                FreeCopyJournalRead.Unreadable,
                FreeCopyImportJournal.readFrom(journalBase, { throw FileNotFoundException("EACCES") })
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun File.writeBytesCompat(bytes: ByteArray): Boolean {
        writeBytes(bytes)
        return isFile
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (TargetAppStorageTest) -> Unit) {
            val s = TargetAppStorageTest()
            block(s)
        }
        runIsolated { s -> s.`maps Android uid to its user id`() }
        runIsolated { s -> s.`builds a work profile cache path`() }
        runIsolated { s -> s.`rejects a cache file path rather than a file name`() }
        runIsolated { s -> s.`atomic file stores recover from bak files without short-circuiting on baseFile`() }
    
        UpdateCheckCoordinatorCases().verifyAllCases()
    }
}

private class UpdateCheckCoordinatorCases {

    private val stable = GitHubReleaseChecker.UpdateChannel.STABLE
    private val preview = GitHubReleaseChecker.UpdateChannel.PREVIEW

    fun firstRequestStartsImmediately() {
        val coordinator = UpdateCheckCoordinator()
        val request = UpdateCheckCoordinator.Request(stable, manual = false)

        assertEquals(request, coordinator.submit(request))
    }

    fun channelSwitchQueuesManualRequestAndSuppressesOldResult() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = false))
        val previewRequest = UpdateCheckCoordinator.Request(preview, manual = true)

        assertNull(coordinator.submit(previewRequest))
        val completion = coordinator.complete(stable, selectedChannel = preview)

        assertFalse(completion.shouldDeliverResult)
        assertEquals(previewRequest, completion.nextRequest)
    }

    fun returningToActiveChannelCancelsOlderQueuedRequest() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = false))
        coordinator.submit(UpdateCheckCoordinator.Request(preview, manual = true))
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = true))

        val completion = coordinator.complete(stable, selectedChannel = stable)

        assertTrue(completion.shouldDeliverResult)
        assertNull(completion.nextRequest)
    }

    fun automaticRequestIsNotQueuedBehindActiveCheck() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = true))

        assertNull(
            coordinator.submit(UpdateCheckCoordinator.Request(preview, manual = false))
        )
        val completion = coordinator.complete(stable, selectedChannel = stable)

        assertTrue(completion.shouldDeliverResult)
        assertNull(completion.nextRequest)
    }

    fun staleQueuedRequestIsDroppedWhenItNoLongerMatchesSelection() {
        val coordinator = UpdateCheckCoordinator()
        coordinator.submit(UpdateCheckCoordinator.Request(stable, manual = false))
        coordinator.submit(UpdateCheckCoordinator.Request(preview, manual = true))

        val completion = coordinator.complete(stable, selectedChannel = stable)

        assertTrue(completion.shouldDeliverResult)
        assertNull(completion.nextRequest)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (UpdateCheckCoordinatorCases) -> Unit) {
            val s = UpdateCheckCoordinatorCases()
            block(s)
        }
        runIsolated { s -> s.firstRequestStartsImmediately() }
        runIsolated { s -> s.channelSwitchQueuesManualRequestAndSuppressesOldResult() }
        runIsolated { s -> s.returningToActiveChannelCancelsOlderQueuedRequest() }
        runIsolated { s -> s.automaticRequestIsNotQueuedBehindActiveCheck() }
        runIsolated { s -> s.staleQueuedRequestIsDroppedWhenItNoLongerMatchesSelection() }
    }
}
