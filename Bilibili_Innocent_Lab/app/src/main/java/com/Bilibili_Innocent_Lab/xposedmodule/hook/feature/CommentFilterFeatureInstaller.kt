package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method
import java.util.Collections

/** 在公开 protobuf 评论列表边界按正文关键词和用户等级过滤。 */
internal class CommentFilterFeatureInstaller(
    keywordFilterEnabled: Boolean,
    rawKeywords: String,
    minimumLevelFilterEnabled: Boolean,
    minimumLevel: Int,
    private val points: VersionAdapter.CommentFilterPoints?
) : FeatureInstaller {

    override val id: String = ID

    private val keywords = if (keywordFilterEnabled) {
        RuleSetCodec.parse(rawKeywords).take(MAX_KEYWORDS).toCollection(linkedSetOf())
    } else {
        emptySet()
    }
    private val minimumLevel = if (minimumLevelFilterEnabled) {
        minimumLevel.coerceIn(MIN_LEVEL, MAX_LEVEL)
    } else {
        null
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (keywords.isEmpty() && minimumLevel == null) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val accessors = Accessors(
            content = resolve(environment, "content", adapted.contentGetter)
                ?: return missing(environment, "missing-content-getter"),
            message = resolve(environment, "message", adapted.messageGetter)
                ?: return missing(environment, "missing-message-getter"),
            member = adapted.memberGetter?.let { resolve(environment, "member", it) },
            level = adapted.levelGetter?.let { resolve(environment, "level", it) },
            memberV2 = adapted.memberV2Getter?.let {
                resolve(environment, "member_v2", it)
            },
            memberV2Basic = adapted.memberV2BasicGetter?.let {
                resolve(environment, "member_v2_basic", it)
            },
            memberV2Level = adapted.memberV2LevelGetter?.let {
                resolve(environment, "member_v2_level", it)
            }
        )
        if (!accessors.hasLevelPath) return missing(environment, "missing-level-getter")

        var installed = 0
        adapted.replyListGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("comment.filter.list.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        if (source.isEmpty()) return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val filtered = filterComments(source) { reply ->
                            shouldRemove(readSignals(reply, accessors), keywords, minimumLevel)
                        }
                        if (filtered !== source) {
                            result = filtered
                            environment.reportRuntimeEvidence(
                                ID,
                                FeatureRuntimeStage.APPLIED,
                                source.size - filtered.size
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "comment_filter_list_$index",
                    "[BIL] 评论关键词/等级过滤 Hook 注册失败(" +
                        "${point.className}#${point.methodName}): $throwable"
                )
            }
        }

        val defaultReply = adapted.replyDefaultInstanceGetter?.let { point ->
            resolve(environment, "reply_default", point)?.let { getter ->
                runCatching { getter.invoke(null) }.getOrNull()
            }
        }
        if (defaultReply != null) {
            adapted.topReplyGetters.forEachIndexed { index, point ->
                runCatching {
                    environment.registrar.adapted("comment.filter.top.$index", point) {
                        after {
                            val reply = result ?: return@after
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                            if (shouldRemove(readSignals(reply, accessors), keywords, minimumLevel)) {
                                result = defaultReply
                                environment.reportRuntimeEvidence(
                                    ID,
                                    FeatureRuntimeStage.APPLIED
                                )
                            }
                        }
                    }
                    installed += 1
                }.onFailure { throwable ->
                    environment.logError(
                        "comment_filter_top_$index",
                        "[BIL] 置顶评论过滤 Hook 注册失败(" +
                            "${point.className}#${point.methodName}): $throwable"
                    )
                }
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val expected = adapted.replyListGetters.size +
            if (defaultReply == null) 0 else adapted.topReplyGetters.size
        val status = if (installed == expected) {
            "success"
        } else {
            "partial:$installed/$expected"
        }
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "comment_filter_ok",
                "[BIL] 评论关键词/等级过滤已安装，hooks=$installed"
            )
        } else {
            environment.logError(
                "comment_filter_partial",
                "[BIL] 评论关键词/等级过滤部分安装，status=$status"
            )
        }
        return FeatureInstallResult.Installed(installed)
    }

    private fun readSignals(reply: Any, accessors: Accessors): Signals {
        val content = invokeCompatible(accessors.content, reply)
        val member = invokeCompatible(accessors.member, reply)
        val legacyLevel = (invokeCompatible(accessors.level, member) as? Number)?.toInt()
        val memberV2 = invokeCompatible(accessors.memberV2, reply)
        val memberV2Basic = invokeCompatible(accessors.memberV2Basic, memberV2)
        return Signals(
            message = invokeCompatible(accessors.message, content)?.toString(),
            level = legacyLevel ?: (invokeCompatible(
                accessors.memberV2Level,
                memberV2Basic
            ) as? Number)?.toInt()
        )
    }

    private fun invokeCompatible(method: Method?, target: Any?): Any? {
        if (method == null || target == null || !method.declaringClass.isInstance(target)) {
            return null
        }
        return runCatching { method.invoke(target) }.getOrNull()
    }

    private fun resolve(
        environment: HookEnvironment,
        suffix: String,
        point: VersionAdapter.HookPoint
    ): Method? = environment.hookPoints.resolveAdapted(
        "comment.filter.resolve.$suffix",
        point.className,
        point.methodName,
        point.paramClassNames
    )

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "comment_filter_missing",
            "[BIL] 评论关键词/等级过滤适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    internal data class Signals(
        val message: String? = null,
        val level: Int? = null
    )

    private data class Accessors(
        val content: Method,
        val message: Method,
        val member: Method?,
        val level: Method?,
        val memberV2: Method?,
        val memberV2Basic: Method?,
        val memberV2Level: Method?
    ) {
        val hasLevelPath: Boolean
            get() = (member != null && level != null) ||
                (memberV2 != null && memberV2Basic != null && memberV2Level != null)
    }

    companion object {
        const val ID = "comment_filter"
        const val DEFAULT_MIN_LEVEL = 3
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "comment_filter_status"
        private const val MAX_KEYWORDS = 64
        private const val MIN_LEVEL = 1
        private const val MAX_LEVEL = 6

        /** 读取失败时保守放行；只有明确命中关键词或明确低于阈值才删除。 */
        internal fun shouldRemove(
            signals: Signals,
            keywords: Set<String>,
            minimumLevel: Int?
        ): Boolean = RuleSetCodec.matches(keywords, signals.message) ||
            (minimumLevel != null && signals.level?.let { it < minimumLevel } == true)

        /** 无命中返回原 List；有命中才创建不可变副本，不改写 protobuf 内部集合。 */
        internal fun filterComments(
            source: List<*>,
            shouldRemove: (Any) -> Boolean
        ): List<*> {
            var filtered: ArrayList<Any?>? = null
            source.forEachIndexed { index, item ->
                if (item != null && shouldRemove(item)) {
                    if (filtered == null) {
                        val target = ArrayList<Any?>(source.size)
                        for (copyIndex in 0 until index) target.add(source[copyIndex])
                        filtered = target
                    }
                } else {
                    filtered?.add(item)
                }
            }
            return filtered?.let(Collections::unmodifiableList) ?: source
        }
    }
}
