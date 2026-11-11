package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.content.Context
import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Only library business anchors; the obfuscated core owner comes from the handler's real result. */
internal object PlayerBufferLocator {
    const val ITEM = "tv.danmaku.ijk.media.player.IjkMediaPlayerItem"
    const val PLAYER = "tv.danmaku.ijk.media.player.IjkMediaPlayer"
    const val ASSET = "tv.danmaku.ijk.media.player.IjkMediaAsset"
    const val CORE = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"

    data class Points(
        val item: Class<*>, val asset: Class<*>, val core: Class<*>, val resource: Class<*>,
        val params: Class<*>, val normalHandler: Class<*>,
        val handlerGetter: Method, val coreCurrent: Method, val realItem: Method,
        val playerCurrent: Method, val playerSelection: List<Method>,
        val init: Method, val options: Method, val bundleMethods: List<Method>,
        val scheme: Method, val assetField: Field, val context: Field, val vodType: Field,
        val stopped: Field, val released: Field, val assetDrm: Method, val streams: Method,
        val streamDrm: Method, val segments: Method, val segmentSize: Method,
        val segmentDuration: Method, val segmentUrl: Method, val lifecycle: List<Method>,
        val existingItemParameter: Class<*>? = null
    ) {
        val creatorCount: Int get() = if (existingItemParameter == null) 2 else 3
    }

    data class CreationMethod(val method: Method, val autoStartIndex: Int?)

    fun resolve(loader: ClassLoader): Points? = runCatching {
        val item = Lookup.classOrNull(loader, ITEM) ?: return null
        val player = Lookup.classOrNull(loader, PLAYER) ?: return null
        val asset = Lookup.classOrNull(loader, ASSET) ?: return null
        val config = Lookup.classOrNull(loader, "tv.danmaku.ijk.media.player.IjkMediaConfigParams") ?: return null
        val core = Lookup.classOrNull(loader, CORE)?.takeIf { it.isInterface } ?: return null
        val resource = Lookup.classOrNull(loader, "com.bilibili.lib.media.resource.MediaResource") ?: return null
        val params = Lookup.classOrNull(loader, "tv.danmaku.videoplayer.coreV2.transformer.MediaItemParams") ?: return null
        val normal = Lookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.NormalVideoPlayHandler") ?: return null
        val handler = Lookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.VideoPlayHandler") ?: return null
        val wrapper = Lookup.classOrNull(loader, "tv.danmaku.videoplayer.coreV2.adapter.ijk.IjkMediaItem") ?: return null
        val mediaItem = Lookup.classOrNull(loader, "tv.danmaku.videoplayer.coreV2.MediaItem") ?: return null
        val stream = Lookup.classOrNull(loader, "$ASSET\$MediaAssetStream") ?: return null
        val segment = Lookup.classOrNull(loader, "$ASSET\$MediaAssertSegment") ?: return null
        val bundleOwners = listOf(
            "tv.danmaku.ijk.media.player.IIjkMediaPlayerItem\$Stub\$Proxy",
            "tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient"
        ).mapNotNull { Lookup.classOrNull(loader, it) }
        if (bundleOwners.isEmpty()) return null
        // An existing owner with an incompatible signature must not disappear from coverage.
        val bundleMethods = bundleOwners.map { owner -> method(owner, "setOptionBundle", Void.TYPE,
            Int::class.javaPrimitiveType!!, Bundle::class.java) ?: return null }.distinctBy(Method::toGenericString)
        val context = Lookup.fieldOrNull(item, "mContext")?.takeIf { it.type == Context::class.java } ?: return null
        val assetField = Lookup.fieldOrNull(item, "mMediaAsset")?.takeIf { it.type == asset } ?: return null
        val vodType = Lookup.fieldOrNull(item, "mVodType")?.takeIf { it.type.isEnum } ?: return null
        val stopped = Lookup.fieldOrNull(item, "mIsStop")?.takeIf { it.type == Boolean::class.javaPrimitiveType } ?: return null
        val released = Lookup.fieldOrNull(item, "mIsReleased")?.takeIf { it.type == Boolean::class.javaPrimitiveType } ?: return null
        val lifecycle = listOf("stop", "reset", "release").mapNotNull { method(item, it, Void.TYPE) }
        if (lifecycle.size != 3) return null
        val selection = listOf("setIjkMediaPlayerItem", "replaceCurrentItem", "removeIjkMediaPlayerItem")
            .mapNotNull { method(player, it, Void.TYPE, item) }
        if (selection.size != 3) return null
        // NormalVideoPlayHandler uses both overloads. Some older cores only expose the three-argument entry.
        val existingItemParameter = mediaItem.takeIf {
            method(core, "setMediaResource", Void.TYPE, it, resource,
                Boolean::class.javaPrimitiveType!!, params, allowAbstract = true) != null
        }
        Points(item, asset, core, resource, params, normal,
            method(handler, "getMPlayerCoreService", core) ?: return null,
            method(core, "getCurrentMediaItem", mediaItem, allowAbstract = true) ?: return null,
            method(wrapper, "realMediaItem", item) ?: return null,
            method(player, "getIjkMediaPlayerItem", item) ?: return null, selection,
            method(item, "init", Void.TYPE, asset, config) ?: return null,
            method(item, "setItemOptions", Void.TYPE) ?: return null, bundleMethods,
            method(item, "getScheme", Int::class.javaPrimitiveType!!) ?: return null,
            assetField, context, vodType, stopped, released,
            method(asset, "getDrmType", Int::class.javaPrimitiveType!!) ?: return null,
            method(asset, "getStreamList", List::class.java) ?: return null,
            method(stream, "getDrmType", Int::class.javaPrimitiveType!!) ?: return null,
            method(stream, "getMediaAssertSegments", List::class.java) ?: return null,
            method(segment, "getSize", Long::class.javaPrimitiveType!!) ?: return null,
            method(segment, "getDuration", Int::class.javaPrimitiveType!!) ?: return null,
            method(segment, "getUrl", String::class.java) ?: return null, lifecycle, existingItemParameter)
    }.getOrNull()

    fun currentCreationMethods(owner: Class<*>, point: Points): List<CreationMethod> {
        if (!point.core.isAssignableFrom(owner)) return emptyList()
        val boolean = Boolean::class.javaPrimitiveType!!
        return listOfNotNull(
            Lookup.inheritedMethodOrNull(owner, "play")?.takeIf(::concreteVoid)?.let { CreationMethod(it, null) },
            Lookup.inheritedMethodOrNull(owner, "setMediaResource", point.resource, boolean, point.params)
                ?.takeIf(::concreteVoid)?.let { CreationMethod(it, 1) },
            point.existingItemParameter?.let { item ->
                Lookup.inheritedMethodOrNull(owner, "setMediaResource", item, point.resource, boolean, point.params)
                    ?.takeIf(::concreteVoid)?.let { CreationMethod(it, 2) }
            }
        )
    }

    private fun concreteVoid(method: Method): Boolean = method.returnType == Void.TYPE &&
        !Modifier.isAbstract(method.modifiers) && !Modifier.isStatic(method.modifiers)

    private fun method(owner: Class<*>, name: String, result: Class<*>, vararg args: Class<*>,
        allowAbstract: Boolean = false): Method? =
        Lookup.methods(owner, includeSuperclasses = true, makeAccessible = true) {
            it.name == name && it.returnType == result && it.parameterTypes.contentEquals(args) &&
                !Modifier.isStatic(it.modifiers) && (allowAbstract || !Modifier.isAbstract(it.modifiers))
        }.distinctBy(Method::toGenericString).singleOrNull()
}
