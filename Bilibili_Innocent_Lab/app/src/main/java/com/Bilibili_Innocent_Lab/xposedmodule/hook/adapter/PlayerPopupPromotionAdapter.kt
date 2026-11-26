package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 仅缓存专用推广成员；复用时重新核对当前宿主的完整 ABI 与 owner 结构。 */
data class PlayerPopupPromotionPoints(val freeDataGuide: HookPoint? = null, val advertFloat: HookPoint? = null) {
    fun toJson() = JSONObject().apply {
        freeDataGuide?.let { put("free_data_guide", it.toJson()) }
        advertFloat?.let { put("advert_float", it.toJson()) }
    }

    companion object {
        fun fromJson(value: JSONObject): PlayerPopupPromotionPoints? = runCatching {
            fun read(key: String, family: String): HookPoint? {
                if (!value.has(key)) return null
                val raw = value.getJSONObject(key)
                require(raw.has("params") && raw.getJSONArray("params").length() == if (key == "free_data_guide") 1 else 0)
                return HookPoint.fromJson(raw).also { point ->
                    val params = requireNotNull(point.paramClassNames)
                    require(point.viewField == null && (point.className.startsWith("$family.") ||
                        PlayerSpeedMenuAdapter.flattenedOwner(point.className)) &&
                        point.className.length <= 512 && point.methodName.length in 1..256 &&
                        validClassName(point.className) && validMethodName(point.methodName) &&
                        params.all { it.length in 1..512 })
                    require(if (key == "free_data_guide") params.size == 1 &&
                        params[0] in setOf("kotlin.coroutines.Continuation", "kotlin.coroutines.jvm.internal.ContinuationImpl")
                        else params.isEmpty())
                }
            }
            PlayerPopupPromotionPoints(read("free_data_guide", PlayerPopupPromotionAdapter.NETWORK_PACKAGE),
                read("advert_float", PlayerPopupPromotionAdapter.MODEL_PACKAGE))
        }.getOrNull()

        private fun validClassName(name: String): Boolean = name.split('.').all { segment ->
            segment.isNotEmpty() && identifierStart(segment[0]) && segment.all { identifierStart(it) || it in '0'..'9' }
        }
        private fun validMethodName(name: String) = name.isNotEmpty() && identifierStart(name[0]) &&
            name.all { identifierStart(it) || it in '0'..'9' }
        private fun identifierStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '$'
    }
}

/** 安装期只做有界反射；语义 DEX 查询由 VersionAdapter 后台共享 Session 调度。 */
internal object PlayerPopupPromotionAdapter {
    const val NETWORK_PACKAGE = "com.bilibili.ship.theseus.united.player.mediaplay.network"
    const val MODEL_PACKAGE = "com.bilibili.adcommon.data.model"
    const val GUIDE_CONTINUATION = "$NETWORK_PACKAGE.NetworkShowToastService\$tryToShowFreeDataGuideToast\$1"
    const val TOAST_SERVICE = "tv.danmaku.biliplayerv2.service.IToastService"
    // 旧宿主把次数键、配置读取和曝光事件拆到 helper；只有专用流程日志仍在入口本体。
    const val GUIDE_FLOW_MARK = "already show free data guide toast in this cold start"
    const val ADVERT_CLASS = "$MODEL_PACKAGE.DmAdvert"
    const val CARD_CLASS = "$MODEL_PACKAGE.Card"
    private const val MAX_CANDIDATES = 32
    private val CONTINUATIONS = setOf("kotlin.coroutines.Continuation", "kotlin.coroutines.jvm.internal.ContinuationImpl")

    enum class Family(val query: DexAssistQuery, val diagnostic: String) {
        FREE_DATA_GUIDE(DexAssistQuery.PLAYER_POPUP_FREE_DATA_GUIDE, "dex.assist.player_popup_guide"),
        ADVERT_FLOAT(DexAssistQuery.PLAYER_POPUP_ADVERT_FLOAT, "dex.assist.player_popup_advert")
    }

    fun direct(loader: ClassLoader): PlayerPopupPromotionPoints = PlayerPopupPromotionPoints(
        directFreeDataGuide(loader)?.let(::point), directAdvertFloat(loader)?.let(::point))

    fun directFreeDataGuide(loader: ClassLoader): Method? {
        val continuation = Lookup.classOrNull(loader, GUIDE_CONTINUATION) ?: return null
        if (continuation.superclass?.name != "kotlin.coroutines.jvm.internal.ContinuationImpl") return null
        val owner = Lookup.declaredFields(continuation, true) {
            !Modifier.isStatic(it.modifiers) && it.type.name.startsWith("$NETWORK_PACKAGE.") && it.type != continuation
        }.map { it.type }.distinct().singleOrNull() ?: return null
        return Lookup.declaredMethods(owner, true, ::verifiedFreeDataGuide).singleOrNull()
    }

    fun directAdvertFloat(loader: ClassLoader): Method? {
        val owner = Lookup.classOrNull(loader, ADVERT_CLASS) ?: return null
        return Lookup.declaredMethods(owner, true) { it.name == "getFloatLayers" && verifiedAdvertFloat(it) }.singleOrNull()
    }

