package com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime

import android.content.ContentProviderClient
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.BilpPackageVerifier
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.BilpVerifyException
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.PluginIndexCodec
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.PluginIndexEntry
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.TrustedSignerRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.container.VerifiedBilpBundle
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeDiagnostics
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.diagnostics.PluginRuntimeStatus
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.HostResolvedFieldRef
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.HostResolvedMemberRef
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.PluginDiagnosticSink
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.PluginHookBridge
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.PluginSettingsReader
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.SandboxedPluginHookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.TierCExtensionSink
import com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.TierCPluginRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.provider.PluginPayloadProvider
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.payload.DualChannelFdOpener
import com.bilab.core.settings.PluginSettingKeySpace
import com.bilab.plugin.api.model.AdaptPatchEntry
import com.bilab.plugin.api.model.DexQuerySpec
import com.bilab.plugin.api.model.HostAppInfo
import com.bilab.plugin.api.model.HostThemeSnapshot
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginQuarantineReason
import com.bilab.plugin.api.model.PluginTier
import com.bilab.plugin.api.spi.ContentFilterExtension
import com.bilab.plugin.api.spi.DanmakuBatchFilter
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

internal interface PluginPackageSource {
    fun fetchIndexJson(): String?
    fun fetchPackageBytes(entry: PluginIndexEntry): ByteArray?
    fun fetchSettingsJson(): String? = null
}

/**
 * Host-side read-only [PluginPackageSource] backed by [PluginPayloadProvider].
 * Uses unstable [ContentProviderClient] channels so provider restarts or crashes never tear down the host.
 */
