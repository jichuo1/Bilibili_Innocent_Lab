package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import com.highcapable.kavaref.extension.isStatic

/** Stable business anchors; obfuscated owners are derived from validated member types. */
internal object PlayerSpeedSessionLocator {
    const val RUN = "com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer\$runPlayable\$1"
    const val PARAMS = "com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams"
    const val CORE = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"
    const val CONTEXT = "tv.danmaku.videoplayer.coreV2.MediaPlayContextImpl"
    const val PREPARED = "tv.danmaku.ijk.media.player.IMediaPlayer\$OnPreparedListener"
    const val PLAYER = "tv.danmaku.ijk.media.player.IMediaPlayer"
    const val RESOURCE = "com.bilibili.lib.media.resource.MediaResource"
    const val INDEX = "com.bilibili.lib.media.resource.PlayIndex"
    const val ITEM = "tv.danmaku.videoplayer.coreV2.MediaItem"
    const val WRAPPER = "com.bilibili.ship.theseus.united.player.oldway.playercontainer.TheseusPlayerContainerProvider\$providePlayerContainer\$playerContainer\$1\$1\$1"

    data class Modern(val run: Method, val active: Method, val params: Method,
        val aid: Method, val cid: Method, val manager: Field, val wrapper: java.lang.reflect.Constructor<*>?)

    fun modern(loader: ClassLoader): Modern? = runCatching {
        val continuation = Lookup.classOrNull(loader, RUN) ?: return null
        val params = Lookup.classOrNull(loader, PARAMS) ?: return null
        val manager = Lookup.classOrNull(loader, PlayerSpeedLocator.SPEED_MANAGER) ?: return null
        val owner = Lookup.declaredFields(continuation, true) {
            !it.isStatic && it.type.name.startsWith("com.bilibili.ship.theseus.keel.player.") &&
                it.type != continuation
        }.singleOrNull()?.type ?: return null
        val managerField = Lookup.declaredFields(owner, true) { !it.isStatic && it.type == manager }
            .singleOrNull() ?: return null
        val run = Lookup.declaredMethods(owner, true) {
            !it.isStatic && it.returnType == classOf<Any>() &&
                it.parameterCount == 2 && it.parameterTypes[1].name in setOf("kotlin.coroutines.Continuation", "kotlin.coroutines.jvm.internal.ContinuationImpl") &&
                it.parameterTypes[0].isInterface && it.parameterTypes[0].name.startsWith("com.bilibili.ship.theseus.keel.player.")
        }.singleOrNull() ?: return null
        val playable = run.parameterTypes[0]
        val active = Lookup.declaredMethods(owner, true) {
            !it.isStatic && it.parameterCount == 0 && it.returnType == playable
        }.singleOrNull() ?: return null
        val getParams = Lookup.declaredMethods(playable, true) {
            !it.isStatic && it.parameterCount == 0 && it.returnType == params
        }.singleOrNull() ?: return null
        val aid = Lookup.methodOrNull(params, "getAvid")?.takeIf { it.returnType == classOf<Long>() && it.parameterCount == 0 } ?: return null
        val cid = Lookup.methodOrNull(params, "getCid")?.takeIf { it.returnType == classOf<Long>() && it.parameterCount == 0 } ?: return null
        val wrapper = Lookup.classOrNull(loader, WRAPPER)?.let { cls ->
            Lookup.declaredConstructors(cls, true) {
                it.parameterTypes.map { type -> type.name } == listOf(CORE, owner.name, "kotlinx.coroutines.CoroutineScope")
            }.singleOrNull()
        }
        Modern(run, active, getParams, aid, cid, managerField, wrapper)
    }.getOrNull()

    data class Prepared(val register: Method, val core: Class<*>, val listener: Class<*>, val player: Class<*>,
        val media: Method, val item: Method, val itemId: Method, val index: Method, val from: Field,
        val speed: Method, val allowedSources: Set<String>)

    fun prepared(loader: ClassLoader): Prepared? = runCatching {
        val core = Lookup.classOrNull(loader, CORE) ?: return null
        val listener = Lookup.classOrNull(loader, PREPARED) ?: return null
        val context = Lookup.classOrNull(loader, CONTEXT) ?: return null
        val player = Lookup.classOrNull(loader, PLAYER) ?: return null
        val resource = Lookup.classOrNull(loader, RESOURCE) ?: return null
        val item = Lookup.classOrNull(loader, ITEM) ?: return null
        val index = Lookup.classOrNull(loader, INDEX) ?: return null
        val register = Lookup.methodOrNull(context, "setOnPreparedListener", listener)
            ?.takeIf { !it.isStatic && it.returnType == Void.TYPE } ?: return null
        val sources = listOf("FROM__BANGUMI", "FROM__DOWNLOADED", "FROM__PUGV", "FROM__VOD_COMMON", "FROM__VUPLOAD")
            .mapNotNull { name -> Lookup.fieldOrNull(index, name)?.takeIf { it.isStatic && it.type == classOf<String>() }?.get(null) as? String }.toSet()
        if (sources.isEmpty()) return null
        Prepared(register, core, listener, player,
            Lookup.methodOrNull(core, "getMediaResource")?.takeIf { it.returnType == resource } ?: return null,
            Lookup.methodOrNull(core, "getCurrentMediaItem")?.takeIf { it.returnType == item } ?: return null,
            Lookup.methodOrNull(item, "getId")?.takeIf { it.returnType == classOf<String>() } ?: return null,
            Lookup.methodOrNull(resource, "getPlayIndex")?.takeIf { it.returnType == index } ?: return null,
            Lookup.fieldOrNull(index, "mFrom")?.takeIf { !it.isStatic && it.type == classOf<String>() } ?: return null,
            Lookup.methodOrNull(core, "getPlaySpeed", classOf<Boolean>())?.takeIf { it.returnType == classOf<Float>() } ?: return null,
            sources)
    }.getOrNull()
}
