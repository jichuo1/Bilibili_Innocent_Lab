package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isAbstract
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 播放器编码偏好与硬/软解覆盖。请求改写和 ijk option 改写均为 copy-on-write/原地键覆盖，
 * 不做 DEX 扫描、I/O、IPC 或逐帧 Hook；所有定位失败都只影响本功能。
 */
internal class PlayerCodecForceFeatureInstaller(
    codecPreferenceValue: Int,
    decodeModeValue: Int
) : FeatureInstaller {
    override val id: String = ID
    private val preference = PlayerCodecPreference.fromValue(codecPreferenceValue)
    private val decodeMode = PlayerDecodeMode.fromValue(decodeModeValue)

    /** 请求侧实际使用的偏好：AV1 × 强制软解已按硬约束降级为 H.264。 */
    private val requestPreference = PlayerCodecForcePolicy.effectivePreference(preference, decodeMode)

    /** 跟随宿主 + 强制软解：请求侧只清 AV1 能力位，这段改写属于"解码方式"一项。 */
    private val stripAv1Only = PlayerCodecForcePolicy.stripsAv1Only(preference, decodeMode)
    override val capabilityIds: List<String> = buildList {
        if (preference != PlayerCodecPreference.FOLLOW_HOST) add(CODEC_CAPABILITY)
        if (decodeMode != PlayerDecodeMode.FOLLOW_HOST) add(DECODE_CAPABILITY)
    }

    private data class InstallCoverage(val available: Int = 0, val installed: Int = 0)

    private data class RequestResolution(
        val owner: Class<*>,
        val request: Class<*>,
        val sync: Method?,
        val async: Method?
    )

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (preference == PlayerCodecPreference.FOLLOW_HOST && decodeMode == PlayerDecodeMode.FOLLOW_HOST) {
            environment.reportStatus(STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        val loader = environment.classLoader ?: run {
            capabilityIds.forEach { capability ->
                environment.reportCapability(
                    capability,
                    FeatureInstallResult.Skipped("missing-class-loader")
                )
            }
            return skipped(environment, "missing-class-loader")
        }
        if (requestPreference != preference) {
            environment.logInfo(
                "$ID.downgraded",
                "[BIL] AV1 与强制软解不可同时使用，编码偏好已降级为 H.264"
            )
        }
        val requestCapability = if (stripAv1Only) DECODE_CAPABILITY else CODEC_CAPABILITY
        val requestCoverage = if (requestPreference != PlayerCodecPreference.FOLLOW_HOST || stripAv1Only) {
            listOf(
                installRequestFamily(
                    environment, loader,
                    MOSS_CLASSES_UNITE, REQUEST_CLASSES_UNITE,
                    setOf("executePlayViewUnite", "playViewUnite", "executePlayView", "playView"),
                    nestedVod = true,
                    capability = requestCapability
                ),
                installRequestFamily(
                    environment, loader,
                    MOSS_CLASSES_LEGACY, REQUEST_CLASSES_LEGACY,
                    setOf("executePlayView", "playView"),
                    nestedVod = false,
                    capability = requestCapability
                ),
                installKotlinRequestFamily(environment, loader, MOSS_CLASSES_UNITE.first(), REQUEST_CLASSES_UNITE.first(),
                    "playViewUnite", nestedVod = true, capability = requestCapability),
                installKotlinRequestFamily(environment, loader, MOSS_CLASSES_LEGACY.first(), REQUEST_CLASSES_LEGACY.first(),
                    "playView", nestedVod = false, capability = requestCapability)
            ).fold(InstallCoverage()) { total, current ->
                InstallCoverage(total.available + current.available, total.installed + current.installed)
            }
        } else InstallCoverage()
        val optionCoverage = if (decodeMode != PlayerDecodeMode.FOLLOW_HOST) {
            installDecodePaths(environment, loader)
        } else InstallCoverage()
        // 强制软解的请求侧 AV1 清位与 ijk 选项改写同属"解码方式"，一并计入它的覆盖单位。
        val decodeCoverage = if (stripAv1Only) {
            InstallCoverage(optionCoverage.available + requestCoverage.available,
                optionCoverage.installed + requestCoverage.installed)
        } else optionCoverage
        if (!stripAv1Only) reportCapabilityCoverage(environment, CODEC_CAPABILITY, requestCoverage)
        reportCapabilityCoverage(environment, DECODE_CAPABILITY, decodeCoverage)

        val installed = requestCoverage.installed + optionCoverage.installed
        val expected = requestCoverage.available + optionCoverage.available
        if (installed == 0) return skipped(environment, "missing-host-structure")
        if (requestCoverage.installed > 0 && !stripAv1Only) {
            environment.reportRuntimeEvidence(CODEC_CAPABILITY, FeatureRuntimeStage.ADAPTED)
        }
        if (decodeCoverage.installed > 0) {
            environment.reportRuntimeEvidence(DECODE_CAPABILITY, FeatureRuntimeStage.ADAPTED)
        }
        val status = if (expected > 0 && installed == expected) "success" else "partial:$installed/$expected"
        environment.reportStatus(STATUS, status)
        return FeatureInstallResult.Installed(installed, complete = expected > 0 && installed == expected)
    }

    private fun installRequestFamily(
        environment: HookEnvironment,
        loader: ClassLoader,
        mossCandidates: List<String>,
        requestCandidates: List<String>,
        methodNames: Set<String>,
        nestedVod: Boolean,
        capability: String
    ): InstallCoverage = runCatching {
        val handlerClass = KavaMemberLookup.classOrNull(loader, MOSS_HANDLER)?.takeIf { it.isInterface }
        val resolved = mossCandidates.asSequence().flatMap { mossName ->
            requestCandidates.asSequence().mapNotNull { requestName ->
                val owner = KavaMemberLookup.classOrNull(loader, mossName) ?: return@mapNotNull null
                val request = KavaMemberLookup.classOrNull(loader, requestName) ?: return@mapNotNull null
                val sync = KavaMemberLookup.methods(owner, includeSuperclasses = true, makeAccessible = true) {
                    it.name in methodNames && it.parameterTypes.contentEquals(arrayOf(request)) &&
                        !Modifier.isStatic(it.modifiers) && !it.returnType.isPrimitive
                }.singleOrNull()
                val async = handlerClass?.let { handler ->
                    KavaMemberLookup.methods(owner, includeSuperclasses = true, makeAccessible = true) {
                        it.name in methodNames &&
                            it.parameterTypes.contentEquals(arrayOf(request, handler)) &&
                            it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
                    }.singleOrNull()
                }
                if (sync == null && async == null) return@mapNotNull null
                RequestResolution(owner, request, sync, async)
            }
        }.firstOrNull() ?: return InstallCoverage()
        val access = RequestAccess.resolve(resolved.request, nestedVod, requestPreference)
            ?: return InstallCoverage(listOfNotNull(resolved.sync, resolved.async).size, 0)
        var available = 0
        var installed = 0
        resolved.sync?.let { method ->
            available++
            if (registerRequestHook(
                    environment,
                    capability,
                    "$ID.request.${resolved.request.simpleName}",
                    resolved.owner,
                    method,
                    access
                )
            ) installed++
        }
        resolved.async?.let { method ->
            available++
            if (registerRequestHook(
                    environment,
                    capability,
                    "$ID.request.${resolved.request.simpleName}.async",
                    resolved.owner,
                    method,
                    access
                )
            ) installed++
        }
        InstallCoverage(available, installed)
    }.getOrElse {
        environment.logError("$ID.request", "[BIL] 编码偏好入口解析/注册失败")
        InstallCoverage()
    }

    /**
     * K*Moss typed request names and fields are R8 aliases. Its generic callback entry is
     * shared by suspend/callback calls and supplies the actual request serializer. Reuse
     * the host's wire codec and the same Java copy-on-write policy for both channels.
     */
    private fun installKotlinRequestFamily(environment: HookEnvironment, loader: ClassLoader,
        javaMoss: String, javaRequest: String, rpc: String, nestedVod: Boolean, capability: String): InstallCoverage {
        val owner = KotlinMossBridgeMembers.kotlinMossClassName(javaMoss)
            ?.let { KavaMemberLookup.classOrNull(loader, it) } ?: return InstallCoverage()
        val missing = InstallCoverage(1, 0)
        val entry = KavaMemberLookup.declaredMethods(owner, true) { method ->
            val types = method.parameterTypes
            method.name == rpc && !method.isStatic && !method.isAbstract &&
                method.returnType == Void.TYPE && types.size in 4..5 && types[0] == classOf<Any>() &&
                types[1].name == KotlinMossBridgeMembers.SERIALIZATION_STRATEGY &&
                types[2].name == KotlinMossBridgeMembers.DESERIALIZATION_STRATEGY &&
                !types[3].isPrimitive && !types[3].isArray &&
                (types.size == 4 || types[4].name == KotlinMossBridgeMembers.PROTO_BUF)
        }.singleOrNull() ?: return missing
        val request = KavaMemberLookup.classOrNull(loader, javaRequest) ?: return missing
        val access = RequestAccess.resolve(request, nestedVod, requestPreference) ?: return missing
        val members = KotlinMossBridgeMembers.resolve(loader) ?: return missing
        val codec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(request) ?: return missing
        // 8.84–9.8 use the host moss service's ProtoBuf.Default; 9.9+ supply the format.
        if (entry.parameterCount == 4 && members.defaultProtoBuf() == null) return missing
        val errorLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        val installed = runCatching {
            environment.registrar.exact("$ID.request.kotlin.$rpc", owner, entry.name, *entry.parameterTypes) {
                before {
                    val original = argOrNull(0) ?: return@before
                    runCatching {
                        val format = if (entry.parameterCount == 4) members.defaultProtoBuf() else argOrNull(4)
                        val bridge = members.bridgeFor(format, argOrNull(1), codec)
                            ?: error("Missing request serializer roundtrip")
                        environment.reportRuntimeEvidence(capability, FeatureRuntimeStage.OBSERVED)
                        val updated = bridge.transform(original) { value -> access.rewrite(value, requestPreference) ?: value }
                        check(original.javaClass.isInstance(updated))
                        if (updated !== original) {
                            args[0] = updated
                            environment.reportRuntimeEvidence(capability, FeatureRuntimeStage.APPLIED)
                        }
                    }.onFailure {
                        environment.reportRuntimeEvidence(capability, FeatureRuntimeStage.ERROR)
                        if (errorLogged.compareAndSet(false, true)) environment.logError("$ID.kotlin.$rpc",
                            "[BIL] Kotlin 播放请求编码偏好改写失败，保留原请求")
                    }
                }
            }
        }.onFailure { environment.logError("$ID.kotlin.$rpc", "[BIL] Kotlin 播放请求入口注册失败") }.isSuccess
        return InstallCoverage(1, if (installed) 1 else 0)
    }

    private fun registerRequestHook(
        environment: HookEnvironment,
        capability: String,
        hookId: String,
        owner: Class<*>,
        method: Method,
        access: RequestAccess
    ): Boolean = runCatching {
        environment.registrar.exact(hookId, owner, method.name, *method.parameterTypes) {
            before {
                val original = argOrNull(0) ?: return@before
                environment.reportRuntimeEvidence(capability, FeatureRuntimeStage.OBSERVED)
                access.rewrite(original, requestPreference)?.let { updated ->
                    args[0] = updated
                    environment.reportRuntimeEvidence(capability, FeatureRuntimeStage.APPLIED)
                }
            }
        }
    }.onFailure {
        environment.logError(hookId, "[BIL] 编码偏好入口解析/注册失败")
    }.isSuccess

    private fun installDecodePaths(environment: HookEnvironment, loader: ClassLoader): InstallCoverage {
        val owner = IJK_CLIENT_CLASSES.firstNotNullOfOrNull { KavaMemberLookup.classOrNull(loader, it) }
            ?: return InstallCoverage()
        var available = 0
        var installed = 0
        val bundleMethod = KavaMemberLookup.methods(owner, includeSuperclasses = true, makeAccessible = true) {
            it.name == "setOptionBundle" && it.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType, Bundle::class.java)
            ) && !Modifier.isStatic(it.modifiers)
        }.singleOrNull()
        if (bundleMethod != null) {
            available++
            if (runCatching {
                    environment.registrar.exact("$ID.bundle", owner, bundleMethod.name, *bundleMethod.parameterTypes) {
                        before {
                            if ((argOrNull(0) as? Number)?.toInt() != 4) return@before
                            val bundle = argOrNull(1) as? Bundle ?: return@before
                            if (!PlayerCodecForcePolicy.OPTION_KEYS.any(bundle::containsKey)) return@before
                            environment.reportRuntimeEvidence(DECODE_CAPABILITY, FeatureRuntimeStage.OBSERVED)
                            val rewrite = PlayerCodecForcePolicy.rewriteBundle(bundle, decodeMode)
                                ?: return@before
                            args[1] = rewrite.bundle
                            environment.reportRuntimeEvidence(
                                DECODE_CAPABILITY,
                                FeatureRuntimeStage.APPLIED,
                                rewrite.changed
                            )
                        }
                    }
                }.isSuccess) installed++
        }

        val optionMethods = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            it.name == "_setOption" && it.parameterCount == 3 && !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == String::class.java &&
                (it.parameterTypes[2] == Long::class.javaPrimitiveType || it.parameterTypes[2] == String::class.java)
        }.distinctBy(Method::toGenericString)
        optionMethods.forEachIndexed { index, method ->
            available++
            if (runCatching {
                    environment.registrar.exact("$ID.option.$index", owner, method.name, *method.parameterTypes) {
                        before {
                            if ((argOrNull(0) as? Number)?.toInt() != 4) return@before
                            val key = argOrNull(1) as? String ?: return@before
                            val target = PlayerCodecForcePolicy.optionValue(key, decodeMode) ?: return@before
                            val current = argOrNull(2)
                            environment.reportRuntimeEvidence(DECODE_CAPABILITY, FeatureRuntimeStage.OBSERVED)
                            val currentValue = PlayerCodecForcePolicy.recognizedOptionValue(key, current)
                                ?: return@before
                            if (currentValue == target) return@before
                            when (current) {
                                is Long -> args[2] = target
                                is String -> args[2] = target.toString()
                                else -> return@before
                            }
                            environment.reportRuntimeEvidence(DECODE_CAPABILITY, FeatureRuntimeStage.APPLIED)
                        }
                    }
                }.isSuccess) installed++
        }
        return InstallCoverage(available, installed)
    }

    private fun reportCapabilityCoverage(
        environment: HookEnvironment,
        capability: String,
        coverage: InstallCoverage
    ) {
        if (capability !in capabilityIds) return
        val result = when {
            coverage.available <= 0 -> FeatureInstallResult.Skipped("missing-host-structure")
            coverage.installed <= 0 -> FeatureInstallResult.Skipped("registration-failed")
            else -> FeatureInstallResult.Installed(
                coverage.installed,
                complete = coverage.installed == coverage.available
            )
        }
        environment.reportCapability(capability, result)
    }

    private fun skipped(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(STATUS, reason)
        return FeatureInstallResult.Skipped(reason)
    }

    private data class RequestAccess(
        val requestPlan: ProtobufBuilderPlan,
        val targetGetter: Method?,
        val targetSetter: Method?,
        val targetPlan: ProtobufBuilderPlan,
        val preferGetter: Method,
        val preferSetter: Method,
        val fnvalGetter: Method,
        val fnvalSetter: Method,
        /** null = 跟随宿主编码，只清 AV1 能力位（强制软解的硬约束）。 */
        val preferredCode: Any?
    ) {
        fun rewrite(original: Any, preference: PlayerCodecPreference): Any? = runCatching {
            val targetValue = targetGetter?.invoke(original) ?: original
            val fnval = (fnvalGetter.invoke(targetValue) as? Number)?.toLong() ?: return null
            val expectedFnval = if (preferredCode == null) {
                PlayerCodecForcePolicy.withoutAv1(fnval)
            } else {
                PlayerCodecForcePolicy.expectedFnval(fnval, preference) ?: return null
            }
            val currentCode = preferGetter.invoke(targetValue)
            if ((preferredCode == null || currentCode == preferredCode) && fnval == expectedFnval) return null
            val updatedTarget = targetPlan.edit(targetValue) { builder ->
                if (preferredCode != null) preferSetter.invoke(builder, preferredCode)
                fnvalSetter.invoke(builder, adaptNumber(expectedFnval, fnvalSetter.parameterTypes[0]))
            }
            if (targetSetter == null) updatedTarget else requestPlan.edit(original) { builder ->
                targetSetter.invoke(builder, updatedTarget)
            }
        }.getOrNull()

        companion object {
            fun resolve(
                request: Class<*>,
                nestedVod: Boolean,
                preference: PlayerCodecPreference
            ): RequestAccess? = runCatching {
                val requestPlan = ProtobufBuilderPlan.resolve(request) ?: return null
                val targetGetter = if (nestedVod) request.methods.firstOrNull { method ->
                    method.name == "getVod" && method.parameterCount == 0 && !Modifier.isStatic(method.modifiers) &&
                        !method.returnType.isPrimitive
                } else null
                val target = targetGetter?.returnType ?: request
                val targetPlan = ProtobufBuilderPlan.resolve(target) ?: return null
                val preferGetter = target.methods.firstOrNull { method ->
                    method.name == "getPreferCodecType" && method.parameterCount == 0 &&
                        !Modifier.isStatic(method.modifiers)
                }
                val codeType = preferGetter?.returnType ?: return null
                val preferSetter = targetPlan.method("setPreferCodecType", codeType) ?: return null
                // 跟随宿主时不改偏好（只清 AV1 位）；其余偏好必须能解析出宿主枚举值。
                val preferredCode = if (preference == PlayerCodecPreference.FOLLOW_HOST) {
                    null
                } else {
                    codeValue(codeType, preference) ?: return null
                }
                val fnvalGetter = target.methods.firstOrNull { method ->
                    method.name == "getFnval" && method.parameterCount == 0 &&
                        Number::class.java.isAssignableFrom(box(method.returnType))
                } ?: return null
                val fnvalSetter = targetPlan.method("setFnval", fnvalGetter.returnType) ?: return null
                val targetSetter = targetGetter?.let { getter ->
                    requestPlan.method("setVod", getter.returnType)
                }
                if (nestedVod && targetSetter == null) return null
                RequestAccess(requestPlan, targetGetter, targetSetter, targetPlan,
                    preferGetter, preferSetter, fnvalGetter, fnvalSetter, preferredCode)
            }.getOrNull()

            private fun box(type: Class<*>): Class<*> = when (type) {
                Long::class.javaPrimitiveType -> Long::class.javaObjectType
                Int::class.javaPrimitiveType -> Int::class.javaObjectType
                else -> type
            }
        }
    }

    companion object {
        const val ID = "player_codec_force"
        private const val CODEC_CAPABILITY = "player_codec_preference"
        private const val DECODE_CAPABILITY = "player_decode_mode"
        private const val STATUS = "player_codec_force_status"
        private const val MOSS_HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
        private val MOSS_CLASSES_UNITE = listOf(
            "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
            "com.bapis.bilibili.app.playerunite.v1.KPlayerMoss"
        )
        private val REQUEST_CLASSES_UNITE = listOf(
            "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq",
            "com.bapis.bilibili.app.playerunite.v1.KPlayViewUniteReq"
        )
        private val MOSS_CLASSES_LEGACY = listOf(
            "com.bapis.bilibili.app.playurl.v1.PlayURLMoss",
            "com.bapis.bilibili.app.playurl.v1.KPlayURLMoss"
        )
        private val REQUEST_CLASSES_LEGACY = listOf(
            "com.bapis.bilibili.app.playurl.v1.PlayViewReq",
            "com.bapis.bilibili.app.playurl.v1.KPlayViewReq"
        )
        private val IJK_CLIENT_CLASSES = listOf(
            "tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient"
        )

        private fun codeValue(type: Class<*>, preference: PlayerCodecPreference): Any? {
            val names = when (preference) {
                PlayerCodecPreference.H264 -> listOf("CODE264", "CODE_264")
                PlayerCodecPreference.H265 -> listOf("CODE265", "CODE_265")
                PlayerCodecPreference.AV1 -> listOf("CODEAV1", "CODE_AV1")
                PlayerCodecPreference.FOLLOW_HOST -> emptyList()
            }
            return names.firstNotNullOfOrNull { name ->
                runCatching {
                    type.getDeclaredField(name).apply { isAccessible = true }.get(null)
                        .takeIf { value -> type.isEnum && type.isInstance(value) }
                }.getOrNull()
            }
        }

        private fun adaptNumber(value: Long, target: Class<*>): Any =
            if (target == Int::class.javaPrimitiveType || target == Int::class.java) value.toInt() else value
    }
}
