package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

/** The diagnostic wire carries fixed codes only, never exception text or filesystem paths. */
internal object AudioModelStatus {
    val allowed = setOf("not_requested", "unsupported_abi", "materializing", "loading", "warming", "ready",
        "remote_missing", "cache_io", "size_overflow", "size_mismatch", "hash_mismatch", "unsafe_directory",
        "busy", "cancelled", "runtime_failed", "fallback")
}
