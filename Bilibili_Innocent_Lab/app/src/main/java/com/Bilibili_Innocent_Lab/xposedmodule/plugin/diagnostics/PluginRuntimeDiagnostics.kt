package com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics

import android.content.Context
import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryContract
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsSource
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptQueryFailure
import com.bilab.plugin.api.model.PluginManifest
import com.bilab.plugin.api.model.PluginQuarantineReason
import com.bilab.plugin.api.model.PluginTier
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Closed set of lifecycle statuses reported per plugin over [HostReceiptWire.PLUGIN_DIAGNOSTICS].
 */
internal enum class PluginRuntimeStatus {
    ACTIVE,
    QUARANTINED,
    UNLOADED,
    DISABLED
}

/**
 * Strictly sanitized per-plugin telemetry entry.
 *
 * Contains only bounded identifiers, enums, and numeric counts; never carries API keys,
 * setting values, content text, UIDs, or exception stacks.
 */
internal data class PluginDiagnosticEntry(
    val pluginId: String,
    val tier: PluginTier,
    val status: PluginRuntimeStatus,
    val quarantineReason: PluginQuarantineReason? = null,
    val faultCount: Int = 0,
    val hookCount: Int = 0
) {
    init {
        require(PluginRuntimeDiagnosticsCodec.isValidPluginId(pluginId)) {
            "Invalid pluginId '$pluginId'"
        }
        require(faultCount in 0..PluginRuntimeDiagnosticsCodec.MAX_FAULT_COUNT) {
            "faultCount out of bounds: $faultCount"
        }
        require(hookCount in 0..PluginRuntimeDiagnosticsCodec.MAX_HOOK_COUNT) {
            "hookCount out of bounds: $hookCount"
        }
    }

    fun sanitized(): PluginDiagnosticEntry = copy(
        faultCount = faultCount.coerceIn(0, PluginRuntimeDiagnosticsCodec.MAX_FAULT_COUNT),
        hookCount = hookCount.coerceIn(0, PluginRuntimeDiagnosticsCodec.MAX_HOOK_COUNT),
        quarantineReason = if (status == PluginRuntimeStatus.ACTIVE) null else quarantineReason
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", pluginId)
        .put("tier", tier.name)
        .put("status", status.name)
        .put("quarantine_reason", quarantineReason?.name ?: JSONObject.NULL)
        .put("faults", faultCount)
        .put("hooks", hookCount)
}

/**
 * Independent plugin diagnostics snapshot isolated from [com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsCodec].
 */
internal data class PluginRuntimeDiagnosticsSnapshot(
    val capturedAtEpochMs: Long,
    val processName: String = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE,
    val plugins: List<PluginDiagnosticEntry> = emptyList(),
    val schemaVersion: Int = PluginRuntimeDiagnosticsCodec.CURRENT_SCHEMA_VERSION
)

internal data class CachedPluginDiagnostics(
    val snapshot: PluginRuntimeDiagnosticsSnapshot,
    val source: HostRuntimeDiagnosticsSource
)

/**
 * Strict whitelist JSON codec for [HostReceiptWire.PLUGIN_DIAGNOSTICS].
 *
 * Rejects unknown keys, arbitrary text fields, duplicate plugin IDs, and oversized payloads.
 */
internal object PluginRuntimeDiagnosticsCodec {
    const val CURRENT_SCHEMA_VERSION = 1
    const val TARGET_PACKAGE = HostRuntimeDiagnosticsQueryContract.TARGET_PACKAGE
    const val MAX_PLUGIN_COUNT = 64
    const val MAX_PAYLOAD_CHARS = 16 * 1024
    const val MAX_HOOK_COUNT = 256
    const val MAX_FAULT_COUNT = 10_000

    private val ROOT_KEYS = setOf("schema", "captured_at", "process", "plugins")
    private val ENTRY_KEYS = setOf("id", "tier", "status", "quarantine_reason", "faults", "hooks")

    fun isValidPluginId(pluginId: String): Boolean =
        PluginManifest.ID_REGEX.matches(pluginId) && !pluginId.contains("..")

    fun encode(snapshot: PluginRuntimeDiagnosticsSnapshot): String {
        require(snapshot.schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported plugin diagnostics schema: ${snapshot.schemaVersion}"
        }
        require(snapshot.processName == TARGET_PACKAGE) {
            "Invalid plugin diagnostics process: ${snapshot.processName}"
        }
        require(snapshot.plugins.size <= MAX_PLUGIN_COUNT) {
            "Plugin diagnostics entry overflow: ${snapshot.plugins.size} > $MAX_PLUGIN_COUNT"
        }
        val array = JSONArray()
        val seenIds = HashSet<String>()
        for (entry in snapshot.plugins) {
            val clean = entry.sanitized()
            require(seenIds.add(clean.pluginId)) {
                "Duplicate plugin diagnostics entry '${clean.pluginId}'"
            }
            array.put(clean.toJson())
        }
        val encoded = JSONObject()
            .put("schema", CURRENT_SCHEMA_VERSION)
            .put("captured_at", snapshot.capturedAtEpochMs.coerceAtLeast(1L))
            .put("process", TARGET_PACKAGE)
            .put("plugins", array)
            .toString()
        require(encoded.length <= MAX_PAYLOAD_CHARS) {
            "Plugin diagnostics payload exceeds MAX_PAYLOAD_CHARS=$MAX_PAYLOAD_CHARS"
        }
        require(encoded.toByteArray(Charsets.UTF_8).size <= HostReceiptWire.MAX_PARCEL_BYTES) {
            "Plugin diagnostics payload exceeds MAX_PARCEL_BYTES"
        }
        return encoded
    }

    fun decodeOrNull(raw: String): PluginRuntimeDiagnosticsSnapshot? = runCatching {
        if (raw.isBlank() || raw.length > MAX_PAYLOAD_CHARS) return@runCatching null
        if (raw.toByteArray(Charsets.UTF_8).size > HostReceiptWire.MAX_PARCEL_BYTES) return@runCatching null
        val root = JSONObject(raw)
        val rootKeyIter = root.keys()
        while (rootKeyIter.hasNext()) {
            if (rootKeyIter.next() !in ROOT_KEYS) return@runCatching null
        }
        if (root.optInt("schema", -1) != CURRENT_SCHEMA_VERSION) return@runCatching null
        if (root.optString("process") != TARGET_PACKAGE) return@runCatching null
        val capturedAt = root.optLong("captured_at", 0L)
        if (capturedAt <= 0L) return@runCatching null
        val array = root.optJSONArray("plugins") ?: return@runCatching null
        if (array.length() > MAX_PLUGIN_COUNT) return@runCatching null

        val entries = ArrayList<PluginDiagnosticEntry>(array.length())
        val seenIds = HashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return@runCatching null
            val itemKeyIter = item.keys()
            while (itemKeyIter.hasNext()) {
                if (itemKeyIter.next() !in ENTRY_KEYS) return@runCatching null
            }
            val id = item.opt("id") as? String ?: return@runCatching null
            if (!isValidPluginId(id) || !seenIds.add(id)) return@runCatching null
            val tierName = item.opt("tier") as? String ?: return@runCatching null
            val tier = runCatching { PluginTier.valueOf(tierName) }.getOrNull() ?: return@runCatching null
            val statusName = item.opt("status") as? String ?: return@runCatching null
            val status = runCatching { PluginRuntimeStatus.valueOf(statusName) }.getOrNull()
                ?: return@runCatching null
            val quarantineReason = if (!item.has("quarantine_reason") || item.isNull("quarantine_reason")) {
                null
            } else {
                val reasonName = item.opt("quarantine_reason") as? String ?: return@runCatching null
                runCatching { PluginQuarantineReason.valueOf(reasonName) }.getOrNull()
                    ?: return@runCatching null
            }
            val faultsAny = item.opt("faults")
            val faults = (faultsAny as? Number)?.toInt() ?: return@runCatching null
            if (faultsAny !is Int && faultsAny !is Long) return@runCatching null
            if (faults !in 0..MAX_FAULT_COUNT) return@runCatching null

            val hooksAny = item.opt("hooks")
            val hooks = (hooksAny as? Number)?.toInt() ?: return@runCatching null
            if (hooksAny !is Int && hooksAny !is Long) return@runCatching null
            if (hooks !in 0..MAX_HOOK_COUNT) return@runCatching null

            entries += PluginDiagnosticEntry(
                pluginId = id,
                tier = tier,
                status = status,
                quarantineReason = quarantineReason,
                faultCount = faults,
                hookCount = hooks
            )
        }
        PluginRuntimeDiagnosticsSnapshot(
            capturedAtEpochMs = capturedAt,
            processName = TARGET_PACKAGE,
            plugins = Collections.unmodifiableList(entries),
            schemaVersion = CURRENT_SCHEMA_VERSION
        )
    }.getOrNull()
}

