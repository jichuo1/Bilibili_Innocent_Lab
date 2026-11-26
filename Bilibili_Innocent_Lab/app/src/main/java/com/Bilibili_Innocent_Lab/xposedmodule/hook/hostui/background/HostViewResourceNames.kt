package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.content.res.Resources
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostViewResourceNames as SharedHostViewResourceNames

/** 委托至 [com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostViewResourceNames]；保留同包测试入口。 */
internal object HostViewResourceNames {
    fun <T> inPass(block: () -> T): T = SharedHostViewResourceNames.inPass(block)

    fun name(view: View): String = SharedHostViewResourceNames.name(view)

    internal fun name(resources: Resources, id: Int): String = SharedHostViewResourceNames.name(resources, id)
}
