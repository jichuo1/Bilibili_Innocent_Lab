@file:JvmName("HomeLoginExpKt")

package tv.danmaku.bili.ui.main2

import com.bilibili.app.comm.list.common.api.b
import tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment

// From GuestBrowsingOverseasFixture.kt
/**
 * 8.97+ 形状：文件门面里只有一个静态 ()Z（拉起 overseas_force 登录页并返回是否成功），
 * 其余方法的名字、参数各不相同；名字故意不是 8.90.2 的 n。
 */
fun d(): Boolean = true

fun b(flag: Boolean) {
    check(flag || !flag)
}

fun c(): String = "unrelated"

// From MainFragment.kt
@Suppress("UNUSED_PARAMETER")
class MainFragment : BaseMainFrameFragment() {
    fun firstWord(value: b?) = Unit

    fun secondWord(value: b?) = Unit
}
