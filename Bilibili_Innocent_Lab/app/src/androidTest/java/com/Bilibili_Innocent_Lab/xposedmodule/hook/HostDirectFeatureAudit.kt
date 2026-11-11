package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerBufferLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PegasusLoadMoreShape
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.matchesDetailPromotionRenderMethod
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.matchesDetailRelateAdRenderMethod
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Modifier
import org.json.JSONArray
import org.json.JSONObject

/** Host members outside the initial registrar plan. This fixture never creates a player or executes a callback. */
internal object HostDirectFeatureAudit {
    private data class Profile(
        val core: String,
        val preparedListener: String,
        val earlyLoadListener: String,
        val detailProvider: String
    )

    private const val PEGASUS_WIDGET = "com.bilibili.pegasus.widget."
    private const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
    private const val AD_DETAIL = "com.bilibili.gripper.api.ad.biz.videodetail."
    private const val AD_UNDER = AD_DETAIL + "underplayer."
    private const val DECORATED_CORE =
        "com.bilibili.ship.theseus.united.player.oldway.playercontainer." +
            "TheseusPlayerContainerProvider\$providePlayerContainer\$playerContainer\$1\$1\$1"
    private val profiles = mapOf(
        9180100 to Profile("sz1.A", "sz1.u", PEGASUS_WIDGET + "h", "Q10.u"),
        9180200 to Profile("rz1.G", "rz1.B", PEGASUS_WIDGET + "i", "Q10.s"),
        9180300 to Profile("sz1.C", "sz1.x", PEGASUS_WIDGET + "h", "Q10.s"),
        9190200 to Profile("rwq1", "mwq1", "d571", "dgt0"),
        9190400 to Profile("rwq1", "mwq1", "d571", "dgt0")
    )

