package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import org.junit.Assert.*
import org.junit.Test

class GuestHostFollowConfirmationTest {
    open class Callback
    class VideoOwner : Callback()
    class LegacyVideoOwner : Callback()
    class OtherPage : Callback()
    class FutureOwnerService { class RenamedCallback : Callback() }
    class HostContext

    class NewHelper(@JvmField var c: Long, @JvmField var i: Callback) {
        var dialogs = 0
        var network = 0
        var throwOnDialog = false
        fun a(context: HostContext) = Unit
        fun b(context: HostContext) {
            dialogs++
            if (throwOnDialog) error("host dialog failed")
        }
        fun c(context: HostContext) { network++ }
    }

    class OldHelper {
        private fun q(context: HostContext) = Unit
        private fun w(context: HostContext) = Unit
        private fun y(context: HostContext) = Unit
    }

    class AmbiguousHelper(@JvmField var c: Long = 42, @JvmField var i: Callback = VideoOwner()) {
        fun a(context: HostContext) = Unit
        fun b(context: HostContext) = Unit
        fun c(context: HostContext) = Unit
        fun extra(context: HostContext) = Unit
    }

    private class Store : GuestHostFollowConfirmation.Source {
        var currentToken: Any = Any()
        var following = false
        var failures = 0
        val reads = mutableListOf<(Boolean) -> Unit>()
        val writes = mutableListOf<Pair<Long, Boolean>>()
        var fallbackAction: (() -> Unit)? = null
        var fallbackAvailable = true
        override fun token(): Any = currentToken
        override fun valid(token: Any): Boolean = token === currentToken
        override fun read(token: Any, mid: Long, done: (Boolean) -> Unit) { reads += done }
        override fun set(token: Any, mid: Long, following: Boolean, announce: Boolean, done: () -> Unit) {
            writes += mid to following
            this.following = following
            done()
        }
        override fun fallback(context: Any, confirm: () -> Unit): Boolean {
            if (fallbackAvailable) fallbackAction = confirm
            return fallbackAvailable
        }
        override fun fail(token: Any) { failures++ }
        fun finishRead() { reads.removeAt(0)(following) }
    }

