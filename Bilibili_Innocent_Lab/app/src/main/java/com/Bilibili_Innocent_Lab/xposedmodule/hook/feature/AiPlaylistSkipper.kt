package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AiPlaylistDirectorAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 连播（合集 / 稍后再看 / 收藏夹）里跳过 AI 声明视频，而不是整页离开播放列表。
 *
 * 详情页那条 `ecode=404 + redirect_url` 在连播页同样会执行（`PlayListBusinessScopeDriverImpl`
 * 里有同一段 "NotFound, jump to:"），结果是离开整个列表。连播要走宿主自己的两个入口
 * （8.89.0 / 9.13.0 反汇编实证；锚点 27 版全在，见 `Temp/ai_playlist_probe.py`）：
 *
 * 1. **预先跳过**：`PlaylistSchedulingService` 播放某条目前先判 `MultiTypeMedia` 的
 *    `!isFromDownload && (attr & 1) != 0`（失效条目），为真直接 `playNext`，连 View 都不请求。
 *    条目由序列化库 `MultiTypeMedia_JsonDescriptor.constructWith(Object[])` 构造；
 *    按 `PojoClassDescriptor.getProperties()[i].getKeyName()` 找到 `attr` / `id` 两格，
 *    已知 AI 的 aid 就把 `attr` 第 0 位置 1。只依赖 JSON 键名与库接口名，不碰混淆成员。
 * 2. **当前这一集**：View 返回后才知道是 AI 时，调 `PlaylistDirectorSerialOperationsService$run$2`
 *    的 `switchToNext(Z)`——它实现播放器库接口（该接口名每版在漂，方法名不漂），内部 `launch`
 *    协程调 `playNext`，布尔参数不被使用。
 *
 * "这次 View 属于连播"的判据：最近一个 `$run$2` 仍存活（其服务的 `CoroutineScope` 还 active），
 * 且该 aid 出现在本进程构造过的连播条目里。
 */
/** 拦截器只需要这两个动作；单测用替身实现。 */
internal interface AiPlaylistRoute {
    /** 这次 View 是不是当前连播里的条目。 */
    fun ownsPlaylistItem(aid: Long): Boolean

    /** 让宿主切到下一集；false 表示没有下一集或连播已销毁。 */
    fun skipToNext(): Boolean
}

