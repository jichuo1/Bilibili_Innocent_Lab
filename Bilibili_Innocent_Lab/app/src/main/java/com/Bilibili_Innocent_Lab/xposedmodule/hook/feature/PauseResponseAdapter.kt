package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 暂停协议的响应入口；安装期预解析，不按协程名阻断携带正常 bar 的请求。 */
internal object PauseResponseAdapter {
    const val PACKAGE = "com.bapis.bilibili.app.viewunite.v1"
    const val REPLY = "$PACKAGE.PlayPauseReply"
    const val REQUEST = "$PACKAGE.PlayPauseReq"
    const val JAVA_MOSS = "$PACKAGE.ViewMoss"
    const val KOTLIN_MOSS = "$PACKAGE.KViewMoss"

    data class Points(
        val reply: Class<*>?,
        val javaPresent: Boolean,
        val kotlinPresent: Boolean,
        val sync: Method?,
        val async: Method?,
        val kotlin: Method?
    ) {
        val expected: Int get() = (if (javaPresent) 2 else 0) + (if (kotlinPresent) 1 else 0)
    }

    data class Coverage(val installed: Int, val expected: Int, val kotlinInstalled: Boolean = false) {
        val complete: Boolean get() = expected > 0 && installed == expected
    }

    fun resolve(loader: ClassLoader): Points = resolve(
        KavaMemberLookup.classOrNull(loader, REPLY),
        KavaMemberLookup.classOrNull(loader, REQUEST),
        KavaMemberLookup.classOrNull(loader, JAVA_MOSS),
        KavaMemberLookup.classOrNull(loader, KOTLIN_MOSS)
    )

