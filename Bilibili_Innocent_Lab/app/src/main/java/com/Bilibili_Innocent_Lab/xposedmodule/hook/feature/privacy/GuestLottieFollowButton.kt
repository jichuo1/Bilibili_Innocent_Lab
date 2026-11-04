package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The joint-creator "创作团队" scroller binds `VerifyLottieFollowButton`, a lottie icon instead of the
 * host `FollowButton`, so `FollowFlowHelper.onButtonClick` hooks never see its clicks. Its own click
 * path runs a login gate (`re6.v.d` on 8.90.2, `bt1.o.b` on 9.16.1) that pops the login dialog for
 * guests before `RelationApiManager.addAuthorAttentionV2` is ever reached.
 *
 * Rather than chasing the obfuscated gate or click entry (a private `(String)V` method on 8.84–8.96,
 * an inlined branch of a shared `View.OnClickListener` SAM on 8.97+), this hook swaps the listener
 * itself: `View.setOnClickListener` is the one call every build makes inside its bind. For guests the
 * stored listener becomes a wrapper that follows locally and delegates to the original listener when
 * the session is no longer guest, so a later login restores host behavior.
 *
 * Matrix 4610f0e8 (all 38 samples): the button keeps `getAuthorId()J`, a unique callback field typed
 * by an interface declaring `onFollowSuccess` (`view.g` on 8.84–8.96, `it1.d`/`dy1.c` on 8.97+), and a
 * unique `FollowStateManager$FollowChangeListener` field; the `tv.danmaku` button's only external
 * user is `StaffsNewComponent` (+ `StoryStaffWidget` on 8.97+), the `com.mall` variant serves the
 * mall video page and shares the structure.
 */
internal class GuestLottieFollowButton(
    private val library: GuestLibraryRuntime,
    private val isGuest: () -> Boolean
) {
    fun install(env: HookEnvironment) {
        val loader = requireNotNull(env.classLoader)
        val listener = requireNotNull(Lookup.classOrNull(loader, CHANGE_LISTENER))
        val targets = BUTTONS.mapNotNull { (tag, name) ->
            Lookup.classOrNull(loader, name)
                ?.let { anchors(it, listener) }
                ?.let { tag to it }
        }
        require(targets.isNotEmpty()) { "lottie follow button anchors" }
        env.reportStatus(
            "guest_lottie_follow_targets",
            BUTTONS.joinToString(",") { (tag, _) -> (if (targets.any { it.first == tag }) "+" else "-") + tag }
        )
        val manager = Lookup.classOrNull(loader, STATE_MANAGER)
        val notify = manager?.let { Lookup.methodOrNull(it, "notify", classOf<Long>(), classOf<Boolean>(), listener) }
        val getInstance = manager?.let { Lookup.methodOrNull(it, "getInstance") }?.takeIf { it.isStatic }
        val applied = AtomicBoolean(false)
        env.registrar.exact(
            "guest.lottie.follow.listen", classOf<View>(), "setOnClickListener", classOf<View.OnClickListener>()
        ) {
            before {
                val target = instance ?: return@before
                val anchors = targets.firstNotNullOfOrNull { (_, anchored) ->
                    anchored.takeIf { it.button.isInstance(target) }
                } ?: return@before
                if (!isGuest()) return@before
                // A null listener means the host is disabling the view; keep it inert.
                val original = argOrNull(0) as? View.OnClickListener ?: return@before
                args[0] = View.OnClickListener { view ->
                    if (!isGuest()) {
                        original.onClick(view)
                    } else {
                        click(env, anchors, applied, view, notify, getInstance)
                    }
                }
            }
        }
    }

    private fun click(
        env: HookEnvironment,
        anchors: Anchors,
        applied: AtomicBoolean,
        view: View,
        notify: Method?,
        getInstance: Method?
    ) {
        val mid = runCatching { anchors.authorId.invoke(view) as? Long }.getOrNull()
        val callback = runCatching { anchors.callback.get(view) }.getOrNull()
        if (mid == null || mid <= 0L) {
            runCatching { callback?.let { anchors.onError?.invoke(it, *arrayOf<Any?>(null)) } }
            return
        }
        library.setFollow(mid, true, null, null)
        runCatching { anchors.play?.invoke(view) }
        runCatching { callback?.let { anchors.onSuccess?.invoke(it) } }
        if (notify != null && getInstance != null) runCatching {
            notify.invoke(getInstance.invoke(null), mid, true, anchors.changed?.get(view))
        }
        if (applied.compareAndSet(false, true)) {
            runCatching { env.reportStatus("guest_lottie_follow", "applied") }
        }
    }

    /** The members the click path reads; a drifted shape simply skips that button class. */
    internal data class Anchors(
        val button: Class<*>,
        val authorId: Method,
        val callback: Field,
        val changed: Field?,
        val onSuccess: Method?,
        val onError: Method?,
        val play: Method?
    )

    internal companion object {
        private val BUTTONS = listOf(
            "bili" to "tv.danmaku.bili.videopage.common.widget.view.VerifyLottieFollowButton",
            "mall" to "com.mall.videodetail.vd.videopage.common.widget.view.VerifyLottieFollowButton"
        )
        private const val STATE_MANAGER = "com.bilibili.relation.FollowStateManager"
        private const val CHANGE_LISTENER = "com.bilibili.relation.FollowStateManager\$FollowChangeListener"

        fun anchors(button: Class<*>, listener: Class<*>): Anchors? {
            val authorId = Lookup.methodOrNull(button, "getAuthorId")
                ?.takeIf { !it.isStatic && it.returnType == classOf<Long>() } ?: return null
            val callback = callbackField(button) ?: return null
            val changed = PrivacyStructuralResolver.uniqueField(button) {
                !it.isStatic && it.type isSubclassOf listener
            }
            return Anchors(
                button, authorId, callback, changed,
                Lookup.methodOrNull(callback.type, "onFollowSuccess")
                    ?.takeIf { it.parameterCount == 0 },
                Lookup.methodOrNull(callback.type, "onFollowError", classOf<Throwable>())
                    ?.takeIf { !it.isStatic },
                Lookup.inheritedMethodOrNull(button, "playAnimation")
                    ?.takeIf { !it.isStatic && it.parameterCount == 0 }
            )
        }

        /** The single field whose type is the page's follow callback interface; never a guessed field. */
        fun callbackField(button: Class<*>): Field? =
            PrivacyStructuralResolver.uniqueField(button) { field ->
                !field.isStatic && field.type.methods.any { it.name == "onFollowSuccess" && it.parameterCount == 0 }
            }
    }
}
