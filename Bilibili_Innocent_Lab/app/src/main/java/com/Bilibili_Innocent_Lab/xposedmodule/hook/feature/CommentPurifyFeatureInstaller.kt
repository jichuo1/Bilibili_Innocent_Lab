package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.highcapable.betterandroid.ui.extension.view.child
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
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
        val adapted = points ?: return missing(environment, "missing-adapter-point")

        var installedCount = 0
        var expectedCount = 0
        val missingGroups = mutableListOf<String>()
        if (removeSearchLinks) {
            val beforeInstalled = installedCount
            val beforeExpected = expectedCount
            val beforeMissing = missingGroups.size
            val urlPoints = adapted.urlMapGetters
            if (urlPoints.isEmpty()) missingGroups += "search"
            expectedCount += urlPoints.size
            urlPoints.forEachIndexed { index, point ->
                runCatching {
                    environment.registrar.adapted("comment.purify.urls.$index", point) {
                        after {
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
                ?.startsWith(SEARCH_URI_PREFIX, ignoreCase = true) == true
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
        private const val SEARCH_URI_PREFIX = "bilibili://search"

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
