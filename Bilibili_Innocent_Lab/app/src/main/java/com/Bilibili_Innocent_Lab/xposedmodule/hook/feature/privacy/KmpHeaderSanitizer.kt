package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

/**
 * Rewrites the live request-header maps of a KMP gRPC call so no real account or device identity
 * leaves the process in an incognito session. The maps are exactly what the host hands to its
 * native engine (ascii headers as-is, binary headers base64-encoded), so scrubbing them here is
 * complete for everything that originates in the host's Java layer.
 *
 * Only header NAMES are ever returned or logged; values are never copied anywhere.
 */
internal class KmpHeaderSanitizer(
    private val rewriteMetadata: (ByteArray, IncognitoSession.Identity) -> ByteArray,
    private val rewriteDevice: (ByteArray, IncognitoSession.Identity) -> ByteArray
) {
    /**
     * Mutates [maps] in place and returns the lower-case header names seen. A recognised header
     * whose value has an unexpected type, or a rewrite that throws, aborts the whole call: the
     * caller must not send a request whose identity could not be sealed.
     */
    fun sanitize(maps: List<MutableMap<Any?, Any?>>, identity: IncognitoSession.Identity): Set<String> {
        val seen = java.util.TreeSet<String>()
        for (map in maps) {
            for (key in ArrayList(map.keys)) {
                val name = (key as? String)?.lowercase() ?: continue
                seen += name
                when (name) {
                    in REMOVED -> map.remove(key)
                    METADATA_BIN -> map[key] = rewritten(map[key]) { rewriteMetadata(it, identity) }
                    DEVICE_BIN -> map[key] = rewritten(map[key]) { rewriteDevice(it, identity) }
                    in DEVICE_IDS -> if (map[key] is String) map[key] = identity.buvid
                    in SESSION_IDS -> if (map[key] is String) map[key] = identity.sessionId
                }
            }
        }
        return seen
    }

    private inline fun rewritten(value: Any?, rewrite: (ByteArray) -> ByteArray): ByteArray {
        val bytes = value as? ByteArray ?: throw IllegalStateException("Unexpected binary header type")
        return if (bytes.isEmpty()) bytes else rewrite(bytes)
    }

    companion object {
        const val METADATA_BIN = "x-bili-metadata-bin"
        const val DEVICE_BIN = "x-bili-device-bin"

        /** Credentials and account-bound tokens; same set the OkHttp boundary strips. */
        internal val REMOVED = setOf("authorization", "cookie", "proxy-authorization", "x-bili-access-key",
            "x-bili-mid", "display-id", "x-bili-ticket", "bili-ticket", "x-bili-aurora-eid")
        internal val DEVICE_IDS = setOf("buvid", "device-id", "fp_local", "fp_remote", "guestid")
        internal val SESSION_IDS = setOf("session-id", "session_id")
    }
}
