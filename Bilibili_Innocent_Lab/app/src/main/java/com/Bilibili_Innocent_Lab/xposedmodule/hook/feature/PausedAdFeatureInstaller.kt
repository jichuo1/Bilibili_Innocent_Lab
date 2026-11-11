package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Modifier

/** 保留暂停协议中的正常配置，独立安装面板、倒计时和 Java/Kotlin 响应清理。 */
internal class PausedAdFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.PausePoints?,
    private val hostVersionCode: Int? = null
) : FeatureInstaller {
    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")

        // 9.15 两条旧请求及新的 RequestResult 都承载 bar。识别点保留作缺失诊断，
        // 未证实纯广告的请求/Function0 不再一律 result=null，也不伪造 typed NO_AD。
        val requestOwners = points?.requestMethods.orEmpty().map { it.className }.toMutableSet()
        points?.legacyCallback?.let { requestOwners += it.className }
        REQUEST_CARRIERS.forEach { if (KavaMemberLookup.classOrNull(loader, it) != null) requestOwners += it }
        if (requestOwners.isNotEmpty()) environment.logInfo("paused_request_preserved",
            "[BIL] 暂停请求同时承载正常 bar，保留宿主请求和取消语义")
        val requestFamilies = PauseCountdownAdapter.requestFamilies(requestOwners)
        var expected = requestFamilies.size
        var hookCount = 0

        val panel = KavaMemberLookup.classOrNull(loader, PANEL_REPOSITORY_CLASS)
        if (panel != null || points?.panelShow != null) {
            expected++
            val show = panel?.let { KavaMemberLookup.declaredMethods(it, makeAccessible = true) { method ->
                !Modifier.isStatic(method.modifiers) && method.name == "showPanel" && method.returnType == Void.TYPE &&
                    method.parameterTypes.map(Class<*>::getName) == listOf("int", PANEL_DATA_INTERFACE, PANEL_LISTENER)
            }.singleOrNull() }
            val dismiss = panel?.let { KavaMemberLookup.methodOrNull(it, "dismissPanel") }
                ?.takeIf { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Void.TYPE }
            if (show != null && dismiss != null) runCatching {
                environment.registrar.exact("paused.panel_show", show.declaringClass, show.name, *show.parameterTypes) {
                    after {
                        if (hasThrowable) return@after
                        val data = args.getOrNull(1) ?: return@after
                        if (data.javaClass.simpleName != PANEL_DATA_NAME) return@after
                        val receiver = instance ?: return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        runCatching { dismiss.invoke(receiver) }.onFailure {
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
                            environment.logError("paused_p2_dismiss", "[BIL] 暂停广告面板关闭失败: $it")
                        }
                        // invoke 成功尚不证明窗口消失；APPLIED 由可回读的协议清理提供。
                    }
                }
            }.onSuccess { hookCount++ }.onFailure {
                environment.logError("paused_p2_register", "[BIL] 暂停广告面板入口注册失败: $it")
            }
        }

        val countdownOwner = KavaMemberLookup.classOrNull(loader, COUNTDOWN_CLASS)
        if (countdownOwner != null || points?.countdown != null ||
            KavaMemberLookup.classOrNull(loader, PAUSED_SERVICE) != null) {
            expected++
            val service = KavaMemberLookup.classOrNull(loader, PAUSED_SERVICE)
            val access = if (countdownOwner != null && service != null) {
                PauseCountdownAdapter.resolve(loader, countdownOwner, service)
            } else null
            if (access != null) runCatching {
                val countdown = access.entry
                environment.registrar.exact("paused.countdown", countdown.declaringClass,
                    countdown.name, *countdown.parameterTypes) {
                    before {
                        if (!access.allows(instance, args.getOrNull(0))) return@before
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        result = access.emptyResult()
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
            }.onSuccess { hookCount++ }.onFailure {
                environment.logError("paused_p3_register", "[BIL] 暂停广告倒计时入口注册失败: $it")
            } else environment.logInfo("paused_p3_preserved",
                "[BIL] 暂停倒计时广告来源/协程门禁不可用，保留正常 bar 与宿主倒计时")
        }

        val response = PauseResponseAdapter.install(environment, PauseResponseAdapter.resolve(loader))
        expected += response.expected
        hookCount += response.installed
        environment.reportStatus(CHANNEL_RESPONSE, when {
            response.expected == 0 -> "not-applicable-host"
            response.complete -> "success"
            else -> "partial:${response.installed}/${response.expected}"
        })
        val protectedFamilies = PauseRequestCoverage.covered(loader, hostVersionCode, requestFamilies, response.kotlinInstalled)
        val ready = hookCount + protectedFamilies.size
        val complete = expected > 0 && ready == expected
        if (requestFamilies.isNotEmpty()) environment.reportStatus("adskip_request_routes",
            requestFamilies.sorted().joinToString(",") { family ->
                "$family=${if (family in protectedFamilies) "covered-by-kotlin-response" else "preserved-unverified"}"
            })
        environment.reportStatus(CHANNEL_STATUS, when {
            hookCount == 0 -> "failed"
            complete -> "success"
            else -> "partial:$ready/$expected"
        })
        environment.logInfo("paused_coverage", "[BIL] 暂停广告覆盖=$ready/$expected，实际注册=$hookCount，响应=${response.installed}/${response.expected}，请求家族=${protectedFamilies.size}/${requestFamilies.size}、载体=${requestOwners.size}")
        return if (hookCount > 0) {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
            FeatureInstallResult.Installed(hookCount, complete)
        } else FeatureInstallResult.Skipped("no-safe-hook-point")
    }

    companion object {
        const val ID = "paused_ad"
        private const val CHANNEL_STATUS = "adskip_status"
        private const val CHANNEL_RESPONSE = "adskip_response_status"
        private const val PAUSED_SERVICE = "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService"
        private const val PANEL_REPOSITORY_CLASS = "com.bilibili.ship.theseus.united.page.ad.AdPanelRepository"
        private const val COUNTDOWN_CLASS = "$PAUSED_SERVICE\$showPauseBarCountdownToast\$3"
        private const val PANEL_DATA_NAME = "AdPausedPagePanelData"
        private const val PANEL_DATA_INTERFACE = "com.bilibili.gripper.api.ad.biz.videodetail.IPanelData"
        private const val PANEL_LISTENER = "com.bilibili.gripper.api.ad.biz.videodetail.IAdPanelListener"
        private val REQUEST_CARRIERS = listOf(
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$2",
            "$PAUSED_SERVICE\$requestPausedPageData\$2",
            "kntr.app.ad.biz.videodetail.pausedpage.AdPausedPageApi\$requestPausedPage\$1",
            "$PAUSED_SERVICE\$requestPausedPageResult\$1"
        )
        internal const val HAS_ADS = "hasAds"
        internal const val CLEAR_ADS = "clearAds"
        internal const val PAUSE_BAR_PRESENCE = "hasBar"
    }
}

/**
 * 只清 `PlayPauseReply.ads`，一个字段都不多动。
 *
 * 安装期解析全部反射；无广告载荷时返回原实例（不分配），异常交付原响应。
 */
internal class PausePayloadCleaner private constructor(
    private val reply: Class<*>,
    private val plan: ProtobufBuilderPlan,
    private val hasAds: java.lang.reflect.Method,
    private val clearAds: java.lang.reflect.Method,
    private val hasBar: java.lang.reflect.Method,
    private val getBar: java.lang.reflect.Method,
    private val defaultInstance: Any?
) {

    fun clearAds(original: Any, environment: HookEnvironment, reportApplied: Boolean = true): Any = runCatching {
        if (!reply.isInstance(original)) return@runCatching original
        if (defaultInstance != null && original === defaultInstance) return@runCatching original
        if (hasAds.invoke(original) as? Boolean != true) return@runCatching original
        environment.reportRuntimeEvidence(
            PausedAdFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED
        )
        val updated = plan.edit(original) { target -> clearAds.invoke(target) }
        // 回读确认：clear* 对空字段静默成功，不回读就没有"真的清掉了"的证据。
        check(isClearedCopy(original, updated)) { "Pause ads/bar clear readback failed" }
        if (reportApplied) environment.reportRuntimeEvidence(
            PausedAdFeatureInstaller.ID, FeatureRuntimeStage.APPLIED
        )
        updated
    }.getOrElse {
        environment.reportRuntimeEvidence(
            PausedAdFeatureInstaller.ID, FeatureRuntimeStage.ERROR
        )
        environment.logError(
            "paused_p4_clean_err",
            "[BIL] 暂停页响应层清理失败，保留原响应: $it"
        )
        original
    }

    internal fun isClearedCopy(original: Any, updated: Any): Boolean =
        reply.isInstance(original) && reply.isInstance(updated) && updated !== original &&
            hasAds.invoke(updated) as? Boolean == false &&
            hasBar.invoke(original) == hasBar.invoke(updated) && getBar.invoke(original) == getBar.invoke(updated)

    companion object {
        fun resolve(reply: Class<*>): PausePayloadCleaner? = runCatching {
            val plan = ProtobufBuilderPlan.resolve(reply) ?: return null
            val hasAds = KavaMemberLookup.methodOrNull(reply, PausedAdFeatureInstaller.HAS_ADS)
                ?.takeIf { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == java.lang.Boolean.TYPE } ?: return null
            val clearAds = plan.method(PausedAdFeatureInstaller.CLEAR_ADS) ?: return null
            val hasBar = KavaMemberLookup.methodOrNull(reply, PausedAdFeatureInstaller.PAUSE_BAR_PRESENCE)
                ?.takeIf { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == java.lang.Boolean.TYPE } ?: return null
            val getBar = KavaMemberLookup.methodOrNull(reply, "getBar")
                ?.takeIf { !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                    !it.returnType.isPrimitive } ?: return null
            val defaultInstance = KavaMemberLookup.methodOrNull(reply, "getDefaultInstance")
                ?.takeIf { Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == reply }
                ?.invoke(null)?.takeIf(reply::isInstance) ?: return null
            PausePayloadCleaner(reply, plan, hasAds, clearAds, hasBar, getBar, defaultInstance)
        }.getOrNull()
    }
}
