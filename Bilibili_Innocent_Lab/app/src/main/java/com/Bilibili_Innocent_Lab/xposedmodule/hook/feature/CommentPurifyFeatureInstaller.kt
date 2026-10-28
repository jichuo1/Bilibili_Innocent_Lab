package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import io.github.jichuo1.meguviz.android.extensions.child
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Field
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** 评论净化：仅接管已由 Adapter 精确确认的 protobuf 与独立组件边界。 */
internal class CommentPurifyFeatureInstaller(
    private val removeSearchLinks: Boolean,
    private val removeEmptyGuide: Boolean,
    private val removeVoteWidgets: Boolean,
    private val removeFollowButtons: Boolean,
    private val removeQoe: Boolean,
    private val removeOperations: Boolean,
    private val blockQuickReply: Boolean = false,
    private val points: VersionAdapter.CommentPurifyPoints?
) : FeatureInstaller {

    override val id: String = ID
    override val capabilityIds: List<String> get() = buildList {
        if (removeSearchLinks) add("comments_search_links_removed")
        if (removeEmptyGuide) add("comments_empty_guide_removed")
        if (removeVoteWidgets) add("comments_vote_widgets_removed")
        if (removeFollowButtons) add("comments_follow_buttons_removed")
        if (removeQoe) add("comments_qoe_removed")
        if (removeOperations) add("comments_operations_removed")
        if (blockQuickReply) add("comments_quick_reply_blocked")
    }

    private val textFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val quickReplyFields = ConcurrentHashMap<Class<*>, QuickReplyIntentFields>()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!removeSearchLinks && !removeEmptyGuide && !removeVoteWidgets &&
            !removeFollowButtons && !removeQoe && !removeOperations && !blockQuickReply) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val compose = CommentComposePurifyBridge(removeVoteWidgets, removeFollowButtons, blockQuickReply).install(environment)
        val baseEnvironment = environment
        val environment = environment.copy(capabilityEvidence = { id, result ->
            baseEnvironment.capabilityEvidence?.invoke(id, mergeCommentLayer(result, compose[id]))
        })
        val adapted = points ?: run {
            compose.forEach { (id, result) -> baseEnvironment.capabilityEvidence?.invoke(id, result) }
            installKotlinChannel(environment)
            val active = compose.values.filterIsInstance<FeatureInstallResult.Installed>()
            return if (active.isEmpty()) missing(environment, "missing-adapter-point")
            else FeatureInstallResult.Installed(active.sumOf { it.hookCount }, complete = false)
        }

        var installedCount = compose.values.filterIsInstance<FeatureInstallResult.Installed>().sumOf { it.hookCount }
        var expectedCount = compose.values.sumOf { (it as? FeatureInstallResult.Installed)?.hookCount ?: 1 }
        val missingGroups = compose.filterValues { it !is FeatureInstallResult.Installed || !it.complete }
            .keys.map { "compose:$it" }.toMutableList()
        if (removeSearchLinks) {
            val beforeInstalled = installedCount
            val beforeExpected = expectedCount
            val beforeMissing = missingGroups.size
            expectedCount += 1
            if (installRequestSideOptOut(environment)) installedCount += 1
            else missingGroups += "search-request"
            val urlPoints = adapted.urlMapGetters
            if (urlPoints.isEmpty()) missingGroups += "search"
            expectedCount += urlPoints.size
            urlPoints.forEachIndexed { index, point ->
                runCatching {
                    environment.registrar.adapted("comment.purify.urls.$index", point) {
                        after {
                            if (KotlinMossChannel.isRaw()) return@after
                            val source = result as? Map<*, *> ?: return@after
                            environment.reportRuntimeEvidence("comments_search_links_removed", FeatureRuntimeStage.OBSERVED)
                            val filtered = withoutSearchUrls(source) { value ->
                                isSearchUrlValue(value)
                            }
                            if (filtered !== source) {
                                result = filtered
                                environment.reportRuntimeEvidence("comments_search_links_removed",
                                    FeatureRuntimeStage.APPLIED,
                                    source.size - filtered.size
                                )
                            }
                        }
                    }
                    installedCount += 1
                }.onFailure { throwable ->
                    environment.logError(
                        "comment_purify_search_$index",
                        "[BIL] 评论搜索跳转净化 Hook 注册失败(" +
                            "${point.className}#${point.methodName}): $throwable"
                    )
                }
            }
            // 第二道防线：把跳转目标字段本身置空。
            //
            // **必须与上面那道互不知情**（AGENTS 纵深防御条目）：各自 try/catch、
            // 各自计入分母、各自降级。上面摘不掉 map 条目时，这里仍然能让链接点不动；
            // 这里定位不到时，上面照常工作。两道都失败才是真的失效。
            //
            // 判据是"渲染侧读这个字段才能跳转"，31 个本地宿主逐版实测它每一版都有
            // 跨 dex 消费者，所以不是空 Hook；反过来 `getUrlsOrDefault` /
            // `getUrlsOrThrow` / `getUrlsCount` 31 版全是 0 消费者，故意不挂。
            val schemaPoints = adapted.urlSchemaGetters
            if (schemaPoints.isEmpty()) missingGroups += "search-schema"
            expectedCount += schemaPoints.size
            schemaPoints.forEachIndexed { index, point ->
                runCatching {
                    environment.registrar.adapted("comment.purify.url_schema.$index", point) {
                        after {
                            if (KotlinMossChannel.isRaw()) return@after
                            val current = result as? String ?: return@after
                            environment.reportRuntimeEvidence("comments_search_links_removed", FeatureRuntimeStage.OBSERVED)
                            if (isSearchJumpUri(current)) {
                                // 置空后的实际表现是「**照常显示、点击不跳转**」，不是不显示。
                                // 9.11.0 反汇编实证（classes4.dex）：
                                // ① `Ph.a#e(Content, ReplyControl)` 只是逐字段把 Url 拷进
                                //    `comment2.model.UrlInfo`（title/prefixIcon/appUrl/…），
                                //    建 span 时**不看** appUrl 是否为空；
                                // ② 真正的判空在点击侧 `Dh.v#e(Context, UrlInfo, …)`：
                                //    `if (appUrl == null || appUrl.length() <= 0) return;`
                                //    早退在 `BLRouter.routeTo` **之前**；
                                //    `UrlInfo.isInternalSchema()` 也对空串返回 false。
                                // 所以这道防线的降级形态是"看得见但点不动"，
                                // 而防线①（摘 map 条目）才是"关键词连带搜索小图标一起不显示"。
                                // 两者都不动 title / message，评论文字和表情照旧。
                                result = ""
                                environment.reportRuntimeEvidence("comments_search_links_removed",
                                    FeatureRuntimeStage.APPLIED)
                            }
                        }
                    }
                    installedCount += 1
                }.onFailure { throwable ->
                    environment.logError(
                        "comment_purify_search_schema_$index",
                        "[BIL] 评论搜索跳转目标净化 Hook 注册失败(" +
                            "${point.className}#${point.methodName}): $throwable"
                    )
                }
            }
            val installedPaths = installedCount - beforeInstalled
            val expectedPaths = (expectedCount - beforeExpected).coerceAtLeast(1)
            val missingUncounted = missingGroups.size > beforeMissing && installedPaths == expectedPaths
            environment.reportCapabilityCoverage(
                "comments_search_links_removed",
                installedPaths > 0 || missingGroups.size == beforeMissing,
                installedPaths, expectedPaths + if (missingUncounted) 1 else 0
            )
        }
        if (removeEmptyGuide) {
            val beforeInstalled = installedCount
            val beforeExpected = expectedCount
            val beforeMissing = missingGroups.size
            val emptyPoints = adapted.emptyPageGetters
            if (emptyPoints.isEmpty()) missingGroups += "empty-page"
            expectedCount += emptyPoints.size
            emptyPoints.forEachIndexed { index, point ->
                runCatching {
                    val defaultGetter = environment.hookPoints.resolveAdapted(
                        "comment.purify.empty.default.$index",
                        point.defaultInstanceGetter.className,
                        point.defaultInstanceGetter.methodName,
                        point.defaultInstanceGetter.paramClassNames
                    ) ?: error("missing-default-instance-getter")
                    val defaultInstance = defaultGetter.invoke(null)
                        ?: error("null-default-instance")
                    environment.registrar.adapted(
                        "comment.purify.empty.content.$index",
                        point.contentGetter
                    ) {
                        after {
                            if (KotlinMossChannel.isRaw()) return@after
                            environment.reportRuntimeEvidence("comments_empty_guide_removed", FeatureRuntimeStage.OBSERVED)
                            if (result !== defaultInstance) {
                                result = defaultInstance
                                environment.reportRuntimeEvidence("comments_empty_guide_removed", FeatureRuntimeStage.APPLIED)
                            }
                        }
                    }
                    installedCount += 1
                }.onFailure { throwable ->
                    environment.logError(
                        "comment_purify_empty_$index",
                        "[BIL] 空评论区引导净化 Hook 注册失败(" +
                            "${point.contentGetter.className}#" +
                            "${point.contentGetter.methodName}): $throwable"
                    )
                }
            }
            val installedPaths = installedCount - beforeInstalled
            val expectedPaths = (expectedCount - beforeExpected).coerceAtLeast(1)
            val missingUncounted = missingGroups.size > beforeMissing && installedPaths == expectedPaths
            environment.reportCapabilityCoverage(
                "comments_empty_guide_removed",
                installedPaths > 0 || missingGroups.size == beforeMissing,
                installedPaths, expectedPaths + if (missingUncounted) 1 else 0
            )
        }
        if (removeVoteWidgets) {
            val beforeInstalled = installedCount
            val beforeExpected = expectedCount
            val beforeMissing = missingGroups.size
            val votePoints = adapted.voteWidgetMethods
            if (votePoints.isEmpty()) missingGroups += "vote"
            expectedCount += votePoints.size
            votePoints.forEachIndexed { index, point ->
                runCatching {
                    environment.registrar.adapted("comment.purify.vote.$index", point) {
                        after {
                            val target = instance as? View ?: return@after
                            environment.reportRuntimeEvidence("comments_vote_widgets_removed", FeatureRuntimeStage.OBSERVED)
                            if (target.visibility != View.GONE) {
                                target.visibility = View.GONE
                                environment.reportRuntimeEvidence("comments_vote_widgets_removed", FeatureRuntimeStage.APPLIED)
                            }
                        }
                    }
                    installedCount += 1
                }.onFailure { throwable ->
                    environment.logError(
                        "comment_purify_vote_$index",
                        "[BIL] 评论投票组件净化 Hook 注册失败(" +
                            "${point.className}#${point.methodName}): $throwable"
                    )
                }
            }
            val installedPaths = installedCount - beforeInstalled
            val expectedPaths = (expectedCount - beforeExpected).coerceAtLeast(1)
            val missingUncounted = missingGroups.size > beforeMissing && installedPaths == expectedPaths
            environment.reportCapabilityCoverage(
                "comments_vote_widgets_removed",
                installedPaths > 0 || missingGroups.size == beforeMissing,
                installedPaths, expectedPaths + if (missingUncounted) 1 else 0
            )
        }
        if (removeFollowButtons) {
            val beforeInstalled = installedCount
            val beforeExpected = expectedCount
            val beforeMissing = missingGroups.size
            val followPoints = adapted.follow
            if (followPoints == null) {
                missingGroups += "follow"
            } else {
                val widgetPoints = followPoints.widgetStateMethods
                if (widgetPoints.isEmpty()) missingGroups += "follow-widget"
                expectedCount += widgetPoints.size
                widgetPoints.forEachIndexed { index, point ->
                    runCatching {
                        val outerField = point.viewField?.let { fieldName ->
                            environment.hookPoints.resolveField(
                                "comment.purify.follow.outer.$index",
                                point.className,
                                fieldName
                            ) ?: error("missing-follow-outer-field")
                        }
                        environment.registrar.adapted(
                            "comment.purify.follow.widget.$index",
                            point
                        ) {
                            after {
                                val target = if (outerField == null) {
                                    instance
                                } else {
                                    runCatching { outerField.get(instance) }.getOrNull()
                                }
                                val view = target as? View ?: return@after
                                environment.reportRuntimeEvidence("comments_follow_buttons_removed", FeatureRuntimeStage.OBSERVED)
                                if (view.visibility != View.GONE) {
                                    view.visibility = View.GONE
                                    environment.reportRuntimeEvidence("comments_follow_buttons_removed",
                                        FeatureRuntimeStage.APPLIED
                                    )
                                }
                            }
                        }
                        installedCount += 1
                    }.onFailure { throwable ->
                        environment.logError(
                            "comment_purify_follow_widget_$index",
                            "[BIL] 评论独立关注控件 Hook 注册失败(" +
                                "${point.className}#${point.methodName}): $throwable"
                        )
                    }
                }

                val headerPoints = followPoints.headerBindMethods
                if (headerPoints.isNotEmpty()) {
                    val buttonClassName = followPoints.followButtonClassName
                    val followButtonClass = buttonClassName?.let { className ->
                        environment.hookPoints.resolveClass(
                            "comment.purify.follow.button_class",
                            className
                        )
                    }
                    if (followButtonClass == null) {
                        missingGroups += "follow-header-button"
                    } else {
                        expectedCount += headerPoints.size
                        headerPoints.forEachIndexed { index, point ->
                            runCatching {
                                environment.registrar.adapted(
                                    "comment.purify.follow.header.$index",
                                    point
                                ) {
                                    after {
                                        val root = instance as? ViewGroup ?: return@after
                                        environment.reportRuntimeEvidence("comments_follow_buttons_removed",
                                            FeatureRuntimeStage.OBSERVED
                                        )
                                        val hidden = hideTypedChildren(root, followButtonClass)
                                        environment.reportRuntimeEvidence("comments_follow_buttons_removed",
                                            FeatureRuntimeStage.APPLIED,
                                            hidden
                                        )
                                    }
                                }
                                installedCount += 1
                            }.onFailure { throwable ->
                                environment.logError(
                                    "comment_purify_follow_header_$index",
                                    "[BIL] 评论头部关注按钮 Hook 注册失败(" +
                                        "${point.className}#${point.methodName}): $throwable"
                                )
                            }
                        }
                    }
                }
            }
            val installedPaths = installedCount - beforeInstalled
            val expectedPaths = (expectedCount - beforeExpected).coerceAtLeast(1)
            val missingUncounted = missingGroups.size > beforeMissing && installedPaths == expectedPaths
            environment.reportCapabilityCoverage(
                "comments_follow_buttons_removed",
                installedPaths > 0 || missingGroups.size == beforeMissing,
                installedPaths, expectedPaths + if (missingUncounted) 1 else 0
            )
        }
        if (removeQoe) {
            val beforeInstalled = installedCount
            val beforeExpected = expectedCount
            val beforeMissing = missingGroups.size
            val qoePoint = adapted.qoe
            if (qoePoint == null) {
                missingGroups += "qoe"
            } else {
                expectedCount += 2
                val installed = installAbsentPayload(
                    environment.forCapabilityRuntime("comments_qoe_removed"),
                    "qoe",
                    "评论反馈",
                    qoePoint
                )
                installedCount += installed
                if (installed != 2) missingGroups += "qoe-read-boundary"
            }
            val installedPaths = installedCount - beforeInstalled
            val expectedPaths = (expectedCount - beforeExpected).coerceAtLeast(1)
            val missingUncounted = missingGroups.size > beforeMissing && installedPaths == expectedPaths
            environment.reportCapabilityCoverage(
                "comments_qoe_removed",
                installedPaths > 0 || missingGroups.size == beforeMissing,
                installedPaths, expectedPaths + if (missingUncounted) 1 else 0
            )
        }
        if (removeOperations) {
            val beforeInstalled = installedCount
            val beforeExpected = expectedCount
            val beforeMissing = missingGroups.size
            val operationPoints = adapted.operations
            expectedCount += 4
            var operationInstalled = 0
            operationPoints.forEachIndexed { index, point ->
                operationInstalled += installAbsentPayload(
                    environment.forCapabilityRuntime("comments_operations_removed"),
                    "operation.$index",
                    "评论运营推广",
                    point
                )
            }
            installedCount += operationInstalled
            if (operationPoints.size != 2 || operationInstalled != 4) {
                missingGroups += "operation-read-boundary"
            }
            val installedPaths = installedCount - beforeInstalled
            val expectedPaths = (expectedCount - beforeExpected).coerceAtLeast(1)
            val missingUncounted = missingGroups.size > beforeMissing && installedPaths == expectedPaths
            environment.reportCapabilityCoverage(
                "comments_operations_removed",
                installedPaths > 0 || missingGroups.size == beforeMissing,
                installedPaths, expectedPaths + if (missingUncounted) 1 else 0
            )
        }
        if (blockQuickReply) {
            val beforeInstalled = installedCount
            val beforeExpected = expectedCount
            val beforeMissing = missingGroups.size
            val quickReplyPoints = adapted.quickReplyDialogMethods
            if (quickReplyPoints.isEmpty()) missingGroups += "quick-reply"
            expectedCount += quickReplyPoints.size
            quickReplyPoints.forEachIndexed { index, point ->
                runCatching {
                    environment.registrar.adapted("comment.purify.quick_reply.$index", point) {
                        before {
                            val intent = args.firstOrNull() ?: return@before
                            environment.reportRuntimeEvidence("comments_quick_reply_blocked", FeatureRuntimeStage.OBSERVED)
                            if (shouldBlockQuickReply(intent)) {
                                result = Unit
                                environment.reportRuntimeEvidence("comments_quick_reply_blocked", FeatureRuntimeStage.APPLIED)
                            }
                        }
                    }
                    installedCount += 1
                }.onFailure { throwable ->
                    environment.logError(
                        "comment_purify_quick_reply_$index",
                        "[BIL] 评论快速回复 Hook 注册失败(" +
                            "${point.className}#${point.methodName}): $throwable"
                    )
                }
            }
            val installedPaths = installedCount - beforeInstalled
            val expectedPaths = (expectedCount - beforeExpected).coerceAtLeast(1)
            val missingUncounted = missingGroups.size > beforeMissing && installedPaths == expectedPaths
            environment.reportCapabilityCoverage(
                "comments_quick_reply_blocked",
                installedPaths > 0 || missingGroups.size == beforeMissing,
                installedPaths, expectedPaths + if (missingUncounted) 1 else 0
            )
        }

        // KMP 评论页走 Kotlin KReplyMoss：协议层子项在那条通道上再做一遍；兜底通道不计入覆盖单位，
        // Java 边界一个都装不上时也照装。
        installKotlinChannel(environment)
        if (installedCount == 0) return missing(environment, "registration-failed")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (missingGroups.isEmpty() && installedCount == expectedCount) {
            "success"
        } else {
            "partial:$installedCount/$expectedCount" +
                missingGroups.takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = ";missing=", separator = ",")
                    .orEmpty()
        }
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status != "success") {
            environment.logError(
                "comment_purify_partial",
                "[BIL] 评论净化部分安装，status=$status"
            )
        } else {
            environment.logInfo(
                "comment_purify_ok",
                "[BIL] 评论净化已安装，hooks=$installedCount"
            )
        }
        return FeatureInstallResult.Installed(installedCount, complete = missingGroups.isEmpty() && installedCount == expectedCount)
    }

    internal fun isSearchUrlValue(value: Any?): Boolean {
        value ?: return false
        val fields = textFields.getOrPut(value.javaClass) {
            KavaMemberLookup.declaredFields(value.javaClass, makeAccessible = true) {
                it.type isSubclassOf classOf<CharSequence>()
            }
        }
        return fields.any { field ->
            runCatching { field.get(value) as? CharSequence }
                .getOrNull()
                ?.let { isSearchJumpUri(it) } == true
        }
    }

    private fun shouldBlockQuickReply(intent: Any): Boolean {
        val fields = quickReplyFields.getOrPut(intent.javaClass) {
            val declared = KavaMemberLookup.declaredFields(
                intent.javaClass,
                makeAccessible = true
            ) { field -> !field.isStatic }
            val booleans = declared.filter { it.type == classOf<Boolean>() }
            QuickReplyIntentFields(
                isReply = booleans.getOrNull(1),
                position = declared.firstOrNull { field ->
                    field.type.isEnum && field.type.simpleName == "Pos"
                }
            )
        }
        val isReply = fields.isReply?.let { field ->
            runCatching { field.getBoolean(intent) }.getOrDefault(false)
        } ?: false
        val position = fields.position?.let { field ->
            runCatching { field.get(intent)?.toString().orEmpty() }.getOrDefault("")
        }.orEmpty()
        return shouldBlockQuickReply(isReply, position)
    }

    /**
     * Kotlin 评论列表、折叠/热点页与广播补插通道：见 [CommentKotlinPurifier]。
     * 搜索跳转开着时，`mainList` 的请求也往返一次，在 `extra` 里声明 `disable_underline=true`（第 0 道防线）。
     */
    private fun installKotlinChannel(environment: HookEnvironment) {
        if (!removeSearchLinks && !removeEmptyGuide && !removeQoe && !removeOperations) return
        // 装不上要留下原因（按 RPC 有界）；否则末尾那句"N 个"读起来像"不适用"，而不是"类没找到"。
        fun skip(reason: String) {
            environment.logInfo("comment_purify_kmoss_skip", "[BIL] 评论净化新通道未接入: $reason")
        }
        val loader = environment.classLoader ?: return skip("no-class-loader")
        val replyInfoClass = KavaMemberLookup.classOrNull(loader, "$REPLY_PACKAGE.ReplyInfo")
            ?: return skip("no-reply-info")
        val members = KotlinMossChannel.prepare(environment, loader, "评论净化", KMOSS_LOG_KEY) ?: return skip("no-bridge")
        val payloads = buildMap {
            if (removeQoe) put("Qoe", "comments_qoe_removed")
            if (removeOperations) {
                put("Operation", "comments_operations_removed")
                put("OperationV2", "comments_operations_removed")
            }
        }
        val appliedSink = ThreadLocal<MutableList<Pair<String, Int>>>()
        val purifier = CommentKotlinPurifier(
            replyInfoClass,
            isSearchUrl = if (removeSearchLinks) ::isSearchUrlValue else null,
            clearEmptyPage = removeEmptyGuide,
            payloads = payloads,
            // 每个类只解析一次，所以这里天然有界；形状解析不出来时净化是空转，必须留痕。
            logSkip = { reason ->
                environment.logInfo("comment_purify_kmoss_shape", "[BIL] 评论净化新通道：读不到 $reason，对应子项不生效")
            }
        ) { capability, stage, count ->
            val sink = appliedSink.get()
            if (stage == FeatureRuntimeStage.APPLIED && sink != null) sink += capability to count
            else environment.reportRuntimeEvidence(capability, stage, count)
        }
        val mainListReq = KavaMemberLookup.classOrNull(loader, "$REPLY_PACKAGE.MainListReq")
        // 请求侧那道防线读不到也要说一声，否则"搜索跳转已关"看上去是生效了的。
        if (mainListReq == null && removeSearchLinks) skip("no-main-list-request")
        val requestPlan = mainListReq?.takeIf { removeSearchLinks }?.let(ProtobufBuilderPlan::resolve)
        val getExtra = mainListReq?.let { KavaMemberLookup.methodOrNull(it, "getExtra") }
            ?.takeIf { it.returnType == classOf<String>() }
        val setExtra = requestPlan?.method("setExtra", classOf<String>())
        val requestTransform: ((Any) -> Any)? =
            if (requestPlan != null && getExtra != null && setExtra != null) {
                fun(request: Any): Any {
                    val extra = KotlinMossChannel.raw { getExtra.invoke(request) as? String }
                    val updated = CommentKotlinPurifier.withDisableUnderline(extra) ?: return request
                    return requestPlan.edit(request) { builder -> setExtra.invoke(builder, updated) }
                }
            } else {
                null
            }
        var hooks = 0
        KMOSS_RPCS.forEach { (rpc, replyName) ->
            val replyClass = KavaMemberLookup.classOrNull(loader, "$REPLY_PACKAGE.$replyName")
                ?: return@forEach skip("no-reply-class:$rpc")
            val rewriteRequest = rpc == "mainList" && requestTransform != null
            val installed = KotlinMossChannel.install(
                environment, loader, members,
                javaMossClassName = "$REPLY_PACKAGE.ReplyMoss",
                rpc = rpc,
                javaReplyClass = replyClass,
                hookId = "comment.purify.kmoss.$rpc",
                what = "评论净化",
                logKey = KMOSS_LOG_KEY,
                javaRequestClass = mainListReq?.takeIf { rewriteRequest },
                transformRequest = requestTransform?.takeIf { rewriteRequest },
                shareUnchangedReply = true,
                confirmedTransform = { javaReply ->
                    val pending = ArrayList<Pair<String, Int>>(4)
                    val previous = appliedSink.get()
                    appliedSink.set(pending)
                    val updated = try { purifier.purify(javaReply) } finally {
                        if (previous == null) appliedSink.remove() else appliedSink.set(previous)
                    }
                    KotlinMossResponseHandlerProxy.ConfirmedReply(updated) {
                        pending.forEach { (capability, count) -> environment.reportRuntimeEvidence(capability, FeatureRuntimeStage.APPLIED, count) }
                    }
                }
            ) { it }
            if (installed) hooks += 1
        }
        environment.logInfo("comment_purify_kmoss", "[BIL] 评论净化：Kotlin 新通道 $hooks 个")
    }

    /** 成对替换 protobuf 的 has/get 公开读取结果；默认实例只在安装期解析一次。 */
    private fun installAbsentPayload(
        environment: HookEnvironment,
        groupId: String,
        displayName: String,
        point: VersionAdapter.CommentOptionalPayloadPoint
    ): Int {
        val defaultInstance = runCatching {
            val defaultGetter = environment.hookPoints.resolveAdapted(
                "comment.purify.$groupId.default",
                point.defaultInstanceGetter.className,
                point.defaultInstanceGetter.methodName,
                point.defaultInstanceGetter.paramClassNames
            ) ?: error("missing-default-instance-getter")
            defaultGetter.invoke(null) ?: error("null-default-instance")
        }.onFailure { throwable ->
            environment.logError(
                "comment_purify_${groupId.replace('.', '_')}_default",
                "[BIL] $displayName 默认实例解析失败: $throwable"
            )
        }.getOrNull() ?: return 0

        var installed = 0
        runCatching {
            environment.registrar.adapted(
                "comment.purify.$groupId.presence",
                point.presenceGetter
            ) {
                after {
                    if (KotlinMossChannel.isRaw()) return@after
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    if (result != false) {
                        result = false
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
            }
            installed += 1
        }.onFailure { throwable ->
            environment.logError(
                "comment_purify_${groupId.replace('.', '_')}_presence",
                "[BIL] $displayName presence Hook 注册失败: $throwable"
            )
        }
        runCatching {
            environment.registrar.adapted(
                "comment.purify.$groupId.content",
                point.contentGetter
            ) {
                after {
                    if (KotlinMossChannel.isRaw()) return@after
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    if (result !== defaultInstance) {
                        result = defaultInstance
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
            }
            installed += 1
        }.onFailure { throwable ->
            environment.logError(
                "comment_purify_${groupId.replace('.', '_')}_content",
                "[BIL] $displayName content Hook 注册失败: $throwable"
            )
        }
        return installed
    }


    /**
     * 第 0 道防线：走宿主自己的开关，**让服务端一开始就不下发**。
     *
     * 2026-09-15 反汇编把整条链追通了（9.11.0）：
     * ```
     * CommentViewModel.<init>(Bundle)
     *   Bundle["search_word_disabled"] → toBooleanStrictOrNull
     *     → RemoteContentFilter(searchWordDisabled, weSearchDisabled, filterTagDisabled)
     *       → 评论状态 bl.m.f
     *         → 构建 MainListReq.extra 的那份 JSON：
     *              if (searchWordDisabled) put("disable_underline", true)
     *           → 随请求发出（extra 里同时有 spmid/from_spmid/track_id，与抓包逐字段对得上）
     * ```
     * 名字也对得上：评论里的搜索关键词就是带下划线渲染的（`Url.underline` 是字段 11）。
     * 宿主自己在用这个开关——会员购商品详情页 `com.mall.ui.page.detail.t#run()`
     * 就往 `CommentV3Fragment` 的参数里塞 `search_word_disabled`。
     *
     * 类名未混淆、31 个本地宿主（8.84.0–9.12.0）逐版实测**类与键都零缺失**，
     * 所以按名解析（与 `LegacyFeedbackPanel` 同一套做法），不接 VersionAdapter。
     *
     * **只在 Bundle 里没有这个键时才写**：宿主自己已经设过（会员购那类页面）就不覆盖，
     * 也不去动 `we_search_disabled` / `filter_tag_disabled`——那是另外两件事。
     */
    private fun installRequestSideOptOut(environment: HookEnvironment): Boolean = runCatching {
        val owner = KavaMemberLookup.classOrNull(
            environment.classLoader, COMMENT_VIEW_MODEL_CLASS
        ) ?: error("missing-comment-view-model")
        val constructor = KavaMemberLookup.declaredConstructors(owner) {
            it.parameterTypes.contentEquals(arrayOf(classOf<android.os.Bundle>()))
        }.singleOrNull() ?: error("ambiguous-or-missing-bundle-constructor")
        environment.registrar.constructor("comment.purify.search.request", constructor) {
            before {
                val bundle = args.firstOrNull() as? android.os.Bundle ?: return@before
                environment.reportRuntimeEvidence("comments_search_links_removed", FeatureRuntimeStage.OBSERVED)
                if (bundle.containsKey(SEARCH_WORD_DISABLED_KEY)) return@before
                bundle.putString(SEARCH_WORD_DISABLED_KEY, "true")
                environment.reportRuntimeEvidence("comments_search_links_removed", FeatureRuntimeStage.APPLIED)
                environment.logInfo(
                    "comment_purify_search_request",
                    "[BIL] 评论请求已声明 $SEARCH_WORD_DISABLED_KEY=true"
                )
            }
        }
        true
    }.onFailure { throwable ->
        environment.logError(
            "comment_purify_search_request_missing",
            "[BIL] 评论搜索链接请求侧开关不可用，退回响应侧两道防线: $throwable"
        )
    }.getOrDefault(false)

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "comment_purify_missing",
            "[BIL] 评论净化适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "comment_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "comment_purify_status"

        /** 未混淆；31 个本地宿主（8.84.0–9.12.0）逐版实测零缺失。 */
        internal const val COMMENT_VIEW_MODEL_CLASS =
            "com.bilibili.app.comment3.viewmodel.CommentViewModel"

        /** 宿主自己的启动参数键；同上，31 版零缺失。值按 `toBooleanStrictOrNull` 解析。 */
        internal const val SEARCH_WORD_DISABLED_KEY = "search_word_disabled"
        private const val REPLY_PACKAGE = "com.bapis.bilibili.main.community.reply.v1"
        private const val KMOSS_LOG_KEY = "comment_purify_kmoss"
        /** Kotlin 新通道：RPC → 同一 proto 的 Java 响应类简单名（与 `ReplyMoss` 同包）。 */
        private val KMOSS_RPCS = listOf(
            "mainList" to "MainListReply",
            "detailList" to "DetailListReply",
            "dialogList" to "DialogListReply",
            "foldList" to "FoldListResp",
            "hotspotPage" to "HotspotPageResp",
            "replyInfo" to "ReplyInfoReply"
        )

        /**
         * 一次会话里最多记几条"服务端到底还发不发"的观测。
         *
         * 这条日志是**判据不是装饰**：请求侧开关开着时，如果 map 仍然带搜索条目，
         * 说明服务端没吃这个 flag、真正生效的是响应侧那两道；如果 map 恒为空，
         * 才是"服务端不下发"。有界，记满彻底静默。
         */
        // 2026-09-15 曾在这里放过一条"服务端到底还发不发 urls"的取样探针，
        // 第 0 道防线实证通过后已移除：它在每条评论的映射路径上都要做一次
        // AtomicInteger 自增，属于纯诊断开销。退化仍然看得见——`getUrlsMap`
        // 那道钩子本来就按 capability 上报 OBSERVED/APPLIED，服务端要是重新下发
        // 搜索链接，第 1 道会开始报 APPLIED 且带删除条数。取样口径的两条教训
        // 记在 AGENTS 与长期文档 2026-09-15（三）里，别照着第一版重写。

        /**
         * 「这是不是一条跳搜索的链接」——**全仓唯一一份判据**。
         *
         * 直接复用 [DetailUnitedPresentationPurifyPolicy.isSearchJumpLabelUri]（scheme=bilibili
         * ＋ host=search），不再自己留一个 `startsWith("bilibili://search")`：
         * 前缀写法会把 `bilibili://searchxyz` 这种误判成命中，也接不住
         * `bilibili://search/` 这类写法；更重要的是档案红线——同一类判据不许存两份，
         * 复制出来的那份迟早和主份漂移。
         *
         * **不按 `from=appcommentline_search` 这个更精确的标记判**：那是"一个词一个开关"，
         * 宿主换个 from 值就静默失效；类别判据（跳搜索）才是稳的。
         */
        internal fun isSearchJumpUri(value: CharSequence?): Boolean =
            DetailUnitedPresentationPurifyPolicy.isSearchJumpLabelUri(value?.toString())

        /** 只遍历已适配的头部装饰小容器，保留其它徽章、图片与长按监听。 */
        internal fun hideTypedChildren(root: ViewGroup, targetClass: Class<*>): Int {
            var hidden = 0
            for (index in 0 until root.childCount) {
                val child = root.child(index)
                if (targetClass.isInstance(child)) {
                    if (child.visibility != View.GONE) {
                        child.visibility = View.GONE
                        hidden += 1
                    }
                }
                if (child is ViewGroup) hidden += hideTypedChildren(child, targetClass)
            }
            return hidden
        }

        /** 无命中时返回原 Map；命中时返回保持顺序的不可变副本，不修改 protobuf 原数据。 */
        internal fun <K, V> withoutSearchUrls(
            source: Map<K, V>,
            isSearchUrl: (V) -> Boolean
        ): Map<K, V> {
            var filtered: LinkedHashMap<K, V>? = null
            source.forEach { (key, value) ->
                if (isSearchUrl(value)) {
                    val target = filtered ?: LinkedHashMap(source).also { filtered = it }
                    target.remove(key)
                }
            }
            return filtered?.let(Collections::unmodifiableMap) ?: source
        }

        /** 只屏蔽评论卡片/正文短按；显式回复按钮、输入栏和三点菜单必须继续工作。 */
        internal fun shouldBlockQuickReply(isReply: Boolean, positionName: String): Boolean {
            if (!isReply) return false
            val position = positionName.uppercase()
            if (position.isBlank()) return true
            return when {
                "REPLY_BUTTON" in position -> false
                "MORE_MENU" in position -> false
                "BAR" in position -> false
                "INPUT" in position -> false
                "CARD" in position -> true
                "ITEM" in position -> true
                "TEXT" in position -> true
                "REPLY" in position -> true
                else -> true
            }
        }
    }

    private data class QuickReplyIntentFields(
        val isReply: Field?,
        val position: Field?
    )
}
