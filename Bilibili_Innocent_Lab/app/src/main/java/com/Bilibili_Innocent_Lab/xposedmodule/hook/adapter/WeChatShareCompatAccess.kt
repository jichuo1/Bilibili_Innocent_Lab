package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * F19.1 的已审计宿主边界。混淆名只属于 8.90.2，不向其它版本猜测外推。
 * 分享包装独占 IWXAPI；登录/支付及 WXEntryActivity 的回调实例不在此列表中。
 * handleIntent 不读 checkSignature，仍使用 SDK 原有来源、checksum 和回调校验。
 */
internal class WeChatShareCompatAccess private constructor(
    val factory: Method,
    val wrappers: List<Wrapper>,
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
        const val HOST_VERSION = 8902100
        const val EXPECTED_WRAPPERS = 2
        private const val SDK = "com.tencent.mm.opensdk.openapi."

        fun locate(loader: ClassLoader?, versionCode: Int): WeChatShareCompatAccess? {
            if (loader == null || versionCode != HOST_VERSION) return null
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
                wrapper(loader, api, "legacy", "kz3.a", "t", Void.TYPE),
                wrapper(loader, api, "kntr", "kntr.common.share.common.handler.wx.k", "j", classOf<Boolean>())
            )
            if (wrappers.isEmpty()) return null
            return WeChatShareCompatAccess(factory, wrappers, flag)
        }

        private fun wrapper(
            loader: ClassLoader, api: Class<*>, key: String, name: String,
            initializeName: String, returnType: Class<*>
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
                val activity = Lookup.classOrNull(loader, "android.app.Activity") ?: return null
                val config = Lookup.classOrNull(loader, "com.bilibili.socialize.share.core.BiliShareConfiguration") ?: return null
                if (Lookup.constructorOrNull(owner, activity, config) == null) return null
            } else {
                val context = Lookup.classOrNull(loader, "android.content.Context") ?: return null
                val type = Lookup.classOrNull(loader, "kntr.common.share.common.handler.wx.WeChatShareType") ?: return null
                if (!type.isEnum || Lookup.constructorOrNull(owner, context, type) == null) return null
            }
            return Wrapper(key, initialize, field)
        }
    }
}
