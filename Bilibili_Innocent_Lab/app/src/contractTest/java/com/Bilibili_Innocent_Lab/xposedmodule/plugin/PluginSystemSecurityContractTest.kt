package com.Bilibili_Innocent_Lab.xposedmodule.plugin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.KotlinSourceScanner
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 插件系统全链路安全与隔离契约门禁：
 * 1. `PluginPayloadProvider` 校验可信调用者 UID、拒绝任何写入模式，并已在 `AndroidManifest.xml` 注册；
 * 2. `PluginApiBridgeClassLoader` 阻断宿主、LSPosed、Meguviz 与 VMDebug 等敏感包前缀及 Xposed 资源；
 * 3. `KavaMemberLookup` 将 `PluginApiBridgeClassLoader` 及其子加载器标记为不可缓存，避免泄漏进持久化 `loaderCaches`；
 * 4. `RemoteHookConfigContract.KEY_PLUGIN_INDEX_DIGEST` 受远端摘要保护、不进 `SettingsCatalog`、禁止 `LiveHookConfigState` 热更新；
 * 5. `HookEntry` 在 `pluginIndexDigest` 为空时短路，不构造或触发 `PluginBootstrap`；
 * 6. `HostReceiptHost` 保留 `AgentWiringContractTest` 锚点并独立支持 `HostReceiptWire.PLUGIN_DIAGNOSTICS`，不改核心诊断 schema v3；
 * 7. `PluginSettingsStore` / `DeclarativePluginSettingsRenderer` / `PluginRuntimeDiagnostics` 保持命名空间、UI 与遥测隔离。
 */
class PluginSystemSecurityContractTest {

    fun pluginPayloadProviderEnforcesTrustedUidReadOnlyAndManifestEntry() {
        val manifest = SourceContract.read("src/main/AndroidManifest.xml")
        assertTrue(
            "AndroidManifest.xml must declare PluginPayloadProvider",
            manifest.contains("PluginPayloadProvider")
        )
        assertTrue(
            "AndroidManifest.xml must bind \${applicationId}.plugin.payload authority",
            manifest.contains("\${applicationId}.plugin.payload")
        )

        val providerPath = "provider/PluginPayloadProvider.kt"
        val raw = SourceContract.read(providerPath)
        val code = KotlinSourceScanner.code(raw)
        assertTrue(
            "PluginPayloadProvider must check Binder.getCallingUid()",
            code.contains("Binder.getCallingUid()")
        )
        assertTrue(
            "PluginPayloadProvider must verify trusted target package or enforceTrustedCaller",
            code.contains("TARGET_PACKAGE") || code.contains("tv.danmaku.bili")
        )
        assertTrue(
            "PluginPayloadProvider must throw SecurityException on untrusted caller",
            code.contains("SecurityException")
        )

        assertTrue(
            "PluginPayloadProvider must declare PATH_SETTINGS = \"plugin_settings\"",
            code.contains("PATH_SETTINGS") && code.contains("\"plugin_settings\"")
        )

        val queryFn = KotlinSourceScanner.function(
            listOf(providerPath to raw),
            "query",
            "PluginPayloadProvider.query"
        )
        val queryCode = KotlinSourceScanner.code(queryFn)
        val enforceFirstIdx = listOf(
            queryCode.indexOf("enforceTrustedCaller("),
            queryCode.indexOf("Binder.getCallingUid()")
        ).filter { it >= 0 }.minOrNull() ?: -1
        val pathSettingsIdx = queryCode.indexOf("PATH_SETTINGS")
        assertTrue(
            "PluginPayloadProvider.query must handle PATH_SETTINGS",
            pathSettingsIdx >= 0
        )
        assertTrue(
            "PluginPayloadProvider.query must enforce caller trust",
            enforceFirstIdx >= 0
        )
        val enforceForSettings = enforceFirstIdx < pathSettingsIdx ||
            queryCode.indexOf("enforceTrustedCaller(", startIndex = pathSettingsIdx) >= 0 ||
            queryCode.indexOf("Binder.getCallingUid()", startIndex = pathSettingsIdx) >= 0
        assertTrue(
            "PluginPayloadProvider.query must enforce trusted caller for PATH_SETTINGS",
            enforceForSettings
        )
        assertFalse(
            "PluginPayloadProvider.query must stay strictly read-only",
            queryCode.contains("MODE_WRITE_ONLY") || queryCode.contains("MODE_READ_WRITE")
        )

        val openFile = KotlinSourceScanner.function(
            listOf(providerPath to raw),
            "openFile",
            "PluginPayloadProvider.openFile"
        )
        val openFileCode = KotlinSourceScanner.code(openFile)
        assertTrue(
            "openFile must validate caller trust",
            openFileCode.contains("enforceTrustedCaller") || openFileCode.contains("Binder.getCallingUid()")
        )
        assertTrue(
            "openFile must strictly allow only read-only mode \"r\"",
            openFileCode.contains("\"r\"")
        )
        assertTrue(
            "openFile must open ParcelFileDescriptor in MODE_READ_ONLY",
            openFileCode.contains("MODE_READ_ONLY")
        )
        assertFalse(
            "openFile must never use MODE_WRITE_ONLY",
            openFileCode.contains("MODE_WRITE_ONLY")
        )
        assertFalse(
            "openFile must never use MODE_READ_WRITE",
            openFileCode.contains("MODE_READ_WRITE")
        )

        for (mutator in listOf("insert", "update", "delete")) {
            val fn = KotlinSourceScanner.function(
                listOf(providerPath to raw),
                mutator,
                "PluginPayloadProvider.$mutator"
            )
            val fnCode = KotlinSourceScanner.code(fn)
            assertTrue(
                "PluginPayloadProvider.$mutator must reject writes",
                fnCode.contains("UnsupportedOperationException") ||
                    fnCode.contains("SecurityException") ||
                    fnCode.contains("return null") ||
                    fnCode.contains("return 0")
            )
        }
    }