    private class Harness(
        failId: String? = null,
        knownOwners: Boolean = true,
        ownerServiceName: String = FutureOwnerService::class.java.name
    ) {
        val store = Store()
        val registrar = PlayerPortTestRegistrar(failId)
        val statuses = mutableMapOf<String, String>()
        val helper = NewHelper(42, VideoOwner())
        val context = HostContext()
        val results = mutableListOf<Boolean>()
        var guest = true
        private val loader = javaClass.classLoader!!
        private val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })
        val controller = GuestHostFollowConfirmation(store, { guest }, GuestHostFollowConfirmation.Names(
            NewHelper::class.java.name,
            Callback::class.java.name,
            if (knownOwners) listOf(VideoOwner::class.java.name, LegacyVideoOwner::class.java.name) else listOf("missing.OwnerCallback"),
            HostContext::class.java,
            ownerServiceName
        )).also { it.install(env) }

        fun click() = controller.click(helper, context, helper.c, announce = false) { results += it }
        fun confirm() = registrar.invoke("guest.video.follow.confirmed", helper, arrayOf(context)) {
            helper.c(context)
            null
        }
    }

    @Test fun followedAuthorUsesHostConfirmationAndOnlyConfirmedActionChangesTheLibrary() {
        val h = Harness()
        h.store.following = true
        assertTrue(h.controller.canHandle(h.helper))
        h.click()
        h.store.finishRead()
        assertEquals(1, h.helper.dialogs)
        assertTrue(h.store.writes.isEmpty())
        assertTrue(h.results.isEmpty())

        h.confirm()
        assertEquals(listOf(42L to false), h.store.writes)
        assertEquals(listOf(false), h.results)
        assertEquals(0, h.helper.network)
        assertEquals("shown", h.statuses["guest_video_follow_confirmation"])
        assertEquals("local", h.statuses["guest_video_follow_confirmed"])

        h.confirm()
        assertEquals(1, h.store.writes.size)
        assertEquals(0, h.helper.network)
    }

    @Test fun unfollowedAuthorIsFollowedWithoutShowingTheUnfollowDialog() {
        val h = Harness()
        h.click()
        h.store.finishRead()
        assertEquals(listOf(42L to true), h.store.writes)
        assertEquals(listOf(true), h.results)
        assertEquals(0, h.helper.dialogs)
    }

    @Test fun dismissedDialogLeavesFollowingUntouchedAndOtherHelpersKeepHostBehavior() {
        val h = Harness()
        h.store.following = true
        h.click()
        h.store.finishRead()
        assertTrue(h.store.following)
        assertTrue(h.store.writes.isEmpty())

        val unrelated = NewHelper(43, OtherPage())
        assertFalse(h.controller.canHandle(unrelated))
        h.registrar.invoke("guest.video.follow.confirmed", unrelated, arrayOf(h.context)) {
            unrelated.c(h.context)
            null
        }
        assertEquals(1, unrelated.network)
    }

    @Test fun staleSessionOrReboundCallbackNeverMutatesLocalStateOrCallsTheNetwork() {
        val stale = Harness()
        stale.store.following = true
        stale.click()
        stale.store.finishRead()
        stale.store.currentToken = Any()
        stale.guest = false
        stale.confirm()
        assertTrue(stale.store.writes.isEmpty())
        assertEquals(0, stale.helper.network)

        val rebound = Harness()
        rebound.store.following = true
        rebound.click()
        rebound.store.finishRead()
        rebound.helper.i = VideoOwner()
        rebound.confirm()
        assertTrue(rebound.store.writes.isEmpty())
        assertEquals(0, rebound.helper.network)
    }

    @Test fun delayedReadDoesNotShowAStaleDialogAndLegacyOwnerIsRecognized() {
        val h = Harness()
        h.helper.i = LegacyVideoOwner()
        assertTrue(h.controller.canHandle(h.helper))
        h.store.following = true
        h.click()
        h.helper.c = 99
        h.store.finishRead()
        assertEquals(0, h.helper.dialogs)
        assertTrue(h.store.writes.isEmpty())
    }

    @Test fun renamedOwnerCallbackKeepsTheHostConfirmationWhenKnownClassesAreMissing() {
        val h = Harness(knownOwners = false)
        h.helper.i = FutureOwnerService.RenamedCallback()
        h.store.following = true
        assertTrue(h.controller.canHandle(h.helper))
        assertEquals("owner-scope", h.statuses["guest_video_follow_callback_match"])
        assertFalse(h.controller.canHandle(NewHelper(43, OtherPage())))

        h.click()
        h.store.finishRead()
        assertEquals(1, h.helper.dialogs)
        assertTrue(h.store.writes.isEmpty())
        h.confirm()
        assertEquals(listOf(42L to false), h.store.writes)
        assertEquals(listOf(false), h.results)
        assertEquals(0, h.helper.network)
    }

    @Test fun missingOwnerServiceAndKnownCallbacksDoNotInstallConfirmation() {
        assertThrows(IllegalArgumentException::class.java) {
            Harness(knownOwners = false, ownerServiceName = "missing.OwnerService")
        }
    }

    @Test fun unknownHostShapeUsesFallbackAndDoesNotUnfollowUntilConfirmed() {
        val store = Store().apply { following = true }
        val registrar = PlayerPortTestRegistrar()
        val loader = javaClass.classLoader!!
        val statuses = mutableMapOf<String, String>()
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })
        val controller = GuestHostFollowConfirmation(store, { true }, GuestHostFollowConfirmation.Names(
            AmbiguousHelper::class.java.name, Callback::class.java.name, listOf(VideoOwner::class.java.name), HostContext::class.java
        )).also { it.install(env) }
        val helper = AmbiguousHelper()
        val context = HostContext()
        val results = mutableListOf<Boolean>()
        controller.click(helper, context, 42, announce = false) { results += it }
        store.finishRead()
        assertEquals("fallback", statuses["guest_video_follow_confirm_mode"])
        assertNotNull(store.fallbackAction)
        assertTrue(store.writes.isEmpty())
        store.fallbackAction!!()
        store.fallbackAction!!()
        assertEquals(listOf(42L to false), store.writes)
        assertEquals(listOf(false), results)
    }

    @Test fun hostDialogFailureUsesFallbackAndBlocksALateHostNetworkAction() {
        val h = Harness()
        h.store.following = true
        h.helper.throwOnDialog = true
        h.click()
        h.store.finishRead()
        assertEquals("fallback", h.statuses["guest_video_follow_confirmation_error"])
        assertNotNull(h.store.fallbackAction)
        h.confirm()
        assertEquals(0, h.helper.network)
        assertTrue(h.store.writes.isEmpty())
        h.store.fallbackAction!!()
        assertEquals(listOf(42L to false), h.store.writes)
    }

    @Test fun fallbackConfirmationDoesNotApplyAfterSessionChanges() {
        val h = Harness()
        h.store.following = true
        h.helper.throwOnDialog = true
        h.click()
        h.store.finishRead()
        h.store.currentToken = Any()
        h.store.fallbackAction!!()
        assertTrue(h.store.writes.isEmpty())
        assertEquals(0, h.helper.network)
    }

    @Test fun hookRegistrationFailureUsesFallback() {
        val h = Harness("guest.video.follow.confirmed")
        h.store.following = true
        h.click()
        h.store.finishRead()
        assertEquals("fallback", h.statuses["guest_video_follow_confirm_mode"])
        assertTrue(h.store.writes.isEmpty())
        h.store.fallbackAction!!()
        assertEquals(listOf(42L to false), h.store.writes)
    }

    @Test fun unavailableFallbackDoesNotUnfollowOrRunHostNetworkAction() {
        val h = Harness()
        h.store.following = true
        h.store.fallbackAvailable = false
        h.helper.throwOnDialog = true
        h.click()
        h.store.finishRead()
        h.confirm()
        assertEquals(1, h.store.failures)
        assertTrue(h.store.writes.isEmpty())
        assertEquals(0, h.helper.network)
    }

    @Test fun resolverAcceptsOnlyTheTwoVerifiedCompleteHostShapes() {
        val context = HostContext::class.java
        val old = GuestHostFollowConfirmation.resolveMethods(OldHelper::class.java, context)
        assertEquals("w", old?.first?.name)
        assertEquals("y", old?.second?.name)
        val newer = GuestHostFollowConfirmation.resolveMethods(NewHelper::class.java, context)
        assertEquals("b", newer?.first?.name)
        assertEquals("c", newer?.second?.name)
        assertNull(GuestHostFollowConfirmation.resolveMethods(AmbiguousHelper::class.java, context))
    }
}
