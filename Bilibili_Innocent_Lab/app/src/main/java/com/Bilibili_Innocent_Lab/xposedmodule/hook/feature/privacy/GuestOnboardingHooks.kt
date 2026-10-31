package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup

/**
 * Skip the new-user interest/gender/age onboarding for guests, without changing host preferences,
 * submitting a choice, or persisting a "completed" flag.
 *
 * Two independent layers, because the host ships two onboarding implementations:
 *
 * 1. First-start wait gate ([installGate]). On a first start the host's `PegasusFragment.onCreate`
 *    does NOT load the home feed; it waits for the interest wizard to finish and loads the feed from
 *    the wizard's completion event. The same predicate ("first start and waiting for interest":
 *    `request.d.h()` on 9.16.x, `request.b.f/g/h()` on 8.99-9.01 and 9.08-9.15) decides whether the
 *    V2 plugin registry mounts the Compose wizard. Forcing it to false for a guest makes the host take
 *    its own non-first-start path: the feed loads immediately and the wizard never mounts. Nothing is
 *    faked, so no loading gate can be stranded. Verified on 9.0.0, 9.16.0 and 9.16.1; the other builds
 *    in that range have only static evidence (matrix q60). Builds where neither class carries the
 *    predicate report `guest_onboarding_gate=absent` and keep the host's behavior.
 *
 * 2. Legacy plugins ([installPlugins]). The pre-V2 `NewUserInterestChoosePlugin` and
 *    `LowActiveInterestChoosePlugin` are located by version-stable ABI shape:
 *     - show   = the SINGLE `(InterestChoose):void` method (`tryShowInterestDialog`).
 *     - finish = the SINGLE `(boolean, <ref>):void` method (New) / `(boolean, <ref>, boolean):void`
 *                method (Low); `finish(false, null[, false])` resets the loading gate and loads the
 *                normal feed with no choice payload.
 *    Each plugin installs independently and fails CLOSED: an absent or ambiguous shape is skipped,
 *    never guessed. These classes exist on every build but only drive the wizard before V2 (8.84-8.96);
 *    on 8.97+ the host instantiates the V2 plugins instead, so these hooks never fire there.
 *
 * Every layer reports its install outcome, so a silent "0 hooks" cannot hide a dead adapter again.
 */
internal class GuestOnboardingHooks(private val isGuest: () -> Boolean) {
    fun install(env: HookEnvironment): Int {
        val loader = requireNotNull(env.classLoader)
        val gate = installGate(env, loader)
        // Both wizard suppressions are only safe once the first-start gate works: with the gate absent the home
        // feed may be waiting for a wizard that this hook just prevented, so they are installed together or not at all.
        val data = if (gate > 0) installDataSuppression(env, loader) else {
            env.reportStatus("guest_onboarding_data", "skipped-no-gate")
            0
        }
        val entry = if (gate > 0) installEntryRejection(env, loader) else {
            env.reportStatus("guest_onboarding_entry", "skipped-no-gate")
            0
        }
        return gate + data + entry + installPlugins(env, loader)
    }

    /**
     * Wizard data source. The Low-active V2 wizard (the path that actually runs for a fresh guest on 9.16.1,
     * confirmed on device) starts only after it is handed an `InterestChoose` object, and the host null-guards
     * that object. It comes from `PegasusResponse.getInterestChoose()`, which has a single caller and a stable,
     * un-obfuscated name on all 38 builds (matrix 9e752e0a). Returning null for a guest leaves the wizard with
     * nothing to show, without touching any wizard internals or the feed itself.
     */
    private fun installDataSuppression(env: HookEnvironment, loader: ClassLoader): Int = runCatching {
        val response = PrivacyStructuralResolver.type(loader, RESPONSE_CLASS)
        val model = PrivacyStructuralResolver.type(loader, MODEL_CLASS)
        val getter = if (response != null && model != null) {
            PrivacyStructuralResolver.method(response, "getInterestChoose")?.takeIf { it.returnType == model }
        } else null
        if (getter == null) {
            env.reportStatus("guest_onboarding_data", "absent")
            return@runCatching 0
        }
        env.registrar.exact("guest.onboarding.data", response!!, getter.name) {
            before {
                if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                result = null
                env.reportStatus("guest_onboarding_data_suppressed", "seen")
            }
        }
        env.reportStatus("guest_onboarding_data", "installed")
        1
    }.getOrElse {
        env.logError("guest_onboarding_data", "[BIL] Guest onboarding data unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_onboarding_data", "failed")
        0
    }

