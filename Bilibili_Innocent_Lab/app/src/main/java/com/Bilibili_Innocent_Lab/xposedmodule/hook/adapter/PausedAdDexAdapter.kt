package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseCountdownAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseRequestCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PauseResponseAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method

/** 定位暂停广告倒计时协程与 16 参请求入口；保留逐版本快路径与全结构验真。 */
internal object PausedAdDexAdapter {
    const val DIAGNOSTIC_COUNTDOWN = "dex.assist.paused_ad_countdown"
    const val DIAGNOSTIC_REQUEST_API = "dex.assist.paused_ad_request_api"
    val queryCountdown = DexAssistQuery.PAUSED_AD_COUNTDOWN
    val queryRequestApi = DexAssistQuery.PAUSED_AD_REQUEST_API

    const val PAUSED_SERVICE = "com.bilibili.ship.theseus.united.page.pausedpage.PausedPageService"
    const val DEFAULT_COUNTDOWN = "$PAUSED_SERVICE\$showPauseBarCountdownToast\$3"
    const val SOURCE_INTERFACE = "kntr.common.ad.source.IKSourceContent"
    const val REPORTABLE_INTERFACE = "kntr.common.ad.capability.report.IReportable"
    private const val PAUSED_PACKAGE = "com.bilibili.ship.theseus.united.page.pausedpage."
    private const val MAX_CANDIDATES = 32

    fun applicableCountdown(loader: ClassLoader): Boolean = Lookup.hasClass(loader, PAUSED_SERVICE)

    fun allowedCountdownOwner(name: String): Boolean = name.length in 1..512 &&
        (name.startsWith(PAUSED_PACKAGE) || PlayerSpeedMenuAdapter.flattenedOwner(name))

    fun directCountdown(loader: ClassLoader): HookPoint? {
        val service = Lookup.classOrNull(loader, PAUSED_SERVICE) ?: return null
        val owner = Lookup.classOrNull(loader, DEFAULT_COUNTDOWN) ?: return null
        return PauseCountdownAdapter.resolve(loader, owner, service)?.entry?.toHookPoint()
    }

    fun resolveCountdown(loader: ClassLoader, cached: HookPoint?): HookPoint? {
        val point = cached ?: return null
        if (point.viewField != null || point.methodName != "invokeSuspend" ||
            !allowedCountdownOwner(point.className)
        ) {
            return null
        }
        val service = Lookup.classOrNull(loader, PAUSED_SERVICE) ?: return null
        val owner = Lookup.classOrNull(loader, point.className) ?: return null
        val entry = PauseCountdownAdapter.resolve(loader, owner, service)?.entry ?: return null
        if (point.paramClassNames != null &&
            entry.parameterTypes.map(Class<*>::getName) != point.paramClassNames
        ) {
            return null
        }
        return entry.toHookPoint()
    }

    fun needsCountdownQuery(
        loader: ClassLoader,
        direct: HookPoint?,
        cached: HookPoint?,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && applicableCountdown(loader) &&
        direct == null && directCountdown(loader) == null && resolveCountdown(loader, cached) == null

    fun locateCountdown(
        loader: ClassLoader,
        session: DexAssistSession?,
        enabled: Boolean,
        direct: HookPoint?,
        cached: HookPoint?,
        planned: Boolean
    ): HookPoint? {
        if (!enabled) return cached
        direct?.takeIf { resolveCountdown(loader, it) != null }?.let { return it }
        directCountdown(loader)?.let { return it }
        resolveCountdown(loader, cached)?.let { return it }
        if (!planned) return null
        val service = Lookup.classOrNull(loader, PAUSED_SERVICE) ?: return null
        val candidates = (session?.result(queryCountdown) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.size > MAX_CANDIDATES) return null
        return candidates.asSequence()
            .filter { allowedCountdownOwner(it.declaringClass.name) }
            .distinctBy { it.declaringClass.name }
            .mapNotNull { PauseCountdownAdapter.resolve(loader, it.declaringClass, service)?.entry }
            .singleOrNull()
            ?.toHookPoint()
    }

    fun applicableRequestApi(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, PauseResponseAdapter.KOTLIN_MOSS) &&
            Lookup.hasClass(loader, "${PauseResponseAdapter.PACKAGE}.KTabType")

    fun needsRequestApiQuery(
        loader: ClassLoader,
        hostVersionCode: Int?,
        cached: HookPoint?,
        enabled: Boolean,
        attempted: Boolean
    ): Boolean = enabled && !attempted && applicableRequestApi(loader) &&
        PauseRequestCoverage.resolveApiEntry(loader, cached) == null &&
        !PauseRequestCoverage.hasVerifiedApiEntry(loader, hostVersionCode, cachedApiEntry = null)

    fun locateRequestApi(
        loader: ClassLoader,
        hostVersionCode: Int?,
        session: DexAssistSession?,
        enabled: Boolean,
        cached: HookPoint?,
        planned: Boolean
    ): HookPoint? {
        if (!enabled) return cached
        if (PauseRequestCoverage.hasVerifiedApiEntry(loader, hostVersionCode, cachedApiEntry = null)) {
            return cached?.takeIf { PauseRequestCoverage.resolveApiEntry(loader, it) != null }
        }
        PauseRequestCoverage.resolveApiEntry(loader, cached)?.let { return cached }
        if (!planned) return null
        val candidates = (session?.result(queryRequestApi) as? DexAssistResult.Candidates)?.methods.orEmpty()
        if (candidates.size > MAX_CANDIDATES) return null
        return candidates.asSequence()
            .filter {
                PauseRequestCoverage.allowedApiOwnerName(it.declaringClass.name) &&
                    PauseRequestCoverage.verifiedApiEntry(it)
            }
            .distinctBy(Method::toGenericString)
            .singleOrNull()
            ?.toHookPoint()
    }

    private fun Method.toHookPoint(): HookPoint = HookPoint(
        className = declaringClass.name,
        methodName = name,
        paramClassNames = parameterTypes.map(Class<*>::getName)
    )
}
