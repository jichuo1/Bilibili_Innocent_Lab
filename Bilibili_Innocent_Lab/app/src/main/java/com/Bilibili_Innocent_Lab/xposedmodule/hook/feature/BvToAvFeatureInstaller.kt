package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method

/**
 * 让宿主展示 AV 号而不是 BV 号。
 *
 * 宿主用 `com.bilibili.droid.BVCompat` 里唯一一个 `(String, String) -> String` 的静态方法
 * 在 avid / bvid 之间二选一（等价于 `getVideoId(avid, bvid)`），选哪个由服务端灰度位
 * `bv.enable_bv` 决定。这里只在这一个出口把结果换回 avid。
 *
 * **为什么不改那个静态布尔位**（哔哩漫游的做法）：那个位同时决定文本里识别视频号用的正则
 * 走"AV|BV 全集"还是"仅 AV"，关掉它会顺带让正文里的 BV 链接不再可点，所以上游还得再补一次
 * 正则替换。改这一个返回值出口既不牵连正则，也不用去写 `static final` 字段。
 *
 * 方法名被 R8 混淆（当前是 `a`），因此**不写死名字**：在稳定类上按"静态 + (String, String) ->
 * String + 唯一"结构定位，候选不唯一即按缺失处理。
 *
 * **版本覆盖（2026-09-06 离线核对 9.7.0–9.11.0 五版）**：该结构选择器在每一版都只命中
 * 唯一一个方法（都叫 `a`），且该方法在每一版都有 5 个以上跨 dex 调用方。
 *
 * 改写本身是自证的：只有当返回值确实是 BV 号、而另一个入参不是 BV 号且非空时才替换，
 * 参数顺序万一在未来版本对调也不会把 AV 号换成 BV 号。
 */
internal class BvToAvFeatureInstaller(
    private val enabled: Boolean
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val compat = environment.hookPoints.resolveClass("bv_to_av.compat", BV_COMPAT_CLASS)
            ?: return missing(environment, "missing-bv-compat-class")
        val candidates = KavaMemberLookup.declaredMethods(compat, makeAccessible = true) { method ->
            method.isStatic && method.returnType == classOf<String>() &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes.all { it == classOf<String>() }
        }.distinctBy(Method::toGenericString)
        val chooser = candidates.singleOrNull()
            ?: return missing(
                environment,
                if (candidates.isEmpty()) "missing-video-id-chooser" else "ambiguous-video-id-chooser"
            )

        return runCatching {
            environment.registrar.exact(
                "bv_to_av.video_id",
                chooser.declaringClass,
                chooser.name,
                *chooser.parameterTypes
            ) {
                after {
                    if (hasThrowable) return@after
                    val current = result as? String ?: return@after
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    val alternative = preferredAvId(
                        current,
                        args.getOrNull(0) as? String,
                        args.getOrNull(1) as? String
                    ) ?: return@after
                    result = alternative
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }
            }
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
            environment.reportStatus(CHANNEL_STATUS, "success")
            environment.logInfo(
                "bv_to_av_ok",
                "[BIL] AV 号显示已安装(${chooser.declaringClass.name}#${chooser.name})"
            )
            FeatureInstallResult.Installed(1)
        }.getOrElse { throwable ->
            environment.logError(
                "bv_to_av_register",
                "[BIL] AV 号显示 Hook 注册失败: $throwable"
            )
            missing(environment, "registration-failed")
        }
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "bv_to_av_missing",
            "[BIL] AV 号显示适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "bv_to_av"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "bv_to_av_status"
        private const val BV_COMPAT_CLASS = "com.bilibili.droid.BVCompat"

        /** BV 号形如 `BV1` + 9 位受限字母表，总长固定 12。 */
        private const val BV_ID_PREFIX = "BV"
        private const val BV_ID_LENGTH = 12

        internal fun looksLikeBvId(value: String?): Boolean =
            value != null && value.length == BV_ID_LENGTH && value.startsWith(BV_ID_PREFIX)

        /**
         * 在两个候选里挑出该展示的 AV 号。
         *
         * @return 需要改写成的 AV 号；本来就不是 BV 号、或者拿不到可用的 AV 号时返回 null。
         */
        internal fun preferredAvId(current: String, first: String?, second: String?): String? {
            if (!looksLikeBvId(current)) return null
            val alternative = listOfNotNull(first, second)
                .firstOrNull { it != current && it.isNotBlank() && !looksLikeBvId(it) }
            return alternative
        }
    }
}
