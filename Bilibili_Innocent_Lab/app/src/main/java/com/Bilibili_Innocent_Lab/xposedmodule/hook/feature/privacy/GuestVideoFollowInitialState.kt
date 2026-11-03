package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface GuestVideoFollowSource {
    fun read(mid: Long, callback: (Boolean) -> Unit)
}

/** Refreshes only the video owner's button after the host has bound its server-provided state. */
internal class GuestVideoFollowInitialState(
    private val source: GuestVideoFollowSource,
    private val isGuest: () -> Boolean,
    private val names: Names = Names()
) {
    constructor(library: GuestLibraryRuntime, isGuest: () -> Boolean) : this(
        GuestVideoFollowSource { mid, callback ->
            library.readCollections { collections -> callback(collections.follows.any { it.mid == mid }) }
        }, isGuest
    )

    internal data class Names(
        val button: String = "com.bilibili.relation.widget.FollowButton",
        val config: String = "com.bilibili.relation.widget.FollowButtonConfig",
        val helper: String = "com.bilibili.relation.utils.FollowFlowHelper",
        val ownerCallback: String = "com.bilibili.ship.theseus.united.page.intro.module.owner.OwnerService\$a",
        val legacyOwnerCallback: String = "com.bilibili.ship.theseus.united.page.intro.module.owner.OwnerService\$b"
    )

    fun install(env: HookEnvironment) {
        val loader = requireNotNull(env.classLoader)
        val button = requireNotNull(Lookup.classOrNull(loader, names.button))
        val config = requireNotNull(Lookup.classOrNull(loader, names.config))
        val helper = requireNotNull(Lookup.classOrNull(loader, names.helper))
        val bind = requireNotNull(Lookup.methodOrNull(button, "bind", config))
            .takeIf { !it.isStatic && it.returnType == Void.TYPE } ?: error("FollowButton.bind shape")
        val configMid = requireNotNull(Lookup.methodOrNull(config, "getMid"))
            .takeIf { !it.isStatic && it.returnType == classOf<Long>() } ?: error("FollowButtonConfig mid")
        val configCallback = requireNotNull(Lookup.methodOrNull(config, "getCallback"))
            .takeIf { !it.isStatic } ?: error("FollowButtonConfig callback")
        // 8.84–8.96 use OwnerService$b; the newer $a class is merely a companion in those versions.
        val ownerCallbacks = listOf(names.ownerCallback, names.legacyOwnerCallback)
            .mapNotNull { Lookup.classOrNull(loader, it) }
            .filter { configCallback.returnType.isAssignableFrom(it) }
        require(ownerCallbacks.isNotEmpty()) { "Video owner callback missing" }
        val guestAttention = requireNotNull(Lookup.methodOrNull(config, "isGuestAttention"))
            .takeIf { !it.isStatic && it.returnType == Boolean::class.javaObjectType } ?: error("FollowButtonConfig guest state")
        val currentHelper = requireNotNull(Lookup.methodOrNull(button, "getMFollowFlowHelper"))
            .takeIf { !it.isStatic && it.returnType == helper } ?: error("FollowButton helper")
        val helperMid = requireNotNull(Lookup.fieldOrNull(helper, "c"))
            .takeIf { !it.isStatic && it.type == classOf<Long>() } ?: error("FollowFlowHelper mid")
        val update = requireNotNull(Lookup.methodOrNull(button, "updateUI", classOf<Boolean>()))
            .takeIf { !it.isStatic && it.returnType == Void.TYPE } ?: error("FollowButton update")
        val updateGuest = requireNotNull(Lookup.methodOrNull(button, "updateUI", classOf<Boolean>(), classOf<Boolean>()))
            .takeIf { !it.isStatic && it.returnType == Void.TYPE } ?: error("FollowButton guest update")
        val applied = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        fun guest(): Boolean = runCatching(isGuest).getOrDefault(false)
        fun reportFailure() {
            if (failed.compareAndSet(false, true)) {
                env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR)
                runCatching { env.reportStatus("guest_video_follow_initial_error", "unavailable") }
            }
        }
        env.registrar.exact("guest.video.follow.initial", button, bind.name, *bind.parameterTypes) {
            after {
                if (hasThrowable || !guest()) return@after
                runCatching bindCheck@ {
                    val target = instance ?: return@bindCheck
                    val options = argOrNull(0) ?: return@bindCheck
                    val boundCallback = configCallback.invoke(options) ?: return@bindCheck
                    if (ownerCallbacks.none { it.isInstance(boundCallback) }) return@bindCheck
                    val mid = (configMid.invoke(options) as? Long)?.takeIf { it > 0 } ?: return@bindCheck
                    val secondary = guestAttention.invoke(options) as? Boolean
                    val boundHelper = currentHelper.invoke(target) ?: return@bindCheck
                    if (helperMid.getLong(boundHelper) != mid) return@bindCheck
                    val weakTarget = WeakReference(target)
                    val weakHelper = WeakReference(boundHelper)
                    source.read(mid) { following ->
                        runCatching applyState@ {
                            val view = weakTarget.get() ?: return@applyState
                            val expected = weakHelper.get() ?: return@applyState
                            if (!guest() || currentHelper.invoke(view) !== expected || helperMid.getLong(expected) != mid) return@applyState
                            if (secondary == null) update.invoke(view, following) else updateGuest.invoke(view, following, secondary)
                            if (applied.compareAndSet(false, true)) runCatching { env.reportStatus("guest_video_follow_initial", "applied") }
                        }.onFailure { reportFailure() }
                    }
                }.onFailure { reportFailure() }
            }
        }
    }
}
