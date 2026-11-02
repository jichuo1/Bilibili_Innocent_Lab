package kz3

import android.app.Activity
import com.bilibili.socialize.share.core.BiliShareConfiguration
import com.tencent.mm.opensdk.modelmsg.SendMessageToWX
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.tencent.mm.opensdk.openapi.WeChatFixtureRuntime

class a(activity: Activity?, config: BiliShareConfiguration) {
    @JvmField var api: IWXAPI? = null
    fun Q(req: SendMessageToWX.Req) = Unit
    fun t() {
        WeChatFixtureRuntime.call("legacy", this) {
            WeChatFixtureRuntime.initializeFailure?.let { throw it }
            if (api == null) {
                api = WXAPIFactory.createWXAPI(null, "share", true)
                if (api!!.isWXAppInstalled()) check(api!!.registerApp("share"))
            }
            check(api!!.isWXAppInstalled()) { "-234" }
        }
    }
}
