package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isAbstract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 仅 UGC 结束页回复及其合并/渲染后的播放器列表；安装时核对结构，不触碰详情页 ViewReply 或 PGC widget。 */
internal object PlayerEndPageRecommendLocator {
    const val MOSS = "com.bapis.bilibili.app.viewunite.v1.ViewMoss"
    const val REPLY = "com.bapis.bilibili.app.viewunite.v1.ViewEndPageReply"
    const val REQUEST = "com.bapis.bilibili.app.viewunite.v1.ViewEndPageReq"
    const val HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
    const val SERVICE = "com.bilibili.ship.theseus.ugc.endpage.relatedrecommand.UGCEndPageRelatedRecommendService"
    const val PACKAGE = "com.bilibili.ship.theseus.ugc.endpage.relatedrecommand."
    const val EXPECTED_PATHS = 5

    data class Access(val reply: Class<*>, val sync: Method?, val async: Method?, val handler: Class<*>?,
        val list: Method?, val count: Method?, val defaultInstance: Method?,
        val plan: ProtobufBuilderPlan?, val clear: Method?, val mergedList: Method?) {
        val canCopy: Boolean get() = list != null && count != null && defaultInstance != null && plan != null && clear != null
    }

    fun resolve(loader: ClassLoader?): Access? = resolve(loader, null)

    fun resolve(loader: ClassLoader?, cached: HookPoint?): Access? {
        val reply = KavaMemberLookup.classOrNull(loader, REPLY) ?: return null
        val direct = resolve(reply, KavaMemberLookup.classOrNull(loader, MOSS),
            KavaMemberLookup.classOrNull(loader, REQUEST), KavaMemberLookup.classOrNull(loader, HANDLER),
            KavaMemberLookup.classOrNull(loader, SERVICE))
        return if (direct.mergedList != null) direct else direct.copy(mergedList = cachedMerger(loader, cached))
    }

    internal fun resolve(reply: Class<*>, moss: Class<*>?, request: Class<*>?, handler: Class<*>?,
        service: Class<*>? = null): Access {
        val list = exact(reply, "getRelatesList", List::class.java)
        val count = exact(reply, "getRelatesCount", Int::class.javaPrimitiveType!!)
        val default = KavaMemberLookup.declaredMethods(reply, makeAccessible = true) {
            it.name == "getDefaultInstance" && Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == reply
        }.singleOrNull()
        val plan = ProtobufBuilderPlan.resolve(reply)
        val clear = plan?.method("clearRelates")
        val mergedList = service?.let { postMergeList(it) ?: packageMerger(it) }
        return Access(reply,
            if (moss != null && request != null) exact(moss, "executeViewEndPage", reply, request) else null,
            if (moss != null && request != null && handler?.isInterface == true)
                exact(moss, "viewEndPage", Void.TYPE, request, handler) else null,
            handler, list, count, default, plan, clear, mergedList)
    }

    /**
     * 9.x 先把详情页卡片合并到结束页列表，8.90.x 则直接在三参数渲染函数中
     * 生成 RunningUIComponent。两者都是结束页专属 service 的 List 输出；优先
     * 选择单参数合并入口，旧版没有该入口时再接受明确的 List/容器/String 形状。
     */
    private fun postMergeList(owner: Class<*>): Method? {
        val candidates = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            val params = it.parameterTypes
            val directMerge = params.contentEquals(arrayOf(List::class.java))
            val rendered = params.size == 3 && params[0] == List::class.java &&
                params[2] == String::class.java && !params[1].isPrimitive &&
                params[1] != String::class.java && params[1] != List::class.java
            (directMerge || rendered) && it.returnType == List::class.java &&
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                !it.isBridge && !it.isSynthetic
        }
        return candidates.firstOrNull { it.parameterTypes.contentEquals(arrayOf(List::class.java)) }
            ?: candidates.singleOrNull()
    }

    /**
     * 9.12.0 起 service 不再有 `(List) -> List` 合并入口：详情页卡片与结束页卡片的合并搬到同包混淆类
     * （9.12–9.14 均为 `relatedrecommand.d#a(List, List) -> List`），由 `selectedRelatedCardsFlow`
     * 调用、产出结束页 UI 的卡片列表。不挂它时，协议层清掉结束页卡片后详情页卡片仍会被合并进来
     * （9.14.0 真机回执 `partial:4/5`）。
     *
     * 同包 `'a'..'z'` 字母表 + 精确形状（具体方法、`(List, List) -> List`）+ 全包唯一；纯 ClassLoader 查找。
     * 31 个本地宿主离线核对：9.12.0–9.14.0 恰好一个，9.11.0 及更早为 0（那时走 service 自己的入口）。
     */
    private fun packageMerger(service: Class<*>): Method? {
        val pkg = service.name.substringBeforeLast('.', "").ifEmpty { return null }
        return ('a'..'z').mapNotNull { KavaMemberLookup.classOrNull(service.classLoader, "$pkg.$it") }
            .flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true, predicate = ::mergerShape)
            }
            .singleOrNull()
    }

    internal fun mergerAbi(method: Method): Boolean = method.declaringClass.name.startsWith(PACKAGE) && mergerShape(method)

    private fun mergerShape(method: Method): Boolean = method.returnType == classOf<List<*>>() && method.parameterTypes.contentEquals(arrayOf(classOf<List<*>>(), classOf<List<*>>())) &&
        !method.isAbstract && !method.isBridge && !method.isSynthetic

    internal fun cachedMerger(loader: ClassLoader?, point: HookPoint?): Method? {
        point ?: return null
        if (point.viewField != null || point.paramClassNames != listOf("java.util.List", "java.util.List")) return null
        val owner = KavaMemberLookup.classOrNull(loader, point.className) ?: return null
        return KavaMemberLookup.declaredMethods(owner, true) { it.name == point.methodName && mergerAbi(it) }.singleOrNull()
    }

    private fun exact(owner: Class<*>, name: String, returns: Class<*>, vararg params: Class<*>): Method? =
        KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            it.name == name && it.returnType == returns && it.parameterTypes.contentEquals(params) &&
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers)
        }.singleOrNull()
}
