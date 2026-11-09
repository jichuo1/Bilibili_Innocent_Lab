package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Modifier

/** Carries the original dialog marker to the host's create-group route. */
internal class GuestFollowGroupRouteHooks(
    private val markerOf: (Any) -> String?,
    private val valid: (String) -> Boolean
) {
    internal data class Types(
        val router: Class<*>, val request: Class<*>, val builder: Class<*>, val extras: Class<*>,
        val fragment: Class<*>, val dialog: Class<*>
    )

    fun install(registrar: HookRegistrar, types: Types, stage: (String) -> Unit = {}) {
        stage("route_shape")
        val route = requireNotNull(Lookup.methodOrNull(types.router, "routeTo", types.request, types.fragment))
        require(Modifier.isStatic(route.modifiers))
        stage("route_target")
        val target = requireNotNull(Lookup.methodOrNull(types.request, "getTargetUri"))
        stage("route_builder")
        val newBuilder = requireNotNull(Lookup.methodOrNull(types.request, "newBuilder"))
        val getExtras = requireNotNull(Lookup.methodOrNull(types.builder, "getExtras"))
        val put = requireNotNull(Lookup.methodOrNull(types.extras, "put", String::class.java, String::class.java))
        val build = requireNotNull(Lookup.methodOrNull(types.builder, "build"))
        require(newBuilder.returnType == types.builder && getExtras.returnType == types.extras && build.returnType == types.request)
        // A failed marked route must never be retried without the marker by the framework's protective mode.
        stage("route_hook")
        registrar.adapted("guest.follow_group.create_route",
            VersionAdapter.HookPoint(types.router.name, "routeTo", listOf(types.request.name, types.fragment.name)),
            HookExceptionPolicy.DELIVER_TO_HOST) {
            before {
                val page = argOrNull(1) ?: return@before
                if (!types.dialog.isInstance(page)) return@before
                try {
                    val marker = markerOf(page) ?: return@before
                    val request = requireNotNull(argOrNull(0))
                    val uri = requireNotNull(target.invoke(request)).toString().substringBefore('?').trimEnd('/')
                    if (uri != CREATE_ROUTE) return@before
                    if (!valid(marker)) {
                        result = null
                        return@before
                    }
                    val builder = requireNotNull(newBuilder.invoke(request))
                    val extras = requireNotNull(getExtras.invoke(builder))
                    put.invoke(extras, MARKER, marker)
                    args[0] = requireNotNull(build.invoke(builder))
                } catch (_: Throwable) {
                    // The host ignores this route's return value in the verified builds; null cancels safely.
                    result = null
                }
            }
        }
    }

    internal companion object {
        const val MARKER = "com.Bilibili_Innocent_Lab.follow_group_session"
        const val CREATE_ROUTE = "bilibili://relation/create-group"

        /** A missing or conflicting marker is blocked for a guest or a previously marked page. */
        fun pageMarker(argumentsPresent: Boolean, arguments: String?, intentPresent: Boolean, intent: String?, guest: Boolean): String? {
            if (!argumentsPresent && !intentPresent) return if (guest) "" else null
            if (argumentsPresent && arguments.isNullOrBlank() || intentPresent && intent.isNullOrBlank()) return ""
            if (argumentsPresent && intentPresent && arguments != intent) return ""
            return if (argumentsPresent) arguments else intent
        }
    }
}
