package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method

/** DEX 发现并经全量结构验真的分享包装落点；只存类名，恢复时完整复验。 */
data class WeChatShareClasses(
    val legacyOwner: String, val legacyInitialize: String,
    val modernOwner: String?, val modernInitialize: String?,
    val usesLegacyConstructors: Boolean
) {
    internal fun toProfile() = WeChatShareVersionProfile(legacyOwner, modernOwner, usesLegacyConstructors,
        legacyInitialize, modernInitialize)
    fun toJson() = JSONObject().put("legacy", legacyOwner).put("legacy_init", legacyInitialize)
        .put("modern", modernOwner ?: JSONObject.NULL).put("modern_init", modernInitialize ?: JSONObject.NULL)
        .put("legacy_ctors", usesLegacyConstructors)

    companion object {
        fun fromJson(value: JSONObject): WeChatShareClasses? {
            fun name(key: String) = value.optString(key).takeIf {
                it.length in 1..512 && it.all { c -> c.isLetterOrDigit() || c in "._$" }
            }
            val modern = if (value.isNull("modern")) null else name("modern") ?: return null
            val modernInit = if (value.isNull("modern_init")) null else name("modern_init") ?: return null
            if ((modern == null) != (modernInit == null)) return null
            if (value.opt("legacy_ctors") !is Boolean) return null
            return WeChatShareClasses(name("legacy") ?: return null, name("legacy_init") ?: return null,
                modern, modernInit, value.getBoolean("legacy_ctors"))
        }
        internal fun fromProfile(profile: WeChatShareVersionProfile) = WeChatShareClasses(
            profile.legacyOwner, profile.legacyInitialize, profile.modernOwner,
            profile.modernOwner?.let { profile.modernInitialize }, profile.usesLegacyConstructors)
    }
}

/**
 * 微信分享的已审计宿主边界。混淆名按核验过的版本匹配，不向其它版本猜测外推。
 * 分享包装独占 IWXAPI；登录/支付及 WXEntryActivity 的回调实例不在此列表中。
 * handleIntent 不读 checkSignature，仍使用 SDK 原有来源、checksum 和回调校验。
 */