    fun bridgeClassLoaderBlocksHostXposedMeguvizAndResourceLeakage() {
        val path = "plugin/sandbox/PluginApiBridgeClassLoader.kt"
        val raw = SourceContract.read(path)
        val code = KotlinSourceScanner.code(raw)

        val headerEnd = code.indexOf("override fun loadClass").let { if (it > 0) it else code.length }
        assertTrue(
            "PluginApiBridgeClassLoader must pass ClassLoader(null) for dual-parent zero-leakage firewall",
            Regex("""class\s+PluginApiBridgeClassLoader\b[\s\S]*?:\s*ClassLoader\s*\(\s*null\s*\)""")
                .containsMatchIn(code.substring(0, headerEnd))
        )

        for (blockedPrefix in listOf(
            "com.Bilibili_Innocent_Lab.",
            "io.github.libxposed.",
            "io.github.jichuo1."
        )) {
            assertTrue(
                "PluginApiBridgeClassLoader must block prefix '$blockedPrefix'",
                code.contains("\"$blockedPrefix\"")
            )
        }
        assertTrue(
            "PluginApiBridgeClassLoader must allow plugin-api prefix",
            code.contains("com.bilab.plugin.api.")
        )
        assertTrue(
            "PluginApiBridgeClassLoader must throw ClassNotFoundException when blocked",
            code.contains("ClassNotFoundException")
        )

        val loadClass = KotlinSourceScanner.function(
            listOf(path to raw),
            "loadClass",
            "PluginApiBridgeClassLoader.loadClass"
        )
        val loadClassCode = KotlinSourceScanner.code(loadClass)
        val throwIndex = loadClassCode.indexOf("ClassNotFoundException")
        val parentCallIndex = listOf(
            loadClassCode.indexOf("super.loadClass"),
            loadClassCode.indexOf("parent")
        ).filter { it >= 0 }.minOrNull() ?: Int.MAX_VALUE
        assertTrue(
            "loadClass must reject blocked classes before delegating to parent",
            throwIndex in 0 until parentCallIndex ||
                loadClassCode.contains("isBlocked") ||
                loadClassCode.contains("isAllowed")
        )

        val hasResourceGuard = KotlinSourceScanner.functions(raw, "getResource").isNotEmpty() ||
            KotlinSourceScanner.functions(raw, "findResource").isNotEmpty()
        assertTrue(
            "PluginApiBridgeClassLoader must guard resource lookups against host/xposed leakage",
            hasResourceGuard
        )
    }

