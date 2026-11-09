package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.EditText
import io.github.jichuo1.meguviz.android.extensions.textToString
import androidx.core.net.toUri
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import java.lang.ref.WeakReference

internal data class GuestLibraryStrings(
    val title: String, val history: String, val favorites: String, val watchLater: String,
    val follows: String, val empty: String, val saved: String, val failure: String,
    val refreshed: String, val refresh: String, val clear: String, val remove: String,
    val folder: String, val noVideo: String, val signIn: String, val incognitoTitle: String,
    val exitToSignIn: String,
    /** The host's own follow feedback text, for a follow the module stored instead of the host. */
    val followed: String, val unfollowed: String, val favoriteRemoved: String
) {
    companion object {
        fun resolve(context: Context): GuestLibraryStrings = when (InjectedUiLocale.resolveEffectiveTag(context)) {
            "zh-CN" -> GuestLibraryStrings("本地账号", "历史记录", "本地收藏夹", "稍后再看", "本地关注",
                "暂无记录", "已保存到本地", "本地资料操作失败", "已刷新无痕身份", "刷新无痕身份",
                "清空历史", "移除", "新建收藏夹", "请先播放一个视频", "登录账号", "无痕会话",
                "请先关闭无痕模式并重启哔哩哔哩，再登录账号。", "关注成功~", "已取消关注", "已取消收藏")
            "zh-Hant" -> GuestLibraryStrings("本機帳號", "歷史記錄", "本機收藏夾", "稍後再看", "本機關注",
                "尚無記錄", "已儲存至本機", "本機資料操作失敗", "已更新無痕身分", "更新無痕身分",
                "清空歷史", "移除", "新增收藏夾", "請先播放一部影片", "登入帳號", "無痕工作階段",
                "請先關閉無痕模式並重新啟動嗶哩嗶哩，再登入帳號。", "關注成功~", "已取消關注", "已取消收藏")
            else -> GuestLibraryStrings("Local account", "History", "Local favorites", "Watch later", "Local follows",
                "No records", "Saved locally", "Local library operation failed", "Incognito identity refreshed",
                "Refresh incognito identity", "Clear history", "Remove", "New folder", "Play a video first",
                "Sign in", "Incognito session", "Turn off incognito and restart Bilibili before signing in.",
                "Followed", "Unfollowed", "Removed from favorites")
        }
    }
}

internal class GuestLibraryUi(
    private val runtime: GuestLibraryRuntime,
    private val reference: WeakReference<Activity>,
    private val token: GuestLibraryRuntime.Token,
    private val strings: GuestLibraryStrings
) {
    private fun dialog(title: String, labels: List<String>, selected: (Int) -> Unit) {
        val activity = reference.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return
        AlertDialog.Builder(activity).setTitle(title)
            .setItems(labels.toTypedArray()) { _, index ->
                HostThreadGuard.run("guest.library.dialog.item") { runtime.onMain(token) { selected(index) } }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
    fun show(section: String) {
        reference.get()?.let { if (runtime.nativePage?.invoke(it, section) == true) return }
        runtime.query(token) { state ->
            when (section) {
                "history" -> videos(strings.history, state.collections.history) { l, v -> l.removeHistory(v.aid, v.cid) }
                "watchLater" -> videos(strings.watchLater, state.collections.watchLater) { l, v -> l.removeWatchLater(v.aid, v.cid) }
                "favorites" -> folders(state.collections)
                "follows" -> {
                    val follows = state.collections.follows
                    dialog(strings.follows, follows.map { it.name }.ifEmpty { listOf(strings.empty) }) { i ->
                        follows.getOrNull(i)?.let { follow ->
                            dialog(follow.name, listOf(strings.follows, strings.remove)) { action ->
                                if (action == 0) open("bilibili://space/${follow.mid}")
                                else runtime.mutate(token, mutation = { it.unfollow(follow.mid) }) { show("follows") }
                            }
                        }
                    }
                }
                else -> {
                    val sections = listOf("history", "favorites", "watchLater", "follows")
                    val labels = listOf(strings.history, strings.favorites, strings.watchLater, strings.follows) +
                        if (runtime.incognito) listOf(strings.refresh) else emptyList()
                    dialog(strings.title, labels) { i ->
                        if (i < sections.size) show(sections[i]) else runtime.refreshIdentity()
                    }
                }
            }
        }
    }
    private fun folders(collections: LocalVirtualLibrary.Collections) {
        val names = collections.favorites.keys.toList()
        dialog(strings.favorites, names + strings.folder) { index ->
            if (index == names.size) {
                val activity = reference.get() ?: return@dialog
                val edit = EditText(activity)
                AlertDialog.Builder(activity).setTitle(strings.folder).setView(edit)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        HostThreadGuard.run("guest.library.folder.create") {
                            runtime.mutate(token, mutation = { it.createFavoriteFolder(edit.textToString().trim()) }) { show("favorites") }
                        }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            } else names.getOrNull(index)?.let { name ->
                videos(name, collections.favorites.getValue(name)) { l, v -> l.removeFavorite(v.aid, v.cid, name) }
            }
        }
    }
    private fun videos(title: String, values: List<LocalVirtualLibrary.Video>,
        remove: (LocalVirtualLibrary, LocalVirtualLibrary.Video) -> Unit) {
        val labels = values.map { "${it.title}\n${time(it.positionMs)} / ${time(it.durationMs)}" }
        dialog(title, labels.ifEmpty { listOf(strings.empty) } +
            if (title == strings.history && values.isNotEmpty()) listOf(strings.clear) else emptyList()) { index ->
            val video = values.getOrNull(index)
            if (video == null) {
                if (values.isNotEmpty()) runtime.mutate(token, mutation = { it.clearHistory() }) { show("history") }
            } else {
                dialog(video.title, listOf(strings.history, strings.remove)) { action ->
                    if (action == 0) open("bilibili://video/${video.aid}?cid=${video.cid}")
                    else runtime.mutate(token, mutation = { remove(it, video) }) { show("home") }
                }
            }
        }
    }
    fun action(action: String, selected: LocalVirtualLibrary.Video? = null) {
        runtime.query(token) { state ->
            val current = selected ?: state.current ?: run { runtime.failure(token, strings.noVideo); return@query }
            when (action) {
                "favorite" -> {
                    val folders = state.collections.favorites.keys.toList()
                    dialog(strings.favorites, folders) { i ->
                        runtime.mutate(token, current, { it.addFavorite(current, folders[i]) }) {}
                    }
                }
                "watchLater" -> runtime.mutate(token, current, { it.addWatchLater(current) }) {}
                "follow" -> {
                    if (current.authorMid <= 0 || current.authorName.isBlank()) runtime.failure(token)
                    else runtime.mutate(token, current, {
                        it.follow(LocalVirtualLibrary.Follow(current.authorMid, current.authorName, current.authorFace))
                    }) {}
                }
            }
        }
    }
    private fun open(uri: String) {
        reference.get()?.let { activity ->
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()).setPackage("tv.danmaku.bili"))
        }
    }
    private fun time(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
}
