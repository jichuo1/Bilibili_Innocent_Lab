package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import org.json.JSONArray
import org.json.JSONObject

internal object HostAdmissionContract {
    const val VERSION = 1
    const val METHOD_PREPARE = "prepare_admission"
    const val METHOD_CONFIRM = "confirm_admission"
    const val NORMAL = "manager"
    const val DIRECT = "module_direct"
    const val MAX_DOCUMENT_CHARS = 192 * 1024
    const val BOOTSTRAP_TIMEOUT_MS = 2_000L
    const val CHALLENGE_TIMEOUT_MS = 2_000L

    fun putIdentity(bundle: Bundle, identity: PublicationIdentity) {
        bundle.putString("incarnation", identity.incarnation)
        bundle.putLong("consentRevision", identity.consentRevision)
        bundle.putLong("policyEpoch", identity.policyEpoch)
        bundle.putLong("snapshotRevision", identity.snapshotRevision)
        bundle.putString("fingerprint", identity.fingerprint)
    }
    fun identity(bundle: Bundle): PublicationIdentity? = runCatching {
        val value = PublicationIdentity(bundle.getString("incarnation").orEmpty(), bundle.getLong("consentRevision"),
            bundle.getLong("policyEpoch"), bundle.getLong("snapshotRevision"), bundle.getString("fingerprint").orEmpty())
        value.takeIf { it.incarnation.length in 16..64 && it.consentRevision > 0 && it.policyEpoch > 0 &&
            it.snapshotRevision > 0 && it.fingerprint.matches(Regex("[a-fA-F0-9]{64}")) }
    }.getOrNull()

    fun identityJson(value: PublicationIdentity): JSONObject = JSONObject()
        .put("incarnation", value.incarnation).put("consentRevision", value.consentRevision)
        .put("policyEpoch", value.policyEpoch).put("snapshotRevision", value.snapshotRevision)
        .put("fingerprint", value.fingerprint)

    fun identityJson(value: JSONObject): PublicationIdentity? = runCatching {
        PublicationIdentity(value.getString("incarnation"), value.getLong("consentRevision"),
            value.getLong("policyEpoch"), value.getLong("snapshotRevision"), value.getString("fingerprint"))
            .takeIf { it.incarnation.length in 16..64 && it.consentRevision > 0 && it.policyEpoch > 0 &&
                it.snapshotRevision > 0 && it.fingerprint.matches(Regex("[a-fA-F0-9]{64}")) }
    }.getOrNull()

    /** 标明 Int/Long 类型，避免 JSON 把小 Long 自动读为 Int 后破坏完整文档校验。 */
    fun encode(document: Map<String, Any>): String {
        require(document.keys == RemoteHookConfigContract.persistedKeys)
        val array = JSONArray()
        document.toSortedMap().forEach { (key, value) ->
            val type = when (value) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is String -> "s"; else -> error("type") }
            array.put(JSONObject().put("k", key).put("t", type).put("v", value))
        }
        return array.toString().also { require(it.length <= MAX_DOCUMENT_CHARS) }
    }
    fun decode(raw: String): Map<String, Any>? = runCatching {
        if (raw.length > MAX_DOCUMENT_CHARS) return@runCatching null
        val array = JSONArray(raw)
        if (array.length() != RemoteHookConfigContract.persistedKeys.size) return@runCatching null
        val result = linkedMapOf<String, Any>()
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val key = entry.getString("k")
            if (key !in RemoteHookConfigContract.persistedKeys || key in result || entry.length() != 3) return@runCatching null
            val rawValue = entry.get("v")
            val value: Any = when (entry.getString("t")) {
                "b" -> rawValue as? Boolean ?: return@runCatching null
                "s" -> rawValue as? String ?: return@runCatching null
                "i" -> rawValue as? Int ?: return@runCatching null
                "l" -> when (rawValue) { is Int -> rawValue.toLong(); is Long -> rawValue; else -> return@runCatching null }
                else -> return@runCatching null
            }
            result[key] = value
        }
        result
    }.getOrNull()
}
