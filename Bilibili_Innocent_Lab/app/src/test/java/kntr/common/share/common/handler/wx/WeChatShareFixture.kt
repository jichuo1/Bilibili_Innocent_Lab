package kntr.common.share.common.handler.wx

import android.content.Context
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.tencent.mm.opensdk.openapi.WeChatFixtureRuntime

enum class WeChatShareType { SESSION, TIMELINE }
class k(context: Context?, type: WeChatShareType) {
    @JvmField var api: IWXAPI? = null
    fun j(): Boolean = WeChatFixtureRuntime.call("kntr", this) {
        if (api == null) {
            api = WXAPIFactory.createWXAPI(null, "share", true)
            if (!api!!.isWXAppInstalled()) return@call false
            api!!.registerApp("share")
        }
        true
    } as Boolean
}
