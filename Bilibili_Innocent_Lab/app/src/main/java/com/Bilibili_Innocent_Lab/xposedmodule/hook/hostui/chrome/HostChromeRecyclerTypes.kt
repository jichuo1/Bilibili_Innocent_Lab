package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostRecyclerTypes

/** Cache class metadata only; the bounded name walk also supports host/plugin class loaders. */
internal class HostChromeRecyclerTypes {
    private val delegate = HostRecyclerTypes()

    fun matches(type: Class<*>): Boolean = delegate.matches(type)
}
