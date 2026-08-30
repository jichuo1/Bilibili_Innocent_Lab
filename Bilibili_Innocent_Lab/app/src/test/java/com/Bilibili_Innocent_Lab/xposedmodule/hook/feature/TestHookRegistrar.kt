package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import java.lang.reflect.Constructor

internal object TestHookRegistrar : HookRegistrar {
    override fun first(
        id: String,
        className: String,
        methodName: String,
        block: ModernMemberHookCreator.() -> Unit
    ) = Unit

    override fun all(
        id: String,
        className: String,
        methodName: String,
        block: ModernMemberHookCreator.() -> Unit
    ) = Unit

    override fun exact(
        id: String,
        owner: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>,
        block: ModernMemberHookCreator.() -> Unit
    ) = Unit

    override fun adapted(
        id: String,
        point: VersionAdapter.HookPoint,
        block: ModernMemberHookCreator.() -> Unit
    ) = Unit

    override fun constructor(
        id: String,
        constructor: Constructor<*>,
        block: ModernMemberHookCreator.() -> Unit
    ) = Unit
}
