package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PrivacyChannelAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class PrivacyFeatureRuntime(
    private val context: Context,
    private val noLogin: Boolean,
    private val incognito: Boolean,
    private val skipOnboarding: Boolean = false,
    kmpGrpcWireEngine: VersionAdapter.HookPoint? = null,
    privacyIgnetHttp: VersionAdapter.HookPoint? = null,
    privacyKNeuron: String? = null,
    guestPlayerCore: String? = null
) {
    private val kmpGrpcWireRef = AtomicReference(kmpGrpcWireEngine)
    private val privacyIgnetRef = AtomicReference(privacyIgnetHttp)
    private val privacyKNeuronRef = AtomicReference(privacyKNeuron)
    private val guestPlayerCoreRef = AtomicReference(guestPlayerCore)
    private val adaptedRetryConsumed = AtomicBoolean(false)
    private val incognitoInstalled = AtomicBoolean(false)
    private val guestInstalled = AtomicBoolean(false)

    @Volatile private var incognitoRetryPending = false
    @Volatile private var guestRetryPending = false

    fun updateAdaptedPoints(adapted: VersionAdapter.AdaptResult?) {
        adapted?.kmpGrpcWireEngine?.let(kmpGrpcWireRef::set)
        adapted?.privacyIgnetHttp?.let(privacyIgnetRef::set)
        adapted?.privacyKNeuron?.let(privacyKNeuronRef::set)
        adapted?.guestPlayerCore?.let(guestPlayerCoreRef::set)
        adaptedRetryConsumed.set(true)
    }

    private val incognitoInstaller: FeatureInstaller = FunctionalFeatureInstaller("incognito_privacy") { env ->
        if (!incognito) {
            incognitoRetryPending = false
            FeatureInstallResult.Skipped("disabled")
        } else if (incognitoInstalled.get()) {
            FeatureInstallResult.Installed(0, complete = false)
        } else {
            val session = IncognitoSession()
            // Broadcast switches may be cached while the remaining host surfaces are resolved.
            // Install the protective disable first, before any preflight can initialize them.
            val broadcastPrepare = runCatching { IncognitoBroadcastConfig().prepare(env) }.getOrNull()
            val broadcast = broadcastPrepare?.let { runCatching { it.invoke() }.getOrNull() } ?: run {
                env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                runCatching { env.reportStatus("incognito_broadcast_policy", "unavailable") }
                0
            }
            val broadcastMissing = broadcastPrepare == null && IncognitoBroadcastConfig.hasBroadcastSurface(env.classLoader)
            // Keep reads sealed until every account/identity adapter has installed. An optional
            // host API disappearing must not leave an authenticated network path open.
            val ready = AtomicBoolean(false)
            // ALL-OR-NOTHING PREFLIGHT for broadcast (when present), network, account, web, KMP, ignet:
            // Do not start incognito if any critical channel cannot be routed cleanly.
            val networkApply = IncognitoNetworkHooks(session) { ready.get() }.prepare(env)
            val accountApply = prepareAccountIsolation(env, session)
            val webApply = IncognitoWebHooks().prepare(env)
            // The host's KMP gRPC pipeline bypasses the Java moss boundary above. If the build has
            // one that cannot be routed, incognito must not start: that channel would stay open.
            val kmpPrepared = KmpMossBoundary.prepare(env, session, { ready.get() }, kmpGrpcWireRef.get())
            // KMP HTTP bypasses the OkHttp and moss boundaries. Broadcast uses the host's
            // disabled-stream configuration when the relevant adapters can be located.
            val ignetApply = IgnetHttpBoundary(session, cachedRequest = privacyIgnetRef.get()) { ready.get() }.prepare(env)
            if (broadcastMissing || networkApply == null || accountApply == null || kmpPrepared == null || ignetApply == null) {
                incognitoRetryPending = !adaptedRetryConsumed.get() && (kmpPrepared == null || ignetApply == null || accountApply == null)
                env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ADAPTED)
                env.reportStatus("incognito_privacy_status",
                    "not-applicable-host:" + listOfNotNull(
                        "broadcast".takeIf { broadcastMissing },
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
            incognitoInstalled.set(true)
            incognitoRetryPending = false
            env.reportStatus("incognito_privacy_status", "partial")
            // Network adapters are version-bound. Never report end-to-end privacy as verified.
            FeatureInstallResult.Installed(count + network + web + kmp + ignet + broadcast + ktorProbe, complete = false)
        }
    }

    private val guestInstaller: FeatureInstaller = FunctionalFeatureInstaller("guest_browsing") { env ->
        if (!noLogin && !incognito) {
            guestRetryPending = false
            FeatureInstallResult.Skipped("disabled")
        } else if (env.processName != "tv.danmaku.bili") {
            guestRetryPending = false
            FeatureInstallResult.Skipped("non-main-process")
        } else if (guestInstalled.get()) {
            FeatureInstallResult.Installed(0, complete = false)
        } else {
            val host = PrivacyHostAccess(requireNotNull(env.classLoader))
            // Account state (login flag) is the gate every guest sub-feature reads. It must
            // resolve before anything runs; a host build without it skips guest browsing cleanly
            // (not-applicable-host) rather than throwing mid-way. BiliAccounts.get(Context)/isLogin
            // are stable, un-obfuscated names across all supported host builds.
            val accounts = PrivacyStructuralResolver.type(host.loader, "com.bilibili.lib.accounts.BiliAccounts")
            val get = accounts?.let { PrivacyStructuralResolver.method(it, "get", classOf<Context>()) }
            val login = accounts?.let { PrivacyStructuralResolver.method(it, "isLogin") }
            if (get == null || login == null) {
                guestRetryPending = false
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
            val playbackHooks = GuestPlaybackHooks(library, guest, cachedPlayerCore = guestPlayerCoreRef.get())
            val playback = runCatching { playbackHooks.install(env) }.getOrElse {
                env.logError("guest_playback", "[BIL] Guest playback adapter unavailable")
                0
            }
            val history = GuestNativeHistoryHooks(library, guest, host)
            // KMP gRPC pipeline (8.92+ history screen and other KMP services): shared with incognito
            // when active, otherwise installed here only to serve the local library.
            var kmpHooks = 0
            val kmp = kmpBoundary ?: (KmpMossBoundary.prepare(env, null, { true }, kmpGrpcWireRef.get()) as? KmpMossBoundary.Prepared.Ready)
                ?.boundary?.also { kmpHooks = it.install() }
            kmp?.attach(guest, { rpc, payload -> history.kmpReply(rpc, payload) },
                playbackHooks.kmpViewObserver(env, host))
            val libraryHostHooks = GuestLibraryHostHooks(library, guest, history)
            val hostHooks = libraryHostHooks.install(env)
            val ignetAnswer: (String, Map<String, String>) -> String? = { path, params ->
                library.nativeBlocking {
                    if (NativeWatchLaterProtocol.handles(path)) NativeWatchLaterProtocol.respond(path, params, it) { aid -> library.videoMetadata(aid, 0) }
                    else NativeFavoriteProtocol.respond(path, params, it)
                }
            }
            val ignetResponses = GuestIgnetLibraryResponses(
                guest,
                ignetAnswer,
                cachedRequest = privacyIgnetRef.get()
            )
            val ignetHooks = ignetResponses.install(env)
            val hostDialog = GuestHostFavoriteDialog {
                libraryHostHooks.localListsInstalled &&
                    (ignetResponses.writeHookInstalled ||
                        PrivacyChannelAdapter.resolveIgHttpRequest(host.loader, privacyIgnetRef.get()) == null)
            }
            val kmpNeedsDeferredAttach = !adaptedRetryConsumed.get() && kmp == null &&
                KmpGrpcWireAdapter.applicable(host.loader)
            val ignetNeedsDeferredAttach = !adaptedRetryConsumed.get() && !ignetResponses.writeHookInstalled &&
                privacyIgnetRef.get() == null
            if (kmpNeedsDeferredAttach || ignetNeedsDeferredAttach) {
                deferredGuestHost = host
                deferredGuestCheck = guest
                deferredGuestPlayback = playbackHooks
                deferredGuestHistory = history
                deferredGuestIgnetAnswer = ignetAnswer
                deferredGuestIgnet = ignetResponses
                guestRetryPending = true
            } else {
                guestRetryPending = false
            }
            guestInstalled.set(true)
            val hooks = onboarding + hostHooks + playback +
                kmpHooks + installMineOverlayGuard(env, guest) + GuestMineOverlayHooks(guest).install(env) +
                GuestMineMenuHooks(guest).install(env) +
                GuestFavoritesRouteHooks(guest).install(env) + GuestVideoFavoriteHooks(GuestVideoFavoriteHooks.storeFor(library), guest, picker = GuestFavoriteFolderPicker(), storyStar = GuestStoryStar(), pageFavorite = GuestPageFavorite(), hostDialog = hostDialog, interactionSource = GuestFavoriteInteraction(), hostSnackbar = GuestHostFavoriteSnackbar()).install(env) +
                ignetHooks
            FeatureInstallResult.Installed(hooks + ((prompts as? FeatureInstallResult.Installed)?.hookCount ?: 0), complete = false)
        }
    }

    fun installers(): List<FeatureInstaller> = listOf(incognitoInstaller, guestInstaller)

    fun asAdaptRetryable(): AdaptRetryableInstaller = object : AdaptRetryableInstaller {
        override val id: String = "privacy_runtime"
        override val isAdaptationRetryPending: Boolean
            get() = incognitoRetryPending || guestRetryPending

        override fun install(environment: HookEnvironment): FeatureInstallResult =
            FeatureInstallResult.Skipped("coordinator-deferred-only")

        override fun prepareAdaptedRetry(adapted: VersionAdapter.AdaptResult?): Boolean {
            if (!isAdaptationRetryPending) return false
            updateAdaptedPoints(adapted)
            val hasPrivacyPoints = adapted != null && (
                adapted.kmpGrpcWireEngine != null ||
                    adapted.privacyIgnetHttp != null ||
                    adapted.privacyKNeuron != null ||
                    adapted.guestPlayerCore != null
                )
            if (!hasPrivacyPoints) {
                incognitoRetryPending = false
                guestRetryPending = false
                return false
            }
            if (incognitoRetryPending &&
                adapted.kmpGrpcWireEngine == null &&
                adapted.privacyIgnetHttp == null &&
                adapted.privacyKNeuron == null
            ) {
                incognitoRetryPending = false
            }
            if (guestRetryPending &&
                adapted.kmpGrpcWireEngine == null &&
                adapted.privacyIgnetHttp == null &&
                adapted.guestPlayerCore == null
            ) {
                guestRetryPending = false
            }
            return isAdaptationRetryPending
        }

        override fun retryAdapted(environment: HookEnvironment, coordinator: FeatureInstallCoordinator) {
            if (incognitoRetryPending && !incognitoInstalled.get()) {
                incognitoRetryPending = false
                coordinator.installAll(listOf(incognitoInstaller))
            }
            if (guestRetryPending) {
                guestRetryPending = false
                if (!guestInstalled.get()) {
                    coordinator.installAll(listOf(guestInstaller))
                } else {
                    retryDeferredGuestChannels(environment)
                }
            }
        }

        override fun onAdaptationFailed() {
            incognitoRetryPending = false
            guestRetryPending = false
        }
    }

    private fun retryDeferredGuestChannels(environment: HookEnvironment) {
        val host = deferredGuestHost ?: return
        val guest = deferredGuestCheck ?: return
        val playbackHooks = deferredGuestPlayback ?: return
        val history = deferredGuestHistory ?: return
        val ignetResponses = deferredGuestIgnet
        val kmpPoint = kmpGrpcWireRef.get()
        if (kmpBoundary == null && kmpPoint != null) {
            runCatching {
                val ready = KmpMossBoundary.prepare(environment, null, { true }, kmpPoint)
                    as? KmpMossBoundary.Prepared.Ready
                val boundary = ready?.boundary
                if (boundary != null) {
                    boundary.install()
                    boundary.attach(
                        guest,
                        { rpc, payload -> history.kmpReply(rpc, payload) },
                        playbackHooks.kmpViewObserver(environment, host)
                    )
                    kmpBoundary = boundary
                }
            }
        }
        val ignetPoint = privacyIgnetRef.get()
        val ignetAnswer = deferredGuestIgnetAnswer
        if (ignetResponses != null && !ignetResponses.writeHookInstalled && ignetPoint != null && ignetAnswer != null) {
            runCatching {
                GuestIgnetLibraryResponses(
                    guest,
                    ignetAnswer,
                    cachedRequest = ignetPoint
                ).install(environment)
            }
        }
    }

    private var activeSession: IncognitoSession? = null
    private var activeIdentityBus: IncognitoIdentityBus? = null
    private var kmpBoundary: KmpMossBoundary? = null
    @Volatile private var deferredGuestHost: PrivacyHostAccess? = null
    @Volatile private var deferredGuestCheck: (() -> Boolean)? = null
    @Volatile private var deferredGuestPlayback: GuestPlaybackHooks? = null
    @Volatile private var deferredGuestHistory: GuestNativeHistoryHooks? = null
    @Volatile private var deferredGuestIgnetAnswer: ((String, Map<String, String>) -> String?)? = null
    @Volatile private var deferredGuestIgnet: GuestIgnetLibraryResponses? = null

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
                intercept()
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
        val telemetryApply = requireNotNull(IncognitoTelemetryHooks(session, privacyKNeuronRef.get()).prepare(env))
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
                intercept()
            }
            env.registrar.exact("incognito.search.clear", recent, "clearHistory") { intercept() }
            count + 2
        }
    }.getOrNull()
}