    /**
     * V2 wizard entry. `NewUserInterestChoosePluginV2.onViewCreated` asks the entry controller whether to start;
     * a false answer makes the host log "entry rejected" and mount nothing, with no state to unwind. The
     * controller class is obfuscated, so it is reached through the stable `AndroidInterestChooseSession`
     * class by following field types (session -> runtime -> entry) and requiring exactly one match.
     */
    private fun installEntryRejection(env: HookEnvironment, loader: ClassLoader): Int = runCatching {
        val session = PrivacyStructuralResolver.type(loader, SESSION_CLASS)
        val decision = session?.let { locateEntryDecision(it) }
        if (decision == null) {
            env.reportStatus("guest_onboarding_entry", "absent")
            return@runCatching 0
        }
        env.registrar.exact("guest.onboarding.entry", decision.declaringClass, decision.name, *decision.parameterTypes) {
            before {
                if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                result = false
                env.reportStatus("guest_onboarding_entry_rejected", "seen")
            }
        }
        env.reportStatus("guest_onboarding_entry", "installed")
        1
    }.getOrElse {
        env.logError("guest_onboarding_entry", "[BIL] Guest onboarding entry unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_onboarding_entry", "failed")
        0
    }

    private fun installGate(env: HookEnvironment, loader: ClassLoader): Int = runCatching {
        val requests = REQUEST_CLASSES.mapNotNull { PrivacyStructuralResolver.type(loader, it) }
        val fragment = PrivacyStructuralResolver.type(loader, FRAGMENT_CLASS)
        // The predicate is the SINGLE static ()Z across request.d and request.b. The matrix (q60, 38 builds)
        // found it on request.d for 9.16.x and on request.b for 8.99-9.01 and 9.08-9.15, each called by
        // PegasusFragment.onCreate; on the other builds neither class has a static ()Z. Reflection cannot see
        // call sites, so uniqueness is the guard: none, or one in each class, installs nothing.
        val gate = if (requests.isNotEmpty() && fragment != null) locateGate(*requests.toTypedArray()) else null
        if (gate == null) {
            env.reportStatus("guest_onboarding_gate", "absent")
            return@runCatching 0
        }
        env.registrar.exact("guest.onboarding.gate", gate.declaringClass, gate.name, *gate.parameterTypes) {
            before {
                if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                result = false
                // A separate key: the status channel keeps only the first value per key, so reusing
                // "guest_onboarding_gate" would hide every application behind the install report.
                env.reportStatus("guest_onboarding_gate_applied", "seen")
            }
        }
        env.reportStatus("guest_onboarding_gate", "installed")
        1
    }.getOrElse {
        env.logError("guest_onboarding_gate", "[BIL] Guest onboarding gate unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_onboarding_gate", "failed")
        0
    }

    private fun installPlugins(env: HookEnvironment, loader: ClassLoader): Int {
        val model = PrivacyStructuralResolver.type(loader, MODEL_CLASS)
        if (model == null) {
            env.reportStatus("guest_onboarding_plugin", "absent")
            return 0
        }
        var installed = 0
        installed += installPlugin(env, loader, model, NEW_PLUGIN, finishTrailingFalse = false,
            status = "skipped", id = "guest.onboarding.new")
        installed += installPlugin(env, loader, model, LOW_PLUGIN, finishTrailingFalse = true,
            status = "skipped-low", id = "guest.onboarding.low")
        env.reportStatus("guest_onboarding_plugin", if (installed > 0) "installed:$installed" else "absent")
        return installed
    }

