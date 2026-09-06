package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.util.concurrent.atomic.AtomicInteger

/**
 * 让开屏页底色跟随系统深色模式。
 *
 * 宿主的品牌开屏底色固定为白色，夜里从深色首页切过去会闪一下白屏。这里只在开屏 Fragment
 * 的 `onViewCreated` 之后改一次背景色：深色模式黑、浅色模式白。
 *
 * 只改背景，不碰开屏图、logo、时长和跳过按钮——那些属于开屏广告净化的范围，两个功能互不
 * 依赖也互不覆盖。
 *
 * **版本覆盖（2026-09-06 离线核对 9.7.0–9.11.0 五版）**：`onViewCreated(View, Bundle)` 与
 * `splash_container` 资源名五版齐全。
 *
 * 资源 id 按名解析并缓存：宿主 `R$id` 数值每版都漂，名字稳定（[SPLASH_CONTAINER_ID_NAME]）。
 * 解析不到时退回 Fragment 根视图，仍然是一次性的、有界的降级。
 */
internal class SplashAutoNightFeatureInstaller(
    private val enabled: Boolean
) : FeatureInstaller {

    override val id: String = ID

    /** 0 = 尚未解析；-1 = 解析失败（后续一律用根视图），其余为宿主资源 id。 */
    private val containerId = AtomicInteger(ID_UNRESOLVED)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val fragmentClass = KavaMemberLookup.classOrNull(loader, BRAND_SPLASH_FRAGMENT_CLASS)
            ?: return missing(environment, "missing-splash-fragment")
        val method = KavaMemberLookup.methodOrNull(
            fragmentClass,
            "onViewCreated",
            classOf<View>(),
            classOf<Bundle>()
        )?.takeIf { !it.isStatic && it.returnType == Void.TYPE }
            ?: return missing(environment, "missing-on-view-created")

        return runCatching {
            environment.registrar.exact(
                "splash.auto_night.on_view_created",
                method.declaringClass,
                method.name,
                *method.parameterTypes
            ) {
                after {
                    if (hasThrowable) return@after
                    val root = args.getOrNull(0) as? View ?: return@after
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    if (applyNightBackground(root)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
            }
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
            environment.reportStatus(CHANNEL_STATUS, "success")
            environment.logInfo(
                "splash_auto_night_ok",
                "[BIL] 开屏页自动亮暗已安装"
            )
            FeatureInstallResult.Installed(1)
        }.getOrElse { throwable ->
            environment.logError(
                "splash_auto_night_register",
                "[BIL] 开屏页自动亮暗 Hook 注册失败: $throwable"
            )
            missing(environment, "registration-failed")
        }
    }

    /** @return 是否真的写了背景色。 */
    private fun applyNightBackground(root: View): Boolean {
        val night = root.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val target = resolveContainer(root) ?: root
        return runCatching {
            target.setBackgroundColor(if (night) Color.BLACK else Color.WHITE)
            true
        }.getOrDefault(false)
    }

    private fun resolveContainer(root: View): View? {
        var resolved = containerId.get()
        if (resolved == ID_UNRESOLVED) {
            resolved = runCatching {
                root.resources.getIdentifier(SPLASH_CONTAINER_ID_NAME, "id", TARGET_PACKAGE)
            }.getOrDefault(0).takeIf { it != 0 } ?: ID_MISSING
            containerId.compareAndSet(ID_UNRESOLVED, resolved)
            resolved = containerId.get()
        }
        if (resolved == ID_MISSING) return null
        return runCatching { root.findViewById<View>(resolved) }.getOrNull()
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "splash_auto_night_missing",
            "[BIL] 开屏页自动亮暗适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "splash_auto_night"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "splash_auto_night_status"
        private const val BRAND_SPLASH_FRAGMENT_CLASS =
            "tv.danmaku.bili.ui.splash.brand.ui.BaseBrandSplashFragment"
        private const val SPLASH_CONTAINER_ID_NAME = "splash_container"
        private const val ID_UNRESOLVED = 0
        private const val ID_MISSING = -1
    }
}
