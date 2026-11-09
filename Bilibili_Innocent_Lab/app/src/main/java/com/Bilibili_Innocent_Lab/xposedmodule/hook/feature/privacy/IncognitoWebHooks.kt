package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.webkit.ServiceWorkerController
import android.webkit.WebSettings
import android.webkit.WebView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import java.util.concurrent.ConcurrentHashMap

/** Chromium bypasses the host OkHttp boundary and can reuse the real profile's cookies.
 * Until it has an independently verified ephemeral profile adapter, embedded pages stay offline. */
internal class IncognitoWebHooks {
    /** WebView anchors are platform classes present on every supported API; prepare never fails. */
    fun prepare(env: HookEnvironment): (() -> Int) = { install(env) }

    fun install(env: HookEnvironment): Int {
        val configuredTypes = ConcurrentHashMap.newKeySet<Class<*>>()
        fun settings(target: Any) {
            val type = target.javaClass
            val setters = listOf("setBlockNetworkLoads" to true, "setCacheMode" to WebSettings.LOAD_NO_CACHE)
            if (configuredTypes.add(type)) {
                try {
                    setters.forEach { (name, value) ->
                        val parameter = if (value is Boolean) classOf<Boolean>() else classOf<Int>()
                        val member = requireNotNull(Lookup.inheritedMethodOrNull(type, name, parameter))
                        env.registrar.exact("incognito.web.settings.${type.name}.$name",
                            member.declaringClass, member.name, parameter) { before { args[0] = value } }
                    }
                } catch (failure: Exception) {
                    configuredTypes.remove(type)
                    throw failure
                }
            }
            // Reapply per entry too: a host attempt to re-enable network cannot open a gap.
            setters.forEach { (name, value) ->
                val parameter = if (value is Boolean) classOf<Boolean>() else classOf<Int>()
                requireNotNull(Lookup.inheritedMethodOrNull(type, name, parameter)).invoke(target, value)
            }
        }
        fun isolate(view: WebView): Boolean = runCatching {
            settings(view.settings)
            settings(ServiceWorkerController.getInstance().serviceWorkerWebSettings)
            env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.APPLIED)
        }.onFailure { env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR) }.isSuccess

        val web = classOf<WebView>()
        var count = 0
        Lookup.declaredConstructors(web).forEachIndexed { index, constructor ->
            env.registrar.constructor("incognito.web.constructor.$index", constructor) {
                after { if (!hasThrowable) (instance as? WebView)?.let(::isolate) }
            }
            count++
        }
        Lookup.declaredMethods(web) { it.name in setOf("loadUrl", "postUrl", "loadData",
            "loadDataWithBaseURL", "evaluateJavascript", "reload", "goBack", "goForward", "goBackOrForward") }.forEach { method ->
            env.registrar.exact("incognito.web.entry.${method.name}.${method.parameterCount}",
                web, method.name, *method.parameterTypes) {
                before {
                    val view = instance as? WebView
                    if (view == null || !isolate(view)) result = null
                }
            }
            count++
        }
        return count
    }
}