    fun collect(loader: ClassLoader, direct: VersionAdapter.AdaptResult, version: Int): JSONObject {
        val profile = profiles[version] ?: return JSONObject()
        val results = JSONObject()
        val registry = HookPointRegistry(loader)
        fun checkMembers(id: String, scope: String, resolve: () -> List<String>) {
            val outcome = runCatching { resolve().also { check(it.isNotEmpty()) { "no members resolved" } } }
            results.put(id, JSONObject().put("scope", scope)
                .put("resolved", outcome.isSuccess)
                .put("members", JSONArray(outcome.getOrDefault(emptyList())))
                .put("error", outcome.exceptionOrNull()?.message))
        }

        checkMembers("free_copy_comment_enabled", "direct-hook-member") {
            listOfNotNull(direct.commentHigh, direct.commentLow).mapNotNull { point ->
                registry.resolveAdapted("audit.free_copy.comment", point.className, point.methodName,
                    point.paramClassNames)?.toGenericString()
            }
        }
        checkMembers("free_copy_description_enabled", "direct-hook-members") {
            val view = requireNotNull(Lookup.classOrNull(loader,
                "tv.danmaku.bili.videopage.common.widget.view.ExpandableTextView"))
            val service = requireNotNull(Lookup.classOrNull(loader,
                "com.bilibili.ship.theseus.ugc.intro.ugcheadline.UgcHeadlineService\$b"))
            listOf(
                requireNotNull(registry.resolveExact("audit.free_copy.description.text", view, "setText",
                    CharSequence::class.java, TextView.BufferType::class.java)),
                requireNotNull(registry.resolveExact("audit.free_copy.description.copy", service, "c",
                    String::class.java, Boolean::class.javaPrimitiveType!!))
            ).map { method ->
                check(method.returnType == Void.TYPE && !Modifier.isStatic(method.modifiers))
                method.toGenericString()
            }
        }
        checkMembers("home_recommend_early_load", "actual-pegasus-watcher-shape") {
            val watcher = requireNotNull(Lookup.classOrNull(loader, profile.earlyLoadListener))
            val scroll = requireNotNull(Lookup.classOrNull(loader, "$RECYCLER\$OnScrollListener"))
            val attach = requireNotNull(Lookup.classOrNull(loader, "$RECYCLER\$OnChildAttachStateChangeListener"))
            val function = requireNotNull(Lookup.classOrNull(loader, "kotlin.jvm.functions.Function0"))
            val shape = requireNotNull(PegasusLoadMoreShape.of(watcher, scroll, attach, function))
            listOf(shape.callback.toGenericString(), shape.enabled.toGenericString())
        }
        checkMembers("player_default_speed_percent", "deferred-prepared-members") {
            val point = requireNotNull(PlayerSpeedSessionLocator.prepared(loader))
            val listener = requireNotNull(Lookup.classOrNull(loader, profile.preparedListener))
            val core = requireNotNull(Lookup.classOrNull(loader, profile.core))
            check(point.listener.isAssignableFrom(listener) && point.core.isAssignableFrom(core))
            val field = Lookup.declaredFields(listener) {
                !Modifier.isStatic(it.modifiers) && point.core.isAssignableFrom(it.type)
            }.single()
            check(field.type == core)
            val callback = requireNotNull(Lookup.methodOrNull(listener, "onPrepared", point.player))
            val setter = requireNotNull(Lookup.inheritedMethodOrNull(core, "setPlaySpeed",
                Float::class.javaPrimitiveType!!))
            listOf(callback, setter).map { method ->
                check(method.returnType == Void.TYPE && !Modifier.isStatic(method.modifiers) &&
                    !Modifier.isAbstract(method.modifiers))
                method.toGenericString()
            }
        }
        checkMembers("player_buffer_strategy", "deferred-default-and-decorated-creator-members") {
            val point = requireNotNull(PlayerBufferLocator.resolve(loader))
            check(point.creatorCount == 3)
            listOf(profile.core, DECORATED_CORE).flatMap { name ->
                val core = requireNotNull(Lookup.classOrNull(loader, name))
                val methods = PlayerBufferLocator.currentCreationMethods(core, point)
                check(methods.map { it.autoStartIndex } == listOf(null, 1, 2)) { "unresolved creator: $name" }
                methods.map { it.method.toGenericString() }
            }
        }
        results.put("player_buffer_preload", results.getJSONObject("player_buffer_strategy"))

        val provider by lazy {
            val contract = requireNotNull(Lookup.classOrNull(loader, "com.bilibili.gripper.api.ad.biz.GAdVideoDetail"))
            requireNotNull(Lookup.classOrNull(loader, profile.detailProvider)).also {
                check(contract.isAssignableFrom(it))
            }
        }
        val promotionMembers by lazy {
            val api = requireNotNull(Lookup.classOrNull(loader, AD_UNDER + "IAdUnderPlayer"))
            val callback = requireNotNull(Lookup.classOrNull(loader, AD_UNDER + "IAdUnderPlayerCallback"))
            val bridge = requireNotNull(Lookup.classOrNull(loader, AD_UNDER + "AdUpperBridge"))
            val config = requireNotNull(Lookup.classOrNull(loader, AD_UNDER + "AdUpperConfig"))
            val getter = requireNotNull(Lookup.methodOrNull(provider, "getUnderPlayer"))
            check(getter.returnType == api && !Modifier.isStatic(getter.modifiers))
            val renderer = requireNotNull(Lookup.classOrNull(loader, profile.detailProvider + "\$e"))
            check(api.isAssignableFrom(renderer))
            getter to Lookup.declaredMethods(renderer) {
                !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                    matchesDetailPromotionRenderMethod(it, callback, bridge, config)
            }
        }
        for ((id, name) in listOf(
            "detail_promotion_nest" to "getUpperNestView",
            "detail_promotion_list" to "getUpperAdView",
            "detail_promotion_hd" to "getUpperHDView"
        )) {
            checkMembers(id, "deferred-video-detail-facade-members") {
                val (getter, renders) = promotionMembers
                val method = renders.single { it.name == name }
                listOf(getter.toGenericString(), method.toGenericString())
            }
        }
        checkMembers("detail_promotion_related_ad", "deferred-video-detail-facade-members") {
            val api = requireNotNull(Lookup.classOrNull(loader, AD_DETAIL + "relate.IAdVideoRelate"))
            val getter = requireNotNull(Lookup.methodOrNull(provider, "getRelate"))
            check(getter.returnType == api && !Modifier.isStatic(getter.modifiers))
            val renderer = requireNotNull(Lookup.classOrNull(loader, profile.detailProvider + "\$d"))
            check(api.isAssignableFrom(renderer))
            val method = Lookup.declaredMethods(renderer, predicate = ::matchesDetailRelateAdRenderMethod).single()
            listOf(getter.toGenericString(), method.toGenericString())
        }
        return results
    }
}
