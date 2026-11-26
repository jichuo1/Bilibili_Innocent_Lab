package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PrivacyChannelAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
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
 * Two hooks, both on the stable ighttp Java API (or [cachedRequest] fallback when obfuscated):
 * - `Builder.build()` seals the URL, headers, `mBody` and `mStreamDataProvider` before native copy;
 * - `IgHttpRequest.execute()` lets through only requests sealed here and allowed by [PrivacyNetworkPolicy.blockHttp].
 */
internal class IgnetHttpBoundary(
    private val session: IncognitoSession,
    private val cachedRequest: VersionAdapter.HookPoint? = null,
    private val isolationReady: () -> Boolean
) {
    /** Null when ighttp exists (or appears present on the host) but cannot be fully routed; the caller refuses incognito. */
    fun prepare(env: HookEnvironment): (() -> Int)? = runCatching {
        val loader = requireNotNull(env.classLoader)
        val members = PrivacyChannelAdapter.resolveIgHttpRequest(loader, cachedRequest)
        if (members == null) {
            if (hasIgHttpSurface(loader)) {
                error("ighttp request unavailable on KMP/ighttp host")
            }
            return@runCatching {
                env.reportStatus("incognito_transport_ignet_http", "absent")
                0
            }
        }
        val request = members.request
        val builder = members.builder
        val build = members.build
        val setUrl = members.setUrl
        val urlField = members.urlField
        val headersField = members.headersField
        val bodyField = members.bodyField
        val streamField = members.streamField
        val produce = members.produce
        val execute = members.execute
        val getUrl = members.getUrl
        val getMethod = members.getMethod
        val close = members.close
        val signer = requireNotNull(Lookup.classOrNull(loader, "com.bilibili.nativelibrary.LibBili")) { "query signer" }
        val signQuery = requireNotNull(Lookup.methodOrNull(signer, "signQuery", classOf<Map<*, *>>())) { "signQuery" }
        val signFn: (Map<String, String>) -> String = { params -> requireNotNull(signQuery.invoke(null, params)).toString() }
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
                        val cleanUrl = IgnetHttpSanitizer.url(url, identity, signFn)
                        if (cleanUrl != url) setUrl.invoke(target, cleanUrl)
                        @Suppress("UNCHECKED_CAST")
                        val headers = headersField.get(target) as MutableMap<String, String>
                        val contentType = IgnetHttpSanitizer.findHeaderCaseInsensitive(headers, "content-type")
                        IgnetHttpSanitizer.headers(headers, identity)

                        val rawBody = bodyField.get(target) as ByteArray?
                        if (rawBody != null) {
                            val cleanBody = IgnetHttpSanitizer.formBody(rawBody, contentType, identity, signFn)
                            if (cleanBody !== rawBody) {
                                bodyField.set(target, cleanBody)
                                IgnetHttpSanitizer.updateContentLengthIfPresent(headers, cleanBody.size)
                            }
                        }

                        if (streamField != null) {
                            val stream = streamField.get(target)
                            if (stream != null) {
                                require(streamField.type.isInterface && produce != null) { "ighttp stream provider shape" }
                                val wrapped = IgnetHttpSanitizer.wrapStreamProvider(
                                    loader,
                                    streamField.type,
                                    stream,
                                    produce,
                                    contentType,
                                    identity,
                                    signFn
                                )
                                streamField.set(target, wrapped)
                                // Sanitized streamed bytes may differ in length from the pre-sanitized form.
                                IgnetHttpSanitizer.removeHeaderCaseInsensitive(headers, "content-length")
                            }
                        }
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
                VersionAdapter.HookPoint(request.name, execute.name, emptyList()), HookExceptionPolicy.DELIVER_TO_HOST) {
                before {
                    // Only the deliberate IOException may leave this callback: every other failure
                    // is folded into "withhold", never into "send".
                    val req = instance
                    val send = runCatching {
                        if (req == null || !isolationReady() || sealed.remove(req) != true) return@runCatching false
                        val (host, path) = IgnetHttpSanitizer.target(getUrl.invoke(req) as String)
                        val allowed = !PrivacyNetworkPolicy.blockHttp(host, path, getMethod.invoke(req) as String)
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
                        req?.let { close.invoke(it) }
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

    private fun hasIgHttpSurface(loader: ClassLoader): Boolean =
        Lookup.hasClass(loader, PrivacyChannelAdapter.IGNET_RESPONSE) ||
            Lookup.hasClass(loader, PrivacyChannelAdapter.IGNET_ENGINE)

    private companion object {
        const val SEALED = "bil.ignet.sealed"
        const val MAX_SEEN = 64
    }
}
