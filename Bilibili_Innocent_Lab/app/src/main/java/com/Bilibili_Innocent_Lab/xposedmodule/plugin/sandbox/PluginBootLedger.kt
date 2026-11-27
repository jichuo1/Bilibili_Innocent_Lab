package com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.SecurePayloadStore
import com.bilab.plugin.api.model.PluginQuarantineReason
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Collections
import java.util.LinkedHashMap

internal data class BootLedgerEntry(
    val pluginId: String,
    val versionCode: Int,
    val consecutiveFailures: Int = 0,
    val quarantineReason: PluginQuarantineReason? = null
)

internal interface BootLedgerStore {
    fun loadAll(): Map<String, BootLedgerEntry>
    fun saveAll(entries: Map<String, BootLedgerEntry>)
}

/**
 * Thread-safe in-memory ledger store for JVM tests.
 */
internal class InMemoryBootLedgerStore : BootLedgerStore {
    private val lock = Any()
    private val entries = LinkedHashMap<String, BootLedgerEntry>()

    override fun loadAll(): Map<String, BootLedgerEntry> = synchronized(lock) {
        Collections.unmodifiableMap(LinkedHashMap(entries))
    }

    override fun saveAll(entries: Map<String, BootLedgerEntry>) {
        synchronized(lock) {
            this.entries.clear()
            this.entries.putAll(entries)
        }
    }
}

/**
 * Atomic file-backed ledger store using NOFOLLOW_LINKS checks, fsync, and ATOMIC_MOVE rename
 * via [SecurePayloadStore] so unclosed crashes survive process death.
 */
internal class FileBootLedgerStore(
    private val file: File,
    private val enforcePermissions: Boolean = true
) : BootLedgerStore {
    private val lock = Any()

    override fun loadAll(): Map<String, BootLedgerEntry> = synchronized(lock) {
        val path = file.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return@synchronized emptyMap()
        }
        if (Files.isSymbolicLink(path) || !SecurePayloadStore.isSafeRegularFile(file)) {
            throw SecurityException("Boot ledger path is unsafe: ${file.name}")
        }
        val length = file.length()
        if (length <= 0L) return@synchronized emptyMap()
        if (length > MAX_LEDGER_BYTES) {
            throw IllegalStateException("Boot ledger exceeds max size: $length")
        }
        val raw = Files.readAllBytes(path).toString(Charsets.UTF_8)
        return@synchronized decodeJson(raw)
    }

    override fun saveAll(entries: Map<String, BootLedgerEntry>) {
        synchronized(lock) {
            val parent = file.parentFile
                ?: throw IllegalArgumentException("Boot ledger file must have a parent directory: ${file.name}")
            ensureSafeParentDir(parent)
            val path = file.toPath()
            if (Files.isSymbolicLink(path)) {
                throw SecurityException("Boot ledger target must not be a symbolic link: ${file.name}")
            }
            val payload = encodeJson(entries).toByteArray(Charsets.UTF_8)
            SecurePayloadStore.writeAtomically(
                target = file,
                bytes = payload,
                enforcePermissions = enforcePermissions,
                fileMode = MODE_FILE_OWNER_RW,
                publicationLock = lock
            ).getOrThrow()
        }
    }

    private fun ensureSafeParentDir(parent: File) {
        val parentPath = parent.toPath()
        if (Files.isSymbolicLink(parentPath)) {
            throw SecurityException("Boot ledger parent directory is a symbolic link: ${parent.name}")
        }
        if (!Files.exists(parentPath, NOFOLLOW_LINKS)) {
            Files.createDirectories(parentPath)
        }
        if (!SecurePayloadStore.isSafeDirectory(parent)) {
            throw SecurityException("Boot ledger parent directory is unsafe: ${parent.name}")
        }
        if (enforcePermissions) {
            SecurePayloadStore.enforcePosixMode(parentPath, SecurePayloadStore.MODE_DIR_PRIVATE)
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_LEDGER_BYTES = 1024L * 1024L
        private const val MODE_FILE_OWNER_RW = 0x180 // 0600

        private fun encodeJson(entries: Map<String, BootLedgerEntry>): String {
            val array = JSONArray()
            for (entry in entries.values.sortedBy { it.pluginId }) {
                array.put(
                    JSONObject()
                        .put("plugin_id", entry.pluginId)
                        .put("version_code", entry.versionCode)
                        .put("consecutive_failures", entry.consecutiveFailures)
                        .put("quarantine_reason", entry.quarantineReason?.name ?: JSONObject.NULL)
                )
            }
            return JSONObject()
                .put("schema_version", SCHEMA_VERSION)
                .put("entries", array)
                .toString()
        }

        private fun decodeJson(raw: String): Map<String, BootLedgerEntry> {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
            if (root.optInt("schema_version", -1) != SCHEMA_VERSION) return emptyMap()
            val array = root.optJSONArray("entries") ?: return emptyMap()
            val out = LinkedHashMap<String, BootLedgerEntry>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val pluginId = item.optString("plugin_id", "").takeIf { it.isNotBlank() } ?: continue
                val versionCode = item.optInt("version_code", 0)
                if (versionCode <= 0) continue
                val failures = item.optInt("consecutive_failures", 0).coerceAtLeast(0)
                val reason = if (!item.has("quarantine_reason") || item.isNull("quarantine_reason")) {
                    null
                } else {
                    val reasonName = item.optString("quarantine_reason", "")
                    runCatching { PluginQuarantineReason.valueOf(reasonName) }.getOrNull()
                }
                out[pluginId] = BootLedgerEntry(
                    pluginId = pluginId,
                    versionCode = versionCode,
                    consecutiveFailures = failures,
                    quarantineReason = reason
                )
            }
            return Collections.unmodifiableMap(out)
        }
    }
}

