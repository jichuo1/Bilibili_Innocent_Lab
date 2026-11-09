package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** Stop producers before Neurons can persist a queue for replay outside the session. */
internal class IncognitoTelemetryHooks(private val session: IncognitoSession) {
    /**
     * Resolve the Java Neurons producers and, when the build has it, the KMP `KNeuron` producers;
     * a missing anchor returns null so the whole feature can skip. KNeuron keeps its own Room event
     * queue and uploads it over the native ighttp channel, so withholding the upload alone would
     * only defer it: the events must never be recorded in the first place.
     */
    fun prepare(env: HookEnvironment): (() -> Int)? = runCatching {
        val owner = PrivacyHostAccess(requireNotNull(env.classLoader))
            .type("com.bilibili.lib.neuron.api.Neurons")
        val producers = Lookup.declaredMethods(owner) {
            it.returnType == Void.TYPE && (it.name.startsWith("report") || it.name.startsWith("track"))
        }
        require(producers.any { it.name == "trackT" } && producers.any { it.name == "reportClick" })
        val kmp = Lookup.classOrNull(env.classLoader, KMP_NEURON)
        val kmpProducers = kmp?.let { kmpProducers(it) }
        if (kmp != null) require(!kmpProducers.isNullOrEmpty()) { "KNeuron producers" }
        return@runCatching {
            val evidence = AtomicBoolean()
            applyProducers(env, owner, producers, "incognito.neurons", evidence) +
                applyUuid(env, owner) +
                (if (kmp != null && kmpProducers != null) applyProducers(env, kmp, kmpProducers, "incognito.kneuron", evidence) else 0)
        }
    }.getOrNull()

    fun install(env: HookEnvironment): Int =
        prepare(env)?.invoke() ?: error("Neurons telemetry anchors unavailable")

    private fun applyProducers(env: HookEnvironment, owner: Class<*>, producers: List<Method>, prefix: String,
                               evidence: AtomicBoolean): Int {
        for (method in producers) {
            env.registrar.exact("$prefix.${method.name}.${method.parameterTypes.joinToString { it.name }}",
                owner, method.name, *method.parameterTypes) {
                before {
                    // Function0<Boolean> in track* is an upload condition, not a completion callback.
                    // Calling it here would run host code even though the event was discarded.
                    result = null
                    if (evidence.compareAndSet(false, true)) {
                        env.reportRuntimeEvidence("incognito_privacy", FeatureRuntimeStage.APPLIED)
                        env.reportStatus("incognito_telemetry_producer", "discarded")
                    }
                }
            }
        }
        return producers.size
    }

    private fun applyUuid(env: HookEnvironment, owner: Class<*>): Int {
        env.registrar.exact("incognito.neurons.uuid.get", owner, "getUUID") {
            before { result = session.identity()?.sessionId.orEmpty() }
        }
        env.registrar.exact("incognito.neurons.uuid.set", owner, "setUUID", classOf<String>()) {
            before { result = null }
        }
        return 2
    }

    internal companion object {
        const val KMP_NEURON = "kntr.base.neuron.KNeuron"

        /**
         * The KNeuron event entry points: its public instance `report*` / `track*` / `simpleTrack*`
         * methods returning void. Names are kept on every supported build (8 on 8.84, 10 on 9.16.1:
         * reportClick/Custom/Exposure/Ijk/PageView/Player/System, simpleTrackT, trackT, trackXCrash).
         * Both must-haves are required so a renamed build skips instead of half-discarding.
         */
        fun kmpProducers(owner: Class<*>): List<Method>? = Lookup.declaredMethods(owner) {
            it.returnType == Void.TYPE && Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                !it.isSynthetic && listOf("report", "track", "simpleTrack").any(it.name::startsWith)
        }.takeIf { list -> list.any { it.name == "trackT" } && list.any { it.name == "reportClick" } }
    }
}
