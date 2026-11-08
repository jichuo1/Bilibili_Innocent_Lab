package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.HostAgentContentRpc
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelClient
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ProtobufBuilderPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorVideoId
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.commentOwnerActivity
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * AI Insight 只接受**一个**由专用配置解析出的对话来源；不再从智能判定的多来源表里挑，
 * 因此昂贵模型不会被四个自动过滤面重复调用。[source] 为 null 表示专用配置未配好，卡片给出引导。
 */
internal class AiInsightFeatureInstaller(private val enabled: Boolean, private val automaticChapters: Boolean,
    private val source: AgentModelSource?, private val cacheRoot: File,
    private val outputBudget: Int = InsightConfig.DEFAULT_OUTPUT_BUDGET,
    private val timeoutMs: Int = InsightConfig.DEFAULT_TIMEOUT_MS,
    private val cachedClasses: () -> SponsorPlayerClasses? = { null }) : FeatureInstaller {
    /** 直接定位失败且尚无后台适配缓存时为 true；适配完成后由 HookEntry 重试，与 SponsorBlock 相同。 */
    @Volatile var requiresAdaptationRetry = false
        private set
    private val registrationStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    override val id = "ai_insight"
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-classloader")
        val player = SponsorPlayerLocator.direct(loader) ?: cachedClasses()?.let { SponsorPlayerLocator.resolve(loader, it) }
        if (player == null) {
            requiresAdaptationRetry = true
            return FeatureInstallResult.Skipped("missing-player-structure")
        }
        requiresAdaptationRetry = false
        val fragment = Lookup.classOrNull(loader, INTRO) ?: return FeatureInstallResult.Skipped("missing-intro-fragment")
        val create = Lookup.methodOrNull(fragment, "onCreateView", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java)
            ?.takeIf { View::class.java.isAssignableFrom(it.returnType) } ?: return FeatureInstallResult.Skipped("missing-intro-view")
        val created = Lookup.methodOrNull(fragment, "onViewCreated", View::class.java, Bundle::class.java)
            ?: return FeatureInstallResult.Skipped("missing-intro-lifecycle")
        val destroy = Lookup.methodOrNull(fragment, "onDestroyView") ?: return FeatureInstallResult.Skipped("missing-intro-destroy")
        val headers = InsightIntroHeaders.resolve(environment) ?: return FeatureInstallResult.Skipped("missing-intro-components")
        // 只重试尚未解析的结构；注册开始后不重放部分 Hook。
        if (!registrationStarted.compareAndSet(false, true)) return FeatureInstallResult.Skipped("already-registered")
        val snapshot = InsightHostSnapshot()
        val cache = InsightCache(cacheRoot)
        // source 来自构造参数（专用配置解析出的单一来源），不再从判定来源表里挑。
        val trace: (String) -> Unit = { event ->
            val mode = event.substringAfter("mode=", "").substringBefore(' ')
            val stage = event.substringAfter("stage=", "").substringBefore(' ')
            environment.logInfo("insight.${event.substringBefore(' ')}.$mode.$stage", "AI Insight $event")
        }
        val covers = InsightChapterCovers(File(cacheRoot.parentFile, "bil_insight_covers"), trace)
        // 复用已有只读评论 RPC 和有界超时；不切换页面、不复制宿主 Cookie，不触发 Agent 任务。
        val comments = HostAgentContentRpc(loader)
        val repository = source?.let { InsightRepository(it, cache, InsightContextClient(snapshot, trace = trace,
            hostComments = { aid ->
                val rows = comments.comments("av$aid", null, 4000).data.getJSONArray("comments")
                (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.optString("message") }
                    .filter { it.isNotBlank() }.distinct().take(40)
            })::load,
            client = AgentModelClient(outputTokenBudget = outputBudget,
                inputTextLimit = InsightProtocol.MAX_CONTEXT, allowReasoningParams = false),
            trace = trace,
            contextRevision = snapshot::revision,
            timeoutMs = timeoutMs) }
        val generated = object : LinkedHashMap<InsightVideo, InsightCache.Entry>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InsightVideo, InsightCache.Entry>?) = size > 32
        }
        fun remember(video: InsightVideo, entry: InsightCache.Entry) { synchronized(generated) { generated[video] = entry } }
        if (source != null) Thread(HostThreadGuard.runnable("insight.cache.restore") {
            cache.restoreChapters(source, ::remember)
        }, "BIL-InsightCache").apply { isDaemon = true }.start()
        var collapsed = false
        val cards = WeakHashMap<Any, WeakReference<InsightCard>>()
        val mounts = WeakHashMap<Any, WeakReference<InsightScrollMount>>()
        val activityCards = WeakHashMap<Activity, WeakReference<InsightCard>>()
        val bridge = InsightPlayerBridge(environment, player) { activity, session -> activityCards[activity]?.get()?.bind(session) }
        var count = bridge.install()
        count += headers.install()
        val nativeClass = Lookup.classOrNull(loader, InsightNativeChapters.REPLY)
        val native = nativeClass?.let(InsightNativeChapters::resolve)
        var chapterRoutes = 0
        if (native != null) {
            val request = Lookup.classOrNull(loader, "$VIEW.ViewProgressReq")
            val aid = request?.let { Lookup.methodOrNull(it, "getAid") }?.takeIf { it.returnType == Long::class.javaPrimitiveType }
            val cid = request?.let { Lookup.methodOrNull(it, "getCid") }?.takeIf { it.returnType == Long::class.javaPrimitiveType }
            if (request != null && aid != null && cid != null) chapterRoutes = InsightRpc.install(environment,
                "$VIEW.ViewMoss", "viewProgress", request, nativeClass) { req, reply ->
                val avid = aid.invoke(req) as Long; val part = cid.invoke(req) as Long
                val bvid = SponsorVideoId.bvidFromAvid(avid)
                if (bvid == null || part <= 0) reply else {
                    snapshot.official(part, native.hasChapters(reply))
                    val value = synchronized(generated) { generated[InsightVideo(bvid, part)] }
                    if (value == null) reply else {
                        covers.schedule(InsightVideo(bvid, part), value)
                        native.apply(reply, value) { second -> covers.uri(InsightVideo(bvid, part), second) }
                    }
                }
            }
        }
        count += chapterRoutes
        val subtitleRoutes = installSubtitles(environment, snapshot)
        val metadataRoutes = installMetadata(environment, snapshot)
        val commentRoutes = installComments(environment, snapshot)
        count += subtitleRoutes + metadataRoutes + commentRoutes
        environment.logInfo("insight.routes", "AI Insight routes subtitles=$subtitleRoutes metadata=$metadataRoutes comments=$commentRoutes chapters=$chapterRoutes")
        environment.registrar.exact("insight.intro.create", fragment, create.name, *create.parameterTypes) { after {
            if (hasThrowable) return@after
            val original = result as? ViewGroup ?: return@after
            if (original.parent != null) return@after
            val owner = instance ?: return@after
            val activity = commentOwnerActivity(original.context) ?: return@after
            if (activity.javaClass.name != InsightPlayerBridge.DETAIL) return@after
            val card = InsightCard(original.context, repository, automaticChapters, chapterRoutes > 0,
                collapsed, { collapsed = it }) { video, entry ->
                remember(video, entry); covers.schedule(video, entry)
            }
            val mounted = headers.attach(original, card) ?: return@after
            cards[owner] = WeakReference(card); activityCards[activity] = WeakReference(card)
            mounts[owner] = WeakReference(mounted)
        } }
        environment.registrar.exact("insight.intro.ready", fragment, created.name, *created.parameterTypes) { after {
            if (hasThrowable) return@after
            val card = cards[instance]?.get() ?: return@after
            val activity = commentOwnerActivity(card.context) ?: return@after
            card.bind(bridge.current(activity))
        } }
        environment.registrar.exact("insight.intro.destroy", fragment, destroy.name, *destroy.parameterTypes) { before {
            mounts.remove(instance)?.get()?.close()
            cards.remove(instance)?.get()?.let { card ->
                card.dispose()
                commentOwnerActivity(card.context)?.let { activity -> if (activityCards[activity]?.get() === card) activityCards.remove(activity) }
            }
        } }
        environment.reportStatus("ai_insight_status", if (chapterRoutes > 0) "installed" else "partial:no-native-chapters")
        return FeatureInstallResult.Installed(count + 3, complete = chapterRoutes > 0)
    }

    private fun installSubtitles(environment: HookEnvironment, snapshot: InsightHostSnapshot): Int =
        installSubtitleRoute(environment, snapshot, DM, "DmViewReq", "DmViewReply", "DMMoss", "dmView", true) +
            installSubtitleRoute(environment, snapshot, "com.bapis.bilibili.subtitle",
                "SubtitleViewReq", "SubtitleViewReply", "SubtitleMoss", "subtitleView", false)

    private fun installSubtitleRoute(environment: HookEnvironment, snapshot: InsightHostSnapshot, family: String,
        requestName: String, replyName: String, moss: String, rpc: String, enableSubtitle: Boolean): Int {
        val loader = environment.classLoader ?: return 0
        val request = Lookup.classOrNull(loader, "$family.$requestName") ?: return 0
        val reply = Lookup.classOrNull(loader, "$family.$replyName") ?: return 0
        val cid = Lookup.methodOrNull(request, "getOid")?.takeIf { it.returnType == Long::class.javaPrimitiveType } ?: return 0
        val read = Lookup.methodOrNull(reply, "getSubtitle") ?: return 0
        val list = Lookup.methodOrNull(read.returnType, "getSubtitlesList") ?: return 0
        val item = Lookup.classOrNull(loader, "$family.SubtitleItem") ?: return 0
        val url = Lookup.methodOrNull(item, "getSubtitleUrl")?.takeIf { it.returnType == String::class.java } ?: return 0
        val language = Lookup.methodOrNull(item, "getLan")?.takeIf { it.returnType == String::class.java } ?: return 0
        val plan = if (enableSubtitle) ProtobufBuilderPlan.resolve(request) else null
        val setWithout = plan?.method("setWithoutSubtitle", Boolean::class.javaPrimitiveType!!)
        return InsightRpc.install(environment, "$family.$moss", rpc, request, reply, transformRequest = { original ->
            if (setWithout == null) original else plan.edit(original) { setWithout.invoke(it, false) }
        }) { req, response ->
            val part = cid.invoke(req) as Long
            val subtitles = list.invoke(read.invoke(response)) as? List<*> ?: return@install response
            environment.logInfo("insight.subtitle.$rpc.${subtitles.isNotEmpty()}", "AI Insight subtitle rpc=$rpc sources=${subtitles.size} cid=$part")
            if (part > 0) snapshot.subtitles(part, subtitles.take(16).filterNotNull().filter(item::isInstance).map {
                InsightSubtitleSource(part, url.invoke(it) as String, language.invoke(it) as String) })
            response
        }
    }
    private fun installMetadata(environment: HookEnvironment, snapshot: InsightHostSnapshot): Int {
        val loader = environment.classLoader ?: return 0
        val request = Lookup.classOrNull(loader, "$VIEW.ViewReq") ?: return 0
        val reply = Lookup.classOrNull(loader, "$VIEW.ViewReply") ?: return 0
        val arc = Lookup.methodOrNull(reply, "getArc") ?: return 0
        val owner = arc.returnType
        val aid = Lookup.methodOrNull(owner, "getAid")?.takeIf { it.returnType == Long::class.javaPrimitiveType } ?: return 0
        val cid = Lookup.methodOrNull(owner, "getCid")?.takeIf { it.returnType == Long::class.javaPrimitiveType } ?: return 0
        val bvid = Lookup.methodOrNull(owner, "getBvid")?.takeIf { it.returnType == String::class.java } ?: return 0
        val title = Lookup.methodOrNull(owner, "getTitle")?.takeIf { it.returnType == String::class.java } ?: return 0
        val duration = Lookup.methodOrNull(owner, "getDuration")?.takeIf { it.returnType == Long::class.javaPrimitiveType } ?: return 0
        return InsightRpc.install(environment, "$VIEW.ViewMoss", "view", request, reply) { _, response ->
            val value = arc.invoke(response) ?: return@install response
            val seconds = duration.invoke(value) as Long; val avid = aid.invoke(value) as Long; val part = cid.invoke(value) as Long
            val identifier = (bvid.invoke(value) as String).ifBlank { SponsorVideoId.bvidFromAvid(avid).orEmpty() }
            if (seconds in 1..Int.MAX_VALUE.toLong() && avid > 0 && part > 0) runCatching {
                snapshot.metadata(InsightMetadata(InsightVideo(identifier, part), avid, (title.invoke(value) as String).take(500), seconds.toInt()))
            }
            response
        }
    }
    private fun installComments(environment: HookEnvironment, snapshot: InsightHostSnapshot): Int {
        val loader = environment.classLoader ?: return 0
        val family = "com.bapis.bilibili.main.community.reply.v1"
        val request = Lookup.classOrNull(loader, "$family.MainListReq") ?: return 0
        val response = Lookup.classOrNull(loader, "$family.MainListReply") ?: return 0
        val item = Lookup.classOrNull(loader, "$family.ReplyInfo") ?: return 0
        val oid = Lookup.methodOrNull(request, "getOid")?.takeIf { it.returnType == Long::class.javaPrimitiveType } ?: return 0
        val type = Lookup.methodOrNull(request, "getType")?.takeIf {
            it.returnType == Long::class.javaPrimitiveType || it.returnType == Int::class.javaPrimitiveType } ?: return 0
        val read = Lookup.methodOrNull(item, "getContent") ?: return 0
        val message = Lookup.methodOrNull(read.returnType, "getMessage")?.takeIf { it.returnType == String::class.java } ?: return 0
        val lists = listOfNotNull(Lookup.methodOrNull(response, "getTopRepliesList"), Lookup.methodOrNull(response, "getRepliesList"))
            .filter { List::class.java.isAssignableFrom(it.returnType) }
        if (lists.isEmpty()) return 0
        return InsightRpc.install(environment, "$family.ReplyMoss", "mainList", request, response) { req, reply ->
            val avid = oid.invoke(req) as Long
            if (avid > 0 && (type.invoke(req) as Number).toLong() == 1L) {
                val sampled = lists.flatMap { (it.invoke(reply) as? List<*>).orEmpty().take(40) }.filterNotNull()
                    .filter(item::isInstance).take(40).mapNotNull { value ->
                        read.invoke(value)?.let { message.invoke(it) as? String }
                    }
                if (sampled.isNotEmpty()) snapshot.comments(avid, sampled)
                environment.logInfo("insight.comments.sample", "AI Insight comments sampled=${sampled.size}")
            }
            reply
        }
    }
    companion object {
        const val INTRO = "com.bilibili.ship.theseus.united.page.TheseusIntroductionFragment"
        private const val VIEW = "com.bapis.bilibili.app.viewunite.v1"
        private const val DM = "com.bapis.bilibili.community.service.dm.v1"
    }
}
