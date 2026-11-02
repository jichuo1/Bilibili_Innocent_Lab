package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the host's follow buttons and the guest's local follows in step.
 *
 * `FollowStateManager.notify(mid, followed, except)` is how every host page announces a follow change; its signature is the
 * same on all 38 builds (matrix q68). For a guest, the follow request behind such a change is answered locally by the
 * incognito seal, so the page shows success while nothing is stored: each broadcast is mirrored into the library instead.
 *
 * The space page (`LocalAuthorSpaceActivity`, all 38 builds) is H5. It renders the relation from its `/x/v2/space` response,
 * which a native `BiliCall` fetches for it, and ignores relation pushes from native (tested on 9.0.0). So for a person the
 * guest follows locally that response says followed (`relation` 1, `card.relation.status` 2), as it would for an account.
 * A space follow also takes the owner's name and avatar from the H5's data models, which share the `ViewModelStore` of the
 * page's `SpaceRelationWrapper` and are recognised by their generated `toString` (`SpaceOwnerInfo(name=`, `SpaceAvatar(face=`).
 */
internal class GuestSpaceFollowSync(private val library: GuestLibraryRuntime, private val isGuest: () -> Boolean) {
    /** Space wrapper to the `ViewModelStore` holding it and the page's data models; both stay weak. */
    private val stores = WeakHashMap<Any, WeakReference<Any>>()

    fun install(env: HookEnvironment) {
        val host = PrivacyHostAccess(requireNotNull(env.classLoader))
        val manager = host.type("com.bilibili.relation.FollowStateManager")
        val listener = host.type("com.bilibili.relation.FollowStateManager\$FollowChangeListener")
        val notify = requireNotNull(Lookup.methodOrNull(manager, "notify", classOf<Long>(), classOf<Boolean>(), listener))
        val wrapper = host.type(WRAPPER)
        val ownerMid = requireNotNull(stringField(wrapper)) { "SpaceRelationWrapper mid" }
        val store = host.type("androidx.lifecycle.ViewModelStore")
        val (put, keys, get) = requireNotNull(storeAccess(store, host.type("androidx.lifecycle.ViewModel")))
        val call = host.type("com.bilibili.okretro.call.BiliCall")
        val response = host.type("okhttp3.Response")
        val convert = requireNotNull(GuestNativeListHttpHooks.locateConverter(call, response, host.type("retrofit2.Response"))) {
            "BiliCall response converter"
        }
        val createBody = requireNotNull(Lookup.methodOrNull(host.type("okhttp3.ResponseBody"), "create",
            host.type("okhttp3.MediaType"), classOf<ByteArray>()))
        val synced = AtomicBoolean(false)
        val shown = AtomicBoolean(false)

        fun ownerOf(mid: Long): Pair<String?, String?> {
            val models = synchronized(stores) {
                stores.entries.firstOrNull { (page, _) -> ownerMid.get(page) == mid.toString() }?.value?.get()
            } ?: return null to null
            var name: String? = null
            var face: String? = null
            (keys.invoke(models) as? Set<*>).orEmpty().mapNotNull { get.invoke(models, it) }.forEach { model ->
                Lookup.declaredFields(model.javaClass, makeAccessible = true) { !it.isStatic }.forEach { field ->
                    val text = field.get(model)?.toString() ?: return@forEach
                    name = name ?: ownerName(text)
                    face = face ?: ownerFace(text)
                }
            }
            return name to face
        }

        env.registrar.exact("guest.space.follow.sync", manager, notify.name, *notify.parameterTypes) {
            before {
                if (!isGuest()) return@before
                val mid = argOrNull(0) as? Long ?: return@before
                val following = argOrNull(1) as? Boolean ?: return@before
                if (mid <= 0) return@before
                val (name, face) = if (following) ownerOf(mid) else null to null
                library.setFollow(mid, following, name, face)
                if (synced.compareAndSet(false, true)) env.reportStatus("guest_space_follow_synced", "applied")
            }
        }
        env.registrar.exact("guest.space.follow.response", call, convert.name, *convert.parameterTypes) {
            before {
                if (!isGuest()) return@before
                val raw = argOrNull(0) ?: return@before
                val url = host.call(requireNotNull(host.call(raw, "request")), "url") ?: return@before
                if (host.call(url, "host") != SPACE_HOST || host.call(url, "encodedPath") != SPACE_PATH) return@before
                if (host.call(raw, "isSuccessful") != true) return@before
                val mid = (host.call(url, "queryParameter", "vmid") as? String)?.toLongOrNull()?.takeIf { it > 0 } ?: return@before
                val followed = runCatching { library.nativeBlocking { it.readSnapshot().collections.follows.any { f -> f.mid == mid } } }
                    .getOrDefault(false)
                if (!followed) return@before
                val body = host.call(raw, "body") ?: return@before
                val type = host.call(body, "contentType")
                val bytes = host.call(body, "bytes") as ByteArray
                // The body is consumed now: whatever happens next, the host gets a readable one back.
                val patched = runCatching { followedSpace(bytes.decodeToString()).encodeToByteArray() }.getOrNull()
                val builder = requireNotNull(host.call(raw, "newBuilder"))
                host.call(builder, "body", createBody.invoke(null, type, patched ?: bytes))
                args[0] = host.call(builder, "build")
                if (patched != null && shown.compareAndSet(false, true)) env.reportStatus("guest_space_follow_shown", "applied")
            }
        }
        env.registrar.exact("guest.space.follow.store", store, put.name, *put.parameterTypes) {
            after {
                val model = argOrNull(1) ?: return@after
                if (model.javaClass != wrapper) return@after
                val owner = instance ?: return@after
                synchronized(stores) { stores[model] = WeakReference(owner) }
            }
        }
    }

