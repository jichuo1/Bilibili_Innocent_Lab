package com.Bilibili_Innocent_Lab.xposedmodule.settings.frame

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameModelSpec
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FrameModelFilesTest {
    val folder = TemporaryFolder()
    private val payloads = listOf("parameters".toByteArray(), "weights".toByteArray())
    private val parts = payloads.mapIndexed { index, bytes ->
        FrameModelSpec.Part("part$index", bytes.size,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
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

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (FrameModelFilesTest) -> Unit) {
            val s = FrameModelFilesTest()
            s.folder.create()
            try {
                block(s)
            } finally {
                s.folder.delete()
            }
        }
        runIsolated { s -> s.aMidPublicationFailureRetainsVerifiedDownloadsAndRetryOnlyWritesTheMissingPart() }
        runIsolated { s -> s.incompleteAndCorruptModelsNeverBecomeReady() }
        runIsolated { s -> s.repairingACorruptCacheAtomicallyReplacesOnlyThatModelPart() }
    }
}
