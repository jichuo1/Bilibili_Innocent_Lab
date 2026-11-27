package com.Bilibili_Innocent_Lab.xposedmodule.plugin.runtime

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
import com.bilab.plugin.api.model.HostAppInfo
import com.bilab.plugin.api.model.HostThemeSnapshot
import com.bilab.plugin.api.model.PluginApiVersion
import com.bilab.plugin.api.model.PluginQuarantineReason
import com.bilab.plugin.api.model.PluginTier
import com.bilab.plugin.api.spi.ContentFilterExtension
import com.bilab.plugin.api.spi.DanmakuBatchFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

internal interface PluginPackageSource {
    fun fetchIndexJson(): String?
    fun fetchPackageBytes(entry: PluginIndexEntry): ByteArray?
}

/**
 * Host-side read-only [PluginPackageSource] backed by [PluginPayloadProvider].
 * Only touches [Context.getContentResolver] when actually queried on non-empty plugin digests.
 */
internal class ProviderPluginPackageSource(
    private val context: Context
) : PluginPackageSource {

    override fun fetchIndexJson(): String? {
        val resolver = context.contentResolver ?: return null
        val cursor = runCatching {
            resolver.query(
                PluginPayloadProvider.INDEX_URI,
                arrayOf(PluginPayloadProvider.COLUMN_INDEX_JSON),
                null,
                null,
                null
            )
        }.getOrNull() ?: return null
        return cursor.use { c ->
            if (!c.moveToFirst()) return@use null
            val idx = c.getColumnIndex(PluginPayloadProvider.COLUMN_INDEX_JSON)
            if (idx < 0) null else c.getString(idx)
        }
    }

    override fun fetchPackageBytes(entry: PluginIndexEntry): ByteArray? {
        val resolver = context.contentResolver ?: return null
        val uri = PluginPayloadProvider.PACKAGE_URI.buildUpon()
            .appendQueryParameter(PluginPayloadProvider.PARAM_PLUGIN_ID, entry.pluginId)
            .build()
        val pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
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

        val activeTierB = ArrayList<String>()
        val activeTierC = ArrayList<String>()
        val quarantined = LinkedHashMap<String, PluginQuarantineReason>()
        val activeQueryMap = LinkedHashMap<String, List<com.bilab.plugin.api.model.DexQuerySpec>>()
        val activePatchEntries = ArrayList<com.bilab.plugin.api.model.AdaptPatchEntry>()

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
                        val resolvedMembers = resolveMembersForSignedBundle(
                            bundle = bundle,
                            apkPaths = apkPaths,
                            hostVersionCode = hostVersionCode,
                            hostClassLoader = hostClassLoader
                        )
                        val extensionSink = BootstrapTierCExtensionSink(
                            pipeline = tierBRuntime.contentFilterPipeline,
                            circuitBreaker = tierCRuntime.circuitBreaker,
                            resolvedMembersByPlugin = mapOf(entry.pluginId to resolvedMembers)
                        )
                        val loaded = HostThreadGuard.call("plugin.tierc.${entry.pluginId}", null) {
                            tierCRuntime.activate(
                                bundle = bundle,
                                packageBytes = packageBytes,
                                hostAppInfo = hostAppInfo,
                                themeSnapshot = themeSnapshot,
                                hostClassLoader = hostClassLoader,
                                hookBridge = hookBridge,
                                settingsReader = settingsReader,
                                diagnosticsSink = diagnosticsSink,
                                extensionSink = extensionSink
                            )
                        }
                        if (loaded == null) {
                            val ledgerReason = tierCRuntime.bootLedger.quarantineReason(entry.pluginId)
                                ?: PluginQuarantineReason.INVALID_PACKAGE
                            quarantined[entry.pluginId] = ledgerReason
                        } else {
                            activeTierC += entry.pluginId
                        }
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

    private fun resolveMembersForSignedBundle(
        bundle: VerifiedBilpBundle,
        apkPaths: List<File>,
        hostVersionCode: Long,
        hostClassLoader: ClassLoader?
    ): Map<String, Member> {
        val patches = bundle.adaptPatches?.entries.orEmpty()
        if (bundle.dexQueries.isEmpty() && patches.isEmpty()) return emptyMap()
        val resolution = PluginAdaptIntegration.resolveAll(
            apkPaths = apkPaths,
            hostVersionCode = hostVersionCode,
            hostClassLoader = hostClassLoader,
            pluginQueries = if (bundle.dexQueries.isEmpty()) emptyMap() else mapOf(bundle.manifest.id to bundle.dexQueries),
            adaptPatches = patches
        )
        val merged = LinkedHashMap<String, Member>()
        for ((key, member) in resolution.resolvedAdaptMembers) {
            if (member != null) merged[key] = member
        }
        for ((queryId, method) in resolution.resolvedMethods) {
            if (method != null) merged[queryId] = method
        }
        return merged
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
