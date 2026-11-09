package fixtures.privacy.manual

import android.view.View

/** 保留真实 Tq 的静态参数形状；以计数替代 activity://main/login/ 的 Android 路由。 */
class HomeUserCenterFragment {
    var loginRequests = 0
    var lastView: View? = null

    companion object {
        @JvmStatic
        fun Tq(fragment: HomeUserCenterFragment, view: View?) {
            fragment.lastView = view
            fragment.loginRequests++
        }
    }
}
