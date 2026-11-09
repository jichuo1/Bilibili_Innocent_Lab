package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Modifier

/** Installs session-bound services before marked host pages can issue group requests. */
internal class GuestFollowGroupPageServices<T : Any>(
    private val bindings: GuestFollowGroupBindings<T>,
    private val markerOf: (Any) -> String?
) {
    internal data class Types(
        val dialog: Class<*>, val editor: Class<*>, val service: Class<*>, val factory: Class<*>,
        val call: Class<*>, val view: Class<*>, val bundle: Class<*>, val inflater: Class<*>,
        val viewGroup: Class<*>, val menu: Class<*>, val menuInflater: Class<*>
    )

    private data class Frame(val marker: String, val previous: Frame?)
    private val current = ThreadLocal<Frame?>()
    private val proxy = GuestFollowGroupServiceProxy(bindings)

    private fun enter(marker: String): Frame = Frame(marker, current.get()).also(current::set)
    private fun exit(frame: Frame) {
        // Out-of-order exits remain blocked until the thread exits instead of authorizing an unmarked request.
        if (current.get() === frame) current.set(frame.previous) else current.set(Frame("", null))
    }

    fun install(registrar: HookRegistrar, types: Types, stage: (String) -> Unit = {}) {
        stage("service_type")
        require(types.service.isInterface)
        stage("dialog_field")
        val dialogField = serviceField(types.dialog, types.service)
        stage("editor_field")
        val editorField = serviceField(types.editor, types.service)
        stage("service_factory_shape")
        val factory = requireNotNull(Lookup.methodOrNull(types.factory, "createService", Class::class.java))
        require(Modifier.isStatic(factory.modifiers) && factory.returnType == Any::class.java)
        stage("dialog_create_view_shape")
        requireNotNull(Lookup.methodOrNull(types.dialog, "onCreateView", types.inflater, types.viewGroup, types.bundle))
        stage("dialog_view_shape")
        requireNotNull(Lookup.methodOrNull(types.dialog, "onViewCreated", types.view, types.bundle))
        stage("editor_create_shape")
        requireNotNull(Lookup.methodOrNull(types.editor, "onCreate", types.bundle))
        stage("editor_create_view_shape")
        requireNotNull(Lookup.methodOrNull(types.editor, "onCreateView", types.inflater, types.viewGroup, types.bundle))
        stage("editor_menu_shape")
        requireNotNull(Lookup.methodOrNull(types.editor, "onCreateToolbarMenu", types.menu, types.menuInflater))
        stage("editor_view_shape")
        requireNotNull(Lookup.methodOrNull(types.editor, "onViewCreated", types.view, types.bundle))
        fun editorSafe(page: Any): Boolean {
            val marker = markerOf(page) ?: return true
            return proxy.wraps(editorField.get(page), marker)
        }
        fun protectDialog(page: Any) {
            val marker = markerOf(page) ?: return
            val delegate = dialogField.get(page) ?: requireNotNull(factory.invoke(null, types.service))
            dialogField.set(page, proxy.wrap(types.service, delegate, types.call, marker))
        }

        // Protective mode could replay an original method after a thrown exception, outside the page scope.
        // These hooks catch their own failures and let an original host exception propagate without replay.
        fun register(id: String, owner: Class<*>, method: String, parameters: List<Class<*>>,
                     block: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.() -> Unit) {
            stage(id.substringAfterLast('.'))
            registrar.adapted(id, VersionAdapter.HookPoint(owner.name, method, parameters.map { it.name }),
                HookExceptionPolicy.DELIVER_TO_HOST, block)
        }
        register("guest.follow_group.service_factory", types.factory, "createService", listOf(Class::class.java)) {
            after {
                var scoped = false
                try {
                    val frame = current.get() ?: return@after
                    scoped = true
                    if (argOrNull(0) !== types.service || hasThrowable) return@after
                    val delegate = result
                    result = null
                    result = proxy.wrap(types.service, requireNotNull(delegate), types.call, frame.marker)
                } catch (_: Throwable) {
                    // A marked page must never receive an unwrapped service, even on adapter failure.
                    if (scoped || !hasThrowable) result = null
                }
            }
        }
        register("guest.follow_group.editor_create", types.editor, "onCreate", listOf(types.bundle)) {
            before {
                var frame: Frame? = null
                try {
                    val marker = markerOf(requireNotNull(instance)) ?: return@before
                    frame = enter(marker)
                    setObjectExtra(FRAME, frame)
                } catch (_: Throwable) {
                    frame?.let(::exit)
                    result = null
                }
            }
            after {
                val frame = getObjectExtra(FRAME) as? Frame ?: return@after
                try {
                    if (!hasThrowable && !proxy.wraps(editorField.get(requireNotNull(instance)), frame.marker)) {
                        // A changed host must not leave an unchecked service accessible to the editor.
                        runCatching { editorField.set(instance, null) }
                    }
                } catch (_: Throwable) {
                    runCatching { editorField.set(instance, null) }
                } finally {
                    exit(frame)
                }
            }
        }
        for ((id, method, parameters) in listOf(
            Triple("editor_create_view", "onCreateView", listOf(types.inflater, types.viewGroup, types.bundle)),
            Triple("editor_menu", "onCreateToolbarMenu", listOf(types.menu, types.menuInflater)),
            Triple("editor_view", "onViewCreated", listOf(types.view, types.bundle))
        )) register("guest.follow_group.$id", types.editor, method, parameters) {
            before {
                try {
                    if (!editorSafe(requireNotNull(instance))) result = null
                } catch (_: Throwable) {
                    result = null
                }
            }
        }
        for ((id, method, parameters) in listOf(
            Triple("dialog_create_view", "onCreateView", listOf(types.inflater, types.viewGroup, types.bundle)),
            Triple("dialog_view", "onViewCreated", listOf(types.view, types.bundle))
        )) register("guest.follow_group.$id", types.dialog, method, parameters) {
            before {
                try {
                    protectDialog(requireNotNull(instance))
                } catch (_: Throwable) {
                    // The host binds clicks in onCreateView, so abort before creating any actionable view.
                    result = null
                }
            }
        }
    }

    private fun serviceField(owner: Class<*>, service: Class<*>) = requireNotNull(
        Lookup.declaredFields(owner, makeAccessible = true) {
            it.type == service && !Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers)
        }.singleOrNull()
    ) { "Missing or ambiguous group service field: ${owner.name}" }

    private companion object { const val FRAME = "guest.follow_group.editor_frame" }
}
