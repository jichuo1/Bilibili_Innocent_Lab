package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.StoryDetailSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.StoryPlayableIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Story（竖屏短视频）播放页的商单跳过所需的宿主成员。全部按稳定类名、字段类型或"同类里唯一"的形状解析，
 * 不依赖混淆后的字母名，也不走语义 DEX 兜底；任一角色缺失或不唯一都整体放弃，由安装器报告 `missing-story-structure`。
 */
internal class SponsorStoryAccess private constructor(
    val onCreate: Method,
    val release: Method,
    val onDestroy: Method,
    val pagerConstructor: Constructor<*>,
    val activityClass: Class<*>,
    val core: SponsorCoreApi,
    private val coreField: Field,
    private val contextField: Field?,
    private val currentPlayable: Method,
    private val pagerPlayer: Field,
    private val pagerItem: Method,
    private val playableId: Method,
    private val display: Method,
    private val avid: Method,
    private val cid: Method,
    private val from: Method,
    private val bizType: Method,
    private val ugc: Any,
    private val detail: DetailMembers
) {
    class DetailMembers(val aid: Method, val cid: Method, val bvid: Method, val ugc: Method,
        val playable: Method, val ad: Method, val adInfo: Method)

    fun coreOf(owner: Any): Any? = runCatching { coreField.get(owner) }.getOrNull()

    /** 页面 Activity：优先 StoryPlayer 持有的 Context，其次 onCreate 的生命周期参数；必须是 Story 播放页。 */
    fun activityOf(owner: Any, lifecycleOwner: Any?): Activity? {
        val context = runCatching { contextField?.get(owner) as? Context }.getOrNull()
        return unwrap(context) ?: (lifecycleOwner as? Activity)?.takeIf(activityClass::isInstance)
    }

    private fun unwrap(context: Context?): Activity? {
        var current = context
        repeat(8) {
            if (current is Activity) return (current as Activity).takeIf(activityClass::isInstance)
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }

    /** 数据源游标指向的条目；StoryPlayer 尚未就绪时为 null。 */
    fun playable(owner: Any): StoryPlayableIdentity? = runCatching {
        val params = currentPlayable.invoke(owner) ?: return null
        val details = display.invoke(params) ?: return null
        StoryPlayableIdentity(
            id = playableId.invoke(params) as? String ?: return null,
            aid = avid.invoke(details) as? Long ?: return null,
            cid = cid.invoke(details) as? Long ?: return null,
            vupload = from.invoke(params) == "vupload",
            ugc = bizType.invoke(params) === ugc)
    }.getOrNull()

    fun isPagerOf(pager: Any, owner: Any): Boolean = runCatching { pagerPlayer.get(pager) === owner }.getOrDefault(false)

    /** 分页器当前页的 StoryDetail 快照；占位页、读取失败都返回 null。 */
    fun detail(pager: Any): StoryDetailSnapshot? = runCatching {
        val item = pagerItem.invoke(pager) ?: return null
        StoryDetailSnapshot(
            aid = detail.aid.invoke(item) as? Long ?: return null,
            cid = detail.cid.invoke(item) as? Long ?: return null,
            bvid = detail.bvid.invoke(item) as? String ?: "",
            ugc = detail.ugc.invoke(item) == true,
            playable = detail.playable.invoke(item) == true,
            ad = detail.ad.invoke(item) != false,
            hasAdInfo = detail.adInfo.invoke(item) != null)
    }.getOrNull()

    companion object {
        const val PLAYER = "com.bilibili.video.story.player.StoryPlayer"
        const val PAGER = "com.bilibili.video.story.player.StoryPagerPlayer"
        const val DETAIL = "com.bilibili.video.story.StoryDetail"
        const val ACTIVITY = "com.bilibili.video.story.StoryVideoActivity"
        const val PLAYABLE = "tv.danmaku.biliplayerv2.service.Video\$PlayableParams"
        const val DISPLAY = "tv.danmaku.biliplayerv2.service.Video\$DisplayParams"
        const val BIZ_TYPE = "tv.danmaku.videoplayer.coreV2.VideoBizType"
        private const val LIFECYCLE_OWNER = "androidx.lifecycle.LifecycleOwner"

        fun resolve(loader: ClassLoader): SponsorStoryAccess? = runCatching {
            val owner = Lookup.classOrNull(loader, PLAYER) ?: return null
            val pager = Lookup.classOrNull(loader, PAGER) ?: return null
            val detailClass = Lookup.classOrNull(loader, DETAIL) ?: return null
            val activity = Lookup.classOrNull(loader, ACTIVITY)?.takeIf { Activity::class.java.isAssignableFrom(it) }
                ?: return null
            val params = Lookup.classOrNull(loader, PLAYABLE) ?: return null
            val displayClass = Lookup.classOrNull(loader, DISPLAY) ?: return null
            val type = Lookup.classOrNull(loader, BIZ_TYPE) ?: return null
            val core = SponsorCoreApi.resolve(loader) ?: return null
            val lifecycleOwner = Lookup.classOrNull(loader, LIFECYCLE_OWNER) ?: return null
            fun lifecycle(name: String, parameters: List<Class<*>>): Method? = Lookup.declaredMethods(owner, true) {
                instance(it) && it.name == name && it.returnType == Void.TYPE && it.parameterTypes.toList() == parameters
            }.singleOrNull()
            val coreField = Lookup.declaredFields(owner, true) { !Modifier.isStatic(it.modifiers) && it.type == core.core }
                .singleOrNull() ?: return null
            // Context 字段不唯一时放弃它，改用 onCreate 的生命周期参数，不猜。
            val contextField = Lookup.declaredFields(owner, true) {
                !Modifier.isStatic(it.modifiers) && it.type == Context::class.java
            }.singleOrNull()
            val pagerConstructor = Lookup.declaredConstructors(pager, true) {
                it.parameterTypes.toList() == listOf(String::class.java)
            }.singleOrNull() ?: return null
            val pagerPlayer = Lookup.declaredFields(pager, true) { !Modifier.isStatic(it.modifiers) && it.type == owner }
                .singleOrNull() ?: return null
            val pagerItem = Lookup.declaredMethods(pager, true) {
                instance(it) && it.parameterCount == 0 && it.returnType == detailClass && !it.isBridge && !it.isSynthetic
            }.singleOrNull() ?: return null
            val ugc = Lookup.fieldOrNull(type, "UGC")?.takeIf { Modifier.isStatic(it.modifiers) && it.type == type }
                ?.get(null) ?: return null
            val long = classOf<Long>()
            val bool = classOf<Boolean>()
            SponsorStoryAccess(
                onCreate = lifecycle("onCreate", listOf(lifecycleOwner)) ?: return null,
                release = lifecycle("release", emptyList()) ?: return null,
                onDestroy = lifecycle("onDestroy", listOf(lifecycleOwner)) ?: return null,
                pagerConstructor = pagerConstructor, activityClass = activity, core = core,
                coreField = coreField, contextField = contextField,
                currentPlayable = method(owner, "getCurrentPlayableParam", params) ?: return null,
                pagerPlayer = pagerPlayer, pagerItem = pagerItem,
                // 真实宿主在抽象基类声明，由具体条目实现。
                playableId = method(params, "id", String::class.java, allowAbstract = true) ?: return null,
                display = method(params, "getDisplayParams", displayClass, allowAbstract = true) ?: return null,
                avid = method(displayClass, "getAvid", long) ?: return null,
                cid = method(displayClass, "getCid", long) ?: return null,
                from = method(params, "getFrom", String::class.java) ?: return null,
                bizType = method(params, "getBizType", type) ?: return null, ugc = ugc,
                detail = DetailMembers(
                    aid = method(detailClass, "getAid", long) ?: return null,
                    cid = method(detailClass, "getCid", long) ?: return null,
                    bvid = method(detailClass, "getBvid", String::class.java) ?: return null,
                    ugc = method(detailClass, "isUgc", bool) ?: return null,
                    playable = method(detailClass, "isPlayableVideo", bool) ?: return null,
                    ad = method(detailClass, "isAd", bool) ?: return null,
                    adInfo = Lookup.declaredMethods(detailClass, true) {
                        instance(it) && it.name == "getAdInfo" && it.parameterCount == 0 && !it.returnType.isPrimitive
                    }.singleOrNull() ?: return null))
        }.getOrNull()

        private fun instance(method: Method) =
            !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) && !method.isBridge

        private fun method(owner: Class<*>, name: String, result: Class<*>, vararg args: Class<*>,
            allowAbstract: Boolean = false): Method? =
            Lookup.methods(owner, includeSuperclasses = true, makeAccessible = true) {
                it.name == name && it.returnType == result && it.parameterTypes.contentEquals(args) &&
                    !Modifier.isStatic(it.modifiers) && (allowAbstract || !Modifier.isAbstract(it.modifiers)) && !it.isBridge
            }.distinctBy { it.name to it.parameterTypes.toList() }.singleOrNull()
    }
}
