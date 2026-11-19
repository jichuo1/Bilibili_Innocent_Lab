package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.assets

import java.io.InputStream
import java.io.OutputStream

internal interface AudioRemoteFiles {
    fun names(): Set<String>
    fun open(name: String): InputStream
    /** Implementations own and close the descriptor exactly once, truncate and sync before returning. */
    fun write(name: String, copy: (OutputStream) -> Unit)
}

internal object AudioAssetPublication {
    fun inspect(parts: List<AudioAsset>, remote: AudioRemoteFiles): String {
        val names = remote.names()
        if (parts.any { it.remoteName !in names }) return "remote_missing"
        for (part in parts) {
            try { remote.open(part.remoteName).use { AudioAssetStore.verify(part, it) } }
            catch (failure: AudioAssetFailure) { return "remote_invalid" }
        }
        return "published"
    }

    fun publish(store: AudioAssetStore, parts: List<AudioAsset>, remote: AudioRemoteFiles, control: AudioDownloadControl) {
        if (parts.any { !store.valid(it) }) throw AudioAssetFailure("local_missing")
        val names = remote.names()
        for (part in parts) {
            control.check()
            val good = part.remoteName in names && runCatching {
                remote.open(part.remoteName).use { AudioAssetStore.verify(part, it) }; true
            }.getOrDefault(false)
            if (!good) remote.write(part.remoteName) { output ->
                store.file(part).inputStream().use { input -> AudioAssetStore.verify(part, input, output) { control.check() } }
            }
        }
        // Re-read the whole set. A completed write alone is never proof of successful publication.
        if (inspect(parts, remote) != "published") throw AudioAssetFailure("remote_invalid")
    }
}