/**
 * Independent plugin diagnostics registry, IPC bundle codec, and module-side receipt cache for
 * [HostReceiptWire.PLUGIN_DIAGNOSTICS].
 */
internal object PluginRuntimeDiagnostics {
    private const val PREFS_NAME = "plugin_runtime_diagnostics"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_TARGET_VERSION = "target_version"
    private const val KEY_TARGET_UPDATE = "target_update"
    private const val KEY_MODULE_VERSION = "module_version"
    private const val PARCEL_OVERHEAD_BYTES = 4096

    private val lock = Any()
    private val entries = LinkedHashMap<String, PluginDiagnosticEntry>()
    private var capturedAtEpochMs: Long = 1L

    @Volatile
    private var hostSource: HostRuntimeDiagnosticsSource? = null

    @Volatile
    private var receivedCache: CachedPluginDiagnostics? = null

    fun updateHostSource(source: HostRuntimeDiagnosticsSource?) {
        hostSource = source?.takeIf { it.isComplete }
    }

    fun record(entry: PluginDiagnosticEntry) {
        val clean = entry.sanitized()
        synchronized(lock) {
            if (clean.pluginId !in entries && entries.size >= PluginRuntimeDiagnosticsCodec.MAX_PLUGIN_COUNT) {
                return
            }
            entries[clean.pluginId] = clean
            capturedAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L)
        }
    }

    fun record(
        pluginId: String,
        tier: PluginTier,
        status: PluginRuntimeStatus,
        quarantineReason: PluginQuarantineReason? = null,
        faultCount: Int = 0,
        hookCount: Int = 0
    ) {
        if (!PluginRuntimeDiagnosticsCodec.isValidPluginId(pluginId)) return
        record(
            PluginDiagnosticEntry(
                pluginId = pluginId,
                tier = tier,
                status = status,
                quarantineReason = quarantineReason,
                faultCount = faultCount.coerceIn(0, PluginRuntimeDiagnosticsCodec.MAX_FAULT_COUNT),
                hookCount = hookCount.coerceIn(0, PluginRuntimeDiagnosticsCodec.MAX_HOOK_COUNT)
            )
        )
    }

    fun replaceAll(
        newEntries: List<PluginDiagnosticEntry>,
        capturedAtMs: Long = System.currentTimeMillis().coerceAtLeast(1L)
    ) {
        synchronized(lock) {
            entries.clear()
            for (entry in newEntries.take(PluginRuntimeDiagnosticsCodec.MAX_PLUGIN_COUNT)) {
                val clean = entry.sanitized()
                entries[clean.pluginId] = clean
            }
            capturedAtEpochMs = capturedAtMs.coerceAtLeast(1L)
        }
    }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    fun hasEntries(): Boolean = synchronized(lock) { entries.isNotEmpty() }

    fun snapshot(): PluginRuntimeDiagnosticsSnapshot = synchronized(lock) {
        PluginRuntimeDiagnosticsSnapshot(
            capturedAtEpochMs = capturedAtEpochMs.coerceAtLeast(1L),
            processName = PluginRuntimeDiagnosticsCodec.TARGET_PACKAGE,
            plugins = Collections.unmodifiableList(entries.values.toList()),
            schemaVersion = PluginRuntimeDiagnosticsCodec.CURRENT_SCHEMA_VERSION
        )
    }

    fun buildReport(): PluginRuntimeDiagnosticsSnapshot = snapshot()

    fun encodeSnapshot(): String = PluginRuntimeDiagnosticsCodec.encode(snapshot())

    fun encodeBundle(
        snapshot: PluginRuntimeDiagnosticsSnapshot,
        nonce: String,
        source: HostRuntimeDiagnosticsSource
    ): Bundle? {
        if (!HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce) || !source.isComplete) return null
        val payload = runCatching { PluginRuntimeDiagnosticsCodec.encode(snapshot) }.getOrNull() ?: return null
        val payloadBytes = payload.toByteArray(Charsets.UTF_8).size
        if (payloadBytes + PARCEL_OVERHEAD_BYTES > HostReceiptWire.MAX_PARCEL_BYTES) return null
        return Bundle().apply {
            putBoolean(HostRuntimeDiagnosticsQueryContract.EXTRA_HANDLED, true)
            putString(HostRuntimeDiagnosticsQueryContract.EXTRA_REQUEST_NONCE, nonce)
            putString(HostRuntimeDiagnosticsQueryContract.EXTRA_STATUS, HostRuntimeDiagnosticsQueryContract.STATUS_READY)
            putString(HostRuntimeDiagnosticsQueryContract.EXTRA_PAYLOAD, payload)
            putString(
                HostRuntimeDiagnosticsQueryContract.EXTRA_PAYLOAD_SHA256,
                HostRuntimeDiagnosticsQueryContract.sha256(payload)
            )
            putLong(HostRuntimeDiagnosticsQueryContract.EXTRA_TARGET_VERSION, source.targetVersionCode)
            putLong(HostRuntimeDiagnosticsQueryContract.EXTRA_TARGET_UPDATE_TIME, source.targetUpdateTime)
            putLong(HostRuntimeDiagnosticsQueryContract.EXTRA_MODULE_VERSION, source.moduleVersionCode)
        }
    }

    /** Host-side Binder query response for [HostReceiptWire.PLUGIN_DIAGNOSTICS]. */
    fun response(nonce: String): Bundle? {
        val currentSource = hostSource ?: return null
        return encodeBundle(snapshot(), nonce, currentSource)
    }

    fun validateEnvelope(
        payload: String,
        digest: String,
        source: HostRuntimeDiagnosticsSource,
        current: HostRuntimeDiagnosticsSource
    ): ReceiptQueryFailure {
        if (!source.isComplete || source != current) return ReceiptQueryFailure.SOURCE_MISMATCH
        if (payload.isEmpty() || payload.length > PluginRuntimeDiagnosticsCodec.MAX_PAYLOAD_CHARS) {
            return ReceiptQueryFailure.MALFORMED_RESPONSE
        }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8).size
        if (payloadBytes + PARCEL_OVERHEAD_BYTES > HostReceiptWire.MAX_PARCEL_BYTES) {
            return ReceiptQueryFailure.MALFORMED_RESPONSE
        }
        if (!HostRuntimeDiagnosticsQueryContract.digestMatches(payload, digest)) {
            return ReceiptQueryFailure.DIGEST_MISMATCH
        }
        if (PluginRuntimeDiagnosticsCodec.decodeOrNull(payload) == null) {
            return ReceiptQueryFailure.MALFORMED_RESPONSE
        }
        return ReceiptQueryFailure.NONE
    }

    fun receivePayload(
        payload: String,
        source: HostRuntimeDiagnosticsSource
    ): Boolean {
        if (!source.isComplete) return false
        val decoded = PluginRuntimeDiagnosticsCodec.decodeOrNull(payload) ?: return false
        receivedCache = CachedPluginDiagnostics(decoded, source)
        return true
    }

    fun receivePublished(
        context: Context,
        payload: String,
        source: HostRuntimeDiagnosticsSource
    ): Boolean {
        if (!receivePayload(payload, source)) return false
        val prefs = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return true
        return if (prefs.getString(KEY_PAYLOAD, null) == payload &&
            prefs.getLong(KEY_TARGET_VERSION, 0L) == source.targetVersionCode &&
            prefs.getLong(KEY_TARGET_UPDATE, 0L) == source.targetUpdateTime &&
            prefs.getLong(KEY_MODULE_VERSION, 0L) == source.moduleVersionCode
        ) {
            true
        } else {
            runCatching {
                prefs.edit()
                    .putString(KEY_PAYLOAD, payload)
                    .putLong(KEY_TARGET_VERSION, source.targetVersionCode)
                    .putLong(KEY_TARGET_UPDATE, source.targetUpdateTime)
                    .putLong(KEY_MODULE_VERSION, source.moduleVersionCode)
                    .commit()
            }.getOrDefault(false)
        }
    }

    fun decodeBundle(
        bundle: Bundle,
        expectedNonce: String,
        expectedSource: HostRuntimeDiagnosticsSource
    ): PluginRuntimeDiagnosticsSnapshot? {
        if (!HostRuntimeDiagnosticsQueryContract.isValidNonce(expectedNonce) || !expectedSource.isComplete) {
            return null
        }
        if (!bundle.getBoolean(HostRuntimeDiagnosticsQueryContract.EXTRA_HANDLED, false)) return null
        if (bundle.getString(HostRuntimeDiagnosticsQueryContract.EXTRA_REQUEST_NONCE) != expectedNonce) return null
        if (bundle.getString(HostRuntimeDiagnosticsQueryContract.EXTRA_STATUS) != HostRuntimeDiagnosticsQueryContract.STATUS_READY) {
            return null
        }
        val payload = bundle.getString(HostRuntimeDiagnosticsQueryContract.EXTRA_PAYLOAD).orEmpty()
        val digest = bundle.getString(HostRuntimeDiagnosticsQueryContract.EXTRA_PAYLOAD_SHA256).orEmpty()
        val source = HostRuntimeDiagnosticsSource(
            targetVersionCode = bundle.getLong(HostRuntimeDiagnosticsQueryContract.EXTRA_TARGET_VERSION, 0L),
            targetUpdateTime = bundle.getLong(HostRuntimeDiagnosticsQueryContract.EXTRA_TARGET_UPDATE_TIME, 0L),
            moduleVersionCode = bundle.getLong(HostRuntimeDiagnosticsQueryContract.EXTRA_MODULE_VERSION, 0L)
        )
        if (validateEnvelope(payload, digest, source, expectedSource) != ReceiptQueryFailure.NONE) return null
        return PluginRuntimeDiagnosticsCodec.decodeOrNull(payload)
    }

    fun receiveBundle(
        bundle: Bundle,
        expectedNonce: String,
        expectedSource: HostRuntimeDiagnosticsSource
    ): PluginRuntimeDiagnosticsSnapshot? {
        val decoded = decodeBundle(bundle, expectedNonce, expectedSource) ?: return null
        receivedCache = CachedPluginDiagnostics(decoded, expectedSource)
        return decoded
    }

    fun latestReceived(): CachedPluginDiagnostics? = receivedCache

    fun readCached(context: Context, expectedSource: HostRuntimeDiagnosticsSource): PluginRuntimeDiagnosticsSnapshot? {
        if (!expectedSource.isComplete) return null
        val inMemory = receivedCache
        if (inMemory != null && inMemory.source == expectedSource) return inMemory.snapshot
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val storedSource = HostRuntimeDiagnosticsSource(
                targetVersionCode = prefs.getLong(KEY_TARGET_VERSION, 0L),
                targetUpdateTime = prefs.getLong(KEY_TARGET_UPDATE, 0L),
                moduleVersionCode = prefs.getLong(KEY_MODULE_VERSION, 0L)
            )
            if (storedSource != expectedSource) return@runCatching null
            val payload = prefs.getString(KEY_PAYLOAD, null).orEmpty()
            PluginRuntimeDiagnosticsCodec.decodeOrNull(payload)
        }.getOrNull()
    }

    internal fun resetForTests() {
        synchronized(lock) {
            entries.clear()
            capturedAtEpochMs = 1L
        }
        hostSource = null
        receivedCache = null
    }
}