/**
 * Tracks plugin boot attempts vs. clean completions across crashes and restarts.
 *
 * Each [recordAttempt] opens a boot slot before plugin bytecode runs; only [markSucceeded] closes
 * it cleanly. When consecutive unclosed attempts reach [maxFailures] (default 2), the plugin is
 * quarantined with [PluginQuarantineReason.BOOT_CRASH_LOOP] and future launches are blocked.
 */
internal class PluginBootLedger @JvmOverloads constructor(
    private val store: BootLedgerStore = InMemoryBootLedgerStore(),
    private val maxFailures: Int = DEFAULT_MAX_FAILURES
) {
    init {
        require(maxFailures >= 1) { "maxFailures must be >= 1, got $maxFailures" }
    }

    private val lock = Any()

    /**
     * Records a boot attempt for [pluginId] at [versionCode].
     *
     * Returns `true` if boot is permitted, or `false` if already quarantined or if prior unclosed
     * attempts have reached [maxFailures] (automatically quarantining with [PluginQuarantineReason.BOOT_CRASH_LOOP]).
     */
    fun recordAttempt(pluginId: String, versionCode: Int): Boolean {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        require(versionCode > 0) { "versionCode must be positive: $versionCode" }
        synchronized(lock) {
            val entries = store.loadAll().toMutableMap()
            val existing = entries[pluginId]
            if (existing?.quarantineReason != null) {
                return false
            }
            val priorUnclosed = if (existing != null && existing.versionCode == versionCode) {
                existing.consecutiveFailures
            } else {
                0
            }
            if (priorUnclosed >= maxFailures) {
                entries[pluginId] = BootLedgerEntry(
                    pluginId = pluginId,
                    versionCode = versionCode,
                    consecutiveFailures = priorUnclosed,
                    quarantineReason = PluginQuarantineReason.BOOT_CRASH_LOOP
                )
                store.saveAll(entries)
                return false
            }
            entries[pluginId] = BootLedgerEntry(
                pluginId = pluginId,
                versionCode = versionCode,
                consecutiveFailures = priorUnclosed + 1,
                quarantineReason = null
            )
            store.saveAll(entries)
            return true
        }
    }

    /**
     * Marks the current boot attempt complete and resets the consecutive failure counter.
     * Existing quarantines are preserved until explicitly cleared via [clear].
     */
    fun markSucceeded(pluginId: String, versionCode: Int) {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        require(versionCode > 0) { "versionCode must be positive: $versionCode" }
        synchronized(lock) {
            val entries = store.loadAll().toMutableMap()
            val existing = entries[pluginId]
            if (existing?.quarantineReason != null) {
                return
            }
            entries[pluginId] = BootLedgerEntry(
                pluginId = pluginId,
                versionCode = versionCode,
                consecutiveFailures = 0,
                quarantineReason = null
            )
            store.saveAll(entries)
        }
    }

    /**
     * Records an in-process boot exception without double-counting the open attempt from [recordAttempt].
     * Quarantines immediately if unclosed attempts have reached [maxFailures].
     */
    fun markFailed(pluginId: String, versionCode: Int) {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        require(versionCode > 0) { "versionCode must be positive: $versionCode" }
        synchronized(lock) {
            val entries = store.loadAll().toMutableMap()
            val existing = entries[pluginId]
            if (existing?.quarantineReason != null) {
                return
            }
            val failures = if (existing != null && existing.versionCode == versionCode) {
                existing.consecutiveFailures.coerceAtLeast(1)
            } else {
                1
            }
            val reason = if (failures >= maxFailures) PluginQuarantineReason.BOOT_CRASH_LOOP else null
            entries[pluginId] = BootLedgerEntry(
                pluginId = pluginId,
                versionCode = versionCode,
                consecutiveFailures = failures,
                quarantineReason = reason
            )
            store.saveAll(entries)
        }
    }

    fun isQuarantined(pluginId: String): Boolean = quarantineReason(pluginId) != null

    fun quarantineReason(pluginId: String): PluginQuarantineReason? = synchronized(lock) {
        store.loadAll()[pluginId]?.quarantineReason
    }

    fun consecutiveFailureCount(pluginId: String): Int = synchronized(lock) {
        store.loadAll()[pluginId]?.consecutiveFailures ?: 0
    }

    fun quarantine(pluginId: String, reason: PluginQuarantineReason) {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        synchronized(lock) {
            val entries = store.loadAll().toMutableMap()
            val existing = entries[pluginId]
            entries[pluginId] = BootLedgerEntry(
                pluginId = pluginId,
                versionCode = existing?.versionCode?.coerceAtLeast(1) ?: 1,
                consecutiveFailures = existing?.consecutiveFailures ?: 0,
                quarantineReason = reason
            )
            store.saveAll(entries)
        }
    }

    fun clear(pluginId: String) {
        synchronized(lock) {
            val entries = store.loadAll().toMutableMap()
            if (entries.remove(pluginId) != null) {
                store.saveAll(entries)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_FAILURES = 2
    }
}