internal class ProviderPluginPackageSource(
    private val context: Context
) : PluginPackageSource {

    private class ClientBackedInputStream(
        delegate: InputStream,
        private val client: ContentProviderClient
    ) : FilterInputStream(delegate) {
        override fun close() {
            try {
                super.close()
            } finally {
                runCatching { client.close() }
            }
        }
    }

    override fun fetchIndexJson(): String? =
        querySingleStringColumn(
            uri = PluginPayloadProvider.INDEX_URI,
            columnName = PluginPayloadProvider.COLUMN_INDEX_JSON
        )

    override fun fetchSettingsJson(): String? =
        querySingleStringColumn(
            uri = PluginPayloadProvider.SETTINGS_URI,
            columnName = PluginPayloadProvider.COLUMN_SETTINGS_JSON
        )

    override fun fetchPackageBytes(entry: PluginIndexEntry): ByteArray? {
        val resolver = context.contentResolver ?: return null
        val uri = PluginPayloadProvider.PACKAGE_URI.buildUpon()
            .appendQueryParameter(PluginPayloadProvider.PARAM_PLUGIN_ID, entry.pluginId)
            .build()
        val unstableClient = runCatching {
            resolver.acquireUnstableContentProviderClient(PluginPayloadProvider.AUTHORITY)
        }.getOrNull()
        val stream = DualChannelFdOpener.openReadOnly(
            openRemote = {
                val client = unstableClient ?: return@openReadOnly null
                try {
                    val pfd = client.openFile(uri, "r")
                    if (pfd != null) {
                        ClientBackedInputStream(ParcelFileDescriptor.AutoCloseInputStream(pfd), client)
                    } else {
                        runCatching { client.close() }
                        null
                    }
                } catch (_: Throwable) {
                    runCatching { client.close() }
                    null
                }
            },
            openProvider = {
                // Only fall back to ContentResolver when acquireUnstableContentProviderClient is unavailable
                // (e.g., Robolectric/mock resolvers) so a crashed remote provider never kills the host.
                if (unstableClient != null) {
                    null
                } else {
                    resolver.openFileDescriptor(uri, "r")?.let { pfd ->
                        ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    }
                }
            }
        ) ?: return null

        return stream.use { input ->
            val maxBytes = BilpPackageVerifier.MAX_PACKAGE_FILE_BYTES.toInt()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                if (out.size() + read > maxBytes) {
                    return null
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    private fun querySingleStringColumn(uri: Uri, columnName: String): String? {
        val resolver = context.contentResolver ?: return null
        val unstableClient = runCatching {
            resolver.acquireUnstableContentProviderClient(PluginPayloadProvider.AUTHORITY)
        }.getOrNull()
        val cursor = if (unstableClient != null) {
            try {
                unstableClient.query(uri, arrayOf(columnName), null, null, null)
            } catch (_: Throwable) {
                null
            }
        } else {
            runCatching {
                resolver.query(uri, arrayOf(columnName), null, null, null)
            }.getOrNull()
        }
        return try {
            cursor?.use { c ->
                if (!c.moveToFirst()) return@use null
                val idx = c.getColumnIndex(columnName)
                if (idx < 0) null else c.getString(idx)
            }
        } finally {
            runCatching { unstableClient?.close() }
        }
    }
}

internal enum class PluginBootstrapStatus {
    SKIPPED_NO_PLUGINS,
    SUCCEEDED,
    INDEX_UNAVAILABLE,
    INDEX_DIGEST_MISMATCH,
    PARTIAL_QUARANTINED
}

internal data class PluginBootstrapResult(
    val status: PluginBootstrapStatus,
    val indexDigest: String = "",
    val activeTierBIds: List<String> = emptyList(),
    val activeTierCIds: List<String> = emptyList(),
    val quarantined: Map<String, PluginQuarantineReason> = emptyMap(),
    val adaptResolution: PluginAdaptResolution = PluginAdaptResolution.EMPTY
) {
    val activePlugins: List<String> = Collections.unmodifiableList(activeTierBIds + activeTierCIds)
    val isEmpty: Boolean get() = status == PluginBootstrapStatus.SKIPPED_NO_PLUGINS

    companion object {
        val EMPTY = PluginBootstrapResult(status = PluginBootstrapStatus.SKIPPED_NO_PLUGINS)
    }
}

/**
 * Host-side plugin bootstrap coordinator with strict zero-overhead fast-path when
 * [pluginIndexDigest] is blank, fail-closed index/package verification, and per-plugin isolation.
 */
internal object PluginBootstrap {

    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    private val DEFAULT_DIAGNOSTIC_SINK = PluginDiagnosticSink { id, tier, status, reason, faults, hooks ->
        PluginRuntimeDiagnostics.record(id, tier, status, reason, faults, hooks)
    }

    private class PendingTierCPlugin(
        val entry: PluginIndexEntry,
        val bundle: VerifiedBilpBundle,
        val packageBytes: ByteArray
    )

    fun bootstrap(
        pluginIndexDigest: String,
        hostVersionCode: Long,
        hostAppInfo: HostAppInfo,
        themeSnapshot: HostThemeSnapshot,
        hostClassLoader: ClassLoader?,
        source: PluginPackageSource,
        tierBRuntime: TierBPluginRuntime,
        tierCRuntime: TierCPluginRuntime? = null,
        hookBridge: PluginHookBridge = PluginHookBridge.NO_OP,
        signerRegistry: TrustedSignerRegistry = TrustedSignerRegistry(),
        settingsReader: PluginSettingsReader = PluginSettingsReader.EMPTY,
        diagnosticsSink: PluginDiagnosticSink? = DEFAULT_DIAGNOSTIC_SINK,
        apiLevel: Int = PluginApiVersion.CURRENT_API_LEVEL,
        apkPaths: List<File> = emptyList()
    ): PluginBootstrapResult {
        if (pluginIndexDigest.isBlank()) return PluginBootstrapResult.EMPTY

        val indexJson = HostThreadGuard.call("plugin.bootstrap.fetch_index", null) {
            source.fetchIndexJson()
        }
        if (indexJson.isNullOrBlank()) {
            return PluginBootstrapResult(
                status = PluginBootstrapStatus.INDEX_UNAVAILABLE,
                indexDigest = pluginIndexDigest
            )
        }

        val indexDoc = PluginIndexCodec.decodeJson(indexJson)
        if (indexDoc == null || !PluginIndexCodec.verifyIndexDigest(indexJson, pluginIndexDigest)) {
            if (indexDoc != null) {
                for (entry in indexDoc.entries) {
                    if (entry.enabled) {
                        recordDiagnostic(
                            diagnosticsSink,
                            entry.pluginId,
                            entry.tier,
                            PluginRuntimeStatus.QUARANTINED,
                            PluginQuarantineReason.SIGNATURE_MISMATCH
                        )
                    }
                }
            }
            return PluginBootstrapResult(
                status = PluginBootstrapStatus.INDEX_DIGEST_MISMATCH,
                indexDigest = pluginIndexDigest
            )
        }

        val settingsJson = HostThreadGuard.call("plugin.bootstrap.fetch_settings", null) {
            source.fetchSettingsJson()
        }
        val settingsSnapshot = decodePluginSettingsSnapshot(settingsJson)

        val activeTierB = ArrayList<String>()
        val activeTierC = ArrayList<String>()
        val quarantined = LinkedHashMap<String, PluginQuarantineReason>()
        val activeQueryMap = LinkedHashMap<String, List<DexQuerySpec>>()
        val activePatchEntries = ArrayList<AdaptPatchEntry>()
        val pendingTierC = ArrayList<PendingTierCPlugin>()

        for (entry in indexDoc.entries) {
            if (entry.quarantineReason != null) {
                quarantined[entry.pluginId] = entry.quarantineReason
                recordDiagnostic(
                    diagnosticsSink,
                    entry.pluginId,
                    entry.tier,
                    PluginRuntimeStatus.QUARANTINED,
                    entry.quarantineReason
                )
                continue
            }
            if (!entry.enabled) {
                recordDiagnostic(
                    diagnosticsSink,
                    entry.pluginId,
                    entry.tier,
                    PluginRuntimeStatus.DISABLED,
                    null
                )
                continue
            }

            try {
                val packageBytes = source.fetchPackageBytes(entry)
                if (packageBytes == null || packageBytes.isEmpty()) {
                    quarantineEntry(
                        entry,
                        PluginQuarantineReason.INVALID_PACKAGE,
                        quarantined,
                        diagnosticsSink
                    )
                    continue
                }
                val actualSha = sha256Hex(packageBytes)
                val expectedSha = entry.packageSha256.lowercase(Locale.ROOT)
                if (!MessageDigest.isEqual(
                        actualSha.toByteArray(Charsets.US_ASCII),
                        expectedSha.toByteArray(Charsets.US_ASCII)
                    )
                ) {
                    quarantineEntry(
                        entry,
                        PluginQuarantineReason.SIGNATURE_MISMATCH,
                        quarantined,
                        diagnosticsSink
                    )
                    continue
                }

                val bundle = BilpPackageVerifier.verify(
                    packageBytes = packageBytes,
                    hostVersionCode = hostVersionCode,
                    signerRegistry = signerRegistry,
                    apiLevel = apiLevel
                )
                if (!matchesIndexEntry(entry, bundle)) {
                    quarantineEntry(
                        entry,
                        PluginQuarantineReason.SIGNATURE_MISMATCH,
                        quarantined,
                        diagnosticsSink
                    )
                    continue
                }

                when (bundle.manifest.tier) {
                    PluginTier.DECLARATIVE_BUNDLE -> {
                        val loaded = HostThreadGuard.call("plugin.tierb.${entry.pluginId}", null) {
                            tierBRuntime.activate(bundle, hostVersionCode)
                        }
                        if (loaded == null) {
                            quarantineEntry(
                                entry,
                                PluginQuarantineReason.INVALID_PACKAGE,
                                quarantined,
                                diagnosticsSink
                            )
                        } else {
                            activeTierB += entry.pluginId
                            if (loaded.dexQueries.isNotEmpty()) {
                                activeQueryMap[entry.pluginId] = loaded.dexQueries
                            }
                            for (patch in loaded.adaptPatches) {
                                if (PluginAdaptIntegration.isEntryActiveForHost(patch, hostVersionCode)) {
                                    activePatchEntries += patch
                                }
                            }
                            recordDiagnostic(
                                diagnosticsSink,
                                entry.pluginId,
                                PluginTier.DECLARATIVE_BUNDLE,
                                PluginRuntimeStatus.ACTIVE,
                                null
                            )
                        }
                    }

                    PluginTier.SIGNED_CODE -> {
                        if (tierCRuntime == null) {
                            quarantineEntry(
                                entry,
                                PluginQuarantineReason.PERMISSION_DENIED,
                                quarantined,
                                diagnosticsSink
                            )
                            continue
                        }
                        if (bundle.dexQueries.isNotEmpty()) {
                            activeQueryMap[entry.pluginId] = bundle.dexQueries
                        }
                        val patches = bundle.adaptPatches?.entries.orEmpty()
                        for (patch in patches) {
                            if (PluginAdaptIntegration.isEntryActiveForHost(patch, hostVersionCode)) {
                                activePatchEntries += patch
                            }
                        }
                        pendingTierC += PendingTierCPlugin(
                            entry = entry,
                            bundle = bundle,
                            packageBytes = packageBytes
                        )
                    }
                }
            } catch (ex: BilpVerifyException) {
                quarantineEntry(entry, ex.reason, quarantined, diagnosticsSink)
            } catch (throwable: Throwable) {
                HostThreadGuard.report("plugin.bootstrap.${entry.pluginId}", throwable)
                quarantineEntry(entry, PluginQuarantineReason.INVALID_PACKAGE, quarantined, diagnosticsSink)
            }
        }

        val adaptResolution = PluginAdaptIntegration.resolveAll(
            apkPaths = apkPaths,
            hostVersionCode = hostVersionCode,
            hostClassLoader = hostClassLoader,
            pluginQueries = activeQueryMap,
            adaptPatches = activePatchEntries
        )

        if (tierCRuntime != null && pendingTierC.isNotEmpty()) {
            val acceptedQueryIdsByPlugin = LinkedHashMap<String, MutableSet<String>>()
            for (qualified in adaptResolution.plan.accepted) {
                acceptedQueryIdsByPlugin
                    .getOrPut(qualified.pluginId) { LinkedHashSet() }
                    .add(qualified.spec.queryId)
            }

            for (pending in pendingTierC) {
                val entry = pending.entry
                try {
                    val pluginId = entry.pluginId
                    val resolvedMembers = LinkedHashMap<String, Member>()
                    val pluginQueryIds = acceptedQueryIdsByPlugin[pluginId].orEmpty()
                    for (queryId in pluginQueryIds) {
                        adaptResolution.resolvedMethods[queryId]?.let { member ->
                            resolvedMembers[queryId] = member
                        }
                    }
                    val pluginPatches = pending.bundle.adaptPatches?.entries.orEmpty()
                    if (pluginPatches.isNotEmpty()) {
                        val patchResolved = PluginAdaptIntegration.resolveAdaptPatchEntries(
                            entries = pluginPatches,
                            hostVersionCode = hostVersionCode,
                            hostClassLoader = hostClassLoader
                        )
                        for ((key, member) in patchResolved) {
                            if (member != null) {
                                resolvedMembers[key] = member
                            }
                        }
                    }

                    val extensionSink = BootstrapTierCExtensionSink(
                        pipeline = tierBRuntime.contentFilterPipeline,
                        circuitBreaker = tierCRuntime.circuitBreaker,
                        resolvedMembersByPlugin = mapOf(pluginId to resolvedMembers)
                    )
                    val pluginSettingsReader = buildPluginSettingsReader(
                        pluginId = pluginId,
                        snapshot = settingsSnapshot,
                        fallback = settingsReader
                    )
                    val loaded = HostThreadGuard.call("plugin.tierc.$pluginId", null) {
                        tierCRuntime.activate(
                            bundle = pending.bundle,
                            packageBytes = pending.packageBytes,
                            hostAppInfo = hostAppInfo,
                            themeSnapshot = themeSnapshot,
                            hostClassLoader = hostClassLoader,
                            hookBridge = hookBridge,
                            settingsReader = pluginSettingsReader,
                            diagnosticsSink = diagnosticsSink,
                            extensionSink = extensionSink
                        )
                    }
                    if (loaded == null) {
                        val ledgerReason = runCatching {
                            tierCRuntime.bootLedger.quarantineReason(pluginId)
                        }.getOrNull() ?: PluginQuarantineReason.INVALID_PACKAGE
                        quarantined[pluginId] = ledgerReason
                    } else {
                        activeTierC += pluginId
                    }
                } catch (throwable: Throwable) {
                    HostThreadGuard.report("plugin.bootstrap.${entry.pluginId}", throwable)
                    quarantineEntry(entry, PluginQuarantineReason.INVALID_PACKAGE, quarantined, diagnosticsSink)
                }
            }
        }

        val finalStatus = if (quarantined.isEmpty()) {
            PluginBootstrapStatus.SUCCEEDED
        } else {
            PluginBootstrapStatus.PARTIAL_QUARANTINED
        }
        return PluginBootstrapResult(
            status = finalStatus,
            indexDigest = pluginIndexDigest,
            activeTierBIds = Collections.unmodifiableList(activeTierB),
            activeTierCIds = Collections.unmodifiableList(activeTierC),
            quarantined = Collections.unmodifiableMap(quarantined),
            adaptResolution = adaptResolution
        )
    }

    internal fun decodePluginSettingsSnapshot(rawJson: String?): Map<String, Any> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(rawJson)
            val result = LinkedHashMap<String, Any>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next() ?: continue
                if (!PluginSettingKeySpace.isPluginKey(key)) continue
                when (val value = json.opt(key)) {
                    is Boolean, is Int, is String -> result[key] = value
                    is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) {
                        result[key] = value.toInt()
                    }
                }
            }
            Collections.unmodifiableMap(result)
        }.getOrDefault(emptyMap())
    }

    private fun buildPluginSettingsReader(
        pluginId: String,
        snapshot: Map<String, Any>,
        fallback: PluginSettingsReader
    ): PluginSettingsReader {
        val hasPluginKeys = snapshot.keys.any { PluginSettingKeySpace.belongsToPlugin(it, pluginId) }
        if (!hasPluginKeys) return fallback
        return object : PluginSettingsReader {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
                if (!PluginSettingKeySpace.belongsToPlugin(key, pluginId)) {
                    return fallback.getBoolean(key, defaultValue)
                }
                return snapshot[key] as? Boolean ?: fallback.getBoolean(key, defaultValue)
            }

            override fun getInt(key: String, defaultValue: Int): Int {
                if (!PluginSettingKeySpace.belongsToPlugin(key, pluginId)) {
                    return fallback.getInt(key, defaultValue)
                }
                return snapshot[key] as? Int ?: fallback.getInt(key, defaultValue)
            }

            override fun getString(key: String, defaultValue: String): String {
                if (!PluginSettingKeySpace.belongsToPlugin(key, pluginId)) {
                    return fallback.getString(key, defaultValue)
                }
                return snapshot[key] as? String ?: fallback.getString(key, defaultValue)
            }
        }
    }

    private fun matchesIndexEntry(entry: PluginIndexEntry, bundle: VerifiedBilpBundle): Boolean {
        val m = bundle.manifest
        if (m.id != entry.pluginId) return false
        if (m.versionCode != entry.versionCode) return false
        if (m.tier != entry.tier) return false
        if (m.permissions != entry.permissions) return false
        val indexPub = entry.publicKeySha256?.lowercase(Locale.ROOT)
        val manifestPub = m.publicKeySha256?.lowercase(Locale.ROOT)
        if (indexPub != null && manifestPub != null && indexPub != manifestPub) return false
        return true
    }

    private fun quarantineEntry(
        entry: PluginIndexEntry,
        reason: PluginQuarantineReason,
        quarantined: MutableMap<String, PluginQuarantineReason>,
        diagnosticsSink: PluginDiagnosticSink?
    ) {
        quarantined[entry.pluginId] = reason
        recordDiagnostic(
            diagnosticsSink,
            entry.pluginId,
            entry.tier,
            PluginRuntimeStatus.QUARANTINED,
            reason
        )
    }

    private fun recordDiagnostic(
        sink: PluginDiagnosticSink?,
        pluginId: String,
        tier: PluginTier,
        status: PluginRuntimeStatus,
        reason: PluginQuarantineReason?
    ) {
        if (sink == null) return
        runCatching {
            sink.record(pluginId, tier, status, reason, faultCount = 0, hookCount = 0)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val chars = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            chars[i * 2] = HEX_DIGITS[v ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(chars)
    }

    private class BootstrapTierCExtensionSink(
        private val pipeline: ContentFilterPipeline,
        private val circuitBreaker: com.Bilibili_Innocent_Lab.xposedmodule.plugin.sandbox.PluginFaultCircuitBreaker,
        private val resolvedMembersByPlugin: Map<String, Map<String, Member>>
    ) : TierCExtensionSink {
        override fun handlesContentFilterFor(pipeline: ContentFilterPipeline): Boolean =
            this.pipeline === pipeline

        override fun registerContentFilter(pluginId: String, extension: ContentFilterExtension): AutoCloseable =
            pipeline.registerContentFilter(pluginId, extension) {
                circuitBreaker.recordFault(pluginId)
            }

        override fun registerDanmakuBatchFilter(pluginId: String, filter: DanmakuBatchFilter): AutoCloseable =
            pipeline.registerDanmakuBatchFilter(pluginId, filter) {
                circuitBreaker.recordFault(pluginId)
            }

        override fun resolveMember(
            pluginId: String,
            queryId: String,
            registrar: SandboxedPluginHookRegistrar
        ): HostResolvedMemberRef? {
            val member = resolvedMembersByPlugin[pluginId]?.get(queryId) ?: return null
            return (member as? Executable)?.let { registrar.issueMemberRef(it) }
        }

        override fun resolveField(
            pluginId: String,
            queryId: String,
            registrar: SandboxedPluginHookRegistrar
        ): HostResolvedFieldRef? {
            val member = resolvedMembersByPlugin[pluginId]?.get(queryId) ?: return null
            return (member as? Field)?.let { registrar.issueFieldRef(it) }
        }
    }
}
