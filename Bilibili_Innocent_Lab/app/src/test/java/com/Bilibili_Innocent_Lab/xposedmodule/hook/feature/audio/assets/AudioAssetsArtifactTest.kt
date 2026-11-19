package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Opt-in offline integration against downloaded upstream bytes; never fetches from a unit-test/build task. */
class AudioAssetsArtifactTest {
    @get:Rule val temporary = TemporaryFolder(File("build/tmp/audio-artifact-tests").apply { mkdirs() })
    @Test fun `official artifacts verify unpack publish and materialize both host ABIs`() {
        val root = System.getenv("BIL_AUDIO_ARTIFACTS")?.let(::File)
        assumeTrue("Set BIL_AUDIO_ARTIFACTS to a directory with the pinned original ONNX and AAR", root != null)
        val store = AudioAssetStore(temporary.newFolder())
        for (part in listOf(AudioAssetSpec.model, AudioAssetSpec.runtime)) {
            val source = File(root, part.name)
            assertTrue("missing ${part.name}", source.isFile)
            store.install(part) { source.inputStream() }
        }
        store.unpackRuntime()
        val remoteRoot = temporary.newFolder()
        val remote = object : AudioRemoteFiles {
            override fun names() = remoteRoot.list()!!.toSet()
            override fun open(name: String) = File(remoteRoot, name).inputStream()
            override fun write(name: String, copy: (java.io.OutputStream) -> Unit) { File(remoteRoot, name).outputStream().use(copy) }
        }
        AudioAssetPublication.publish(store, AudioAssetSpec.allParts, remote, AudioDownloadControl())
        assertEquals("published", AudioAssetPublication.inspect(AudioAssetSpec.allParts, remote))
        for (abi in AudioAssetSpec.native.keys) {
            val host = AudioAssetStore(temporary.newFolder(), abi)
            for (part in AudioAssetSpec.parts(abi)) host.install(part) { remote.open(part.remoteName) }
            assertTrue(AudioAssetSpec.parts(abi).all { host.valid(it) })
            assertTrue(File(host.directory, "libonnxruntime.so").isFile)
            // Offline replay must never attempt a remote read.
            for (part in AudioAssetSpec.parts(abi)) host.install(part) { error("unexpected remote access") }
        }
    }
}