    internal companion object {
        const val WRAPPER = "com.bilibili.app.authorspace.local.data.SpaceRelationWrapper"
        private const val SPACE_HOST = "app.bilibili.com"
        private const val SPACE_PATH = "/x/v2/space"

        internal data class StoreAccess(val put: Method, val keys: Method, val get: Method)

        /** Lifecycle versions use either String or Object keys; never select mismatched or ambiguous pairs. */
        fun storeAccess(store: Class<*>, viewModel: Class<*>): StoreAccess? {
            val keys = Lookup.methodOrNull(store, "keys")
                ?.takeIf { !it.isStatic && classOf<Set<*>>().isAssignableFrom(it.returnType) } ?: return null
            return listOf(classOf<String>(), classOf<Any>()).mapNotNull { key ->
                val put = Lookup.methodOrNull(store, "put", key, viewModel)
                    ?.takeIf { !it.isStatic && it.returnType == Void.TYPE } ?: return@mapNotNull null
                val get = Lookup.methodOrNull(store, "get", key)
                    ?.takeIf { !it.isStatic && it.returnType == viewModel } ?: return@mapNotNull null
                StoreAccess(put, keys, get)
            }.singleOrNull()
        }
        fun stringField(owner: Class<*>): Field? =
            Lookup.declaredFields(owner, makeAccessible = true) { !it.isStatic && it.type == classOf<String>() }.singleOrNull()

        /**
         * The space response as an account that follows the owner gets it. A guest's says `relation` -999 and
         * `card.relation.status` 1; following is 1 and 2. A response without `data` is returned unchanged.
         */
        fun followedSpace(json: String): String {
            val root = JSONObject(json)
            val data = root.optJSONObject("data") ?: return json
            data.put("relation", 1)
            data.optJSONObject("card")?.let { card ->
                (card.optJSONObject("relation") ?: JSONObject().also { card.put("relation", it) }).put("status", 2).put("is_follow", 1)
            }
            return root.toString()
        }

        fun ownerName(text: String): String? = between(text, "SpaceOwnerInfo(name=", ", sign=")

        fun ownerFace(text: String): String? = between(text, "SpaceAvatar(face=", ", digits=")

        private fun between(text: String, prefix: String, end: String): String? {
            if (!text.startsWith(prefix)) return null
            val value = text.substring(prefix.length).substringBefore(end, "")
            return value.takeIf { it.isNotBlank() && it != "null" }
        }
    }
}
