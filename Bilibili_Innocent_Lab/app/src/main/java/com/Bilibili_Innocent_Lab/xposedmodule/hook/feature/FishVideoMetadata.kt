package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.HostUnitedVideoDescription
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.hostGetter
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.hostRead
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.hostText
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight.InsightRpc
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf

/** 从宿主已经收到的视频详情提取标题和简介，按响应自带的 AID 发布有界快照。 */
internal class FishVideoMetadata {
    @Volatile var snapshot: Map<Long, FishRootEcho.Video> = emptyMap()
        private set

    internal fun remember(aid: Long, title: String?, description: String?) {
        if (aid <= 0) return
        val boundedTitle = hostText(title, MAX_TITLE)
        val boundedDescription = hostText(description, HostUnitedVideoDescription.MAX_TEXT)
        if (boundedTitle == null && boundedDescription == null) return
        synchronized(this) {
            val previous = snapshot[aid]
            val next = FishRootEcho.Video(boundedTitle ?: previous?.title, boundedDescription ?: previous?.description)
            if (previous == next) return
            val entries = LinkedHashMap(snapshot)
            entries.remove(aid)
            entries[aid] = next
            if (entries.size > CAPACITY) entries.remove(entries.keys.first())
            snapshot = entries
        }
    }

    fun install(environment: HookEnvironment) {
        val loader = environment.classLoader ?: return
        val legacy = installRoute(environment, loader, LEGACY_VIEW, united = false)
        val united = installRoute(environment, loader, UNITED_VIEW, united = true)
        environment.logInfo(HOOK_ID, "[BIL] 评论视频复述来源: legacy=$legacy united=$united")
    }

    private fun installRoute(environment: HookEnvironment, loader: ClassLoader, family: String, united: Boolean): Int =
        runCatching {
            val request = Lookup.classOrNull(loader, "$family.ViewReq") ?: return 0
            val response = Lookup.classOrNull(loader, "$family.ViewReply") ?: return 0
            val hasArc = hostGetter(response, "hasArc", classOf<Boolean>()) ?: return 0
            val arc = hostGetter(response, "getArc") ?: return 0
            val aid = hostGetter(arc.returnType, "getAid", classOf<Long>()) ?: return 0
            val title = hostGetter(arc.returnType, "getTitle", String::class.java)
            val legacyDesc = if (united) null else hostGetter(arc.returnType, "getDesc", String::class.java)
            val unitedDesc = if (united) HostUnitedVideoDescription.resolve(loader, response) else null
            if (title == null && legacyDesc == null && unitedDesc == null) return 0
            InsightRpc.observe(environment, "$family.ViewMoss", "view", request, response, HOOK_ID, "评论视频复述") { reply ->
                if (hostRead(hasArc, reply) != true) return@observe
                val value = hostRead(arc, reply) ?: return@observe
                val videoId = hostRead(aid, value) as? Long ?: return@observe
                if (videoId <= 0) return@observe
                remember(videoId, hostRead(title, value) as? String, unitedDesc?.read(reply) ?: hostRead(legacyDesc, value) as? String)
            }
        }.getOrDefault(0)

    private companion object {
        const val CAPACITY = 12
        const val MAX_TITLE = 500
        const val HOOK_ID = "comment.filter.video_metadata"
        const val LEGACY_VIEW = "com.bapis.bilibili.app.view.v1"
        const val UNITED_VIEW = "com.bapis.bilibili.app.viewunite.v1"
    }
}
