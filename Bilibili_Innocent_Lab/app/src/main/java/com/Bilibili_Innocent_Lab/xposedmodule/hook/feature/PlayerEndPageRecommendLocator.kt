package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 仅 UGC ViewEndPage 回复；安装时核对结构，不触碰详情页 ViewReply 或 PGC widget。 */
internal object PlayerEndPageRecommendLocator {
    const val MOSS = "com.bapis.bilibili.app.viewunite.v1.ViewMoss"
    const val REPLY = "com.bapis.bilibili.app.viewunite.v1.ViewEndPageReply"
    const val REQUEST = "com.bapis.bilibili.app.viewunite.v1.ViewEndPageReq"
    const val HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
    const val EXPECTED_PATHS = 4

    data class Access(val reply: Class<*>, val sync: Method?, val async: Method?, val handler: Class<*>?,
        val list: Method?, val count: Method?, val defaultInstance: Method?,
        val plan: ProtobufBuilderPlan?, val clear: Method?) {
        val canCopy: Boolean get() = list != null && count != null && defaultInstance != null && plan != null && clear != null
    }

    fun resolve(loader: ClassLoader?): Access? {
        val reply = KavaMemberLookup.classOrNull(loader, REPLY) ?: return null
        return resolve(reply, KavaMemberLookup.classOrNull(loader, MOSS),
            KavaMemberLookup.classOrNull(loader, REQUEST), KavaMemberLookup.classOrNull(loader, HANDLER))
    }

    internal fun resolve(reply: Class<*>, moss: Class<*>?, request: Class<*>?, handler: Class<*>?): Access {
        val list = exact(reply, "getRelatesList", List::class.java)
        val count = exact(reply, "getRelatesCount", Int::class.javaPrimitiveType!!)
        val default = KavaMemberLookup.declaredMethods(reply, makeAccessible = true) {
            it.name == "getDefaultInstance" && Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == reply
        }.singleOrNull()
        val plan = ProtobufBuilderPlan.resolve(reply)
        val clear = plan?.method("clearRelates")
        return Access(reply,
            if (moss != null && request != null) exact(moss, "executeViewEndPage", reply, request) else null,
            if (moss != null && request != null && handler?.isInterface == true)
                exact(moss, "viewEndPage", Void.TYPE, request, handler) else null,
            handler, list, count, default, plan, clear)
    }

    private fun exact(owner: Class<*>, name: String, returns: Class<*>, vararg params: Class<*>): Method? =
        KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            it.name == name && it.returnType == returns && it.parameterTypes.contentEquals(params) &&
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers)
        }.singleOrNull()
}
