package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.diagnostics.DiagnosticCapabilityCatalog
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/** 安装期解析所有反射；响应和 getter 后备共用白名单，失败只丢弃副本。 */
internal class PlayerInteractiveReplyCleaner(
    loader: ClassLoader,
    points: VersionAdapter.PlayerInteractiveOverlayPoints
) {
    data class Fallback(val getter: Method, val ids: Set<String>, val transform: (Any, HookEnvironment) -> Any)
    private data class Field(val id: String, val present: Method, val clear: Method) {
        fun populated(target: Any): Boolean = when (val value = present.invoke(target)) {
            is Boolean -> value
            is Int -> value > 0
            else -> error("Unexpected presence result")
        }
    }
    private data class Change(val value: Any, val ids: Set<String>)
    private class Fields(val builder: ProtobufBuilderPlan, val fields: List<Field>) {
        val ids = fields.mapTo(linkedSetOf()) { it.id }
        fun clean(original: Any, environment: HookEnvironment): Change? {
            val populated = fields.filter { it.populated(original) }
            if (populated.isEmpty()) return null
            populated.forEach { environment.reportRuntimeEvidence(it.id, FeatureRuntimeStage.OBSERVED) }
            val updated = builder.edit(original) { target -> populated.forEach { it.clear.invoke(target) } }
            check(populated.none { it.populated(updated) }) { "Interactive clear readback failed" }
            return Change(updated, populated.mapTo(linkedSetOf()) { it.id })
        }
    }
    private data class Carrier(val getter: Method, val setter: Method?, val fields: Fields)
    private data class Response(val builder: ProtobufBuilderPlan?, val carriers: List<Carrier>, val direct: Fields?)
    private val responses = hashMapOf<String, Response>()
    private val mutableFallbacks = arrayListOf<Fallback>()
    val fallbacks: List<Fallback> get() = mutableFallbacks
    private val inside = ThreadLocal<Boolean>()
    val transformingResponse: Boolean get() = inside.get() == true

    init {
        for (spec in VersionAdapter.PLAYER_INTERACTIVE_MOSS_FAMILIES) {
            val family = points.families.firstOrNull { it.replyClassName == spec.replyClassName } ?: continue
            val reply = KavaMemberLookup.classOrNull(loader, spec.replyClassName) ?: continue
            val root = ProtobufBuilderPlan.resolve(reply)
            val carriers = arrayListOf<Carrier>()
            fun carrier(point: VersionAdapter.HookPoint?, names: List<VersionAdapter.HookPoint>, allowed: List<String>, kind: String) {
                val getter = point?.let { resolve(loader, it) } ?: return
                if (getter.declaringClass != reply || getter.name != if (kind == "guide") "getVideoGuide" else "getDm") return
                val fields = resolveFields(getter.returnType, names.map { it.methodName }.filter { it in allowed }) {
                    DiagnosticCapabilityCatalog.byLocatorKey["${spec.diagnosticFamilyId}/$kind/$it"]?.id
                } ?: return
                val setter = root?.method("set" + getter.name.removePrefix("get"), getter.returnType)
                carriers += Carrier(getter, setter, fields)
                mutableFallbacks += Fallback(getter, fields.ids) { value, env ->
                    safely(value, env) { fields.clean(value, env) }
                }
            }
            carrier(family.guideGetter, family.guideClears, spec.clearNames, "guide")
            carrier(family.dmGetter, family.dmClears, spec.dmClearNames, "dm")
            responses[reply.name] = Response(root, carriers, null)
        }
        KavaMemberLookup.classOrNull(loader, VersionAdapter.PLAYER_INTERACTIVE_DM_REPLY_CLASS)?.let { reply ->
            val names = listOfNotNull(points.commandClear, points.commandActivityMetaClear).map { it.methodName }
                .filter { it == VersionAdapter.PLAYER_INTERACTIVE_DM_REPLY_CLEAR || it == VersionAdapter.PLAYER_INTERACTIVE_DM_ACTIVITY_META_CLEAR }
            val fields = resolveFields(reply, names) {
                if (it == VersionAdapter.PLAYER_INTERACTIVE_DM_REPLY_CLEAR) COMMAND_ID else BANNER_ID
            }
            responses[reply.name] = Response(fields?.builder, emptyList(), fields)
            val commandGetter = points.commandGetter?.let { resolve(loader, it) }
            val defaultCommand = points.commandDefault?.let { point -> runCatching { resolve(loader, point)?.invoke(null) }.getOrNull() }
            if (commandGetter != null && commandGetter.declaringClass == reply && commandGetter.name == "getCommand" &&
                defaultCommand != null && commandGetter.returnType.isInstance(defaultCommand)) {
                mutableFallbacks += Fallback(commandGetter, setOf(COMMAND_ID)) { value, env ->
                    if (value === defaultCommand) value else {
                        env.reportRuntimeEvidence(COMMAND_ID, FeatureRuntimeStage.OBSERVED)
                        applied(env, setOf(COMMAND_ID)); defaultCommand
                    }
                }
            }
            if (points.commandActivityMetaClear != null) {
                KavaMemberLookup.methodOrNull(reply, "getActivityMetaList")
                    ?.takeIf { it.returnType isSubclassOf classOf<List<*>>() }?.let { getter ->
                        mutableFallbacks += Fallback(getter, setOf(BANNER_ID)) { value, env ->
                            if (value is List<*> && value.isNotEmpty()) {
                                env.reportRuntimeEvidence(BANNER_ID, FeatureRuntimeStage.OBSERVED)
                                applied(env, setOf(BANNER_ID)); emptyList<Any>()
                            } else value
                        }
                    }
            }
        }
    }

    fun responseIds(replyName: String): Set<String> = responses[replyName]?.let { plan ->
        if (plan.builder == null) emptySet() else plan.direct?.ids ?: plan.carriers
            .filter { it.setter != null }.flatMapTo(linkedSetOf()) { it.fields.ids }
    }.orEmpty()

    fun cleanResponse(reply: Any, environment: HookEnvironment): Any {
        val plan = responses[reply.javaClass.name] ?: return reply
        val builder = plan.builder ?: return reply
        val previous = inside.get()
        inside.set(true)
        return try {
            safely(reply, environment) {
                if (plan.direct != null) return@safely plan.direct.clean(reply, environment)
                val changes = plan.carriers.filter { it.setter != null }.mapNotNull { carrier ->
                    val child = carrier.getter.invoke(reply) ?: return@mapNotNull null
                    carrier.fields.clean(child, environment)?.let { carrier to it }
                }
                if (changes.isEmpty()) return@safely null
                val updated = builder.edit(reply) { target ->
                    changes.forEach { (carrier, changed) -> carrier.setter!!.invoke(target, changed.value) }
                }
                check(changes.all { (carrier, change) -> carrier.getter.invoke(updated) === change.value })
                Change(updated, changes.flatMapTo(linkedSetOf()) { it.second.ids })
            }
        } finally {
            if (previous == null) inside.remove() else inside.set(previous)
        }
    }

    private fun safely(original: Any, environment: HookEnvironment, action: () -> Change?): Any = runCatching {
        val changed = action() ?: return@runCatching original
        applied(environment, changed.ids)
        changed.value
    }.getOrElse {
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
        environment.logError("player_interactive_copy_failed", "[BIL] 播放器互动数据副本清理失败，保留原响应: $it")
        original
    }

    private fun applied(environment: HookEnvironment, ids: Set<String>) {
        ids.forEach { environment.reportRuntimeEvidence(it, FeatureRuntimeStage.APPLIED) }
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
    }

    private fun resolveFields(owner: Class<*>, names: List<String>, id: (String) -> String?): Fields? {
        val builder = ProtobufBuilderPlan.resolve(owner) ?: return null
        val fields = names.filter { it != PlayerInteractiveOverlayPolicy.PRESERVED_VIDEO_POINT_CLEAR }.mapNotNull { name ->
            val suffix = name.removePrefix("clear")
            val present = KavaMemberLookup.methodOrNull(owner, "has$suffix")?.takeIf { it.returnType == classOf<Boolean>() }
                ?: KavaMemberLookup.methodOrNull(owner, "get${suffix}Count")?.takeIf { it.returnType == classOf<Int>() }
                ?: return@mapNotNull null
            Field(id(name) ?: return@mapNotNull null, present, builder.method(name) ?: return@mapNotNull null)
        }
        return fields.takeIf { it.isNotEmpty() }?.let { Fields(builder, it) }
    }

    private fun resolve(loader: ClassLoader, point: VersionAdapter.HookPoint): Method? {
        if (point.paramClassNames.orEmpty().isNotEmpty()) return null
        val owner = KavaMemberLookup.classOrNull(loader, point.className) ?: return null
        return KavaMemberLookup.methodOrNull(owner, point.methodName)
    }

    companion object {
        private const val ID = "player_interactive_overlay"
        private const val COMMAND_ID = "player_interactive_dm_commands"
        private const val BANNER_ID = "player_interactive_activity_banner"
    }
}
