package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method

/** Client eligibility only. Language tracks and generation remain owned by the official server. */
internal class PlayerAiSubtitleFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-classloader")
        var installed = 0
        var languageGateHooked = false
        KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.dd.DeviceDecision")?.let { owner ->
            KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                !it.isStatic && it.name == "getBoolean" && it.returnType == classOf<Boolean>() &&
                    it.parameterCount in 2..3 &&
                    it.parameterTypes[0] == classOf<String>() &&
                    it.parameterTypes[1] == classOf<Boolean>()
            }.forEach { method ->
                val hookId = if (method.parameterCount == 2) "player.subtitle.ai.language"
                else "player.subtitle.ai.language.${method.parameterCount}"
                if (registerLanguageFlagHook(environment, hookId, method, keyIndex = 0)) {
                    languageGateHooked = true
                }
            }
        }
        KavaMemberLookup.classOrNull(loader, "kntr.base.config.KConfig")?.let { owner ->
            KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                !it.isStatic && it.name == "ff" && it.returnType == classOf<Boolean>() &&
                    it.parameterTypes.contentEquals(arrayOf(classOf<String>(), classOf<Boolean>()))
            }.singleOrNull()?.let { method ->
                if (registerLanguageFlagHook(environment, "player.subtitle.ai.kconfig", method, keyIndex = 0)) {
                    languageGateHooked = true
                }
            }
        }
        KavaMemberLookup.classOrNull(loader, "kntr.base.dd.IDeviceDecisionKt")?.let { owner ->
            KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                it.isStatic && it.name == "getBool" && it.returnType == classOf<Boolean>() &&
                    it.parameterCount in 3..4 &&
                    it.parameterTypes[1] == classOf<String>() &&
                    it.parameterTypes[2] == classOf<Boolean>()
            }.forEach { method ->
                if (registerLanguageFlagHook(
                        environment,
                        "player.subtitle.ai.kotlin_dd.${method.parameterCount}",
                        method,
                        keyIndex = 1
                    )
                ) {
                    languageGateHooked = true
                }
            }
        }
        if (languageGateHooked) installed++
        KavaMemberLookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.Video\$DanmakuResolveParams")?.let { owner ->
            val request = KavaMemberLookup.methodOrNull(owner, "getRequestSubtitle")
                ?.takeIf { it.returnType == classOf<Boolean>() }
            val from = KavaMemberLookup.methodOrNull(owner, "getFrom")?.takeIf { it.returnType == classOf<String>() }
            val avid = KavaMemberLookup.methodOrNull(owner, "getAvid")?.takeIf { it.returnType == classOf<Long>() }
            val cid = KavaMemberLookup.methodOrNull(owner, "getCid")?.takeIf { it.returnType == classOf<Long>() }
            if (request != null && from != null && avid != null && cid != null) {
                environment.registrar.exact("player.subtitle.ai.request", owner, request.name) {
                    after {
                        if (hasThrowable) return@after
                        val params = instance ?: return@after
                        if (!eligible(from.invoke(params) as? String, avid.invoke(params) as Long, cid.invoke(params) as Long)) return@after
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        if (result == false) {
                            result = true
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        }
                    }
                }
                installed++
            }
        }
        if (installed == 0) return FeatureInstallResult.Skipped("missing-ai-subtitle-client-gates")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(installed, complete = installed == 2)
    }

    private fun registerLanguageFlagHook(
        environment: HookEnvironment,
        hookId: String,
        method: Method,
        keyIndex: Int
    ): Boolean = runCatching {
        environment.registrar.exact(hookId, method.declaringClass, method.name, *method.parameterTypes) {
            after {
                if (hasThrowable || argOrNull(keyIndex) != LANGUAGE_FLAG) return@after
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                if (result == false) {
                    result = true
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }
            }
        }
        true
    }.getOrDefault(false)

    companion object {
        const val ID = "player_ai_subtitle"
        const val LANGUAGE_FLAG = "ff_player_ai_language_subtitle"
        internal fun eligible(from: String?, avid: Long, cid: Long): Boolean =
            from != null && from != "live" && avid > 0 && cid > 0
    }
}
