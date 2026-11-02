package com.tencent.mm.opensdk.openapi

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** 宿主调用链夹具；证书比较/包存在/SDK 拒绝相互独立，不能用 Hook 假成功替代。 */
object WeChatFixtureRuntime {
    val hooks = ConcurrentHashMap<String, (Any?, Array<Any?>, (Array<Any?>) -> Any?) -> Any?>()
    @Volatile var installed = true
    @Volatile var trustedCertificate = false
    @Volatile var acceptRequest = true
    var initializeFailure: Throwable? = null
    fun call(key: String, receiver: Any?, args: Array<Any?> = emptyArray(), original: (Array<Any?>) -> Any?): Any? =
        hooks[key]?.invoke(receiver, args, original) ?: original(args)
    fun reset() {
        hooks.clear(); installed = true; trustedCertificate = false; acceptRequest = true; initializeFailure = null
    }
}

interface IWXAPI {
    fun isWXAppInstalled(): Boolean
    fun registerApp(id: String): Boolean
    fun sendReq(): Boolean
}

open class BaseWXApiImplV10(@JvmField var checkSignature: Boolean) : IWXAPI {
    override fun isWXAppInstalled() = WeChatFixtureRuntime.installed &&
        (!checkSignature || WeChatFixtureRuntime.trustedCertificate)
    override fun registerApp(id: String) = isWXAppInstalled()
    override fun sendReq() = isWXAppInstalled() && WeChatFixtureRuntime.acceptRequest
}

object WXAPIFactory {
    @JvmStatic fun createWXAPI(context: Context?, appId: String): IWXAPI = createWXAPI(context, appId, true)
    @JvmStatic fun createWXAPI(context: Context?, appId: String, checkSignature: Boolean): IWXAPI =
        createWXAPI(context, appId, checkSignature, 2)
    @JvmStatic fun createWXAPI(context: Context?, appId: String, checkSignature: Boolean, launchMode: Int): IWXAPI =
        WeChatFixtureRuntime.call("factory", null, arrayOf(context, appId, checkSignature, launchMode)) {
            BaseWXApiImplV10(it[2] as Boolean)
        } as IWXAPI
}
