package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method
import java.util.Locale

/**
 * 净化分享结果：去掉链接与文案里的追踪参数，并把小程序卡片降级为普通链接分享。
 *
 * 边界是宿主分享中台的公开数据类 `ShareClickResult`——类名与 getter/setter 名都没混淆，
 * 且 9.11.0 里业务侧确实经 getter 读取（跨 dex 引用可查），不存在"按字段序列化绕过 getter"
 * 的问题。
 *
 * 改写后同步写回同名字段：分享面板会多次读取同一个结果对象，只改本次返回值会让复制链接
 * 和真正发出去的内容出现不一致。
 *
 * **版本覆盖（2026-09-06 离线核对 9.7.0–9.11.0 五版）**：全部 getter/setter 五版齐全，
 * `shareMode` 在每一版都是装箱 `java.lang.Integer`，`getLink` 每版都有跨 dex 调用方。
 *
 * 小程序降级只动 `shareMode`：6/7 是小程序，改成 0 让宿主走普通图文链接分享。附带的
 * 标题回填是因为小程序模式下 `title` 固定是应用名、真正的标题在 `content` 里，不回填会得到
 * 一张标题为"哔哩哔哩"的卡片。这里不追加任何署名文案。
 */
internal class SharePurifyFeatureInstaller(
    private val purifyContent: Boolean,
    private val miniProgramDirectLink: Boolean
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!purifyContent && !miniProgramDirectLink) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val resultClass = environment.hookPoints.resolveClass(
            "share.purify.result",
            SHARE_CLICK_RESULT_CLASS
        ) ?: return missing(environment, "missing-share-result-class")

        var installed = 0
        var expected = 0

        if (purifyContent) {
            val setLink = stringSetter(resultClass, "setLink")
            val getLink = stringGetter(resultClass, "getLink")
            expected += 1
            if (getLink == null) {
                environment.logError(
                    "share_purify_link_missing",
                    "[BIL] 分享链接净化缺少 getLink 读取路径"
                )
            } else if (installStringPurifier(
                    environment,
                    "share.purify.link",
                    getLink,
                    setLink
                ) { ShareLinkPurifier.purifyUrl(it) }
            ) {
                installed += 1
            }

            val setContent = stringSetter(resultClass, "setContent")
            val getContent = stringGetter(resultClass, "getContent")
            expected += 1
            if (getContent == null) {
                environment.logError(
                    "share_purify_content_missing",
                    "[BIL] 分享文案净化缺少 getContent 读取路径"
                )
            } else if (installStringPurifier(
                    environment,
                    "share.purify.content",
                    getContent,
                    setContent
                ) { ShareLinkPurifier.purifyText(it) }
            ) {
                installed += 1
            }
        }

        if (miniProgramDirectLink) {
            expected += 1
            if (installMiniProgramDowngrade(environment, resultClass)) installed += 1
        }

        if (installed == 0) return missing(environment, "registration-failed")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (installed == expected) "success" else "partial:$installed/$expected"
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "share_purify_ok",
                "[BIL] 分享净化已安装，hooks=$installed，" +
                    "purify=$purifyContent，miniProgram=$miniProgramDirectLink"
            )
        } else {
            environment.logError(
                "share_purify_partial",
                "[BIL] 分享净化部分安装，status=$status"
            )
        }
        return FeatureInstallResult.Installed(installed)
    }

    private fun installStringPurifier(
        environment: HookEnvironment,
        id: String,
        getter: Method,
        setter: Method?,
        purify: (String) -> String?
    ): Boolean = runCatching {
        environment.registrar.exact(id, getter.declaringClass, getter.name) {
            after {
                if (hasThrowable) return@after
                val original = result as? String ?: return@after
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                val purified = purify(original) ?: return@after
                result = purified
                // 写回同一对象，后续读取（复制链接、二次分享）拿到的也是净化后的值。
                setter?.let { runCatching { it.invoke(instance, purified) } }
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
            }
        }
        true
    }.getOrElse { throwable ->
        environment.logError(
            "share_purify_register_${id.substringAfterLast('.')}",
            "[BIL] 分享净化 Hook 注册失败($id): $throwable"
        )
        false
    }

    private fun installMiniProgramDowngrade(
        environment: HookEnvironment,
        resultClass: Class<*>
    ): Boolean {
        val getShareMode = KavaMemberLookup.methodOrNull(resultClass, "getShareMode")
            ?.takeIf { !it.isStatic && it.parameterCount == 0 }
            ?: run {
                environment.logError(
                    "share_mini_program_missing",
                    "[BIL] 小程序转直链缺少 getShareMode 读取路径"
                )
                return false
            }
        val setShareMode = KavaMemberLookup.declaredMethods(resultClass, makeAccessible = true) {
            !it.isStatic && it.name == "setShareMode" && it.parameterCount == 1
        }.singleOrNull()
        val getTitle = stringGetter(resultClass, "getTitle")
        val getContent = stringGetter(resultClass, "getContent")
        val setTitle = stringSetter(resultClass, "setTitle")
        val plainMode = coerceShareMode(getShareMode.returnType, PLAIN_SHARE_MODE)
            ?: run {
                environment.logError(
                    "share_mini_program_mode_type",
                    "[BIL] 小程序转直链无法构造普通分享模式返回值" +
                        "(${getShareMode.returnType.name})"
                )
                return false
            }

        return runCatching {
            environment.registrar.exact(
                "share.purify.mini_program",
                getShareMode.declaringClass,
                getShareMode.name
            ) {
                after {
                    if (hasThrowable) return@after
                    val mode = (result as? Number)?.toInt() ?: return@after
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    if (mode !in MINI_PROGRAM_SHARE_MODES) return@after
                    result = plainMode
                    val target = instance
                    if (target != null) {
                        setShareMode?.let { runCatching { it.invoke(target, plainMode) } }
                        restoreTitleFromContent(target, getTitle, getContent, setTitle)
                    }
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }
            }
            true
        }.getOrElse { throwable ->
            environment.logError(
                "share_mini_program_register",
                "[BIL] 小程序转直链 Hook 注册失败: $throwable"
            )
            false
        }
    }

    /**
     * 小程序模式下标题固定是应用名，真正的标题在正文里。
     *
     * 只有标题为空或就是应用名时才回填，避免覆盖宿主已经写好的正常标题。
     */
    private fun restoreTitleFromContent(
        target: Any,
        getTitle: Method?,
        getContent: Method?,
        setTitle: Method?
    ) {
        getTitle ?: return
        getContent ?: return
        setTitle ?: return
        val title = runCatching { getTitle.invoke(target) }.getOrNull() as? String
        if (title != null && title.isNotBlank() &&
            title.trim().lowercase(Locale.ROOT) !in APP_NAME_TITLES
        ) {
            return
        }
        val content = (runCatching { getContent.invoke(target) }.getOrNull() as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return
        runCatching { setTitle.invoke(target, content.take(MAX_TITLE_LENGTH)) }
    }

    /** `shareMode` 在不同版本可能是 `int` 或 `Integer`，返回值类型必须对得上。 */
    private fun coerceShareMode(returnType: Class<*>, value: Int): Any? = when (returnType) {
        classOf<Int>(), classOf<Int>(primitiveType = false) -> value
        else -> null
    }

    private fun stringGetter(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf { method ->
            !method.isStatic && method.parameterCount == 0 &&
                method.returnType == classOf<String>()
        }

    private fun stringSetter(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name, classOf<String>())?.takeIf { method ->
            !method.isStatic && method.returnType == Void.TYPE
        }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "share_purify_missing",
            "[BIL] 分享净化适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "share_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "share_purify_status"
        private const val SHARE_CLICK_RESULT_CLASS =
            "com.bilibili.lib.sharewrapper.online.api.ShareClickResult"

        /** 宿主分享模式：6/7 为小程序，0 走普通图文链接。 */
        private val MINI_PROGRAM_SHARE_MODES = setOf(6, 7)
        private const val PLAIN_SHARE_MODE = 0
        private const val MAX_TITLE_LENGTH = 120

        /** 小程序占位标题；只用于判断"这不是真标题"，不参与任何文案生成。 */
        private val APP_NAME_TITLES = setOf("哔哩哔哩", "嗶哩嗶哩", "bilibili")
    }
}