    internal fun resolve(reply: Class<*>?, request: Class<*>?, java: Class<*>?, kotlin: Class<*>?): Points {
        fun unique(owner: Class<*>?, predicate: (Method) -> Boolean): Method? = owner?.let {
            KavaMemberLookup.declaredMethods(it, makeAccessible = true) {
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) && predicate(it)
            }.singleOrNull()
        }
        val sync = if (reply == null || request == null) null else unique(java) {
            it.name == "executePlayPause" && it.returnType == reply &&
                it.parameterTypes.contentEquals(arrayOf(request))
        }
        val async = if (request == null) null else unique(java) {
            val parameters = it.parameterTypes
            it.name == "playPause" && it.returnType == Void.TYPE && parameters.size == 2 &&
                parameters[0] == request && parameters[1].name == JAVA_HANDLER && handler(parameters[1])
        }
        val callback = unique(kotlin) {
            it.name == "playPause" && KotlinMossBridgeMembers.isCallbackEntry(it) && handler(it.parameterTypes[3])
        } ?: unique(kotlin) {
            it.name == "playPause" && KotlinMossBridgeMembers.isLegacyCallbackEntry(it) && handler(it.parameterTypes[3])
        }
        return Points(reply, java != null, kotlin != null, sync, async, callback)
    }

    private fun handler(type: Class<*>): Boolean = type.isInterface && type.methods.any {
        it.name == "onNext" && it.returnType == Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(Any::class.java))
    }

    /** 缺失和注册失败仍计入各自家族的分母。 */
    fun install(
        environment: HookEnvironment,
        points: Points,
        kmpGrpcWireEngine: com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint? = null,
        skipJava: Boolean = false
    ): Coverage {
        val cleaner = points.reply?.let(PausePayloadCleaner::resolve)
            ?: return Coverage(0, points.expected)
        var installed = 0
        fun register(id: String, method: Method?, block: (Method) -> Unit): Boolean {
            if (method == null) return false
            return runCatching { block(method) }.onSuccess { installed++ }.onFailure {
                environment.logError("${id}_register", "[BIL] 暂停协议响应入口注册失败: $it")
            }.isSuccess
        }
        if (!skipJava) {
            register("paused.response.execute_play_pause", points.sync) { method ->
                environment.registrar.exact("paused.response.execute_play_pause", method.declaringClass,
                    method.name, *method.parameterTypes) {
                    after {
                        if (hasThrowable) return@after
                        val original = result ?: return@after
                        val updated = cleaner.clearAds(original, environment)
                        if (updated !== original) result = updated
                    }
                }
            }
            register("paused.response.play_pause", points.async) { method ->
                val handler = method.parameterTypes[1]
                environment.registrar.exact("paused.response.play_pause", method.declaringClass,
                    method.name, *method.parameterTypes) {
                    before {
                        val delegate = args.getOrNull(1) ?: return@before
                        val proxy = MossResponseHandlerProxy.wrapTransform(handler, delegate) {
                            cleaner.clearAds(it, environment)
                        } ?: return@before
                        args[1] = proxy
                    }
                }
            }
        }
        val loader = environment.classLoader
        val wireSurface = loader?.let {
            com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KmpGrpcWireAdapter.resolve(it, kmpGrpcWireEngine)
        }
        val expected = points.expected + if (wireSurface != null && !points.kotlinPresent) 1 else 0
        val wireInstalled = wireSurface != null && KmpGrpcWireChannel.install(
            environment = environment,
            surface = wireSurface,
            javaMossClassName = JAVA_MOSS,
            rpc = "playPause",
            javaReplyClass = points.reply,
            what = "暂停页广告",
            logKey = "paused_p4_kmoss"
        ) { reply ->
            cleaner.clearAds(reply, environment)
        }
        if (wireInstalled) {
            installed++
            return Coverage(installed, expected, kotlinInstalled = true)
        }
        val members = if (points.kotlin != null && loader != null) {
            KotlinMossChannel.prepare(environment, loader, "暂停页广告", "paused_p4_kmoss")
        } else null
        val codec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(points.reply)
        val legacy = points.kotlin?.parameterCount == 4
        val defaultProtoBuf = if (legacy) members?.defaultProtoBuf() else null
        val kotlinInstalled = members != null && codec != null && (!legacy || defaultProtoBuf != null) &&
            register("paused.response.kmoss.play_pause", points.kotlin) { method ->
                val handler = method.parameterTypes[3]
                environment.registrar.exact("paused.response.kmoss.play_pause", method.declaringClass,
                    method.name, *method.parameterTypes) {
                    before {
                        val delegate = args.getOrNull(3) ?: return@before
                        val format = if (legacy) defaultProtoBuf else args.getOrNull(4)
                        val bridge = members.bridgeFor(format, args.getOrNull(2), codec) ?: return@before
                        val proxy = MossResponseHandlerProxy.wrapTransform(handler, delegate) { reply ->
                            transformKotlin(bridge, cleaner, reply, environment)
                        } ?: return@before
                        args[3] = proxy
                    }
                }
            }
        return Coverage(installed, expected, kotlinInstalled)
    }

    /** 回写与再次解码核验成功后才记 APPLIED；转换失败不把 Java 中间副本当交付结果。 */
    internal fun transformKotlin(bridge: KotlinMossReplyBridge, cleaner: PausePayloadCleaner,
        original: Any, environment: HookEnvironment): Any = runCatching {
        var javaOriginal: Any? = null
        val updated = bridge.transform(original) { reply ->
            javaOriginal = reply
            cleaner.clearAds(reply, environment, reportApplied = false)
        }
        if (updated !== original) {
            check(cleaner.isClearedCopy(checkNotNull(javaOriginal), bridge.read(updated))) {
                "Pause Kotlin ads/bar readback failed"
            }
            environment.reportRuntimeEvidence(PausedAdFeatureInstaller.ID, FeatureRuntimeStage.APPLIED)
        }
        updated
    }.getOrElse {
        environment.reportRuntimeEvidence(PausedAdFeatureInstaller.ID, FeatureRuntimeStage.ERROR)
        environment.logError("paused_p4_kmoss_failed", "[BIL] 暂停页 Kotlin 响应清理失败，保留原响应: $it")
        original
    }

    private const val JAVA_HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
}
