package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 功能层宿主防线与凭据收口护栏：
 * - access_key 仅以状态码离开宿主探测层，强力模式预检受授权约束；
 * - 详情/面板/免登录无痕的宿主代理和 View/Handler 入口均过 HostThreadGuard 并返回类型化零值；
 * - 不喜欢面板保持共用落点，不隐式改写模块规则。
 */
class FeatureHostGuardAndContainmentContractTest {

    @Test
    fun `bili access key stays contained as state codes and only precheck requires authorization`() {
        val probe = SourceContract.read("hook/feature/BiliAccessKeyProbe.kt")
        val installer = SourceContract.read("hook/feature/AiDeclaredVideoFeatureInstaller.kt")
        val entry = SourceContract.read("hook/HookEntry.kt")

        val body = probe.after("internal class BiliAccessKeyProbe").before("companion object")
        // 令牌只在一个表达式里判空，不落到字段或变量。
        assertTrue(body.contains("(getAccessKey.invoke(token) as? String)?.isNotBlank() == true"))
        listOf("log", "report", "Log.", "println", "putString", "var ").forEach { forbidden ->
            assertFalse("探测层不得出现 $forbidden", body.contains(forbidden))
        }

        val precheck = installer.after("private fun installPrechecker(").before("private fun installRelatesFeed(")
        assertTrue(precheck.contains("environment.reportStatus(ACCESS_KEY_STATUS, state.code)"))
        assertFalse(precheck.contains("getAccessKey"))

        assertEquals(1, Regex("""precheck = aiDeclaredPrecheckEffective\(prefs\)""").findAll(entry).count())
        assertTrue(entry.contains("accessKeyAuthorized = prefs.getBoolean(FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED, false)"))
        assertTrue(entry.contains("precheck = prefs.getBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_PRECHECK, false)"))
        val install = installer.after("override fun install(").before("private fun installPrechecker(")
        assertTrue(install.contains("if (precheck) installPrechecker("))
    }

    @Test
    fun `feature host proxies and feedback panel keep guarded callbacks and shared landing points`() {
        val handler = SourceContract.read("hook/feature/DetailViewPurifyFeatureInstaller.kt")
            .after("private class Handler(").before("private companion object")
        assertTrue(handler.contains("HostThreadGuard.run(\"detail_view_purify.attached\")"))
        assertTrue(handler.contains("else -> hostProxyDefaultValue(method.returnType)"))
        assertFalse(handler.contains("else -> null"))

        val proxy = SourceContract.read("hook/feature/LegacyFeedbackPanel.kt")
            .after("Proxy.newProxyInstance(").before("ctor.newInstance(")
        assertTrue(proxy.contains("HostThreadGuard.run(\"section_pick.legacy_click\") { entry.click() }"))
        assertTrue(proxy.contains("else -> hostProxyDefaultValue(method.returnType)"))
        assertFalse(proxy.contains("else -> null"))

        val nativePanel = SourceContract.read("hook/feature/NativeFeedbackPanel.kt")
        assertTrue(nativePanel.contains("kntr.app.pegasus.feedbackdialog.FeedbackDialogKt"))
        assertTrue(nativePanel.contains("BottomSheetContent"))
        assertTrue(nativePanel.contains("getOnClick"))
        assertFalse(nativePanel.contains("feedbackDialogFragmentCreate"))

        val sectionPick = SourceContract.read("hook/feature/SectionPickFeatureInstaller.kt")
        assertTrue(sectionPick.contains("NativeFeedbackPanel.install"))
        assertFalse(sectionPick.contains("dislikeReasonSetter"))
        assertFalse(sectionPick.contains("feedbackItemExtendGetter"))
    }