internal class WeChatShareCompatAccess private constructor(
    val factory: Method,
    val wrappers: List<Wrapper>,
    val expectedWrappers: Int,
    private val signatureCheck: Field
) {
    data class Wrapper(val key: String, val initialize: Method, val api: Field)

    fun relaxExisting(wrapper: Wrapper, receiver: Any): Boolean {
        val api = wrapper.api.get(receiver) ?: return false
        if (!signatureCheck.declaringClass.isInstance(api)) return false
        if (!signatureCheck.getBoolean(api)) return false
        signatureCheck.setBoolean(api, false)
        return !signatureCheck.getBoolean(api)
    }

    fun isRelaxed(api: Any?): Boolean = api != null &&
        signatureCheck.declaringClass.isInstance(api) && !signatureCheck.getBoolean(api)

    companion object {
        private const val SDK = "com.tencent.mm.opensdk.openapi."
        const val DIAGNOSTIC = "dex.assist.wechat_share"
        private const val WX_PACKAGE = "kntr.common.share.common.handler.wx."
        private const val MAX_CANDIDATES = 32
        val queries = setOf(DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY)

        fun locate(loader: ClassLoader?, versionCode: Int, cached: WeChatShareClasses? = null): WeChatShareCompatAccess? {
            if (loader == null) return null
            val direct = WeChatShareVersionProfile.forVersion(versionCode)
                ?.let { locateWithProfile(loader, it) }
            if (direct != null && direct.wrappers.size == direct.expectedWrappers) return direct
            val fromCache = cached?.let { locateWithProfile(loader, it.toProfile()) }
            return when {
                direct == null -> fromCache
                fromCache != null && fromCache.wrappers.size > direct.wrappers.size -> fromCache
                else -> direct
            }
        }

        /** 已验真落点（版本表或 DEX 发现）的缓存恢复：仍走完整结构验真。 */
        fun resolve(loader: ClassLoader, classes: WeChatShareClasses): WeChatShareCompatAccess? =
            locateWithProfile(loader, classes.toProfile())

        fun applicable(loader: ClassLoader): Boolean =
            Lookup.hasClass(loader, SDK + "IWXAPI") && Lookup.hasClass(loader, SDK + "WXAPIFactory") &&
                Lookup.hasClass(loader, SDK + "BaseWXApiImplV10")

        fun needsQuery(enabled: Boolean, applicable: Boolean, directFound: Boolean) = enabled && applicable && !directFound
        fun refreshCache(enabled: Boolean, applicable: Boolean, directFound: Boolean, cachedFound: Boolean, attempted: Boolean) =
            needsQuery(enabled, applicable, directFound) && !cachedFound && !attempted

        fun assisted(loader: ClassLoader, session: DexAssistSession?): WeChatShareClasses? {
            val candidates = session?.result(DexAssistQuery.WECHAT_SHARE_WRAPPER_DISCOVERY)
                as? DexAssistResult.Candidates ?: return null
            return discover(loader, candidates)
        }

        /**
         * DEX 只作候选发现：createWXAPI 的全部调用者（含登录/支付/小程序）逐个过分享专属全量验真。
         * legacy 必须恰一个验真通过；kntr 包内有调用者时必须恰一个验真通过，否则整功能缺失。
         * 构造代次（usesLegacyConstructors）由实际验真分支导出，legacy 与 kntr 的代次必须一致。
         */
        fun discover(loader: ClassLoader, candidates: DexAssistResult.Candidates): WeChatShareClasses? {
            if (candidates.methods.size > MAX_CANDIDATES) return null
            val api = Lookup.classOrNull(loader, SDK + "IWXAPI") ?: return null
            val byOwner = candidates.methods.groupBy { it.declaringClass }
            data class LegacyHit(val owner: Class<*>, val initialize: Method, val usesLegacyConstructors: Boolean)
            data class ModernHit(val owner: Class<*>, val initialize: Method, val usesLegacyConstructors: Boolean)
            val legacyHits = mutableListOf<LegacyHit>()
            val modernHits = mutableListOf<ModernHit>()
            var wxCandidateOwners = 0
            for ((owner, methods) in byOwner) {
                val inWxPackage = owner.name.startsWith(WX_PACKAGE)
                if (inWxPackage) wxCandidateOwners++
                Lookup.declaredFields(owner, makeAccessible = true) {
                    !it.isStatic && it.type == api
                }.singleOrNull() ?: continue
                var modernMatched = false
                if (inWxPackage || '.' !in owner.name) {
                    for (usesLegacy in listOf(true, false)) {
                        val contextName = if (usesLegacy) "android.content.Context" else WX_PACKAGE + "WeChatShareActivity"
                        val context = Lookup.classOrNull(loader, contextName) ?: continue
                        val type = Lookup.classOrNull(loader, WX_PACKAGE + "WeChatShareType") ?: continue
                        if (!type.isEnum || Lookup.constructorOrNull(owner, context, type) == null) continue
                        methods.filter { !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<Boolean>() }.singleOrNull()
                            ?.let { modernHits += ModernHit(owner, it, usesLegacy); modernMatched = true }
                    }
                    if (!inWxPackage && modernMatched) wxCandidateOwners++
                }
                if (!inWxPackage && !modernMatched) {
                    val req = Lookup.classOrNull(loader, "com.tencent.mm.opensdk.modelmsg.SendMessageToWX\$Req") ?: continue
                    if (Lookup.declaredMethods(owner) { !it.isStatic &&
                            it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(req)) }.size != 1) continue
                    val initialize = methods.filter { !it.isStatic && it.parameterCount == 0 && it.returnType == Void.TYPE }
                        .singleOrNull() ?: continue
                    val activity = Lookup.classOrNull(loader, "android.app.Activity")
                    val config = Lookup.classOrNull(loader, "com.bilibili.socialize.share.core.BiliShareConfiguration")
                    val legacyCtor = activity != null && config != null && Lookup.constructorOrNull(owner, activity, config) != null
                    val page = Lookup.classOrNull(loader, "com.bilibili.socialize.share.core.shareparam.ShareParamWebPage")
                    val webpage = page != null && Lookup.declaredMethods(owner) { !it.isStatic && it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(arrayOf(page)) }.size == 1
                    // 两条构造分支只能命中其一；都命中或都不命中都不是已审计形态。
                    if (legacyCtor == webpage) continue
                    legacyHits += LegacyHit(owner, initialize, legacyCtor)
                }
            }
            val legacy = legacyHits.singleOrNull() ?: return null
            val modern = when {
                wxCandidateOwners == 0 -> if (modernHits.isEmpty()) null else return null
                else -> modernHits.singleOrNull() ?: return null
            }
            if (modern != null && modern.usesLegacyConstructors != legacy.usesLegacyConstructors) return null
            val classes = WeChatShareClasses(legacy.owner.name, legacy.initialize.name,
                modern?.owner?.name, modern?.initialize?.name, legacy.usesLegacyConstructors)
            // 发现结果必须再完整通过既有 locate 全量验真（含工厂与签名标志、全部预期包装）才可缓存。
            return classes.takeIf {
                locateWithProfile(loader, it.toProfile())?.let { access ->
                    access.wrappers.size == access.expectedWrappers
                } == true
            }
        }

        private fun locateWithProfile(loader: ClassLoader, profile: WeChatShareVersionProfile): WeChatShareCompatAccess? {
            val api = Lookup.classOrNull(loader, SDK + "IWXAPI") ?: return null
            val implementation = Lookup.classOrNull(loader, SDK + "BaseWXApiImplV10") ?: return null
            val factoryClass = Lookup.classOrNull(loader, SDK + "WXAPIFactory") ?: return null
            val context = Lookup.classOrNull(loader, "android.content.Context") ?: return null
            val flag = Lookup.fieldOrNull(implementation, "checkSignature")
                ?.takeIf { !it.isStatic && it.type == classOf<Boolean>() } ?: return null
            // 两/三参工厂均委托四参版本；只修改本次创建的参数，不改变全局 SDK/PM 状态。
            val factory = Lookup.methodOrNull(factoryClass, "createWXAPI", context,
                classOf<String>(), classOf<Boolean>(), classOf<Int>())
                ?.takeIf { it.isStatic && it.returnType == api } ?: return null
            val wrappers = listOfNotNull(
                wrapper(loader, api, "legacy", profile.legacyOwner, profile.legacyInitialize, Void.TYPE, profile),
                profile.modernOwner?.let { name ->
                    wrapper(loader, api, "kntr", name, profile.modernInitialize, classOf<Boolean>(), profile)
                }
            )
            if (wrappers.isEmpty()) return null
            return WeChatShareCompatAccess(factory, wrappers, profile.expectedWrappers, flag)
        }

        private fun wrapper(
            loader: ClassLoader, api: Class<*>, key: String, name: String,
            initializeName: String, returnType: Class<*>, profile: WeChatShareVersionProfile
        ): Wrapper? {
            val owner = Lookup.classOrNull(loader, name) ?: return null
            val initialize = Lookup.methodOrNull(owner, initializeName)
                ?.takeIf { !it.isStatic && it.returnType == returnType } ?: return null
            val field = Lookup.declaredFields(owner, makeAccessible = true) {
                !it.isStatic && it.type == api
            }.singleOrNull() ?: return null
            // 除版本外再次核对分享专属结构，漂移或歧义时保留宿主原行为。
            if (key == "legacy") {
                val req = Lookup.classOrNull(loader, "com.tencent.mm.opensdk.modelmsg.SendMessageToWX\$Req") ?: return null
                if (Lookup.declaredMethods(owner) { !it.isStatic &&
                        it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(req)) }.size != 1) return null
                if (profile.usesLegacyConstructors) {
                    val activity = Lookup.classOrNull(loader, "android.app.Activity") ?: return null
                    val config = Lookup.classOrNull(loader, "com.bilibili.socialize.share.core.BiliShareConfiguration") ?: return null
                    if (Lookup.constructorOrNull(owner, activity, config) == null) return null
                } else {
                    // 新版样本不再声明旧构造签名；另核对分享网页的方法，避免只凭混淆名匹配。
                    val page = Lookup.classOrNull(loader, "com.bilibili.socialize.share.core.shareparam.ShareParamWebPage") ?: return null
                    if (Lookup.declaredMethods(owner) { !it.isStatic && it.returnType == Void.TYPE &&
                            it.parameterTypes.contentEquals(arrayOf(page)) }.size != 1) return null
                }
            } else {
                val contextName = if (profile.usesLegacyConstructors) "android.content.Context"
                    else "kntr.common.share.common.handler.wx.WeChatShareActivity"
                val context = Lookup.classOrNull(loader, contextName) ?: return null
                val type = Lookup.classOrNull(loader, "kntr.common.share.common.handler.wx.WeChatShareType") ?: return null
                if (!type.isEnum || Lookup.constructorOrNull(owner, context, type) == null) return null
            }
            return Wrapper(key, initialize, field)
        }
    }
}