    /** @param finishTrailingFalse Low's finish takes a trailing realtime flag; New's does not. */
    private fun installPlugin(
        env: HookEnvironment, loader: ClassLoader, model: Class<*>,
        className: String, finishTrailingFalse: Boolean, status: String, id: String
    ): Int = runCatching {
        val plugin = PrivacyStructuralResolver.type(loader, className) ?: return@runCatching 0
        val show = PrivacyStructuralResolver.uniqueMethod(plugin) { m ->
            m.returnType == Void.TYPE && m.parameterTypes.toList() == listOf(model)
        } ?: return@runCatching 0
        val finishShape: (Method) -> Boolean = { m ->
            m.returnType == Void.TYPE && isRefParam(m, model) &&
                if (finishTrailingFalse) {
                    m.parameterCount == 3 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                        m.parameterTypes[2] == Boolean::class.javaPrimitiveType
                } else {
                    m.parameterCount == 2 && m.parameterTypes[0] == Boolean::class.javaPrimitiveType
                }
        }
        val finish = PrivacyStructuralResolver.uniqueMethod(plugin, predicate = finishShape) ?: return@runCatching 0
        val args: Array<Any?> = if (finishTrailingFalse) arrayOf(false, null, false) else arrayOf(false, null)
        env.registrar.exact("$id.show", plugin, show.name, *show.parameterTypes) {
            before {
                if (!isGuest()) return@before
                val target = instance ?: return@before
                result = null
                finish.invoke(target, *args)
                env.reportStatus("guest_onboarding", status)
            }
        }
        1
    }.getOrElse {
        env.logError("${id.replace('.', '_')}", "[BIL] Guest onboarding adapter unavailable: ${it.javaClass.simpleName}")
        0
    }

    /** The finish method carries exactly one reference parameter (the choice/callback), never the model itself. */
    private fun isRefParam(m: Method, model: Class<*>): Boolean {
        val refs = m.parameterTypes.filter { !it.isPrimitive }
        return refs.size == 1 && refs[0] != model
    }

    internal companion object {
        private val REQUEST_CLASSES = listOf("com.bilibili.pegasus.request.d", "com.bilibili.pegasus.request.b")
        private const val FRAGMENT_CLASS = "com.bilibili.pegasus.PegasusFragment"
        private const val SESSION_CLASS = "com.bilibili.pegasus.components.interestv2.host.AndroidInterestChooseSession"
        private const val RESPONSE_CLASS = "com.bilibili.pegasus.data.base.PegasusResponse"
        private const val MODEL_CLASS = "com.bilibili.pegasus.data.interestchoose.InterestChoose"
        private const val NEW_PLUGIN = "com.bilibili.pegasus.components.interest.NewUserInterestChoosePlugin"
        private const val LOW_PLUGIN = "com.bilibili.pegasus.components.interest.LowActiveInterestChoosePlugin"

        /** The single static no-arg boolean across [requests]; null when none or more than one (fails closed). */
        fun locateGate(vararg requests: Class<*>): Method? = requests.flatMap { request ->
            Lookup.methods(request, includeSuperclasses = false, makeAccessible = true) { m ->
                Modifier.isStatic(m.modifiers) && m.returnType == java.lang.Boolean.TYPE && m.parameterCount == 0
            }
        }.distinct().singleOrNull()

        /**
         * The entry decision `(int, String, ref, boolean[, ref]): boolean` (the trailing step-session argument was
         * added in 9.15.2). It sits two field hops below [session]: session -> runtime -> entry controller.
         * Exactly one match across all hops, otherwise null; platform and primitive field types are never walked.
         */
        fun locateEntryDecision(session: Class<*>): Method? {
            fun walkable(type: Class<*>) = !type.isPrimitive && !type.isArray && !type.isInterface &&
                listOf("java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.").none { type.name.startsWith(it) }
            val found = linkedSetOf<Method>()
            for (runtimeField in session.declaredFields) {
                val runtime = runtimeField.type.takeIf(::walkable) ?: continue
                for (entryField in runtime.declaredFields) {
                    val entry = entryField.type.takeIf(::walkable) ?: continue
                    PrivacyStructuralResolver.uniqueMethod(entry) { m ->
                        !Modifier.isStatic(m.modifiers) && m.returnType == java.lang.Boolean.TYPE &&
                            m.parameterCount in 4..5 && m.parameterTypes[0] == Integer.TYPE &&
                            m.parameterTypes[1] == String::class.java
                    }?.let(found::add)
                }
            }
            return found.singleOrNull()
        }
    }
}
