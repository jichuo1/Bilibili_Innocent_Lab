package Ov0

import com.bilibili.socialize.share.core.shareparam.ShareParamWebPage
import com.tencent.mm.opensdk.modelmsg.SendMessageToWX
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.tencent.mm.opensdk.openapi.WeChatFixtureRuntime

class a {
    @JvmField var api: IWXAPI? = null
    fun A(req: SendMessageToWX.Req) = Unit
    fun s(page: ShareParamWebPage) = Unit
    fun l() {
        WeChatFixtureRuntime.call("legacy", this) {
            if (api == null) api = WXAPIFactory.createWXAPI(null, "share", true)
            check(api!!.isWXAppInstalled())
        }
    }
}
