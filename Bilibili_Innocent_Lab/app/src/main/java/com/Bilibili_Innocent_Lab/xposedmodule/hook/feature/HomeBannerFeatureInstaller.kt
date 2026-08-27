package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.util.Collections

/** 收起首页 V8Banner 以及极旧版本的容器数据入口。 */
internal class HomeBannerFeatureInstaller(
    private val enabled: Boolean,
    private val point: VersionAdapter.BannerPoint?,
    private val collapseBanner: (View) -> Unit
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }

        val results = LinkedHashMap<String, Boolean>()
        val bannerPoint = point
        if (bannerPoint != null) {
            val bannerClass = KavaMemberLookup.classOrNull(
                environment.classLoader,
                bannerPoint.bannerClassName
            )
            bannerPoint.lifecycleMethods.forEachIndexed { index, lifecyclePoint ->
                val key = "${lifecyclePoint.className}#${lifecyclePoint.methodName}"
                runCatching {
                    environment.registrar.adapted("home_banner.lifecycle.$index", lifecyclePoint) {
                        after {
                            val banner = instance as? View ?: return@after
                            if (bannerClass?.isInstance(banner) != true) return@after
                            if (
                                lifecyclePoint.methodName == "onVisibilityChanged" &&
                                (args.getOrNull(1) as? Int) != View.VISIBLE
                            ) return@after
                            collapseBanner(banner)
                        }
                    }
                    results[key] = true
                }.onFailure { throwable ->
                    results[key] = false
                    environment.logInfo(
                        "banner_adapter_${lifecyclePoint.methodName}_err",
                        "[BIL] Banner Adapter 入口失败: $throwable"
                    )
                }
            }
        } else {
            runCatching {
                environment.registrar.first(
                    "home_banner.legacy_container",
                    LEGACY_CONTAINER_CLASS,
                    LEGACY_ITEMS_METHOD
                ) { replaceTo(Collections.emptyList<Any>()) }
                results["legacyContainer"] = true
            }.onFailure {
                results["legacyContainer"] = false
            }
        }

        val ready = results.any { (key, registered) ->
            registered && (key.endsWith("#onAttachedToWindow") || key == "legacyContainer")
        }
        environment.reportStatus(CHANNEL_STATUS, if (ready) "success" else "failed")
        if (ready) {
            environment.logInfo("banner_ok", "[BIL] Banner Adapter 已注册 V8Banner 收起入口")
            return FeatureInstallResult.Installed(results.count { it.value })
        }
        environment.logError("banner_failed", "[BIL] Banner Adapter 未找到可用入口")
        return FeatureInstallResult.Skipped("no_required_hook_point")
    }

    companion object {
        const val ID = "home_banner"
        private const val CHANNEL_STATUS = "banner_ad_status"
        private const val LEGACY_CONTAINER_CLASS = "xm3.d"
        private const val LEGACY_ITEMS_METHOD = "l"
    }
}