    fun kavaMemberLookupTreatsPluginBridgeClassLoadersAsNonCacheable() {
        val path = "runtime/KavaMemberLookup.kt"
        val raw = SourceContract.read(path)

        val isolatedCheck = KotlinSourceScanner.function(
            listOf(path to raw),
            "isIsolatedOrDynamicLoader",
            "KavaMemberLookup.isIsolatedOrDynamicLoader"
        )
        val isolatedCode = KotlinSourceScanner.code(isolatedCheck)
        assertTrue(
            "isIsolatedOrDynamicLoader must inspect ClassLoader parent chain",
            isolatedCode.contains(".parent")
        )
        assertTrue(
            "isIsolatedOrDynamicLoader must detect plugin package or PluginApiBridgeClassLoader",
            isolatedCode.contains("com.Bilibili_Innocent_Lab.xposedmodule.plugin.") ||
                isolatedCode.contains("PluginApiBridgeClassLoader")
        )

        val loaderCacheFn = KotlinSourceScanner.function(
            listOf(path to raw),
            "loaderCache",
            "KavaMemberLookup.loaderCache"
        )
        val loaderCacheCode = KotlinSourceScanner.code(loaderCacheFn)
        val uncachedReturn = loaderCacheCode.after("if (!isCacheableLoader(loader))").before("synchronized(loaderCaches)")
        assertTrue(
            "Uncacheable plugin loaders must return an ephemeral LoaderCache before loaderCaches access",
            uncachedReturn.contains("return LoaderCache()")
        )
    }

    fun remoteHookConfigProtectsPluginIndexDigestWithoutTouchingSettingsCatalogOrLiveApply() {
        val contractPath = "settings/remote/RemoteHookConfigContract.kt"
        val contractRaw = SourceContract.read(contractPath)
        val contractCode = KotlinSourceScanner.code(contractRaw)

        assertTrue(
            "RemoteHookConfigContract must declare KEY_PLUGIN_INDEX_DIGEST",
            contractCode.contains("KEY_PLUGIN_INDEX_DIGEST") && contractCode.contains("\"plugin_index_digest\"")
        )
        val runtimeKeys = KotlinSourceScanner.property(
            listOf(contractPath to contractRaw),
            "runtimeKeys",
            "RemoteHookConfigContract.runtimeKeys"
        )
        assertTrue(
            "runtimeKeys must include KEY_PLUGIN_INDEX_DIGEST",
            KotlinSourceScanner.code(runtimeKeys).contains("KEY_PLUGIN_INDEX_DIGEST")
        )
        val digestFn = KotlinSourceScanner.function(
            listOf(contractPath to contractRaw),
            "digest",
            "RemoteHookConfigContract.digest"
        )
        assertTrue(
            "RemoteHookConfigContract.digest must cover KEY_PLUGIN_INDEX_DIGEST",
            KotlinSourceScanner.code(digestFn).contains("KEY_PLUGIN_INDEX_DIGEST")
        )

        val catalogRaw = SourceContract.read("settings/backup/SettingsCatalog.kt")
        assertFalse(
            "SettingsCatalog must never include KEY_PLUGIN_INDEX_DIGEST",
            catalogRaw.contains("KEY_PLUGIN_INDEX_DIGEST") || catalogRaw.contains("plugin_index_digest")
        )
        assertFalse(
            "SettingsCatalog must never merge plugin namespace keys",
            catalogRaw.contains("PluginSettingKeySpace")
        )

        val livePath = "hook/config/LiveHookConfigState.kt"
        val liveRaw = SourceContract.read(livePath)
        val liveApply = KotlinSourceScanner.function(
            listOf(livePath to liveRaw),
            "apply",
            "LiveHookConfigState.apply"
        )
        val liveApplyCode = KotlinSourceScanner.code(liveApply)
        assertFalse(
            "LiveHookConfigState.apply must never allow hot-updating KEY_PLUGIN_INDEX_DIGEST",
            Regex("""allowed\s*(?:\+=|\.add\()\s*[^;\n]*(?:KEY_PLUGIN_INDEX_DIGEST|"plugin_index_digest")""")
                .containsMatchIn(liveApplyCode)
        )
    }

