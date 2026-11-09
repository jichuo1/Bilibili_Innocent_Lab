package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

/**
 * The verified owner callback is a SimpleCallback inside OwnerService in all 38 archived samples.
 * Keep exact known classes, but also accept another nested class with that verified base type when
 * the host renames the callback. This does not classify callbacks from unrelated host pages.
 */
internal class GuestVideoOwnerCallbacks(
    private val knownClasses: List<Class<*>>,
    private val fallbackBase: Class<*>?,
    ownerClassName: String
) {
    private val ownerPrefix = "$ownerClassName\$"

    fun matches(callback: Any): Boolean = knownClasses.any { it.isInstance(callback) } ||
        (fallbackBase?.isInstance(callback) == true && callback.javaClass.name.let {
            it.startsWith(ownerPrefix) && it.length > ownerPrefix.length
        })

    companion object {
        const val OWNER_CLASS = "com.bilibili.ship.theseus.united.page.intro.module.owner.OwnerService"
        const val CALLBACK_BASE = "com.bilibili.relation.utils.FollowFlowHelper\$SimpleCallback"
    }
}
