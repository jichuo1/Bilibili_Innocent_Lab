package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator.MemberHookCreator

internal object TestHookRegistrar : HookRegistrar {
    override fun first(
        id: String,
        className: String,
        methodName: String,
        block: MemberHookCreator.() -> Unit
    ) = Unit

    override fun all(
        id: String,
        className: String,
        methodName: String,
        block: MemberHookCreator.() -> Unit
    ) = Unit

    override fun exact(
        id: String,
        owner: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        block: MemberHookCreator.() -> Unit
    ) = Unit

    override fun adapted(
        id: String,
        point: VersionAdapter.HookPoint,
        block: MemberHookCreator.() -> Unit
    ) = Unit
}