    fun hookEntryShortCircuitsBootstrapWhenPluginIndexDigestIsEmpty() {
        val hookPath = "hook/HookEntry.kt"
        val hookRaw = SourceContract.read(hookPath)
        val hookCode = KotlinSourceScanner.code(hookRaw)

        assertTrue(
            "HookEntry must read KEY_PLUGIN_INDEX_DIGEST",
            hookCode.contains("KEY_PLUGIN_INDEX_DIGEST")
        )
        assertTrue(
            "HookEntry must reference PluginBootstrap for active plugin configurations",
            hookCode.contains("PluginBootstrap")
        )

        val bootstrapCallers = KotlinSourceScanner.nonNestedFunctions(hookRaw)
            .filter { (_, body) -> KotlinSourceScanner.code(body).contains("PluginBootstrap") }
        assertTrue(
            "Expected at least one HookEntry function invoking PluginBootstrap",
            bootstrapCallers.isNotEmpty()
        )

        val emptyGuardPattern = Regex("""isEmpty\(\)|isBlank\(\)|isNotEmpty\(\)|isNotBlank\(\)""")
        val guarded = bootstrapCallers.all { (_, body) ->
            val bodyCode = KotlinSourceScanner.code(body)
            val bootstrapIdx = bodyCode.indexOf("PluginBootstrap")
            val digestIdx = bodyCode.indexOf("KEY_PLUGIN_INDEX_DIGEST")
            if (digestIdx >= 0) {
                val guardMatch = emptyGuardPattern.find(bodyCode, startIndex = digestIdx)
                bootstrapIdx > digestIdx &&
                    guardMatch != null &&
                    guardMatch.range.first < bootstrapIdx
            } else {
                val guardMatch = emptyGuardPattern.find(bodyCode)
                guardMatch != null &&
                    guardMatch.range.first < bootstrapIdx &&
                    hookCode.contains("KEY_PLUGIN_INDEX_DIGEST")
            }
        }
        assertTrue(
            "HookEntry must guard every PluginBootstrap path behind an empty/blank check on KEY_PLUGIN_INDEX_DIGEST",
            guarded
        )
    }

    fun hostReceiptHostRetainsAgentWiringAllowlistAndRoutesPluginDiagnosticsIndependently() {
        val hostPath = "runtime/HostReceiptHost.kt"
        val hostRaw = SourceContract.read(hostPath)
        assertTrue(
            "HostReceiptHost must preserve AgentWiringContractTest literal allowlist condition",
            hostRaw.contains("channel != HostReceiptWire.DIAGNOSTICS && channel !in MineComponentSnapshotCodec.ALLOWED_SURFACES")
        )
        val hostCode = KotlinSourceScanner.code(hostRaw)
        assertTrue(
            "HostReceiptHost must route PLUGIN_DIAGNOSTICS queries to PluginRuntimeDiagnostics.response(nonce)",
            hostCode.contains("HostReceiptWire.PLUGIN_DIAGNOSTICS") &&
                hostCode.contains("PluginRuntimeDiagnostics.response(nonce)")
        )
        val publishFn = KotlinSourceScanner.function(
            listOf(hostPath to hostRaw),
            "publish",
            "HostReceiptHost.publish"
        )
        val publishCode = KotlinSourceScanner.code(publishFn)
        assertTrue(
            "HostReceiptHost.publish must permit HostReceiptWire.PLUGIN_DIAGNOSTICS and guard parcel size",
            publishCode.contains("HostReceiptWire.PLUGIN_DIAGNOSTICS") &&
                publishCode.contains("HostReceiptWire.MAX_PARCEL_BYTES")
        )

        val registryRaw = SourceContract.read("runtime/HostReceiptRegistry.kt")
        val registryCode = KotlinSourceScanner.code(registryRaw)
        assertTrue(
            "HostReceiptRegistry must handle HostReceiptWire.PLUGIN_DIAGNOSTICS via PluginRuntimeDiagnostics",
            registryCode.contains("HostReceiptWire.PLUGIN_DIAGNOSTICS") &&
                registryCode.contains("PluginRuntimeDiagnostics.")
        )

        val coreDiagRaw = SourceContract.read("runtime/HostRuntimeDiagnostics.kt")
        val coreDiagCode = KotlinSourceScanner.code(coreDiagRaw)
        assertTrue(
            "HostRuntimeDiagnosticsCodec must stay on CURRENT_SCHEMA_VERSION = 3",
            coreDiagCode.contains("const val CURRENT_SCHEMA_VERSION = 3")
        )
        assertFalse(
            "HostRuntimeDiagnosticsCodec must stay decoupled from plugin diagnostics",
            coreDiagCode.contains("PluginRuntimeDiagnostics") || coreDiagCode.contains("PLUGIN_DIAGNOSTICS")
        )
    }

