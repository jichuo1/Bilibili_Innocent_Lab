package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 播放器内核 `IPlayerCoreService` 与它的观察者接口：这些是 `tv.danmaku.biliplayerv2` 框架契约，
 * 不是某个页面的混淆类，详情页与 Story 共用同一份。安装期一次解析，运行期只调用已验证的成员。
 */
internal class SponsorCoreApi private constructor(
    val core: Class<*>,
    val position: Method,
    val duration: Method,
    val realPosition: Method,
    val realDuration: Method,
    val state: Method,
    val seek: Method,
    private val currentMedia: Method,
    private val mediaId: Method,
    val registerProgress: Method,
    val unregisterProgress: Method,
    val registerSeek: Method,
    val unregisterSeek: Method,
    val registerState: Method,
    val unregisterState: Method,
    val registerRelease: Method,
    val unregisterRelease: Method,
    val progressObserver: Class<*>,
    val seekObserver: Class<*>,
    val stateObserver: Class<*>,
    val releaseObserver: Class<*>
) {
    /** 内核当前装载的媒体条目编号；未装载或读取失败为 null。 */
    fun loadedId(target: Any): String? = runCatching {
        currentMedia.invoke(target)?.let { mediaId.invoke(it) as? String }
    }.getOrNull()

    fun int(getter: Method, target: Any): Int? = runCatching { getter.invoke(target) as? Int }.getOrNull()

    companion object {
        const val CORE_CLASS = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"
        const val MEDIA_ITEM_CLASS = "tv.danmaku.videoplayer.coreV2.MediaItem"
        private const val SERVICE = "tv.danmaku.biliplayerv2.service."

        fun resolve(loader: ClassLoader): SponsorCoreApi? = runCatching {
            val core = Lookup.classOrNull(loader, CORE_CLASS)?.takeIf { it.isInterface } ?: return null
            val mediaItem = Lookup.classOrNull(loader, MEDIA_ITEM_CLASS) ?: return null
            val progress = Lookup.classOrNull(loader, SERVICE + "PlayerProgressObserver") ?: return null
            val seekObserver = Lookup.classOrNull(loader, SERVICE + "PlayerSeekObserver") ?: return null
            val stateObserver = Lookup.classOrNull(loader, SERVICE + "PlayerStateObserver") ?: return null
            val release = Lookup.classOrNull(loader, SERVICE + "IPlayerReleaseObserver") ?: return null
            if (listOf(progress, seekObserver, stateObserver, release).any { !it.isInterface }) return null
            if (method(progress, "onPlayerProgressChange", Void.TYPE, classOf<Int>(), classOf<Int>()) == null ||
                method(seekObserver, "onSeekStart", Void.TYPE, classOf<Long>()) == null ||
                method(seekObserver, "onSeekComplete", Void.TYPE, classOf<Long>()) == null ||
                method(stateObserver, "onPlayerStateChanged", Void.TYPE, classOf<Int>()) == null ||
                method(release, "onPlayerWillRelease", Void.TYPE) == null) return null
            SponsorCoreApi(core,
                method(core, "getCurrentPosition", classOf<Int>()) ?: return null,
                method(core, "getDuration", classOf<Int>()) ?: return null,
                method(core, "getRealCurrentPosition", classOf<Int>()) ?: return null,
                method(core, "getRealDuration", classOf<Int>()) ?: return null,
                method(core, "getState", classOf<Int>()) ?: return null,
                method(core, "seekTo", Void.TYPE, classOf<Int>(), classOf<Boolean>()) ?: return null,
                method(core, "getCurrentMediaItem", mediaItem) ?: return null,
                method(mediaItem, "getId", classOf<String>()) ?: return null,
                method(core, "registerPlayerProgressObserver", Void.TYPE, progress) ?: return null,
                method(core, "unregisterPlayerProgressObserver", Void.TYPE, progress) ?: return null,
                method(core, "registerSeekObserver", Void.TYPE, seekObserver) ?: return null,
                method(core, "unregisterSeekObserver", Void.TYPE, seekObserver) ?: return null,
                method(core, "registerState", Void.TYPE, stateObserver, IntArray::class.java) ?: return null,
                method(core, "unregisterState", Void.TYPE, stateObserver) ?: return null,
                method(core, "addPlayerReleaseObserver", Void.TYPE, release) ?: return null,
                method(core, "removePlayerReleaseObserver", Void.TYPE, release) ?: return null,
                progress, seekObserver, stateObserver, release)
        }.getOrNull()

        private fun method(owner: Class<*>, name: String, result: Class<*>, vararg parameters: Class<*>): Method? =
            Lookup.inheritedMethodOrNull(owner, name, *parameters)?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.returnType == result
            }
    }
}
