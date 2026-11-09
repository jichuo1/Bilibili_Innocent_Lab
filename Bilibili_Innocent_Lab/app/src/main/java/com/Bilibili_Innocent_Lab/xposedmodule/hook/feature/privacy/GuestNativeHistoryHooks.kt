package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic

/**
 * Local history cards, tabs, search and editing; no host history database or remote RPC.
 * The same answers serve the Java HistoryMoss RPCs (8.84-8.96 history page) and, as raw protobuf
 * bytes, the KMP history screen that 8.92+ routes through KHistoryMoss ([kmpReply]).
 */
internal class GuestNativeHistoryHooks(
    private val library: GuestLibraryRuntime,
    private val isGuest: () -> Boolean,
    private val host: PrivacyHostAccess
) {
    private fun proto(name: String, fields: Map<String, Any> = emptyMap()): Any {
        val builder = requireNotNull(host.static(host.type(PREFIX + name), "newBuilder"))
        fields.forEach { (key, value) -> host.call(builder, key, value) }
        return requireNotNull(host.call(builder, "build"))
    }

    private fun tab(name: String, business: String, search: Boolean) = proto("CursorTab", mapOf(
        "setName" to name, "setBusiness" to business, "setFocus" to (business == "all"),
        "setRouter" to "bilibili://main/history/${if (search) "search/" else ""}${if (business == "archive") "video" else business}"))

    private fun card(video: LocalVirtualLibrary.Video): Any = proto("CursorItem", mapOf(
        "setBusiness" to "archive", "setKid" to video.aid, "setOid" to video.aid,
        "setTitle" to video.title, "setViewAt" to video.updatedAtMs / 1000,
        "setUri" to "bilibili://video/${video.aid}?cid=${video.cid}", "setTp" to 3,
        "setCardUgc" to proto("CardUGC", mapOf("setCid" to video.cid,
            "setCover" to video.cover.orEmpty(), "setDuration" to video.durationMs / 1000,
            "setProgress" to if (video.isComplete) -1L else video.positionMs / 1000,
            "setName" to video.authorName, "setMid" to video.authorMid, "setVideos" to 1L, "setPage" to 1))))

    private fun response(name: String, request: Any, data: LocalVirtualLibrary): Any {
        if (name == "historyTab" || name == "historyTabV2") {
            val search = host.call(request, "getSource").toString().contains("search", true)
            return proto("HistoryTabReply", mapOf("addAllTab" to listOf(tab("全部", "all", search),
                tab("视频", "archive", search), tab("未看完", "nonfinish", search))))
        }
        val snapshot = data.readSnapshot()
        if (name == "clear" || name == "delete") {
            val ids = if (name == "delete") (host.call(request, "getHisInfoList") as List<*>).map {
                (host.call(requireNotNull(it), "getKid") as Number).toLong()
            }.toSet() else null
            val business = if (name == "clear") host.call(request, "getBusiness") as String else ""
            val remaining = snapshot.collections.history.filterNot {
                (ids == null || it.aid in ids) && (business != "nonfinish" || !it.isComplete)
            }
            data.writeSnapshot(snapshot.copy(collections = snapshot.collections.copy(history = remaining)))
            return proto("NoReply")
        }
        val business = host.call(request, "getBusiness") as String
        var videos = snapshot.collections.history.filter { business != "nonfinish" || !it.isComplete }
        if (business !in setOf("", "all", "archive", "video", "nonfinish")) videos = emptyList()
        if (name == "search") {
            val keyword = host.call(request, "getKeyword") as String
            videos = videos.filter { it.title.contains(keyword, true) || it.authorName.contains(keyword, true) }
            val pn = (host.call(request, "getPn") as Number).toLong().coerceAtLeast(1)
            return proto("SearchReply", mapOf("addAllItems" to if (pn == 1L) videos.map(::card) else emptyList(),
                "setHasMore" to false, "setPage" to proto("Page", mapOf("setPn" to pn, "setTotal" to videos.size.toLong()))))
        }
        val cursor = requireNotNull(host.call(request, "getCursor"))
        if ((host.call(cursor, "getMax") as Number).toLong() != 0L) videos = emptyList()
        return proto("CursorV2Reply", mapOf("addAllItems" to videos.map(::card), "setHasMore" to false,
            "setCursor" to proto("Cursor")))
    }

    /**
     * Answer for the KMP history screen: serialized request in, serialized reply out. Null means
     * [fullMethodName] is not a history RPC served locally. Runs on the engine's worker thread.
     */
    fun kmpReply(fullMethodName: String, payload: ByteArray): ByteArray? {
        if (!fullMethodName.startsWith(KMP_SERVICE)) return null
        val name = fullMethodName.substring(KMP_SERVICE.length).replaceFirstChar { it.lowercase() }
        val requestName = REQUESTS[name] ?: return null
        val request = requireNotNull(host.static(host.type(PREFIX + requestName), "parseFrom", payload))
        val reply = library.nativeBlocking { data -> response(name, request, data) }
        return host.call(reply, "toByteArray") as ByteArray
    }

    fun install(env: HookEnvironment) {
        // Preflight: every RPC/callback anchor is resolved before any hook exists. HistoryMoss and its
        // request/reply protobufs are stable gRPC names on all 38 hostlab builds (8.84-9.17).
        val moss = host.type(PREFIX + "HistoryMoss")
        val handler = host.type("com.bilibili.lib.moss.api.MossResponseHandler")
        val next = requireNotNull(Lookup.methodOrNull(handler, "onNext", classOf<Any>()))
        val completed = requireNotNull(Lookup.methodOrNull(handler, "onCompleted"))
        val exception = host.type("com.bilibili.lib.moss.api.MossException")
        val error = requireNotNull(Lookup.methodOrNull(handler, "onError", exception))
        val errorConstructor = requireNotNull(Lookup.constructorOrNull(exception, classOf<String>(), classOf<Throwable>()))
        val rpcs = REQUESTS.mapValues { (name, request) ->
            val type = host.type(PREFIX + request)
            requireNotNull(Lookup.methodOrNull(moss, name, type, handler)) { "HistoryMoss.$name" }
            type
        }
        val plan = PrivacyInstallPlan()
        for ((name, requestType) in rpcs) plan.step {
            env.registrar.exact("guest.native.history.$name", moss, name, requestType, handler) {
                before {
                    if (!isGuest()) return@before
                    result = null
                    val request = requireNotNull(argOrNull(0))
                    val callback = requireNotNull(argOrNull(1))
                    library.nativeRequest({ response(name, request, it) }) { outcome ->
                        outcome.fold({ value -> next.invoke(callback, value); completed.invoke(callback) },
                            { error.invoke(callback, errorConstructor.newInstance("Local history unavailable", it)) })
                    }
                }
            }
        }
        // Presentation is version-specific and optional: 8.84-8.96 expose a fragment isLogin() gate,
        // a few builds the up(boolean) login banner and fallback-cache getters. 8.97+ inline these into
        // BiliAccounts calls; there only the login banner is hidden ([installBannerGuard]).
        val presentation = mutableListOf<String>()
        val fragment = Lookup.classOrNull(host.loader, "com.bilibili.app.history.HistoryFragmentV3")
        fragment?.let { Lookup.methodOrNull(it, "isLogin") }
            ?.takeIf { !it.isStatic && it.returnType == classOf<Boolean>() }?.let { method ->
                presentation += "isLogin"
                plan.step {
                    env.registrar.exact("guest.native.history.presentation", method.declaringClass, method.name) {
                        before { if (isGuest()) result = true }
                    }
                }
            }
        fragment?.let { Lookup.methodOrNull(it, "up", classOf<Boolean>()) }
            ?.takeIf { !it.isStatic && it.returnType == Void.TYPE }?.let { method ->
                presentation += "banner"
                plan.step {
                    env.registrar.exact("guest.native.history.login_banner", method.declaringClass, method.name, classOf<Boolean>()) {
                        before { if (isGuest()) args[0] = false }
                    }
                }
            }
        if ("isLogin" !in presentation) fragment?.let { page ->
            val banner = bannerField(page, Lookup.classOrNull(host.loader, BANNER_TYPE)) ?: return@let
            val viewCreated = Lookup.methodOrNull(page, "onViewCreated", classOf<View>(), classOf<Bundle>()) ?: return@let
            presentation += "banner-guard"
            plan.step {
                env.registrar.exact("guest.native.history.banner_guard", page, viewCreated.name, *viewCreated.parameterTypes) {
                    after {
                        if (hasThrowable || !isGuest()) return@after
                        (banner.get(requireNotNull(instance)) as? View)?.let(::installBannerGuard)
                    }
                }
            }
        }
        for ((name, getter) in mapOf("com.bilibili.app.history.ui.viewmodel.HistoryContentViewModel" to "I2",
            "com.bilibili.app.history.search.presenter.HistorySearchTabViewModel" to "u2")) {
            val method = Lookup.classOrNull(host.loader, name)?.let { Lookup.methodOrNull(it, getter) }
                ?.takeIf { !it.isStatic && it.returnType.name.startsWith("com.bilibili.common.fallbackcache.") }
                ?: continue
            presentation += "cache.$getter"
            plan.step {
                env.registrar.exact("guest.native.history.cache.$getter", method.declaringClass, method.name) {
                    before { if (isGuest()) result = null }
                }
            }
        }
        plan.apply()
        env.reportStatus("guest_native_history",
            if (presentation.isEmpty()) "adapted-rpc" else "adapted:${presentation.joinToString("+")}")
    }

    /**
     * 8.97+ show the "sign in for more history" banner whenever `BiliAccounts` reports a signed-out account, on every tab
     * load, and its button opens the login page. For a guest it is collapsed right before each draw instead, so no frame
     * shows it; the host's own visibility logic is left untouched. The listener lives only while the banner is attached.
     */
    private fun installBannerGuard(banner: View) {
        val guard = ViewTreeObserver.OnPreDrawListener {
            if (isGuest() && banner.visibility != View.GONE) {
                banner.visibility = View.GONE
                false
            } else true
        }
        fun attach(view: View) = view.viewTreeObserver.addOnPreDrawListener(guard)
        if (banner.isAttachedToWindow) attach(banner)
        banner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = attach(view)
            override fun onViewDetachedFromWindow(view: View) = view.viewTreeObserver.removeOnPreDrawListener(guard)
        })
    }

    companion object {
        private const val BANNER_TYPE = "com.bilibili.magicasakura.widgets.TintConstraintLayout"

        /**
         * The login banner: the fragment's single instance field of the host's `TintConstraintLayout` type (`n` on 8.92 to
         * 9.09, `T` on 9.10 to 9.17; matrix q66). Null when there is none or more than one.
         */
        fun bannerField(fragment: Class<*>, bannerType: Class<*>?): java.lang.reflect.Field? = bannerType?.let { type ->
            Lookup.declaredFields(fragment, makeAccessible = true) { !it.isStatic && it.type == type }.singleOrNull()
        }

        private const val PREFIX = "com.bapis.bilibili.app.interfaces.v1."
        /** `package.Service/` as the KMP method descriptor names it (the proto package says `interface`). */
        private const val KMP_SERVICE = "bilibili.app.interface.v1.History/"
        private val REQUESTS = mapOf("historyTab" to "HistoryTabReq", "historyTabV2" to "HistoryTabReq",
            "cursorV2" to "CursorV2Req", "search" to "SearchReq", "clear" to "ClearReq", "delete" to "DeleteReq")
    }
}
