package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 独立于 VideoGuide 互动层；每个广告列表入口独立安装，局部失败保留其余路径。 */
internal class PlayerPopupPromotionFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return skipped(environment, "disabled")
        if (environment.processName != "tv.danmaku.bili") return skipped(environment, "non-main-process")
        val access = PlayerPopupPromotionLocator.resolve(environment.classLoader)
            ?: return skipped(environment, "missing-host-structure")
        var installed = 0
        val readCardType = access::cardType
        val errorLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        for (reader in access.readers) {
            runCatching {
                environment.registrar.exact("player.popup.promotion.${reader.name}", reader.declaringClass, reader.name) {
                    after {
                        if (hasThrowable) return@after
                        val source = result as? List<*> ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        // 任一字段读取失败时保留整份原列表，不发布半份过滤结果。
                        val updated = runCatching {
                            PlayerPopupPromotionPolicy.filter(source, readCardType)
                        }.getOrElse {
                            if (errorLogged.compareAndSet(false, true)) environment.logError(
                                "player_popup_promotion_read", "[BIL] 播放器推广卡读取失败，已保留原列表")
                            return@after
                        }
                        if (updated !== source) {
                            result = updated
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, source.size - updated.size)
                        }
                    }
                }
                installed++
            }.onFailure {
                environment.logError("player_popup_promotion_${reader.name}", "[BIL] 播放器推广过滤入口注册失败")
            }
        }
        if (installed == 0) return skipped(environment, "no-safe-hook-point")
        val expected = PlayerPopupPromotionLocator.READERS.size
        environment.reportStatus(STATUS, if (installed == expected) "success" else "partial:$installed/$expected")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    private fun skipped(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(STATUS, reason)
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "player_popup_promotion"
        private const val STATUS = "player_popup_promotion_status"
    }
}
