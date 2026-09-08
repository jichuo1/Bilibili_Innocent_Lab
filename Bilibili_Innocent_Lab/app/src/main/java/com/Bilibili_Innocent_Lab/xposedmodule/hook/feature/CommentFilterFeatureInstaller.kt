package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/**
 * 在公开 protobuf 评论列表边界按正文关键词、用户等级、@ 整条和发布者过滤。
 *
 * 四条判据共用同一批列表 getter Hook：多开一个判据不会多挂一个 Hook，只是在同一次遍历里
 * 多读几个字段，而且只读当前真的启用的那几个。
 *
 * 覆盖单位口径：
 * - 列表 / 置顶 getter 各算一个单位。
 * - 关键词、等级、@ 整条与发布者分别校验依赖：**用户开了就各算一个单位**，读取路径缺失时计入分母、不计入
 *   分子，让"开了却读不到"表现为 `partial` 而不是悄悄失效。
 */
internal class CommentFilterFeatureInstaller(
    keywordFilterEnabled: Boolean,
    rawKeywords: String,
    minimumLevelFilterEnabled: Boolean,
    minimumLevel: Int,
    removeAtOnlyComments: Boolean = false,
    userFilterEnabled: Boolean = false,
    rawUserRules: String = "",
    private val points: VersionAdapter.CommentFilterPoints?
) : FeatureInstaller {

    override val id: String = ID
    override val capabilityIds: List<String> get() = buildList {
        if (keywords.isNotEmpty()) add("comments_keyword_filter_enabled")
        if (minimumLevel != null) add("comments_minimum_level_filter_enabled")
        if (removeAtOnly) add("comments_at_only_removed")
        if (!userRules.isEmpty()) add("comments_user_filter_enabled")
    }

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
    private val removeAtOnly = removeAtOnlyComments
    private val userRules = if (userFilterEnabled) {
        AuthorRuleSet.parse(rawUserRules)
    } else {
        AuthorRuleSet.EMPTY
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (keywords.isEmpty() && minimumLevel == null && !removeAtOnly && userRules.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val accessors = Accessors(
            content = adapted.contentGetter?.let { resolve(environment, "content", it) },
            message = adapted.messageGetter?.let { resolve(environment, "message", it) },
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
            },
            atNameCount = adapted.atNameCountGetter?.let {
                resolve(environment, "at_count", it)
            },
            atNameMap = adapted.atNameMapGetter?.let { resolve(environment, "at_map", it) },
            memberName = adapted.memberNameGetter?.let {
                resolve(environment, "member_name", it)
            },
            memberMid = adapted.memberMidGetter?.let { resolve(environment, "member_mid", it) },
            memberV2Name = adapted.memberV2NameGetter?.let {
                resolve(environment, "member_v2_name", it)
            },
            memberV2Mid = adapted.memberV2MidGetter?.let {
                resolve(environment, "member_v2_mid", it)
            }
        )

        // 判据可用性只算一次：热路径只做布尔判断，不再重复检查 Method 是否为 null。
        val plan = JudgementPlan(
            keywords = if (accessors.hasMessagePath) keywords else emptySet(),
            minimumLevel = minimumLevel?.takeIf { accessors.hasLevelPath },
            removeAtOnly = removeAtOnly && accessors.hasAtPath,
            userRules = userRules.available(accessors.hasAuthorNamePath, accessors.hasAuthorMidPath)
        )
        if (!plan.hasAnyJudgement) return missing(environment, "missing-judgement-getter")

        var installed = 0
        adapted.replyListGetters.forEachIndexed { index, point ->
            runCatching {
                environment.registrar.adapted("comment.filter.list.$index", point) {
                    after {
                        val source = result as? List<*> ?: return@after
                        if (source.isEmpty()) return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        val filtered = filterComments(source) { reply ->
                            shouldRemove(readSignals(reply, accessors, plan), plan)
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
                    "[BIL] 评论过滤 Hook 注册失败(" +
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
                            if (shouldRemove(readSignals(reply, accessors, plan), plan)) {
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
        val sharedInstalled = installed
        val sharedExpected = adapted.replyListGetters.size + adapted.topReplyGetters.size
        for (capability in capabilityIds) {
            val usable = when (capability) {
                "comments_minimum_level_filter_enabled" -> accessors.hasLevelPath
                "comments_at_only_removed" -> plan.removeAtOnly
                "comments_user_filter_enabled" -> !plan.userRules.isEmpty()
                else -> plan.keywords.isNotEmpty()
            }
            environment.reportCapabilityCoverage(capability, usable, sharedInstalled,
                sharedExpected + if (capability == "comments_user_filter_enabled" && plan.userRules != userRules) 1 else 0)
        }

        // 判据覆盖：用户开了但适配读不到的判据，要在分母里留下痕迹。
        var expected = sharedExpected
        val degraded = ArrayList<String>(4)
        if (keywords.isNotEmpty()) {
            expected += 1
            if (plan.keywords.isNotEmpty()) installed += 1 else degraded += "keyword"
        }
        if (minimumLevel != null) {
            expected += 1
            if (plan.minimumLevel != null) installed += 1 else degraded += "level"
        }
        if (removeAtOnly) {
            expected += 1
            if (plan.removeAtOnly) installed += 1 else degraded += "at-only"
        }
        if (!userRules.isEmpty()) {
            expected += 1
            if (plan.userRules == userRules) installed += 1 else degraded += "author"
        }
        if (degraded.isNotEmpty()) {
            environment.logError(
                "comment_filter_degraded",
                "[BIL] 评论过滤判据缺少可用读取路径: ${degraded.joinToString(",")}"
            )
        }

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (installed == expected) {
            "success"
        } else {
            "partial:$installed/$expected"
        }
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "comment_filter_ok",
                "[BIL] 评论过滤已安装，hooks=$installed，判据=${plan.describe()}"
            )
        } else {
            environment.logError(
                "comment_filter_partial",
                "[BIL] 评论过滤部分安装，status=$status"
            )
        }
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    /** 只读当前启用判据真正需要的字段；未启用的判据一次反射都不做。 */
    private fun readSignals(
        reply: Any,
        accessors: Accessors,
        plan: JudgementPlan
    ): Signals {
        val needContent = plan.needsMessage || plan.removeAtOnly
        val content = if (needContent) invokeCompatible(accessors.content, reply) else null
        val message = if (plan.needsMessage) {
            invokeCompatible(accessors.message, content)?.toString()
        } else {
            null
        }
        val atNames = if (plan.removeAtOnly) readAtNames(content, accessors) else null

        var level: Int? = null
        var authorName: String? = null
        var authorMid: Long? = null
        if (plan.minimumLevel != null || !plan.userRules.isEmpty()) {
            val member = invokeCompatible(accessors.member, reply)
            val memberV2 = invokeCompatible(accessors.memberV2, reply)
            val memberV2Basic = invokeCompatible(accessors.memberV2Basic, memberV2)
            if (plan.minimumLevel != null) {
                level = (invokeCompatible(accessors.level, member) as? Number)?.toInt()
                    ?: (invokeCompatible(accessors.memberV2Level, memberV2Basic) as? Number)
                        ?.toInt()
            }
            if (!plan.userRules.isEmpty()) {
                authorName = (invokeCompatible(accessors.memberName, member) as? String)
                    ?.takeIf(String::isNotBlank)
                    ?: (invokeCompatible(accessors.memberV2Name, memberV2Basic) as? String)
                        ?.takeIf(String::isNotBlank)
                authorMid = (invokeCompatible(accessors.memberMid, member) as? Number)?.toLong()
                    ?.takeIf { it > 0L }
                    ?: (invokeCompatible(accessors.memberV2Mid, memberV2Basic) as? Number)
                        ?.toLong()
                        ?.takeIf { it > 0L }
            }
        }
        return Signals(
            message = message,
            level = level,
            atNames = atNames,
            authorName = authorName,
            authorMid = authorMid
        )
    }

    /** 先读计数：没有 @ 的评论（绝大多数）连 Map 视图都不会构造。 */
    private fun readAtNames(content: Any?, accessors: Accessors): Set<String>? {
        content ?: return null
        val count = (invokeCompatible(accessors.atNameCount, content) as? Number)?.toInt()
            ?: return null
        if (count <= 0) return emptySet()
        val map = invokeCompatible(accessors.atNameMap, content) as? Map<*, *> ?: return null
        return map.keys.asSequence()
            .filterIsInstance<String>()
            .filter(String::isNotBlank)
            .toCollection(linkedSetOf())
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
            "[BIL] 评论过滤适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    internal data class Signals(
        val message: String? = null,
        val level: Int? = null,
        /** null = 本次没读 @ 名单（判据未启用或读取失败），不能当成"没有 @"。 */
        val atNames: Set<String>? = null,
        val authorName: String? = null,
        val authorMid: Long? = null
    )

    /** 安装期定型的判据集合；热路径只读它，不再回头判断适配是否完整。 */
    internal data class JudgementPlan(
        val keywords: Set<String>,
        val minimumLevel: Int?,
        val removeAtOnly: Boolean,
        val userRules: AuthorRuleSet
    ) {
        val needsMessage: Boolean
            get() = keywords.isNotEmpty() || removeAtOnly

        val hasAnyJudgement: Boolean
            get() = keywords.isNotEmpty() || minimumLevel != null || removeAtOnly ||
                !userRules.isEmpty()

        fun describe(): String = buildList {
            if (keywords.isNotEmpty()) add("keyword=${keywords.size}")
            minimumLevel?.let { add("level>=$it") }
            if (removeAtOnly) add("at-only")
            if (!userRules.isEmpty()) {
                add("author=${userRules.mids.size}uid+${userRules.names.size}name")
            }
        }.joinToString("/")
    }

    private data class Accessors(
        val content: Method?,
        val message: Method?,
        val member: Method?,
        val level: Method?,
        val memberV2: Method?,
        val memberV2Basic: Method?,
        val memberV2Level: Method?,
        val atNameCount: Method?,
        val atNameMap: Method?,
        val memberName: Method?,
        val memberMid: Method?,
        val memberV2Name: Method?,
        val memberV2Mid: Method?
    ) {
        val hasMessagePath: Boolean get() = content != null && message != null
        val hasLevelPath: Boolean
            get() = (member != null && level != null) ||
                (memberV2 != null && memberV2Basic != null && memberV2Level != null)

        val hasAtPath: Boolean
            get() = hasMessagePath && atNameCount != null && atNameMap != null

        val hasAuthorNamePath: Boolean get() = (member != null && memberName != null) ||
            (memberV2 != null && memberV2Basic != null && memberV2Name != null)
        val hasAuthorMidPath: Boolean get() = (member != null && memberMid != null) ||
            (memberV2 != null && memberV2Basic != null && memberV2Mid != null)
    }

    companion object {
        const val ID = "comment_filter"
        const val DEFAULT_MIN_LEVEL = 3
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "comment_filter_status"
        private const val MAX_KEYWORDS = 64
        private const val MIN_LEVEL = 1
        private const val MAX_LEVEL = 6

        /**
         * 去掉 @ 名字后仍算"空正文"的残留标点。
         *
         * 空白另由 Char.isWhitespace 判断，这里只收分隔性标点；表情、汉字、字母数字一律
         * 留给“有正文”那一侧，宁可漏删也不错删。
         */
        private const val AT_ONLY_RESIDUE_PUNCTUATION = ",，.。、;；:：!！?？~～·|/-—_+&*"

        /** 读取失败时保守放行；只有明确命中某条判据才删除。 */
        internal fun shouldRemove(
            signals: Signals,
            plan: JudgementPlan
        ): Boolean = RuleSetCodec.matches(plan.keywords, signals.message) ||
            (plan.minimumLevel != null && signals.level?.let { it < plan.minimumLevel } == true) ||
            (plan.removeAtOnly && isAtOnlyComment(signals.message, signals.atNames)) ||
            (!plan.userRules.isEmpty() &&
                plan.userRules.matches(signals.authorName, signals.authorMid))

        /** 兼容旧签名的窄入口，供只关心关键词/等级两条判据的单测使用。 */
        internal fun shouldRemove(
            signals: Signals,
            keywords: Set<String>,
            minimumLevel: Int?
        ): Boolean = shouldRemove(
            signals,
            JudgementPlan(keywords, minimumLevel, removeAtOnly = false, userRules = AuthorRuleSet.EMPTY)
        )

        /**
         * 判断整条评论是否只由 @ 组成。
         *
         * [atNames] 为 null 表示这次没能读到 @ 名单——此时一律保留，绝不按"正文很短"猜。
         */
        internal fun isAtOnlyComment(message: String?, atNames: Set<String>?): Boolean {
            if (atNames.isNullOrEmpty()) return false
            val text = message ?: return false
            if (text.isBlank()) return false
            var residue = text
            // 长名字先删，避免"@张三"把"@张三丰"的前缀吃掉后留下孤立的"丰"。
            atNames.sortedByDescending(String::length).forEach { name ->
                residue = residue.replace("@$name", " ")
            }
            return residue.all { it.isWhitespace() || it in AT_ONLY_RESIDUE_PUNCTUATION }
        }

        /** 无命中返回原 List；有命中才创建不可变副本，不改写 protobuf 内部集合。 */
        internal fun filterComments(
            source: List<*>,
            shouldRemove: (Any) -> Boolean
        ): List<*> = ProtobufListRetention.filterOrSame(source, shouldRemove)
    }
}
