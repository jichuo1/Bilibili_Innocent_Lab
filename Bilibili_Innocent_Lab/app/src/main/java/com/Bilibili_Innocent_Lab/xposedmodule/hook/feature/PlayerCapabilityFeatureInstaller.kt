package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerCapabilityAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic

/** 同步/异步播放响应只调整已选客户端能力；不创建播放地址、不更改服务器权限。 */
internal class PlayerCapabilityFeatureInstaller(private val options: PlayerCapabilityOptions) : FeatureInstaller {
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (options.enabled.isEmpty()) {
            environment.reportStatus(CHANNEL, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val handler = KavaMemberLookup.classOrNull(loader, MOSS_HANDLER)?.takeIf { it.isInterface }
        // 每个已选能力的两族同步/异步边界，以及小窗的两个菜单构造槽位。
        val expected = options.enabled.size * FAMILIES.size * 2 + if (options.smallWindow) 2 else 0
        var covered = 0
        var hooks = 0
        for (family in FAMILIES) {
            val access = PlayerCapabilityAccess.resolve(loader, family.reply, family.shared, options.enabled)
            val owner = KavaMemberLookup.classOrNull(loader, family.moss)
            val request = KavaMemberLookup.classOrNull(loader, family.request)
            if (access == null || owner == null || request == null) {
                environment.logError("player_capability_missing_${family.id}",
                    "[BIL] 播放器能力缺少响应结构(${family.id})，保留该链路原行为")
                continue
            }
            fun apply(reply: Any) {
                if (!access.replyClass.isInstance(reply)) return
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                runCatching { access.apply(reply) }.onSuccess { changed ->
                    if (changed > 0) environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, changed)
                }.onFailure {
                    environment.logError("player_capability_runtime_${family.id}",
                        "[BIL] 播放器能力改写失败(${family.id}): ${it.javaClass.simpleName}")
                }
            }
            val sync = listOf("execute${family.method.replaceFirstChar(Char::uppercaseChar)}", family.method)
                .firstNotNullOfOrNull { name ->
                    KavaMemberLookup.methodOrNull(owner, name, request)?.takeIf {
                        !it.isStatic && it.returnType == access.replyClass
                    }
                }
            if (sync != null && runCatching {
                    environment.registrar.exact("player.capability.${family.id}.sync", owner,
                        sync.name, *sync.parameterTypes) {
                        after { if (!hasThrowable) result?.let(::apply) }
                    }
                }.isSuccess) {
                hooks++
                covered += access.capabilityCount
            }
            val async = handler?.let {
                KavaMemberLookup.methodOrNull(owner, family.method, request, it)?.takeIf { method ->
                    !method.isStatic && method.returnType == Void.TYPE
                }
            }
            if (async != null && runCatching {
                    environment.registrar.exact("player.capability.${family.id}.async", owner,
                        async.name, *async.parameterTypes) {
                        before {
                            val delegate = argOrNull(1) ?: return@before
                            val wrapped = MossResponseHandlerProxy.wrap(handler, delegate, ::apply) ?: return@before
                            args[1] = wrapped
                        }
                    }
                }.isSuccess) {
                hooks++
                covered += access.capabilityCount
            }
        }
        if (options.smallWindow) {
            val owner = KavaMemberLookup.classOrNull(loader, MINI_MENU)
            val type = KavaMemberLookup.classOrNull(loader, MINI_TYPE)
            val mini = type?.let { KavaMemberLookup.fieldOrNull(it, "MINIPLAYER") }
                ?.takeIf { it.isStatic && it.type == type }
                ?.let { runCatching { it.get(null) }.getOrNull() }
            if (owner != null && type != null && mini != null) {
                val signatures = listOf(
                    arrayOf(classOf<Boolean>(), type), arrayOf(classOf<Boolean>(), type, classOf<List<*>>())
                )
                signatures.forEachIndexed { index, parameters ->
                    val constructor = KavaMemberLookup.constructorOrNull(owner, *parameters)
                        ?: return@forEachIndexed
                    if (runCatching {
                            environment.registrar.constructor("player.capability.mini.$index", constructor) {
                                before {
                                    if (argOrNull(1) !== mini) return@before
                                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                                    if (argOrNull(0) == false) args[0] = true
                                    // 构造参数写入不是模型读回证据；APPLIED 只由上面的响应验证记录。
                                }
                            }
                        }.isSuccess) {
                        hooks++
                        covered++
                    }
                }
            }
        }
        val status = if (covered == expected) "success" else "partial:$covered/$expected"
        environment.reportStatus(CHANNEL, status)
        if (hooks == 0) return FeatureInstallResult.Skipped("missing-player-capability-points")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.logInfo("player_capability_installed",
            "[BIL] 播放器客户端能力已安装($status, hooks=$hooks)，仅含 ${options.enabled.joinToString { it.name }}")
        return FeatureInstallResult.Installed(hooks)
    }

    private data class Family(val id: String, val moss: String, val request: String,
        val reply: String, val method: String, val shared: Boolean)

    companion object {
        const val ID = "player_capabilities"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL = "player_capabilities_status"
        private const val MOSS_HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
        private const val MINI_MENU = "com.bilibili.lib.media.resource.PlayConfig\$PlayMenuConfig"
        private const val MINI_TYPE = "com.bilibili.lib.media.resource.PlayConfig\$PlayConfigType"
        private val FAMILIES = listOf(
            Family("unite", "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
                "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq",
                "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply", "playViewUnite", true),
            Family("legacy", "com.bapis.bilibili.app.playurl.v1.PlayURLMoss",
                "com.bapis.bilibili.app.playurl.v1.PlayViewReq",
                "com.bapis.bilibili.app.playurl.v1.PlayViewReply", "playView", false)
        )
    }
}
