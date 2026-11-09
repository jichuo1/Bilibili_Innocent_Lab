package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import androidx.core.net.toUri
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.io.IOException

/** Short circuit before interceptors, signing, HTTP cache and network; retain the host converter/callback executor. */
internal class GuestNativeListHttpHooks(
    private val library: GuestLibraryRuntime,
    private val isGuest: () -> Boolean,
    private val followGroupBindings: GuestFollowGroupBindings<GuestLibraryRuntime.Token>
) {
    var followGroupClonesInstalled: Boolean = false
        private set

    fun install(env: HookEnvironment) {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val groupGuard = GuestFollowGroupCallGuard(followGroupBindings, library::accepts)
        val call = host.type("com.bilibili.okretro.call.BiliCall")
        val response = host.type("okhttp3.Response")
        val convert = requireNotNull(locateConverter(call, response, host.type("retrofit2.Response"))) {
            "BiliCall response converter"
        }
        // The two flag fields drift by build (`m` is "executed" up to 9.09 but "canceled" from 9.10), so the
        // public isExecuted()/isCanceled() are used instead; the Type field is the only one of its type.
        val responseType = requireNotNull(locateResponseType(call)) { "BiliCall response type field" }
        val media = host.static(host.type("okhttp3.MediaType"), "parse", "application/json; charset=utf-8")
        val createBody = requireNotNull(Lookup.methodOrNull(host.type("okhttp3.ResponseBody"), "create", host.type("okhttp3.MediaType"), classOf<String>()))
        val protocol = requireNotNull(Lookup.fieldOrNull(host.type("okhttp3.Protocol"), "HTTP_1_1")).get(null)
        registerExecute(env.registrar, call) {
            before {
                val target = instance ?: run {
                    throwable = IOException("Local request unavailable")
                    return@before
                }
                val group = groupGuard.decide(target)
                if (group !is GuestFollowGroupCallGuard.Decision.Unrelated) {
                    // This call belongs to our dialog, even if the user is no longer a guest.
                    throwable = IOException("Local follow group session unavailable")
                    if (group !is GuestFollowGroupCallGuard.Decision.Local) return@before
                }
                val guest = try { isGuest() } catch (failure: Exception) {
                    throwable = IOException("Local session unavailable", failure)
                    return@before
                }
                if (!guest) return@before
                val (request, url, path) = try {
                    val request = requireNotNull(host.call(target, "request"))
                    val url = requireNotNull(host.call(request, "url"))
                    Triple(request, url, host.call(url, "encodedPath") as String)
                } catch (failure: Exception) {
                    throwable = IOException("Unable to inspect local request", failure)
                    return@before
                }
                if (group is GuestFollowGroupCallGuard.Decision.Local) {
                    if (!NativeFollowGroupProtocol.handles(path)) return@before
                } else if (!NativeFavoriteProtocol.handles(path) && !NativeWatchLaterProtocol.handles(path)) return@before
                // Set a failure BEFORE parsing local data: an adapter exception must never run the real request.
                throwable = IOException("Local library response unavailable")
                try {
                    check(host.call(target, "isCanceled") != true && host.call(target, "isExecuted") != true)
                    val params = linkedMapOf<String, String>()
                    repeat(host.call(url, "querySize") as Int) { index ->
                        params[host.call(url, "queryParameterName", index) as String] = host.call(url, "queryParameterValue", index) as? String ?: ""
                    }
                    val body = host.call(request, "body")
                    if (body != null && host.type("okhttp3.FormBody").isInstance(body)) repeat(host.call(body, "size") as Int) { index ->
                        params[host.call(body, "name", index) as String] = host.call(body, "value", index) as String
                    }
                    // A feed card can add a video this session never opened. Read its archive here on the host's network
                    // thread: the blocking gRPC call must not run inside the library's single IO executor. A failed read
                    // leaves the id unknown, so the protocol fails the add instead of inventing data.
                    val adds = when {
                        NativeWatchLaterProtocol.isAdd(path) -> NativeWatchLaterProtocol.resourceIds(params)
                        NativeFavoriteProtocol.isAdd(path, params) -> NativeFavoriteProtocol.resourceIds(params)
                        else -> emptyList()
                    }
                    val archives = adds.filter { library.videoMetadata(it, 0) == null }
                        .mapNotNull { aid -> runCatching { aid to PrivacyVideoMetadata.fetch(host, aid) }.getOrNull() }.toMap()
                    val metadata = { aid: Long -> library.videoMetadata(aid, 0) ?: archives[aid] }
                    val json = if (group is GuestFollowGroupCallGuard.Decision.Local) {
                        val binding = group.binding
                        library.nativeBlocking(binding.token) {
                            NativeFollowGroupProtocol.respond(path, params, it, binding.mid, library.followGroupLabels())
                        }
                    } else library.nativeBlocking {
                        if (NativeWatchLaterProtocol.handles(path)) NativeWatchLaterProtocol.respond(path, params, it, metadata)
                        else NativeFavoriteProtocol.respond(path, params, it, metadata)
                    }
                    // The same pin endpoint is decoded as BaseResponse by Favorites V2 and String by the older detail page.
                    val pinString = path == "/x/v3/fav/folder/add_top" &&
                        (responseType.get(target) as? java.lang.reflect.ParameterizedType)?.actualTypeArguments?.singleOrNull() == classOf<String>()
                    val payload = if (pinString) org.json.JSONObject(json).put("data", "").toString() else json
                    check(host.call(target, "isCanceled") != true)
                    val builder = host.create(host.type("okhttp3.Response\$Builder"))
                    host.call(builder, "request", request)
                    host.call(builder, "protocol", protocol)
                    host.call(builder, "code", 200)
                    host.call(builder, "message", "OK")
                    host.call(builder, "header", "Cache-Control", "no-store")
                    host.call(builder, "body", createBody.invoke(null, media, payload))
                    val converted = convert.invoke(target, host.call(builder, "build"))
                    if (group is GuestFollowGroupCallGuard.Decision.Local && !library.accepts(group.binding.token)) {
                        throw IOException("Local follow group session changed")
                    }
                    result = converted
                } catch (failure: Exception) {
                    throwable = IOException("Local library response unavailable", failure)
                    env.logError("guest_native_list_response", "[BIL] Native local list response failed")
                }
            }
        }
        // The remaining hooks are independent of the response short circuit above: a build that lacks one of them
        // (8.97+ has no cache getters, 9.0 has no `fa6.b`) must still get the local list responses.
        val missing = mutableListOf<String>()
        fun optional(name: String, action: () -> Unit) {
            runCatching(action).onFailure { missing += name }
        }
        optional("follow_group_clone") {
            check(GuestFollowGroupCloneHooks.install(env.registrar, call, followGroupBindings))
            followGroupClonesInstalled = true
        }
        val cacheDropped = java.util.concurrent.atomic.AtomicBoolean(false)
        for ((name, method) in mapOf("FavoritesActivityViewModel" to "P2", "FolderListViewModel" to "Q2", "FavoritesListViewModel" to "Z2")) {
            optional("cache.$name") {
                val viewModel = host.type("tv.danmaku.bili.ui.favorites.viewmodel.$name")
                if (Lookup.methodOrNull(viewModel, method) != null) {
                    env.registrar.exact("guest.native.favorite.cache.$method", viewModel, method) {
                        before { if (isGuest()) result = null }
                    }
                } else {
                    // 8.97+: no getter, the cache sits in a Lazy field that every constructor leaves initialized.
                    val constructors = Lookup.declaredConstructors(viewModel, makeAccessible = true)
                    require(constructors.isNotEmpty()) { "${viewModel.name} constructors" }
                    for (constructor in constructors) {
                        env.registrar.constructor("guest.native.favorite.cache.$name.${constructor.parameterCount}", constructor) {
                            after {
                                if (hasThrowable || !isGuest()) return@after
                                val created = instance ?: return@after
                                if (dropCacheLazies(created) > 0 && cacheDropped.compareAndSet(false, true)) {
                                    env.reportStatus("guest_native_lists_cache_dropped", "seen")
                                }
                            }
                        }
                    }
                }
            }
        }
        optional("fragments") {
            val fragments = mapOf(
                "bilibili://user_center/favorite_v2/favorite/folders" to host.type("tv.danmaku.bili.ui.favorites.fragment.FavoritesFolderTabFragment"),
                "bilibili://user_center/favorite_v2/favorite/items" to host.type("tv.danmaku.bili.ui.favorites.fragment.FavoritesTabFragment"))
            env.registrar.exact("guest.native.favorite.fragments", host.type("com.bilibili.app.comm.list.widget.utils.ListExtentionsKt"),
                "findFragmentByUrl", classOf<android.content.Context>(), classOf<String>(), classOf<android.os.Bundle>()) {
                before {
                    if (!isGuest()) return@before
                    val type = fragments[(argOrNull(1) as? String)?.substringBefore('?')] ?: return@before
                    val fragment = host.create(type)
                    host.call(fragment, "setArguments", (argOrNull(2) as? android.os.Bundle)?.let { android.os.Bundle(it) } ?: android.os.Bundle())
                    result = fragment
                }
            }
        }
        optional("folder_route") {
            env.registrar.exact("guest.native.favorite.folder_route", host.type("fa6.b"), "a", classOf<String>(),
                host.type("androidx.fragment.app.Fragment")) {
                before {
                    if (!isGuest()) return@before
                    val uri = (argOrNull(0) as? String ?: return@before).toUri()
                    if (uri.scheme != "bilibili" || uri.host != "music" || !uri.path.orEmpty().startsWith("/playlist/detail/")) return@before
                    val id = uri.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 } ?: return@before
                    result = null
                    val fragment = argOrNull(1) ?: return@before
                    val activity = host.call(fragment, "getActivity") as? android.app.Activity ?: return@before
                    activity.startActivity(android.content.Intent().setClassName(activity.packageName,
                        "com.bilibili.playset.MultiTypeListDetailActivityV2").setData(uri).putExtra("playlistId", id))
                }
            }
        }
        optional("detail_owner") {
            val detail = host.type("com.bilibili.playset.MultiTypeListDetailActivityV2")
            env.registrar.exact("guest.native.favorite.detail_owner", detail, "Wc") { before { if (isGuest()) result = true } }
        }
        env.reportStatus("guest_native_lists_http", if (missing.isEmpty()) "adapted" else "adapted;skipped=${missing.joinToString(",")}")
    }

    companion object {
        /** An IOException must reach the caller; protective mode would retry the original network request. */
        internal fun registerExecute(registrar: HookRegistrar, call: Class<*>, block: ModernMemberHookCreator.() -> Unit) {
            registrar.adapted("guest.native.lists.http", VersionAdapter.HookPoint(call.name, "execute", emptyList()),
                HookExceptionPolicy.DELIVER_TO_HOST, block)
        }
        /**
         * `BiliCall`'s `(okhttp3.Response) -> retrofit2.Response` converter. 8.84 to 8.96 declare it as `p` next to a
         * private `q` of the same shape; 8.97 and later keep one public final instance method, renamed `a`
         * (hostlab q56, 38/38 builds). The name wins where it exists; otherwise only a unique public shape is used.
         */
        internal fun locateConverter(call: Class<*>, response: Class<*>, result: Class<*>): java.lang.reflect.Method? =
            PrivacyStructuralResolver.method(call, "p", response)
                ?: PrivacyStructuralResolver.uniqueMethod(call) {
                    java.lang.reflect.Modifier.isPublic(it.modifiers) && !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes.singleOrNull() == response && it.returnType == result
                }

        const val CACHE_PACKAGE = "com.bilibili.common.fallbackcache."

        /**
         * 8.97 and later keep each favorites ViewModel's disk fallback cache in a `kotlin.Lazy` field instead of behind a
         * getter (8.90.2's `P2/Q2/Z2`). The Lazy that holds the cache is the one whose value comes from [cachePackage];
         * it is replaced by a Lazy that yields null, which the ViewModel already treats as "no cache" (`if (cache != null)`
         * guards both the read and the write). Returns how many Lazies were replaced.
         *
         * The Lazies hold static configuration (cache object, key, switch), so evaluating each once is what the first use
         * would do anyway; one that throws is left alone.
         */
        internal fun dropCacheLazies(viewModel: Any, cachePackage: String = CACHE_PACKAGE): Int {
            var dropped = 0
            // The field type is the HOST's kotlin.Lazy. The module bundles its own Kotlin runtime in another class loader,
            // so `Lazy::class.java` would not match: compare by name and replace through a proxy of the field's own type.
            for (field in Lookup.fields(viewModel.javaClass, includeSuperclasses = true, makeAccessible = true) {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type.name == LAZY
            }) {
                val lazy = field.get(viewModel) ?: continue
                val value = runCatching { field.type.getMethod("getValue").invoke(lazy) }.getOrNull() ?: continue
                if (!value.javaClass.name.startsWith(cachePackage)) continue
                val empty = java.lang.reflect.Proxy.newProxyInstance(field.type.classLoader, arrayOf(field.type)) { proxy, method, args ->
                    when (method.name) {
                        "getValue" -> null
                        "isInitialized" -> true
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "NoFallbackCache"
                        else -> null
                    }
                }
                if (runCatching { field.set(viewModel, empty) }.isSuccess) dropped++
            }
            return dropped
        }

        private const val LAZY = "kotlin.Lazy"

        /** The call's declared response [java.lang.reflect.Type]; the one field of that type in all 38 builds. */
        internal fun locateResponseType(call: Class<*>): java.lang.reflect.Field? =
            PrivacyStructuralResolver.uniqueField(call) { it.type == java.lang.reflect.Type::class.java }
    }
}
