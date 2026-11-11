package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免登录与无痕挂在宿主 View、Handler 和线程上的入口不经过 Hook 链：逃逸异常会直接杀宿主进程。
 * 入口必须过 HostThreadGuard，宿主直接调用的代理对未知方法按返回类型给零值。
 */
class PrivacyHostGuardContractTest {
    private fun read(file: String) = SourceContract.read("hook/feature/privacy/$file")

    @Test fun schedulersRunInsideTheGuard() {
        val threads = read("PrivacyHostThreads.kt")
        assertTrue(threads.contains("HostThreadGuard.run(key) { super.dispatchMessage(msg) }"))
        assertTrue(threads.contains("HostThreadGuard.run(key) { task.run() }"))
        mapOf(
            "GuestLibraryRuntime.kt" to listOf("private val main = MainThreadDispatcher()",
                "main.post(HostThreadGuard.runnable(\"guest.library.main\", block))",
                "guardedSingleThreadExecutor(\"guest.library.io\""),
            "GuestPlaybackHooks.kt" to listOf("guardedMainHandler(\"guest.playback.main\")"),
            "GuestPageFavorite.kt" to listOf("guardedMainHandler(\"guest.page_favorite.main\")"),
            "GuestIgnetLibraryResponses.kt" to listOf("HostThreadGuard.runnable(\"guest.ignet.form_drain\")")
        ).forEach { (file, needles) ->
            val source = read(file)
            needles.forEach { assertTrue("$file: $it", source.contains(it)) }
            assertFalse("$file posts on an unguarded main handler", source.contains("Handler(Looper.getMainLooper())"))
        }
    }

    @Test fun hostViewEntriesAreGuarded() {
        mapOf(
            "GuestLibraryHostHooks.kt" to listOf("guest.mine.avatar.click", "guest.mine.sign_in.click"),
            "GuestFollowListPage.kt" to listOf("guest.follow_list.row.click"),
            "GuestNativeFollowListHooks.kt" to listOf("guest.native_follow_list.row.click",
                "guest.native_follow_list.unfollow.click", "guest.native_follow_list.unfollow.confirm"),
            "GuestNativeHistoryHooks.kt" to listOf("guest.history.banner.attach", "guest.history.banner.detach"),
            "GuestLottieFollowButton.kt" to listOf("guest.lottie.follow.click"),
            "GuestLibraryUi.kt" to listOf("guest.library.dialog.item", "guest.library.folder.create"),
            "GuestFavoriteFolderPicker.kt" to listOf("guest.folder_picker.done", "guest.folder_picker.create",
                "guest.folder_picker.back"),
            "GuestHostFollowConfirmation.kt" to listOf("guest.follow.confirm"),
            "GuestHostFavoriteDialog.kt" to listOf("guest.favorite.dialog.dismiss"),
            "GuestHostFavoriteSnackbar.kt" to listOf("guest.favorite.snackbar.click")
        ).forEach { (file, keys) ->
            val source = read(file)
            keys.forEach { assertTrue("$file: unguarded $it", source.contains("HostThreadGuard.run(\"$it\")")) }
        }
        assertTrue(read("GuestLibraryHostHooks.kt").contains("HostThreadGuard.call(\"guest.mine.header.long_click\", false)"))
        assertTrue("Predraw must fail open so the host keeps drawing",
            read("GuestNativeHistoryHooks.kt").contains("HostThreadGuard.call(\"guest.history.banner.predraw\", true)"))
    }

    @Test fun hostCalledProxiesReturnTypedDefaults() {
        listOf("GuestHostFavoriteSnackbar.kt", "GuestNativeListHttpHooks.kt", "GuestNativeFollowGroupMenu.kt")
            .forEach { file ->
                val source = read(file)
                assertTrue(file, source.contains("hostProxyDefaultValue(method.returnType)"))
                assertFalse(file, source.contains("else -> null"))
                assertFalse(file, source.contains("return@newProxyInstance null"))
            }
        assertTrue(read("IncognitoNetworkHooks.kt").contains("else -> hostProxyDefaultValue(m.returnType)"))
    }
}