internal class AiPlaylistSkipper private constructor(
    val directorClass: Class<*>,
    private val switchToNext: Method,
    private val hasNext: Method,
    private val serviceField: Field,
    private val scopeField: Field,
    private val isActive: Method,
    private val propertiesGetter: Method,
    private val keyNameGetter: Method
) : AiPlaylistRoute {
    @Volatile
    private var director: WeakReference<Any>? = null

    /** 本进程见过的连播条目 aid；有界，只用于归属判断。 */
    private val playlistAids = object : LinkedHashMap<Long, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>?) = size > MAX_PLAYLIST_AIDS
    }

    /** 每个描述器类的 `attr` / `id` 下标；按类缓存，只在首次构造时读一次属性表。 */
    private val slots = java.util.concurrent.ConcurrentHashMap<Class<*>, IntArray>()

    fun onDirectorCreated(instance: Any) {
        director = WeakReference(instance)
    }

    /**
     * `constructWith` 之前调用：记下 aid，已知 AI 就把 `attr` 第 0 位置 1。
     * @return true 表示这次确实把一个条目标成了失效。
     */
    fun onConstruct(descriptor: Any, values: Array<Any?>): Boolean {
        val slot = slots.getOrPut(descriptor.javaClass) { resolveSlots(descriptor) }
        val result = markKnownAi(values, slot[0], slot[1], AiDeclaredVideoRegistry::contains)
        result.aid?.let { aid -> synchronized(playlistAids) { playlistAids[aid] = Unit } }
        return result.marked
    }

    /** [markKnownAi] 的结果：本条目的 aid（读不到为 null）与是否确实标成了失效。 */
    internal data class MarkResult(val aid: Long?, val marked: Boolean)

    override fun ownsPlaylistItem(aid: Long): Boolean {
        if (aid <= 0 || activeDirector() == null) return false
        return synchronized(playlistAids) { playlistAids.containsKey(aid) }
    }

    override fun skipToNext(): Boolean {
        val target = activeDirector() ?: return false
        if (hasNext.invoke(target, false) as? Boolean != true) return false
        // 连播的调度协程跑在主线程作用域上；从 Moss 回调线程直接调也会被 launch 派发，
        // 但统一 post 到主线程，避免在响应交付之前就切走当前条目。
        Handler(Looper.getMainLooper()).post(HostThreadGuard.runnable("ai_declared.playlist_next") {
            activeDirector()?.let { switchToNext.invoke(it, false) }
        })
        return true
    }

    private fun activeDirector(): Any? {
        val target = director?.get() ?: return null
        return runCatching {
            val service = serviceField.get(target) ?: return@runCatching null
            val scope = scopeField.get(service) ?: return@runCatching null
            target.takeIf { isActive.invoke(null, scope) as? Boolean == true }
        }.getOrNull()
    }

    private fun resolveSlots(descriptor: Any): IntArray {
        @Suppress("UNCHECKED_CAST")
        val properties = propertiesGetter.invoke(descriptor) as? Array<Any?> ?: return intArrayOf(-1, -1)
        return slotsOf(properties.map { property -> property?.let { keyNameGetter.invoke(it) as? String } })
    }

    companion object {
        const val DIRECTOR_CLASS =
            "com.bilibili.ship.theseus.playlist.PlaylistDirectorSerialOperationsService\$run\$2"
        const val SERVICE_CLASS = "com.bilibili.ship.theseus.playlist.PlaylistDirectorSerialOperationsService"
        const val MEDIA_DESCRIPTOR_CLASS = "com.bilibili.ship.theseus.playlist.api.MultiTypeMedia_JsonDescriptor"
        private const val POJO_CLASS = "com.bilibili.bson.common.PojoClassDescriptor"
        private const val PROPERTY_CLASS = "com.bilibili.bson.common.PojoPropertyDescriptor"
        private const val SCOPE_CLASS = "kotlinx.coroutines.CoroutineScope"
        private const val SCOPE_KT_CLASS = "kotlinx.coroutines.CoroutineScopeKt"
        private const val MAX_PLAYLIST_AIDS = 1024

        /** 属性表里 `attr` 与 `id` 的下标；缺哪个就是 -1，调用方据此整条放行。 */
        internal fun slotsOf(keyNames: List<String?>): IntArray =
            intArrayOf(keyNames.indexOf("attr"), keyNames.indexOf("id"))

        /**
         * 纯函数：读 aid；已知 AI 且 `attr` 第 0 位未置位就原地置位。
         * `attr` 缺省（null）按 0 处理——宿主构造器对缺省值的默认也是 0。
         */
        internal fun markKnownAi(
            values: Array<Any?>,
            attrIndex: Int,
            idIndex: Int,
            isKnownAi: (Long) -> Boolean
        ): MarkResult {
            if (idIndex !in values.indices) return MarkResult(null, false)
            val aid = (values[idIndex] as? Number)?.toLong()?.takeIf { it > 0 } ?: return MarkResult(null, false)
            if (attrIndex !in values.indices || !isKnownAi(aid)) return MarkResult(aid, false)
            val attr = values[attrIndex] as? Int ?: 0
            if (attr and 1 != 0) return MarkResult(aid, false)
            values[attrIndex] = attr or 1
            return MarkResult(aid, true)
        }

        /** 任一环节缺失返回 null：连播退回详情页那条行为，其余不受影响。 */
        fun resolve(loader: ClassLoader, cachedDirectorClass: String? = null): AiPlaylistSkipper? {
            resolveDirector(loader, DIRECTOR_CLASS)?.let { return it }
            val cached = cachedDirectorClass?.takeIf(AiPlaylistDirectorAdapter::allowedOwnerName) ?: return null
            return resolveDirector(loader, cached)
        }

        internal fun resolveDirector(loader: ClassLoader, directorClassName: String): AiPlaylistSkipper? = runCatching {
            fun cls(name: String) = KavaMemberLookup.classOrNull(loader, name)
            val director = cls(directorClassName)?.takeIf {
                !it.isInterface && !Modifier.isAbstract(it.modifiers) && it.declaredConstructors.isNotEmpty()
            } ?: return null
            val service = cls(SERVICE_CLASS) ?: return null
            val scope = cls(SCOPE_CLASS) ?: return null
            val pojo = cls(POJO_CLASS) ?: return null
            val property = cls(PROPERTY_CLASS) ?: return null
            // 字段按类型取且必须唯一：混淆名每版可能不同，类型不会。
            fun uniqueField(owner: Class<*>, type: Class<*>): Field? =
                KavaMemberLookup.fields(owner, includeSuperclasses = false, makeAccessible = true) {
                    !it.isStatic && it.type == type
                }.singleOrNull()
            AiPlaylistSkipper(
                directorClass = director,
                switchToNext = KavaMemberLookup.methodOrNull(director, "switchToNext", classOf<Boolean>())
                    ?.takeIf { it.returnType == Void.TYPE } ?: return null,
                hasNext = KavaMemberLookup.methodOrNull(director, "hasNext", classOf<Boolean>())
                    ?.takeIf { it.returnType == classOf<Boolean>() } ?: return null,
                serviceField = uniqueField(director, service) ?: return null,
                scopeField = uniqueField(service, scope) ?: return null,
                isActive = cls(SCOPE_KT_CLASS)?.let { KavaMemberLookup.methodOrNull(it, "isActive", scope) }
                    ?.takeIf { it.isStatic && it.returnType == classOf<Boolean>() } ?: return null,
                propertiesGetter = KavaMemberLookup.methodOrNull(pojo, "getProperties") ?: return null,
                keyNameGetter = KavaMemberLookup.methodOrNull(property, "getKeyName")
                    ?.takeIf { it.returnType == classOf<String>() } ?: return null
            )
        }.getOrNull()
    }
}
