package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Field
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** 评论净化：在 protobuf 的公开 URL 映射边界移除关键词搜索跳转。 */
internal class CommentPurifyFeatureInstaller(
    private val removeSearchLinks: Boolean,
    private val removeEmptyGuide: Boolean,
    private val points: VersionAdapter.CommentPurifyPoints?
) : FeatureInstaller {

    override val id: String = ID

    private val textFields = ConcurrentHashMap<Class<*>, List<Field>>()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!removeSearchLinks && !removeEmptyGuide) {
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
            val urlPoints = adapted.urlMapGetters
            if (urlPoints.isEmpty()) missingGroups += "search"
            expectedCount += urlPoints.size
            urlPoints.forEachIndexed { index, point ->
                runCatching {
                    environment.registrar.adapted("comment.purify.urls.$index", point) {
                        after {
                            val source = result as? Map<*, *> ?: return@after
                            val filtered = withoutSearchUrls(source) { value ->
                                isSearchUrlValue(value)
                            }
                            if (filtered !== source) result = filtered
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
        }
        if (removeEmptyGuide) {
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
                        after { result = defaultInstance }
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
        }

        if (installedCount == 0) return missing(environment, "registration-failed")
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
        return FeatureInstallResult.Installed(installedCount)
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
    }
}
