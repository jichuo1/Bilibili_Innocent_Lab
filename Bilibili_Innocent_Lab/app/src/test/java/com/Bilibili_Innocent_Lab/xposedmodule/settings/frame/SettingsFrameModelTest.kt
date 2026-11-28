package com.Bilibili_Innocent_Lab.xposedmodule.settings.frame

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameModelSpec
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchMode
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NPatchRemoteGateway.SyncResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.FrameworkServiceCall
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsFrameModelTest {

    @Rule
    @JvmField
    val folder = TemporaryFolder()

    @Test
    fun frameFileRouteAndCheckGatePolicies() {
        FrameFileRouteCases().verifyAllCases()
        FrameModelCheckGateCases().verifyAllCases()
    }

    @Test
    fun frameModelFilesDownloadPublishAndRepair() {
        FrameModelFilesCases(folder).verifyAllCases()
    }
}

private class FrameFileRouteCases {
    fun `without the no-root channel the pushed framework service is used for every patch mode`() {
        for (mode in NPatchMode.entries) for (standard in listOf(false, true)) {
            assertEquals(
                FrameFileRoute.PUSHED_SERVICE,
                FrameFileRoute.select(noRootSelected = false, mode = mode, standardFrameworkConnected = standard)
            )
        }
    }

    fun `a selected NPatch channel only uses the authenticated pull channel`() {
        assertEquals(FrameFileRoute.PULLED_NPATCH, FrameFileRoute.select(true, NPatchMode.MANAGER, false))
        assertEquals(FrameFileRoute.PULLED_NPATCH, FrameFileRoute.select(true, NPatchMode.UNKNOWN, false))
    }

    fun `a connected root or LSPatch framework keeps the pushed route even with the no-root switch on`() {
        for (mode in NPatchMode.entries) {
            assertEquals(FrameFileRoute.PUSHED_SERVICE, FrameFileRoute.select(true, mode, standardFrameworkConnected = true))
        }
    }

    fun `embedded mode cannot reach the host private store`() {
        assertEquals(FrameFileRoute.UNSUPPORTED_EMBEDDED, FrameFileRoute.select(true, NPatchMode.EMBEDDED, false))
    }

    fun `pull failures reuse the push failure reasons so the settings text and retry stay consistent`() {
        assertEquals(FrameworkServiceCall.Reason.NOT_CONNECTED, FrameFileRoute.failureReason(SyncResult.ManagerMissing))
        assertEquals(FrameworkServiceCall.Reason.NOT_CONNECTED, FrameFileRoute.failureReason(SyncResult.ModuleNotRegistered))
        assertEquals(FrameworkServiceCall.Reason.METADATA_UNAVAILABLE, FrameFileRoute.failureReason(SyncResult.ConnectionTimeout))
        assertEquals(FrameworkServiceCall.Reason.OPERATION_FAILED, FrameFileRoute.failureReason(SyncResult.Failure("x")))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameFileRouteCases) -> Unit) {
            val s = FrameFileRouteCases()
            block(s)
        }
        runIsolated { s -> s.`without the no-root channel the pushed framework service is used for every patch mode`() }
        runIsolated { s -> s.`a selected NPatch channel only uses the authenticated pull channel`() }
        runIsolated { s -> s.`a connected root or LSPatch framework keeps the pushed route even with the no-root switch on`() }
        runIsolated { s -> s.`embedded mode cannot reach the host private store`() }
        runIsolated { s -> s.`pull failures reuse the push failure reasons so the settings text and retry stay consistent`() }
    }
}

private class FrameModelCheckGateCases {
    fun serviceEventsDuringAReadCoalesceAndDiscardItsStaleResult() {
        val gate = FrameModelCheckGate()
        gate.attach(true)
        val old = gate.begin()!!
        repeat(4) { gate.invalidate(); assertNull(gate.begin()) }
        assertFalse(gate.current(old))
        assertTrue(gate.finish(old))
        val latest = gate.begin()!!
        assertTrue(gate.current(latest))
        assertFalse(gate.finish(latest))
    }

    fun detachedRowsRejectLateResultsAndReattachmentSchedulesAFreshRead() {
        val gate = FrameModelCheckGate()
        assertNull(gate.begin())
        gate.attach(true)
        val old = gate.begin()!!
        gate.attach(false)
        assertFalse(gate.current(old))
        gate.attach(true)
        assertNull(gate.begin())
        assertTrue(gate.finish(old))
        assertTrue(gate.current(gate.begin()!!))
    }

    fun verifyAllCases() {
        fun runIsolated(block: (FrameModelCheckGateCases) -> Unit) {
            val s = FrameModelCheckGateCases()
            block(s)
        }
        runIsolated { s -> s.serviceEventsDuringAReadCoalesceAndDiscardItsStaleResult() }
        runIsolated { s -> s.detachedRowsRejectLateResultsAndReattachmentSchedulesAFreshRead() }
    }
}

private class FrameModelFilesCases(private val folder: TemporaryFolder) {
    private val payloads = listOf("parameters".toByteArray(), "weights".toByteArray())
    private val parts = payloads.mapIndexed { index, bytes ->
        FrameModelSpec.Part(
            "part$index",
            bytes.size,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        )
    }
    private val files = FrameModelFiles(parts)
    private fun fetch(part: FrameModelSpec.Part, file: File): Boolean {
        file.writeBytes(payloads[parts.indexOf(part)])
        return true
    }

    fun aMidPublicationFailureRetainsVerifiedDownloadsAndRetryOnlyWritesTheMissingPart() {
        val directory = folder.newFolder()
        val cached = files.prepare(directory, ::fetch)!!
        val remote = HashMap<String, ByteArray>()
        try {
            files.publish(cached, emptySet(), { remote.getValue(it).inputStream() }) { name, source ->
                if (remote.isNotEmpty()) throw IOException("service interrupted")
                remote[name] = source.readBytes()
            }
            fail("publication must fail")
        } catch (_: IOException) { }
        val again = files.prepare(directory) { _, _ -> error("verified files must not be downloaded again") }!!
        var writes = 0
        assertTrue(files.publish(again, remote.keys, { remote.getValue(it).inputStream() }) { name, source ->
            writes++; remote[name] = source.readBytes()
        })
        assertEquals(1, writes)
        assertEquals(FrameModelFiles.State.READY, files.inspect(remote.keys) { remote.getValue(it).inputStream() })
    }

    fun incompleteAndCorruptModelsNeverBecomeReady() {
        assertEquals(FrameModelFiles.State.MISSING, files.inspect(emptySet()) { error("missing") })
        assertEquals(FrameModelFiles.State.INVALID, files.inspect(parts.map { it.remoteName }.toSet()) { byteArrayOf(1).inputStream() })
        val directory = folder.newFolder()
        assertNull(files.prepare(directory) { _, target -> target.writeText("corrupt"); true })
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    fun repairingACorruptCacheAtomicallyReplacesOnlyThatModelPart() {
        val directory = folder.newFolder()
        files.prepare(directory, ::fetch)
        File(directory, parts.first().fileName).writeText("bad")
        var downloads = 0
        assertNotNull(files.prepare(directory) { part, target -> downloads++; fetch(part, target) })
        assertEquals(1, downloads)
        assertTrue(parts.all { FrameModelSpec.matches(it, File(directory, it.fileName)) })
    }

    fun verifyAllCases() {
        aMidPublicationFailureRetainsVerifiedDownloadsAndRetryOnlyWritesTheMissingPart()
        incompleteAndCorruptModelsNeverBecomeReady()
        repairingACorruptCacheAtomicallyReplacesOnlyThatModelPart()
    }
}
