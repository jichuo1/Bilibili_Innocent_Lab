package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.*
import com.bilibili.app.dialogmanager.MainDialogManager.DialogManagerInfo
import com.bilibili.app.comment3.data.model.CommentSubjectDescription
import org.junit.Assert.*
import org.junit.Test

class GuestBrowsingFeatureInstallerTest {
    private class Harness(
        commentClass: String = "com.bilibili.app.comment3.data.model.CommentSubjectDescription"
    ) {
        var guest = true
        var stateFailure = false
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val statuses = mutableMapOf<String, String>()
        val loader = GuestBrowsingFeatureInstallerTest::class.java.classLoader!!
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value },
            runtimeEvidence = { _, stage, _ -> stages += stage })
        init {
            GuestBrowsingFeatureInstaller(true, {
                check(!stateFailure)
                guest
            }, commentClass).install(env)
        }
    }

    private class AmbiguousCommentDescription(val first: Boolean, val second: Boolean)

    @Test fun onlyPassiveLoginGuideIsRejectedAndOtherDialogsKeepTheirOriginalResult() {
        val h = Harness()
        var originalCalls = 0
        fun call(key: String?) = h.registrar.invoke("guest_browsing.login-dialog",
            args = arrayOf(DialogManagerInfo(key), null)) { originalCalls++; true }
        assertEquals(false, call("login_guide"))
        assertEquals(0, originalCalls)
        listOf("login_guide_block", "permission", "teenager", null).forEach { assertEquals(true, call(it)) }
        assertEquals(4, originalCalls)
        assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
    }

    @Test fun loginStateIsReadAtInvocationAndReadFailuresDoNotDismissUnrelatedDialogs() {
        val h = Harness()
        h.guest = false
        assertEquals(true, h.registrar.invoke("guest_browsing.login-dialog",
            args = arrayOf(DialogManagerInfo("login_guide"), null)) { true })
        h.guest = true
        assertEquals(true, h.registrar.invoke("guest_browsing.login-dialog",
            args = arrayOf(DialogManagerInfo("login_guide", failRead = true), null)) { true })
        h.stateFailure = true
        assertEquals(true, h.registrar.invoke("guest_browsing.login-dialog",
            args = arrayOf(DialogManagerInfo("login_guide"), null)) { true })
        assertTrue(FeatureRuntimeStage.ERROR in h.stages)
    }

    @Test fun onePassLoginGuideIsSuppressedForGuestsOnly() {
        val h = Harness()
        assertTrue(h.registrar.registrationAttempts.any { it.first == "guest_browsing.one-pass-guide" })
        var ran = 0
        fun fire() = h.registrar.invoke("guest_browsing.one-pass-guide", args = arrayOf("home", null, true)) { ran++ }
        fire()
        assertEquals(0, ran)
        h.guest = false
        fire()
        assertEquals(1, ran)
    }

    @Test fun commentGuestLimitIsClearedAtConstructionForGuestsOnly() {
        val h = Harness()
        var constructed: CommentSubjectDescription? = null
        fun build(guestLimit: Boolean) {
            val args = arrayOf<Any?>(guestLimit, listOf("reply"))
            h.registrar.invoke("guest_browsing.comment-guest-limit", args = args) { a ->
                @Suppress("UNCHECKED_CAST")
                constructed = CommentSubjectDescription(a[0] as Boolean, a[1] as List<String>)
                null
            }
        }
        build(true)
        val guestResult = requireNotNull(constructed)
        assertFalse(guestResult.guestLimit)
        assertEquals(listOf("reply"), guestResult.data)
        assertFalse(guestResult.hasNext)
        h.guest = false
        build(true)
        val hostResult = requireNotNull(constructed)
        assertTrue(hostResult.guestLimit)
        assertEquals(listOf("reply"), hostResult.data)
    }

    @Test fun commentLimitDoesNotBindTheVersionSpecificGetter() {
        val h = Harness()
        assertTrue(h.registrar.registrationAttempts.any { it.first == "guest_browsing.comment-guest-limit" })
        assertFalse(h.registrar.registrationAttempts.any {
            it.first.contains("comment") && it.second.name == "k"
        })
    }

    @Test fun commentLimitFailsClosedWhenTheConstructorShapeIsAmbiguous() {
        val h = Harness(AmbiguousCommentDescription::class.java.name)
        assertFalse(h.registrar.registrationAttempts.any { it.first == "guest_browsing.comment-guest-limit" })
    }

    @Test fun eachGuardedEntryReportsItsOwnAppliedEvidenceForGuestsOnly() {
        val h = Harness()
        h.guest = false
        h.registrar.invoke("guest_browsing.login-dialog",
            args = arrayOf(DialogManagerInfo("login_guide"), null)) { true }
        assertFalse(h.statuses.containsKey("guest_browsing_login_dialog_applied"))
        h.guest = true
        h.registrar.invoke("guest_browsing.login-dialog",
            args = arrayOf(DialogManagerInfo("login_guide"), null)) { true }
        assertEquals("seen", h.statuses["guest_browsing_login_dialog_applied"])

        val comment = Harness()
        comment.registrar.invoke("guest_browsing.comment-guest-limit",
            args = arrayOf<Any?>(true, listOf("reply"))) { null }
        assertEquals("seen", comment.statuses["guest_browsing_comment_guest_limit_applied"])
        assertFalse(comment.statuses.containsKey("guest_browsing_login_dialog_applied"))
    }

    @Test fun playerLoginToastIsNotScheduledForGuestsAndOtherConfigReadsPassThrough() {
        val h = Harness()
        val config = com.bilibili.lib.config.BLRemoteConfig()
        fun read(key: String) = h.registrar.invoke("guest_browsing.force-login-toast-config", config,
            arrayOf(key, true)) { true }
        assertEquals(false, read("enable_player_force_login_toast"))
        assertEquals(true, read("enable_player_force_login_qn"))
        assertEquals(true, read("some_other_flag"))
        assertTrue(FeatureRuntimeStage.APPLIED in h.stages)
        h.guest = false
        assertEquals(true, read("enable_player_force_login_toast"))
    }

    @Test fun guestQualityCapIsLiftedOnlyForTheForcedLoginKey() {
        val h = Harness()
        val config = com.bilibili.lib.config.BLRemoteConfig()
        fun read(key: String) = h.registrar.invoke("guest_browsing.guest-quality-cap-config", config,
            arrayOf(key, 32)) { 32 }
        assertEquals(Int.MAX_VALUE, read("enable_player_force_login_qn"))
        assertEquals(32, read("Memorable_qn"))
        h.guest = false
        assertEquals(32, read("enable_player_force_login_qn"))
    }

    @Test fun unrelatedConfigReadsNeverTouchAccountState() {
        val h = Harness()
        h.stateFailure = true
        val config = com.bilibili.lib.config.BLRemoteConfig()
        assertEquals(true, h.registrar.invoke("guest_browsing.force-login-toast-config", config,
            arrayOf("some_other_flag", true)) { true })
        assertEquals(32, h.registrar.invoke("guest_browsing.guest-quality-cap-config", config,
            arrayOf("some_other_int", 32)) { 32 })
        assertFalse(FeatureRuntimeStage.ERROR in h.stages)
        // 命中目标键但读不到登录态：放行宿主，并留下错误证据。
        assertEquals(true, h.registrar.invoke("guest_browsing.force-login-toast-config", config,
            arrayOf("enable_player_force_login_toast", true)) { true })
        assertTrue(FeatureRuntimeStage.ERROR in h.stages)
    }

    @Test fun overseasForcedLoginIsLocatedByTheSingleStaticBooleanShape() {
        val h = Harness()
        val attempt = h.registrar.registrationAttempts.single { it.first == "guest_browsing.overseas-forced-login" }
        assertEquals("d", attempt.second.name)
        assertEquals(false, h.registrar.invoke("guest_browsing.overseas-forced-login") { true })
        h.guest = false
        assertEquals(true, h.registrar.invoke("guest_browsing.overseas-forced-login") { true })
    }

    @Test fun manualLoginAndPromptCleanupRemainCallable() {
        val h = Harness()
        val prompt = lp3.a()
        prompt.a()
        h.registrar.invoke("guest_browsing.force-login-toast", prompt) { prompt.run() }
        prompt.c()
        assertEquals(1, prompt.scheduled)
        assertEquals(0, prompt.shown)
        assertEquals(1, prompt.cleaned)
        val account = fixtures.privacy.manual.HomeUserCenterFragment()
        fixtures.privacy.manual.HomeUserCenterFragment.Tq(account, null)
        assertEquals(1, account.loginRequests)
        assertFalse(h.registrar.registrationAttempts.any { it.second.name == "Tq" })
    }
}
