package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.highcapable.kavaref.extension.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The host's KMP modules send HTTP through Ktor on the native "ktor-ignet" engine, which hands every
 * request to `com.bilibili.lib.ighttp.IgHttpRequest` — never to OkHttp, so the OkHttp boundary never
 * sees it. On 9.16.1 an incognito session still sent KMP Neuron telemetry and play reports this way.
 *
 * Two hooks, both on the stable, un-obfuscated ighttp Java API (present on all 38 supported builds):
 * - `Builder.build()` seals the URL and headers before the native request is created from them
 *   (the URL is copied into native memory by the constructor, so this is the last point to change it);
 * - `IgHttpRequest.execute()` lets through only requests that were sealed here and that
 *   [PrivacyNetworkPolicy.blockHttp] allows; everything else ends with an IOException the host
 *   already handles. That hook delivers its exception to the host on purpose: under the default
 *   protective mode a thrown exception would run the original request instead.
 */
internal class IgnetHttpBoundary(
    private val session: IncognitoSession,
    private val isolationReady: () -> Boolean
) {
    /** Null when ighttp exists but cannot be fully routed; the caller must then refuse incognito. */
    fun prepare(env: HookEnvironment): (() -> Int)? = runCatching {
        val loader = requireNotNull(env.classLoader)
        val request = Lookup.classOrNull(loader, REQUEST) ?: return@runCatching {
            env.reportStatus("incognito_transport_ignet_http", "absent")
            0
        }
        val builder = requireNotNull(Lookup.classOrNull(loader, "$REQUEST\$Builder")) { "ighttp builder" }
        val build = requireNotNull(Lookup.methodOrNull(builder, "build")) { "ighttp build" }
        require(build.returnType == request) { "ighttp build shape" }
        val setUrl = requireNotNull(Lookup.methodOrNull(builder, "url", classOf<String>())) { "ighttp url setter" }
        val urlField = requireNotNull(Lookup.fieldOrNull(builder, "mUrl")) { "ighttp url field" }
        val headersField = requireNotNull(Lookup.fieldOrNull(builder, "mHeaders")) { "ighttp headers field" }
        val execute = requireNotNull(Lookup.methodOrNull(request, "execute")) { "ighttp execute" }
        val getUrl = requireNotNull(Lookup.methodOrNull(request, "url")) { "ighttp url" }
        val getMethod = requireNotNull(Lookup.methodOrNull(request, "method")) { "ighttp method" }
        val close = requireNotNull(Lookup.methodOrNull(request, "close")) { "ighttp close" }
        val signer = requireNotNull(Lookup.classOrNull(loader, "com.bilibili.nativelibrary.LibBili")) { "query signer" }
        val signQuery = requireNotNull(Lookup.methodOrNull(signer, "signQuery", classOf<Map<*, *>>())) { "signQuery" }
        // Requests sealed by build(); weak so a request the host drops is never kept alive here.
        val sealed: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap())
        val seen = ConcurrentHashMap.newKeySet<String>()
        val gameTrackerEvidence = java.util.concurrent.atomic.AtomicBoolean()
        val adMeasurementEvidence = java.util.concurrent.atomic.AtomicBoolean()

        return@runCatching {
            env.registrar.exact("incognito.ignet.http.build", builder, build.name) {
                before {
                    val target = instance ?: return@before
                    runCatching {
                        val identity = requireNotNull(session.identity())
                        val url = urlField.get(target) as String
                        val clean = IgnetHttpSanitizer.url(url, identity) { requireNotNull(signQuery.invoke(null, it)).toString() }
                        if (clean != url) setUrl.invoke(target, clean)
                        @Suppress("UNCHECKED_CAST")
                        IgnetHttpSanitizer.headers(headersField.get(target) as MutableMap<String, String>, identity)
                        setObjectExtra(SEALED, true)
                    }.onFailure {
                        // Not marked sealed, so execute() withholds it.
                        env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                    }
                }
                after {
                    if (!hasThrowable && getObjectExtra(SEALED) == true) result?.let { sealed[it] = true }
                }
            }
            env.registrar.adapted("incognito.ignet.http.execute",
                VersionAdapter.HookPoint(REQUEST, execute.name, emptyList()), HookExceptionPolicy.DELIVER_TO_HOST) {
                before {
                    // Only the deliberate IOException may leave this callback: every other failure
                    // is folded into "withhold", never into "send".
                    val target = instance
                    val send = runCatching {
                        if (target == null || !isolationReady() || sealed.remove(target) != true) return@runCatching false
                        val (host, path) = IgnetHttpSanitizer.target(getUrl.invoke(target) as String)
                        val allowed = !PrivacyNetworkPolicy.blockHttp(host, path, getMethod.invoke(target) as String)
                        if (!allowed && PrivacyNetworkPolicy.isGameTrackerReport(host, path) &&
                            gameTrackerEvidence.compareAndSet(false, true)) {
                            runCatching { env.reportStatus("incognito_game_tracker_report", "withheld-ignet") }
                        }
                        if (!allowed && PrivacyNetworkPolicy.isAdMeasurementBeacon(host, path) &&
                            adMeasurementEvidence.compareAndSet(false, true)) {
                            runCatching { env.reportStatus("incognito_ad_measurement_ignet", "withheld") }
                        }
                        val target = PrivacyNetworkPolicy.diagnosticTarget(host, path)
                        if (allowed && BuildConfig.DEBUG && seen.size < MAX_SEEN && seen.add(target)) {
                            env.logInfo("incognito_ignet_seen:$target", "[BIL] ignet HTTP sealed and sent: $target")
                        }
                        if (!allowed && BuildConfig.DEBUG && seen.size < MAX_SEEN && seen.add("blocked:$target")) {
                            env.logInfo("incognito_ignet_blocked:$target", "[BIL] ignet HTTP withheld: $target")
                        }
                        allowed
                    }.getOrDefault(false)
                    if (send) return@before
                    runCatching {
                        target?.let { close.invoke(it) }
                        env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.APPLIED)
                        env.reportStatus("incognito_transport_ignet_http", "withheld")
                    }
                    throwable = IOException("Request withheld by the incognito session")
                }
            }
            env.reportStatus("incognito_transport_ignet_http", "sealed")
            2
        }
    }.onFailure {
        env.logError("incognito_ignet_http", "[BIL] ighttp boundary unavailable: ${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}")
    }.getOrNull()

    private companion object {
        const val REQUEST = "com.bilibili.lib.ighttp.IgHttpRequest"
        const val SEALED = "bil.ignet.sealed"
        const val MAX_SEEN = 64
    }
}
