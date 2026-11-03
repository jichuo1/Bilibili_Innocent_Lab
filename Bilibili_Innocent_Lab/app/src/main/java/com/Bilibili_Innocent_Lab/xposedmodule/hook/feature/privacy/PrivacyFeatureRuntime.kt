package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.highcapable.kavaref.extension.classOf
import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup

internal class PrivacyFeatureRuntime(
    private val context: Context,
    private val noLogin: Boolean,
    private val incognito: Boolean,
    private val skipOnboarding: Boolean = false
) {
    fun installers(): List<FeatureInstaller> = listOf(
        FunctionalFeatureInstaller("incognito_privacy") { env ->
            if (!incognito) FeatureInstallResult.Skipped("disabled")
            else {
                val session = IncognitoSession()
                // Broadcast switches may be cached while the remaining host surfaces are resolved.
                // Install the protective disable first, before any preflight can initialize them.
                val broadcast = runCatching { IncognitoBroadcastConfig().prepare(env)?.invoke() }.getOrNull() ?: run {
                    env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                    runCatching { env.reportStatus("incognito_broadcast_policy", "unavailable") }
                    0
                }
                // Keep reads sealed until every account/identity adapter has installed. An optional
                // host API disappearing must not leave an authenticated network path open.
                val ready = java.util.concurrent.atomic.AtomicBoolean(false)
                // ALL-OR-NOTHING PREFLIGHT for the remaining network, account and web isolation:
                // the earlier independent broadcast disable is deliberately protective even if this
                // preflight fails. Do not install any of these other boundaries on a missing anchor.
                val networkApply = IncognitoNetworkHooks(session) { ready.get() }.prepare(env)
                val accountApply = prepareAccountIsolation(env, session)
                val webApply = IncognitoWebHooks().prepare(env)
                // The host's KMP gRPC pipeline bypasses the Java moss boundary above. If the build has
                // one that cannot be routed, incognito must not start: that channel would stay open.
                val kmpPrepared = KmpMossBoundary.prepare(env, session) { ready.get() }
                // KMP HTTP bypasses the OkHttp and moss boundaries. Broadcast uses the host's
                // disabled-stream configuration when the relevant adapters can be located.
                val ignetApply = IgnetHttpBoundary(session) { ready.get() }.prepare(env)
                if (networkApply == null || accountApply == null || kmpPrepared == null || ignetApply == null) {
                    env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ADAPTED)
                    env.reportStatus("incognito_privacy_status",
                        "not-applicable-host:" + listOfNotNull(
                            "network".takeIf { networkApply == null },
                            "account-isolation".takeIf { accountApply == null },
                            "kmp-moss".takeIf { kmpPrepared == null },
                            "ignet-http".takeIf { ignetApply == null }).joinToString(","))
                    return@FunctionalFeatureInstaller FeatureInstallResult.Skipped("missing-host-structure")
                }
                // Enter the session only once the boundary is guaranteed to install completely.
                session.enter()
                val network = networkApply()
                val count = accountApply()
                val web = webApply()
                kmpBoundary = (kmpPrepared as? KmpMossBoundary.Prepared.Ready)?.boundary
                val kmp = kmpBoundary?.install() ?: 0
                val ignet = ignetApply()
                // Audit-only: shows what the native-ignet Ktor client sends, which no boundary here covers.
                val ktorProbe = KtorTrafficProbe.install(env)
                ready.set(true)
                activeSession = session
                activeIdentityBus = IncognitoIdentityBus(context, session) {
                    env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                }.also { bus -> env.postToMain?.invoke { bus.register() } }
                env.reportStatus("incognito_privacy_status", "partial")
                // Network adapters are version-bound. Never report end-to-end privacy as verified.
                FeatureInstallResult.Installed(count + network + web + kmp + ignet + broadcast + ktorProbe, complete = false)
            }
        },
        FunctionalFeatureInstaller("guest_browsing") { env ->
            if (!noLogin && !incognito) FeatureInstallResult.Skipped("disabled")
            else if (env.processName != "tv.danmaku.bili") FeatureInstallResult.Skipped("non-main-process")
            else {
                val host = PrivacyHostAccess(requireNotNull(env.classLoader))
                // Account state (login flag) is the gate every guest sub-feature reads. It must
                // resolve before anything runs; a host build without it skips guest browsing cleanly
                // (not-applicable-host) rather than throwing mid-way. BiliAccounts.get(Context)/isLogin
                // are stable, un-obfuscated names across all supported host builds.
                val accounts = PrivacyStructuralResolver.type(host.loader, "com.bilibili.lib.accounts.BiliAccounts")
                val get = accounts?.let { PrivacyStructuralResolver.method(it, "get", classOf<Context>()) }
                val login = accounts?.let { PrivacyStructuralResolver.method(it, "isLogin") }
                if (get == null || login == null) {
                    env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ADAPTED)
                    env.reportStatus("guest_browsing_status", "not-applicable-host:account-state")
                    return@FunctionalFeatureInstaller FeatureInstallResult.Skipped("missing-host-structure")
                }
                val guest = { incognito || login.invoke(get.invoke(null, context)) == false }
                val library = GuestLibraryRuntime(context, incognito, guest) {
                    env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR)
                }
                // The network and local memory share exactly one identity generation.
                if (incognito) activeSession?.let { network ->
                    library.useNetworkSession(network) { activeIdentityBus?.publish(it) }
                }
                env.postToMain?.invoke { library.preload() }
                val prompts = GuestBrowsingFeatureInstaller(true, guest).install(env)
                val onboarding = if (skipOnboarding) runCatching { GuestOnboardingHooks(guest).install(env) }.getOrElse {
                    env.logError("guest_onboarding", "[BIL] Guest onboarding adapter unavailable")
                    0
                } else 0
                val playbackHooks = GuestPlaybackHooks(library, guest)
                val playback = runCatching { playbackHooks.install(env) }.getOrElse {
                    env.logError("guest_playback", "[BIL] Guest playback adapter unavailable")
                    0
                }
                val history = GuestNativeHistoryHooks(library, guest, host)
                // KMP gRPC pipeline (8.92+ history screen and other KMP services): shared with incognito
                // when active, otherwise installed here only to serve the local library.
                var kmpHooks = 0
                val kmp = kmpBoundary ?: (KmpMossBoundary.prepare(env, null) { true } as? KmpMossBoundary.Prepared.Ready)
                    ?.boundary?.also { kmpHooks = it.install() }
                kmp?.attach(guest, { rpc, payload -> history.kmpReply(rpc, payload) },
                    playbackHooks.kmpViewObserver(env, host))
                val hooks = onboarding + GuestLibraryHostHooks(library, guest, history).install(env) + playback +
                    kmpHooks + installMineOverlayGuard(env, guest) + GuestMineOverlayHooks(guest).install(env) +
                    GuestMineMenuHooks(guest).install(env) +
                    GuestFavoritesRouteHooks(guest).install(env) + GuestVideoFavoriteHooks(GuestVideoFavoriteHooks.storeFor(library), guest, picker = GuestFavoriteFolderPicker(), storyStar = GuestStoryStar(), pageFavorite = GuestPageFavorite(), hostDialog = GuestHostFavoriteDialog(), interactionSource = GuestFavoriteInteraction(), hostSnackbar = GuestHostFavoriteSnackbar()).install(env) +
                    GuestIgnetLibraryResponses(guest, { path, params ->
                        library.nativeBlocking {
                            if (NativeWatchLaterProtocol.handles(path)) NativeWatchLaterProtocol.respond(path, params, it) { aid -> library.videoMetadata(aid, 0) }
                            else NativeFavoriteProtocol.respond(path, params, it)
                        }
                    }).install(env)
                FeatureInstallResult.Installed(hooks + ((prompts as? FeatureInstallResult.Installed)?.hookCount ?: 0), complete = false)
            }
        }
    )

    private var activeSession: IncognitoSession? = null
    private var activeIdentityBus: IncognitoIdentityBus? = null
    private var kmpBoundary: KmpMossBoundary? = null

    private fun installMineOverlayGuard(env: HookEnvironment, guest: () -> Boolean): Int {
        val fragment = Lookup.classOrNull(env.classLoader, "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment")
            ?: return 0
        val method = Lookup.methodOrNull(fragment, "Ir", classOf<Boolean>()) ?: return 0
        // Ir(boolean) only creates/shows HomeUserCenterForceLoginFragment; it is not the login button.
        env.registrar.exact("guest.mine.overlay", fragment, method.name, *method.parameterTypes) {
            before { if (guest()) result = null }
        }
        return 1
    }

    private fun installAccountIsolation(env: HookEnvironment, session: IncognitoSession): Int =
        prepareAccountIsolation(env, session)?.invoke()
            ?: error("Account isolation anchors unavailable")

    /**
     * Resolve every required account/identity/telemetry anchor FIRST, collecting the registrations
     * as deferred closures. Return null (registering nothing) when any required host structure is
     * missing, so the caller skips the whole incognito feature rather than leaving a partial,
     * leaking boundary. The applier performs all registrations only after every anchor resolved.
     */
    private fun prepareAccountIsolation(env: HookEnvironment, session: IncognitoSession): (() -> Int)? = runCatching {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val plan = PrivacyInstallPlan()
        var count = 0
        val accounts = host.type("com.bilibili.lib.accounts.BiliAccounts")
        val values: Map<String, Any?> = mapOf(
            "isLogin" to false, "isTokenValid" to false, "isTokenExpired" to true,
            "getAccessKey" to "", "loadAccessTokenString" to null, "loadAccessTokenMid" to 0L,
            "mid" to 0L, "getAccessToken" to null, "loadAccessToken" to null,
            "getAccountCookie" to null, "hasFastLogin" to false, "hasFirstLogin" to false,
            "getFastLoginInfo" to null, "fetchAvailableAccounts" to emptyList<Any>()
        )
        values.forEach { (name, value) ->
            val m = requireNotNull(Lookup.methodOrNull(accounts, name))
            // A host page the module opened for the guest may see itself logged in (GuestLoginScope); nothing else does.
            val scoped = name == "isLogin"
            plan.step { env.registrar.exact("incognito.account.$name", accounts, name, *m.parameterTypes) {
                before { result = if (scoped && GuestLoginScope.active) true else value }
            } }
            count++
        }
        // Guest responses must never expire/revoke the real account persisted before the session.
        Lookup.declaredMethods(accounts) { it.returnType == Void.TYPE &&
            it.name in setOf("logout", "logoutByUser", "clearAccessToken", "clearAccessTokenByAuthJsb",
                "clearAccessTokenModel", "clearFastLogin", "signedInWithToken") }.forEach { m ->
            plan.step { env.registrar.exact("incognito.account.preserve.${m.name}.${m.parameterCount}", accounts, m.name, *m.parameterTypes) {
                before { result = null }
            } }
            count++
        }
        for (ownerName in listOf("com.bilibili.lib.biliid.api.BuvidHelper", "com.bilibili.api.BiliConfig")) {
            val owner = host.type(ownerName)
            Lookup.declaredMethods(owner) { it.returnType == classOf<String>() && it.parameterCount == 0 &&
                it.name in setOf("getBuvid", "getLocalBuvid", "getRemoteBuvid", "softGetBuvid",
                    "getFpLocal", "getFpRemote", "getFp", "getSessionId", "getGuestId", "getAccessKey") }.forEach { m ->
                plan.step { env.registrar.exact("incognito.identity.$ownerName.${m.name}", owner, m.name) {
                    before {
                        result = when (m.name) {
                            "getAccessKey" -> ""
                            "getSessionId" -> session.identity()!!.sessionId
                            else -> session.identity()!!.buvid
                        }
                    }
                } }
                count++
            }
        }

        for (name in listOf("com.bilibili.api.PhoneDeviceId", "com.bilibili.api.DisplayID")) {
            val owner = host.type(name)
            requireNotNull(Lookup.methodOrNull(owner, "get"))
            plan.step { env.registrar.exact("incognito.identity.$name", owner, "get") {
                before { result = session.identity()!!.buvid }
            } }
            count++
        }
        // Version-independent identity scrub: the metadata/device protobuf messages are immutable,
        // so every construction path (including the obfuscated RPC factory) writes identity through
        // their Builder setters. The Metadata/Device protos and their Builder setters are stable
        // across all host builds, so resolving the Builder via newBuilder() and replacing the
        // buvid/access-key/fp args there seals identity without pinning the obfuscated factory.
        for ((msgName, setters) in mapOf(
            "com.bapis.bilibili.metadata.Metadata" to mapOf("setAccessKey" to false, "setBuvid" to true),
            "com.bapis.bilibili.metadata.device.Device" to mapOf("setBuvid" to true, "setFpLocal" to true, "setFpRemote" to true, "setFp" to true, "setGuestId" to true)
        )) {
            val msg = Lookup.classOrNull(host.loader, msgName) ?: continue
            val builderType = requireNotNull(Lookup.methodOrNull(msg, "newBuilder")).returnType
            setters.forEach { (setter, fingerprint) ->
                val m = Lookup.methodOrNull(builderType, setter, classOf<String>()) ?: return@forEach
                plan.step { env.registrar.exact("incognito.metadata.$msgName.$setter", builderType, m.name, *m.parameterTypes) {
                    before { args[0] = if (fingerprint) session.identity()!!.buvid else "" }
                } }
                count++
            }
        }
        // The obfuscated RPC-metadata factory (serialized bytes + identify_v1 header) is host-build
        // specific. It is NOT required: the setter hooks above plus the OkHttp/gRPC boundary already
        // seal identity. Adapt it only when its 8.90.2 shape is present; never block the feature on it.
        runCatching { host.type("hv2.a") }.getOrNull()?.let { metadataFactory ->
            val stringFactories = mapOf("l" to { "" }, "a" to { session.identity()!!.buvid })
                .filterKeys { Lookup.methodOrNull(metadataFactory, it) != null }
            stringFactories.forEach { (method, value) ->
                plan.step { env.registrar.exact("incognito.rpc.identity.$method", metadataFactory, method) {
                    before { result = value() }
                } }
                count++
            }
            val metadataEvidence = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            for ((method, typeName) in mapOf("p" to "com.bapis.bilibili.metadata.Metadata",
                "m" to "com.bapis.bilibili.metadata.device.Device")) {
                if (Lookup.methodOrNull(metadataFactory, method) == null) continue
                val type = host.type(typeName)
                plan.step { env.registrar.exact("incognito.rpc.metadata.$method", metadataFactory, method) {
                    after {
                        if (hasThrowable) return@after
                        val bytes = result as? ByteArray
                        // Set the safe fallback before reflection. PROTECT_HOST must never restore
                        // the original credentials when a future host changes this adapter.
                        result = byteArrayOf()
                        runCatching {
                            val original = requireNotNull(host.static(type, "parseFrom", requireNotNull(bytes)))
                            val builder = requireNotNull(host.call(original, "toBuilder"))
                            val buvid = requireNotNull(session.identity()).buvid
                            host.call(builder, "setBuvid", buvid)
                            if (method == "p") host.call(builder, "setAccessKey", "")
                            else {
                                for (setter in listOf("setFp", "setFpLocal", "setFpRemote", "setGuestId"))
                                    host.call(builder, setter, buvid)
                                host.call(builder, "setFts", 0L)
                            }
                            result = host.call(requireNotNull(host.call(builder, "build")), "toByteArray")
                            if (metadataEvidence.add(method)) env.reportStatus("incognito_rpc_metadata_$method", "sanitized")
                        }.onFailure {
                            env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                            if (metadataEvidence.add("error-$method")) env.logError("incognito_rpc_metadata_$method",
                                "[BIL] RPC metadata adapter failed: ${it.javaClass.simpleName}; " +
                                    if (it is IllegalArgumentException) it.message.orEmpty() else "host-call-failed")
                        }
                    }
                } }
                count++
            }
        }
        val telemetryApply = requireNotNull(IncognitoTelemetryHooks(session).prepare(env))
        val provider = host.type("com.bilibili.search2.provider.BiliSearchSuggestionProvider")
        requireNotNull(Lookup.methodOrNull(provider, "query", classOf<android.net.Uri>(),
            classOf<Array<String>>(), classOf<String>(), classOf<Array<String>>(), classOf<String>()))
        val recent = classOf<android.provider.SearchRecentSuggestions>()

        return@runCatching {
            plan.apply()
            count += telemetryApply()
            env.registrar.exact("incognito.search.query", provider, "query", classOf<android.net.Uri>(),
                classOf<Array<String>>(), classOf<String>(), classOf<Array<String>>(), classOf<String>()) {
                before {
                    result = android.database.MatrixCursor(arrayOf("_id", "suggest_intent_query", "suggest_text_1", "suggest_icon_1"))
                }
            }
            count++
            // Android's search provider must neither write nor erase the user's pre-session history.
            env.registrar.exact("incognito.search.save", recent, "saveRecentQuery", classOf<String>(), classOf<String>()) {
                before { result = null }
            }
            env.registrar.exact("incognito.search.clear", recent, "clearHistory") { before { result = null } }
            count + 2
        }
    }.getOrNull()
}
