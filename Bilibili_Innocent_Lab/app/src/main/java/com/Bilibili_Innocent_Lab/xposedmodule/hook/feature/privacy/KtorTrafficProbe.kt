package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug builds only: the host's KMP modules send HTTP through a shared Ktor client whose engine is the
 * native ignet stack ("ktor-ignet"), not OkHttp. This logs what such a call looks like — host, path
 * and the NAMES of its query parameters and headers, never any value — so the [IgnetHttpBoundary]
 * can be audited on a device. It changes nothing about the request.
 */
internal object KtorTrafficProbe {
    private const val MAX_LOGGED = 64
    private val IDENTITY_PARAMS = setOf("access_key", "mid", "buvid", "buvid3", "buvid4", "device_id", "session_id",
        "csrf", "csrf_token", "fp_local", "fp_remote")
    private val logged = ConcurrentHashMap.newKeySet<String>()

    fun install(env: HookEnvironment): Int = if (!BuildConfig.DEBUG) 0 else runCatching {
        val loader = requireNotNull(env.classLoader)
        val host = PrivacyHostAccess(loader)
        val statement = host.type("io.ktor.client.statement.HttpStatement")
        val constructor = requireNotNull(Lookup.constructorOrNull(
            statement, host.type("io.ktor.client.request.HttpRequestBuilder"), host.type("io.ktor.client.HttpClient"))) {
            "HttpStatement constructor"
        }
        env.registrar.constructor("incognito.ktor.probe", constructor) {
            after {
                if (hasThrowable || logged.size >= MAX_LOGGED) return@after
                runCatching {
                    val builder = argOrNull(0) ?: return@after
                    val url = requireNotNull(host.call(builder, "getUrl"))
                    // URLBuilder.toString() is the full URL; keep only host + path and the parameter NAMES.
                    val full = url.toString()
                    val target = full.substringAfter("://").substringBefore('?').substringBefore('#')
                    val query = full.substringAfter('?', "").substringBefore('#')
                    val queryNames = query.split('&').filter { it.isNotEmpty() }.map { pair ->
                        val name = pair.substringBefore('=').lowercase()
                        // For identity-like parameters report only whether a value is present, never the value.
                        if (name in IDENTITY_PARAMS) name + if (pair.substringAfter('=', "").isEmpty()) ":empty" else ":set" else name
                    }.sorted().joinToString(",")
                    val headerNames = (host.call(requireNotNull(host.call(builder, "getHeaders")), "names") as Set<*>)
                        .map { it.toString().lowercase() }.sorted().joinToString(",")
                    val summary = "$target query=[$queryNames] headers=[$headerNames]"
                    if (logged.add(summary)) env.logInfo("ktor_probe:$summary", "[BIL] KTOR request: $summary")
                }.onFailure {
                    env.logError("ktor_probe:${it.javaClass.simpleName}",
                        "[BIL] Ktor probe could not read a request: ${it.javaClass.simpleName}: ${it.message.orEmpty().take(80)}")
                }
            }
        }
        env.reportStatus("ktor_probe", "registered")
        1
    }.getOrElse {
        env.reportStatus("ktor_probe", "unavailable:${it.javaClass.simpleName}:${it.message.orEmpty().take(60)}")
        0
    }
}
