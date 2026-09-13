package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 共用 Compose 入口：兼容首页 Fragment 和详情页 Nf 弹窗，无宿主网络请求。 */
internal class NativeFeedbackPanel(
    private val injector: FeedbackPanelInjector,
    private val groupItems: Method,
    private val groupStyle: Method,
    private val groupTitle: Method,
    private val groupCopy: Method,
    private val itemClick: Method,
    private val cardMatches: (Any) -> Boolean,
    private val entries: (Any) -> List<Any>,
    private val onSkip: (String) -> Unit = {}
) {
    data class LegacyEntry(val title: String, val click: () -> Unit)

    fun legacyEntries(items: List<Any>): List<LegacyEntry> {
        val card = FeedbackCardGraph.findUnique(items, cardMatches) ?: run {
            onSkip("legacy-missing-current-card")
            return emptyList()
        }
        return entries(card).map { entry ->
            LegacyEntry(injector.titleOf(entry)) { injector.click(entry) }
        }.also { if (it.isEmpty()) onSkip("legacy-missing-tag-metadata") }
    }

    private var original = WeakReference<List<*>>(null)
    private var replacement = WeakReference<List<*>>(null)

    /** 重组复用同一列表；缓存只持弱引用，关闭面板后不保留卡片、回调或 Activity。 */
    fun merge(groups: List<*>): List<*>? {
        if (original.get() === groups) return replacement.get()
        original = WeakReference(groups)
        replacement = WeakReference(null)
        if (groups.isEmpty() || groups.size > 16) return null
        val itemLists = groups.map { group ->
            if (group == null || !groupItems.declaringClass.isInstance(group)) return null
            @Suppress("UNCHECKED_CAST")
            (groupItems.invoke(group) as? List<Any>) ?: return null
        }
        if (itemLists.any { list -> list.any(injector::isOurs) }) return null
        val allItems = itemLists.flatten()
        if (allItems.size > 64 || !injector.accepts(allItems)) return null
        val callbacks = allItems.mapNotNull { itemClick.invoke(it) }
        val card = FeedbackCardGraph.findUnique(callbacks, cardMatches) ?: run {
            onSkip("missing-current-card")
            return null
        }
        val extra = entries(card)
        if (extra.isEmpty()) {
            onSkip("missing-tag-metadata")
            return null
        }
        val targetIndex = itemLists.indexOfLast { it.isNotEmpty() }
        if (targetIndex < 0) return null
        val target = groups[targetIndex]
        val copied = groupCopy.invoke(target, groupStyle.invoke(target), groupTitle.invoke(target),
            itemLists[targetIndex] + extra) ?: return null
        val result = groups.toMutableList().also { it[targetIndex] = copied }
        original = WeakReference(groups)
        replacement = WeakReference(result)
        return result
    }

    companion object {
        private const val MODEL = "kntr.app.pegasus.feedbackdialog.model."
        private const val CONTENT = "kntr.app.pegasus.feedbackdialog.FeedbackDialogKt"
        private const val HOME_CARD = "com.bilibili.pegasus.data.base.BasePegasusData"

        fun install(
            environment: HookEnvironment,
            home: VersionAdapter.HomeRecommendFeedPoints?,
            related: VersionAdapter.VideoRelatePoints?
        ): FeatureInstallResult = runCatching {
            val loader = environment.classLoader
            fun owner(name: String) = checkNotNull(KavaMemberLookup.classOrNull(loader, name)) { name }
            fun getter(type: Class<*>, name: String): Method? = KavaMemberLookup.methods(
                type, includeSuperclasses = true, makeAccessible = true
            ) { it.name == name && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
                .distinctBy(Method::toGenericString).singleOrNull()
            val content = KavaMemberLookup.declaredMethods(owner(CONTENT), makeAccessible = true) {
                it.name == "BottomSheetContent" && Modifier.isStatic(it.modifiers) &&
                    it.returnType == Void.TYPE &&
                    FeedbackContentSignature.changedIndex(it.parameterTypes.map(Class<*>::getName)) != null
            }.single()
            val changedIndex = checkNotNull(FeedbackContentSignature.changedIndex(content.parameterTypes.map(Class<*>::getName)))
            val item = owner(MODEL + "FeedbackItem")
            val ctor = KavaMemberLookup.declaredConstructors(item) {
                it.parameterTypes.none { type -> type.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
            }.maxByOrNull { it.parameterCount } ?: error("item-constructor")
            val typeGetter = checkNotNull(getter(item, "getType"))
            val clickGetter = checkNotNull(getter(item, "getOnClick"))
            val injector = FeedbackPanelInjector(ctor, checkNotNull(getter(item, "getTitle")),
                typeGetter, clickGetter, item, owner("kotlin.jvm.functions.Function1"),
                checkNotNull(FeedbackPanelInjector.defaultTypeOf(typeGetter.returnType)))
            check(injector.isUsable) { "item-calibration" }
            val group = owner(MODEL + "Feedback")
            val itemsGetter = checkNotNull(getter(group, "getItems"))
            val styleGetter = checkNotNull(getter(group, "getStyle"))
            val titleGetter = checkNotNull(getter(group, "getTitle"))
            val copy = KavaMemberLookup.declaredMethods(group, makeAccessible = true) {
                it.name == "copy" && !Modifier.isStatic(it.modifiers) && it.returnType == group &&
                    it.parameterTypes.contentEquals(arrayOf(styleGetter.returnType, String::class.java, List::class.java))
            }.single()

            val homeCard = KavaMemberLookup.classOrNull(loader, HOME_CARD)
            val cardArgs = home?.argsGetter?.let { point ->
                environment.hookPoints.resolveAdapted("feedback.home.args", point.className,
                    point.methodName, point.paramClassNames)
            } ?: homeCard?.let { getter(it, "getArgs") }
            val tid = cardArgs?.returnType?.let { getter(it, "getTid") }
            val tname = cardArgs?.returnType?.let { getter(it, "getTname") }
            val up = cardArgs?.returnType?.let { getter(it, "getUpName") }
            val mid = cardArgs?.returnType?.let { getter(it, "getUpId") }
            val detail = related?.detailRelateService?.componentFactory?.let { point ->
                environment.hookPoints.resolveAdapted("feedback.detail.factory", point.className,
                    point.methodName, point.paramClassNames)?.parameterTypes
                    ?.mapNotNull(RelatedFeedbackTags::resolve)?.singleOrNull()
            }
            val homeReady = homeCard != null && cardArgs != null && tid != null
            check(homeReady || detail != null) { "missing-card-readers" }
            val publisher = ScanSnapshotPublisher(environment,
                MineComponentSnapshotCodec.SURFACE_SECTION_PICKS, setOf("home_recommend_tid_block"))
            val authors = ScanSnapshotPublisher(environment,
                MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS, setOf("home_recommend_author_block"))
            fun picked(tag: FeedbackTag) {
                val added = SectionPickSession.add(tag.id)
                if (!added && !SectionPickSession.contains(tag.id)) return
                publisher.accumulate(MineComponentScanEntry(SectionPickPolicy.snapshotKey(tag.id),
                    SectionPickPolicy.SNAPSHOT_KIND, tag.name, tag.id.toString(), null, true,
                    selectionToken = java.util.UUID.randomUUID().toString()))
                if (!added) return
                environment.reportRuntimeEvidence(SectionPickFeatureInstaller.ID, FeatureRuntimeStage.APPLIED)
                environment.logInfo("section_pick_hit:${tag.id}", "[BIL] 已选择标签 id=${tag.id}，本次会话生效，长期保存需在模块中确认")
            }
            val panel = NativeFeedbackPanel(injector, itemsGetter, styleGetter, titleGetter, copy,
                clickGetter, { card ->
                    homeReady && homeCard!!.isInstance(card) || detail?.cardClass?.isInstance(card) == true
                }, { card ->
                    val messages = InjectedUiLocale.messages()
                    val data = if (homeReady && homeCard!!.isInstance(card)) cardArgs!!.invoke(card) else null
                    val tags = if (data != null) {
                        val id = (tid!!.invoke(data) as? Number)?.toLong() ?: 0L
                        val name = tname?.invoke(data) as? String
                        if (id > 0) listOf(FeedbackTag(id, name?.takeIf(String::isNotBlank) ?: id.toString()))
                        else emptyList()
                    } else detail?.read(card).orEmpty()
                    buildList {
                        tags.forEach { tag ->
                            injector.newEntry(String.format(messages.panelBlockTagLabel, tag.name)) { picked(tag) }
                                ?.let(::add)
                        }
                        val upName = data?.let { up?.invoke(it) as? String }?.takeIf(String::isNotBlank)
                        if (upName != null) {
                            val upId = (mid?.invoke(data) as? Number)?.toLong()?.takeIf { it > 0 }
                            injector.newEntry(String.format(messages.panelBlockAuthorLabel, upName)) {
                                if (AuthorPickSession.add(upName)) {
                                    authors.accumulate(MineComponentScanEntry.create("author", upName, upName, null, true)
                                        ?.copy(selectionToken = java.util.UUID.randomUUID().toString()))
                                }
                            }?.let(::add)
                        }
                    }
                }, { reason ->
                    environment.logInfo("section_pick_panel_skip:$reason", "[BIL] 原生面板本次不追加模块选项: $reason")
                })
            environment.registrar.exact("feedback.native.content", content.declaringClass,
                content.name, *content.parameterTypes) {
                before {
                    val groups = args[0] as? List<*> ?: return@before
                    runCatching { panel.merge(groups) }.onSuccess { merged ->
                        if (merged != null) {
                            args[0] = merged
                            // 第一个参数被替换，清掉调用方针对原列表预计算的 Compose changed 位。
                            args[changedIndex] = (args[changedIndex] as Int) and 0b1110.inv()
                            environment.reportRuntimeEvidence(SectionPickFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED)
                        }
                    }.onFailure {
                        environment.logError("section_pick_inject_error", "[BIL] 面板注入失败，本次保留原面板: ${it.javaClass.simpleName}")
                    }
                }
            }
            val legacyReady = LegacyFeedbackPanel.install(environment, panel)
            val complete = homeReady && detail != null && legacyReady
            environment.reportStatus("section_pick_status", if (complete) "success" else "partial:card-reader")
            environment.logInfo("section_pick_installed", "[BIL] 原生面板注入已注册，home=$homeReady, related=${detail != null}, legacy=$legacyReady")
            FeatureInstallResult.Installed(if (legacyReady) 2 else 1, complete = complete)
        }.getOrElse {
            environment.reportStatus("section_pick_status", "missing-panel-structure")
            environment.logError("section_pick_missing", "[BIL] 原生面板注入不可用: ${it.message}")
            FeatureInstallResult.Skipped("missing-panel-structure")
        }
    }
}
