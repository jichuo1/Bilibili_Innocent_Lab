package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import java.security.MessageDigest

/** Stable local IDs are never account IDs and never sent to the host service. */
internal object NativeFavoriteFolders {
    fun id(state: LocalVirtualLibrary.Collections, name: String): Long =
        state.folderInfo[name]?.id ?: id(name)

    fun id(name: String): Long = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
        .take(6).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) } + 1
}