    fun verifiedFreeDataGuide(method: Method): Boolean {
        val owner = method.declaringClass.name
        if (!safeInstance(method) || method.returnType != Any::class.java || method.parameterCount != 1 ||
            method.parameterTypes[0].name !in CONTINUATIONS ||
            (!owner.startsWith("$NETWORK_PACKAGE.") && !PlayerSpeedMenuAdapter.flattenedOwner(owner))) return false
        val fields = Lookup.declaredFields(method.declaringClass) { !Modifier.isStatic(it.modifiers) }
        return fields.count { it.type.name == "android.content.Context" } == 1 &&
            fields.count { it.type.name == TOAST_SERVICE } == 1 &&
            fields.count { it.type.name == "android.content.SharedPreferences" } == 1
    }

    fun verifiedAdvertFloat(method: Method): Boolean = safeInstance(method) && method.returnType == List::class.java &&
        method.parameterCount == 0 &&
        (method.declaringClass.name.startsWith("$MODEL_PACKAGE.") || PlayerSpeedMenuAdapter.flattenedOwner(method.declaringClass.name))

    fun resolveFreeDataGuide(loader: ClassLoader, point: HookPoint?): Method? = resolve(loader, point, ::verifiedFreeDataGuide)
    fun resolveAdvertFloat(loader: ClassLoader, point: HookPoint?): Method? = resolve(loader, point, ::verifiedAdvertFloat)

    private fun resolve(loader: ClassLoader, point: HookPoint?, verified: (Method) -> Boolean): Method? {
        point ?: return null
        if (point.viewField != null || point.paramClassNames == null || point.paramClassNames.size > 1) return null
        val owner = Lookup.classOrNull(loader, point.className) ?: return null
        return Lookup.declaredMethods(owner, true) {
            it.name == point.methodName && it.parameterTypes.map(Class<*>::getName) == point.paramClassNames && verified(it)
        }.singleOrNull()
    }

    fun missing(loader: ClassLoader, cached: PlayerPopupPromotionPoints? = null): Set<Family> = buildSet {
        if (directFreeDataGuide(loader) == null && resolveFreeDataGuide(loader, cached?.freeDataGuide) == null) add(Family.FREE_DATA_GUIDE)
        if (directAdvertFloat(loader) == null && resolveAdvertFloat(loader, cached?.advertFloat) == null) add(Family.ADVERT_FLOAT)
    }

    /** 其它功能补适配也不能重查同宿主已失败的家族，或丢弃仍可验真的 DEX 缓存。 */
    fun planned(loader: ClassLoader, cached: PlayerPopupPromotionPoints?, attemptedMissing: Set<Family>, enabled: Boolean): Set<Family> =
        if (!enabled) emptySet() else missing(loader, cached) - attemptedMissing

    fun locate(loader: ClassLoader, session: DexAssistSession?, enabled: Boolean,
               cached: PlayerPopupPromotionPoints? = null, planned: Set<Family> = Family.entries.toSet()): PlayerPopupPromotionPoints {
        // 关闭时只保留已验证宿主快照中的纯描述；再次启用使用前才二次验真。
        if (!enabled) return cached ?: PlayerPopupPromotionPoints()
        val direct = direct(loader)
        val priorGuide = resolveFreeDataGuide(loader, cached?.freeDataGuide)?.let(::point)
        val priorFloat = resolveAdvertFloat(loader, cached?.advertFloat)?.let(::point)
        fun assisted(family: Family): HookPoint? {
            // result() 会补查未计划 Query；跳过的家族必须在这里挡住，不能触发额外建桥。
            if (!enabled || family !in planned) return null
            val methods = (session?.result(family.query) as? DexAssistResult.Candidates)?.methods.orEmpty()
            if (methods.size > MAX_CANDIDATES) return null
            val verified = methods.mapNotNull { candidate ->
                val description = point(candidate)
                if (family == Family.FREE_DATA_GUIDE) resolveFreeDataGuide(loader, description)
                else resolveAdvertFloat(loader, description)
            }.distinctBy(Method::toGenericString).singleOrNull() ?: return null
            return point(verified)
        }
        return PlayerPopupPromotionPoints(direct.freeDataGuide ?: priorGuide ?: assisted(Family.FREE_DATA_GUIDE),
            direct.advertFloat ?: priorFloat ?: assisted(Family.ADVERT_FLOAT))
    }

    fun merge(live: PlayerPopupPromotionPoints?, cached: PlayerPopupPromotionPoints?): PlayerPopupPromotionPoints? =
        if (live == null) cached else PlayerPopupPromotionPoints(live.freeDataGuide ?: cached?.freeDataGuide,
            live.advertFloat ?: cached?.advertFloat)

    fun point(method: Method) = HookPoint(method.declaringClass.name, method.name, method.parameterTypes.map(Class<*>::getName))
    private fun safeInstance(method: Method) = !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) && !method.isBridge
}
