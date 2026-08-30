package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 推荐视频时长过滤范围，单位统一为秒。
 *
 * 0 表示对应边界未设置；损坏的负值或反向区间整体失效，避免误删全部推荐内容。
 */
internal data class VideoDurationRange(
    val minSeconds: Int,
    val maxSeconds: Int
) {
    val isConfigured: Boolean
        get() = minSeconds != 0 || maxSeconds != 0

    val isValid: Boolean
        get() = minSeconds >= 0 && maxSeconds >= 0 &&
            (minSeconds == 0 || maxSeconds == 0 || minSeconds <= maxSeconds)

    val isEnabled: Boolean
        get() = isConfigured && isValid

    /** 未知或非正时长保守放行；恰好等于上下限的卡片保留。 */
    fun shouldRemove(durationSeconds: Long?): Boolean {
        if (!isEnabled || durationSeconds == null || durationSeconds <= 0L) return false
        return (minSeconds > 0 && durationSeconds < minSeconds.toLong()) ||
            (maxSeconds > 0 && durationSeconds > maxSeconds.toLong())
    }
}

/** 安装期构建并缓存的详情页时长 getter 链。 */
internal data class VideoDurationMethodPath(
    val containerGetter: Method?,
    val durationGetter: Method
)

/** 只调用已适配并缓存的公开成员；读取失败或值无效时返回 null。 */
internal object VideoDurationReader {

    fun fromField(
        item: Any,
        containerGetter: Method,
        durationField: Field
    ): Long? {
        if (!containerGetter.declaringClass.isInstance(item)) return null
        val container = runCatching { containerGetter.invoke(item) }.getOrNull() ?: return null
        if (!durationField.declaringClass.isInstance(container)) return null
        return positiveSeconds(runCatching { durationField.get(container) }.getOrNull())
    }

    fun buildMethodPaths(
        directDurationGetters: List<Method>,
        nestedChains: List<Pair<Method, Method>>
    ): List<VideoDurationMethodPath> = buildList {
        directDurationGetters.forEach { add(VideoDurationMethodPath(null, it)) }
        nestedChains.forEach { (container, duration) ->
            if (container.returnType isSubclassOf duration.declaringClass) {
                add(VideoDurationMethodPath(container, duration))
            }
        }
    }.distinctBy { path ->
        path.containerGetter?.toGenericString().orEmpty() + "|" +
            path.durationGetter.toGenericString()
    }

    fun fromMethods(item: Any, paths: List<VideoDurationMethodPath>): Long? {
        paths.forEach { path ->
            val container = path.containerGetter?.let { getter ->
                if (!getter.declaringClass.isInstance(item)) return@forEach
                runCatching { getter.invoke(item) }.getOrNull() ?: return@forEach
            } ?: item
            val getter = path.durationGetter
            if (!getter.declaringClass.isInstance(container)) return@forEach
            positiveSeconds(runCatching { getter.invoke(container) }.getOrNull())?.let {
                return it
            }
        }
        return null
    }

    private fun positiveSeconds(value: Any?): Long? = (value as? Number)
        ?.toLong()
        ?.takeIf { it > 0L }
}
