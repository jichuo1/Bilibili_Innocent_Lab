package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method

/** Identifies only the host video-page favorite events while their synchronous handler runs. */
internal class GuestFavoriteInteraction : GuestVideoFavoriteHooks.InteractionSource {
    private val current = ThreadLocal.withInitial { GuestVideoFavoriteHooks.Interaction.UNKNOWN }

    override fun install(env: HookEnvironment, loader: ClassLoader): Boolean = runCatching {
        val event = requireNotNull(Lookup.classOrNull(loader, EVENT))
        val click = requireNotNull(Lookup.classOrNull(loader, "$EVENT\$a"))
        val longPress = requireNotNull(Lookup.classOrNull(loader, "$EVENT\$b"))
        val continuation = requireNotNull(Lookup.classOrNull(loader, "kotlin.coroutines.Continuation"))
        val service = requireNotNull(Lookup.classOrNull(loader, "${PREFIX}KingPositionService"))
        require(click.superclass == event && longPress.superclass == event && click != longPress)
        val owners = listOf(COLLECTOR_13, COLLECTOR_14).mapNotNull { Lookup.classOrNull(loader, it) }
        val method = requireNotNull(locateCollector(owners, event, continuation, service))
        env.registrar.exact("guest.video.favorite.interaction", method.declaringClass, method.name, *method.parameterTypes) {
            before {
                setObjectExtra(PREVIOUS, current.get())
                current.set(when (argOrNull(0)?.javaClass) {
                    click -> GuestVideoFavoriteHooks.Interaction.CLICK
                    longPress -> GuestVideoFavoriteHooks.Interaction.LONG_PRESS
                    else -> GuestVideoFavoriteHooks.Interaction.UNKNOWN
                })
            }
            after { current.set(getObjectExtra(PREVIOUS) as? GuestVideoFavoriteHooks.Interaction ?: GuestVideoFavoriteHooks.Interaction.UNKNOWN) }
        }
        true
    }.getOrDefault(false)

    override fun current(): GuestVideoFavoriteHooks.Interaction = current.get() ?: GuestVideoFavoriteHooks.Interaction.UNKNOWN

    internal companion object {
        private const val PREFIX = "com.bilibili.ship.theseus.united.page.intro.module.kingposition."
        private const val EVENT = "${PREFIX}KingPositionComponent2\$c"
        private const val COLLECTOR_13 = "${PREFIX}KingPositionService\$create\$1\$1\$13\$a"
        private const val COLLECTOR_14 = "${PREFIX}KingPositionService\$create\$1\$1\$14\$a"
        private const val PREVIOUS = "guest.video.favorite.interaction.previous"

        /** Older collectors expose a typed handler; newer ones inline it into emit. Ambiguity is never guessed. */
        fun locateCollector(owners: List<Class<*>>, event: Class<*>, continuation: Class<*>, service: Class<*>): Method? {
            val typed = owners.flatMap { owner ->
                Lookup.declaredMethods(owner, makeAccessible = true) {
                    !it.isStatic && !it.isSynthetic && it.returnType == Any::class.java &&
                        it.parameterTypes.contentEquals(arrayOf(event, continuation))
                }
            }
            if (typed.size == 1) return typed.single()
            if (typed.isNotEmpty()) return null
            val owner = owners.singleOrNull { it.name == COLLECTOR_14 } ?: return null
            val constructor = Lookup.declaredConstructors(owner) { it.parameterTypes.contentEquals(arrayOf(service)) }.singleOrNull()
                ?: return null
            if (constructor.isSynthetic) return null
            if (Lookup.declaredFields(owner) { !it.isStatic && it.type == service }.size != 1) return null
            return Lookup.methodOrNull(owner, "emit", Any::class.java, continuation)?.takeIf { it.returnType == Any::class.java }
        }
    }
}