    fun pluginSettingsUiAndDiagnosticsStayIsolatedFromCorePrivilegedStores() {
        val storeCode = KotlinSourceScanner.code(
            SourceContract.read("plugin/settings/PluginSettingsStore.kt")
        )
        assertTrue(
            "PluginSettingsStore must enforce PluginSettingKeySpace.requireIsolatedKey",
            storeCode.contains("PluginSettingKeySpace.requireIsolatedKey(")
        )
        assertFalse(
            "PluginSettingsStore must never touch SettingsCatalog",
            storeCode.contains("SettingsCatalog")
        )
        assertFalse(
            "PluginSettingsStore must never touch FeaturePreferences",
            storeCode.contains("FeaturePreferences")
        )
        assertFalse(
            "PluginSettingsStore must never touch RUNTIME_API_KEYS",
            storeCode.contains("RUNTIME_API_KEYS")
        )

        val rendererCode = KotlinSourceScanner.code(
            SourceContract.read("plugin/ui/DeclarativePluginSettingsRenderer.kt")
        )
        assertFalse(
            "DeclarativePluginSettingsRenderer must stay pure view-model without Android View references",
            rendererCode.contains("android.view.") || rendererCode.contains("ClassLoader")
        )

        val diagCode = KotlinSourceScanner.code(
            SourceContract.read("plugin/diagnostics/PluginRuntimeDiagnostics.kt")
        )
        assertFalse(
            "PluginRuntimeDiagnostics must not couple to HostRuntimeDiagnosticsCodec or RUNTIME_API_KEYS",
            diagCode.contains("HostRuntimeDiagnosticsCodec") || diagCode.contains("RUNTIME_API_KEYS")
        )
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (PluginSystemSecurityContractTest) -> Unit) {
            val s = PluginSystemSecurityContractTest()
            block(s)
        }
        runIsolated { s -> s.pluginPayloadProviderEnforcesTrustedUidReadOnlyAndManifestEntry() }
        runIsolated { s -> s.bridgeClassLoaderBlocksHostXposedMeguvizAndResourceLeakage() }
        runIsolated { s -> s.kavaMemberLookupTreatsPluginBridgeClassLoadersAsNonCacheable() }
        runIsolated { s -> s.remoteHookConfigProtectsPluginIndexDigestWithoutTouchingSettingsCatalogOrLiveApply() }
        runIsolated { s -> s.hookEntryShortCircuitsBootstrapWhenPluginIndexDigestIsEmpty() }
        runIsolated { s -> s.hostReceiptHostRetainsAgentWiringAllowlistAndRoutesPluginDiagnosticsIndependently() }
        runIsolated { s -> s.pluginSettingsUiAndDiagnosticsStayIsolatedFromCorePrivilegedStores() }
    }
}
