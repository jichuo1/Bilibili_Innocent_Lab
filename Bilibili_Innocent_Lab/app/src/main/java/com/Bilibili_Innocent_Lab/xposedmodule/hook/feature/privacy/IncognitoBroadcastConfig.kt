package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Use the host's own disabled-stream path, which reports unsupported instead of queuing requests. */
internal class IncognitoBroadcastConfig(
    private val names: Names = Names(),
    private val stack: () -> Array<StackTraceElement> = { Throwable().stackTrace }
) {
    internal data class Names(
        val kmpConfig: String = "kntr.base.config.KConfig",
        val runtimeHelper: String = "com.bilibili.lib.moss.utils.RuntimeHelper",
        val configContract: String = "com.bilibili.lib.moss.utils.di.ConfigManager",
        val engine: String = "com.bilibili.rpc.ignet.RpcEngine",
        val modernCaller: String = "kntr.base.moss.ignet.impl.stream.tunnel.Tunnel_androidKt\$createTunnel\$3",
        val legacyCaller: String = "kntr.base.moss.ignet.impl.stream.q"
    )

    /** Unknown host shapes do not change any host methods. */
    fun prepare(env: HookEnvironment): (() -> Int)? = runCatching {
        val loader = requireNotNull(env.classLoader)
        val config = requireNotNull(Lookup.classOrNull(loader, names.kmpConfig))
        val ff = requireNotNull(Lookup.methodOrNull(config, "ff", classOf<String>(), classOf<Boolean>()))
            .takeIf { !it.isStatic && it.returnType == classOf<Boolean>() } ?: error("KConfig.ff shape")
        val helper = requireNotNull(Lookup.classOrNull(loader, names.runtimeHelper))
        val contract = requireNotNull(Lookup.classOrNull(loader, names.configContract))
        require(contract.isInterface)
        val getCm = requireNotNull(Lookup.methodOrNull(helper, "getCm"))
            .takeIf { !it.isStatic && it.returnType == contract } ?: error("RuntimeHelper.getCm shape")
        val engine = Lookup.classOrNull(loader, names.engine)
        val create = engine?.let { Lookup.methodOrNull(it, "createRpc", classOf<String>(), classOf<Int>(), classOf<String>()) }
            ?.takeIf { !it.isStatic && it.returnType == classOf<Long>() }
        return@runCatching {
            val configured = mutableSetOf<Method>()
            val lock = Any()
            val evidence = ConcurrentHashMap.newKeySet<String>()
            fun reportOnce(key: String, value: String) {
                if (evidence.add(key)) runCatching { env.reportStatus(key, value) }
            }
            fun applyConfigManager(target: Any) {
                require(contract.isInstance(target)) { "config contract" }
                val method = requireNotNull(Lookup.inheritedMethodOrNull(target.javaClass, "ff", classOf<String>()))
                    .takeIf { !it.isStatic && it.returnType == Boolean::class.javaObjectType } ?: error("ConfigManager.ff shape")
                synchronized(lock) {
                    if (method in configured) return
                    env.registrar.exact("incognito.broadcast.java.${method.declaringClass.name}",
                        method.declaringClass, method.name, *method.parameterTypes) {
                        before {
                            val feature = disabledFeature(argOrNull(0)) ?: return@before
                            result = false
                            reportOnce("incognito_broadcast_java_$feature", "disabled")
                        }
                    }
                    configured += method
                }
                reportOnce("incognito_broadcast_java_hook", "installed")
            }
            env.registrar.exact("incognito.broadcast.kmp", config, ff.name, *ff.parameterTypes) {
                before {
                    val feature = disabledFeature(argOrNull(0)) ?: return@before
                    result = false
                    reportOnce("incognito_broadcast_kmp_$feature", "disabled")
                }
            }
            // Resolve the concrete ConfigManager when the host supplies it. Installing this hook in
            // getCm's after callback finishes before the caller can read the returned configuration.
            env.registrar.exact("incognito.broadcast.get_cm", helper, getCm.name) {
                after {
                    if (hasThrowable) return@after
                    val target = result ?: return@after
                    runCatching { applyConfigManager(target) }.onFailure {
                        env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                        runCatching { env.logError("incognito_broadcast_java_hook", "[BIL] Broadcast config adapter unavailable") }
                    }
                }
            }
            if (engine != null && create != null) {
                // Diagnostic only: do not change native handles or suppress callbacks. On the 38
                // archived builds this overload's broadcast callers have one of these stack frames.
                env.registrar.exact("incognito.broadcast.observe", engine, create.name, *create.parameterTypes) {
                    before {
                        val observed = runCatching { isBroadcastCall(stack(), names) }.getOrDefault(true)
                        if (observed && evidence.add("incognito_broadcast_tunnel_attempt")) {
                            runCatching { env.reportStatus("incognito_broadcast_tunnel_attempt", "observed") }
                            env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.ERROR)
                        }
                    }
                }
            }
            reportOnce("incognito_broadcast_policy", if (create == null) "installed;monitor-missing" else "installed")
            if (create == null) 2 else 3
        }
    }.onFailure {
        runCatching { env.logError("incognito_broadcast_policy", "[BIL] Broadcast config anchors unavailable") }
    }.getOrNull()

    internal companion object {
        private val DISABLED = mapOf(
            "grpc_broadcast_enable" to "global",
            "grpc_stream_biz_enabled" to "business",
            "grpc_stream_biz_room_enabled" to "room"
        )

        fun disabledFeature(key: Any?): String? = (key as? String)?.let(DISABLED::get)

        fun isBroadcastCall(frames: Array<StackTraceElement>, names: Names = Names()): Boolean = frames.any {
            (it.className == names.modernCaller && it.methodName == "invokeSuspend") ||
                (it.className == names.legacyCaller && it.methodName == "b")
        }
    }
}