    @Test
    fun `privacy schedulers, host view listeners, and proxies stay guarded with typed defaults`() {
        fun readPrivacy(file: String) = SourceContract.read("hook/feature/privacy/$file")

        val threads = readPrivacy("PrivacyHostThreads.kt")
        assertTrue(threads.contains("HostThreadGuard.run(key) { super.dispatchMessage(msg) }"))
        assertTrue(threads.contains("HostThreadGuard.run(key) { task.run() }"))
        mapOf(
            "GuestLibraryRuntime.kt" to listOf(
                "private val main = MainThreadDispatcher()",
                "main.post(HostThreadGuard.runnable(\"guest.library.main\", block))",
                "guardedSingleThreadExecutor(\"guest.library.io\""
            ),
            "GuestPlaybackHooks.kt" to listOf("guardedMainHandler(\"guest.playback.main\")"),
            "GuestPageFavorite.kt" to listOf("guardedMainHandler(\"guest.page_favorite.main\")"),
            "GuestIgnetLibraryResponses.kt" to listOf("HostThreadGuard.runnable(\"guest.ignet.form_drain\")")
        ).forEach { (file, needles) ->
            val source = readPrivacy(file)
            needles.forEach { assertTrue("$file: $it", source.contains(it)) }
            assertFalse("$file posts on an unguarded main handler", source.contains("Handler(Looper.getMainLooper())"))
        }

        mapOf(
            "GuestLibraryHostHooks.kt" to listOf("guest.mine.avatar.click", "guest.mine.sign_in.click"),
            "GuestFollowListPage.kt" to listOf("guest.follow_list.row.click"),
            "GuestNativeFollowListHooks.kt" to listOf(
                "guest.native_follow_list.row.click",
                "guest.native_follow_list.unfollow.click",
                "guest.native_follow_list.unfollow.confirm"
            ),
            "GuestNativeHistoryHooks.kt" to listOf("guest.history.banner.attach", "guest.history.banner.detach"),
            "GuestLottieFollowButton.kt" to listOf("guest.lottie.follow.click"),
            "GuestLibraryUi.kt" to listOf("guest.library.dialog.item", "guest.library.folder.create"),
            "GuestFavoriteFolderPicker.kt" to listOf(
                "guest.folder_picker.done",
                "guest.folder_picker.create",
                "guest.folder_picker.back"
            ),
            "GuestHostFollowConfirmation.kt" to listOf("guest.follow.confirm"),
            "GuestHostFavoriteDialog.kt" to listOf("guest.favorite.dialog.dismiss"),
            "GuestHostFavoriteSnackbar.kt" to listOf("guest.favorite.snackbar.click")
        ).forEach { (file, keys) ->
            val source = readPrivacy(file)
            keys.forEach { assertTrue("$file: unguarded $it", source.contains("HostThreadGuard.run(\"$it\")")) }
        }
        assertTrue(readPrivacy("GuestLibraryHostHooks.kt").contains("HostThreadGuard.call(\"guest.mine.header.long_click\", false)"))
        assertTrue(
            "Predraw must fail open so the host keeps drawing",
            readPrivacy("GuestNativeHistoryHooks.kt").contains("HostThreadGuard.call(\"guest.history.banner.predraw\", true)")
        )

        listOf(
            "GuestHostFavoriteSnackbar.kt",
            "GuestNativeListHttpHooks.kt",
            "GuestNativeFollowGroupMenu.kt",
            "IgnetHttpSanitizer.kt"
        ).forEach { file ->
            val source = readPrivacy(file)
            assertTrue(file, source.contains("hostProxyDefaultValue(method.returnType)"))
            assertFalse(file, source.contains("else -> null"))
            assertFalse(file, source.contains("return@newProxyInstance null"))
        }
        val netSource = readPrivacy("IncognitoNetworkHooks.kt")
        assertTrue(netSource.contains("else -> hostProxyDefaultValue(m.returnType)"))
        assertTrue(netSource.contains("X-Bili-Fawkes-Req-Bin"))
        assertTrue(netSource.contains("\"Buvid3\", \"Buvid4\""))
    }
}
