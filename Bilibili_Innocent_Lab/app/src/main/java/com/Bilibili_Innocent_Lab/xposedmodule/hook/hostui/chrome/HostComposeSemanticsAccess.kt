package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Never create a same-named key: accessibility reads the framework's canonical key identity.
 * The receiver contract supplies the actual R8 key type, then the DEX-discovered holder and the
 * key's verified accessibility-name representation prove the exact ContentDescription field. Only immutable library
 * metadata/the canonical singleton are retained; no receiver, node, title or View is cached.
 */
internal class HostComposeSemanticsAccess private constructor(
    private val receiver: Class<*>, private val write: (Any, String) -> Unit
) {
    fun describe(target: Any, title: String) {
        require(receiver.isInstance(target))
        write(target, title)
    }

    companion object {
        private const val RECEIVER = "androidx.compose.ui.semantics.SemanticsPropertyReceiver"
        private const val PROPERTIES_KT = "androidx.compose.ui.semantics.SemanticsPropertiesKt"
        private const val LEGACY_HOLDER = "androidx.compose.ui.semantics.SemanticsProperties"
        private const val DESCRIPTION = "ContentDescription"
        private const val MAX_FIELDS = 128

        fun directAvailable(loader: ClassLoader): Boolean = resolve(loader, null) != null

        fun resolve(loader: ClassLoader, point: HomeChromeSemanticsPoint? = null,
                    onError: (Throwable) -> Unit = {}): HostComposeSemanticsAccess? = runCatching {
            val receiver = Lookup.classOrNull(loader, RECEIVER) ?: return@runCatching null
            val direct = Lookup.classOrNull(loader, PROPERTIES_KT)?.let {
                Lookup.methodOrNull(it, "setContentDescription", receiver, String::class.java)
            }
            val holders = buildList {
                Lookup.classOrNull(loader, LEGACY_HOLDER)?.let(::add)
                point?.takeIf { it.isStructurallyValid() }?.let {
                    Lookup.classOrNull(loader, it.holderClass)?.let(::add)
                }
            }.distinct()
            resolveForTypes(receiver, direct, point, holders)
        }.onFailure { runCatching { onError(it) } }.getOrNull()

        fun locate(loader: ClassLoader, candidateClasses: Collection<Class<*>>): HomeChromeSemanticsPoint? {
            val receiver = Lookup.classOrNull(loader, RECEIVER) ?: return null
            return locateForReceiver(receiver, candidateClasses.filter {
                Lookup.classOrNull(loader, it.name) === it
            })
        }

        internal fun locateForReceiver(receiver: Class<*>, candidates: Collection<Class<*>>): HomeChromeSemanticsPoint? {
            val contract = Contract.resolve(receiver) ?: return null
            return candidates.distinct().mapNotNull { holder -> contract.key(holder, null)?.first }
                .distinct().singleOrNull()
        }

        internal fun resolveForTypes(receiver: Class<*>, direct: Method?, point: HomeChromeSemanticsPoint?,
                                     holders: Collection<Class<*>>): HostComposeSemanticsAccess? {
            if (direct != null && Modifier.isStatic(direct.modifiers) && direct.returnType == Void.TYPE &&
                direct.parameterTypes.contentEquals(arrayOf(receiver, String::class.java))) {
                return HostComposeSemanticsAccess(receiver) { target, title -> direct.invoke(null, target, title) }
            }
            val contract = Contract.resolve(receiver) ?: return null
            val found = holders.distinct().mapNotNull { holder ->
                val expected = if (point?.holderClass == holder.name && holder.name != LEGACY_HOLDER) point else null
                contract.key(holder, expected)
            }.distinctBy { it.first }.singleOrNull() ?: return null
            val key = found.second
            return HostComposeSemanticsAccess(receiver) { target, title ->
                contract.setter.invoke(target, key, listOf(title))
            }
        }

        private class Contract(val setter: Method, private val keyType: Class<*>) {
            fun key(holder: Class<*>, point: HomeChromeSemanticsPoint?): Pair<HomeChromeSemanticsPoint, Any>? {
                val fields = Lookup.declaredFields(holder, makeAccessible = true)
                if (fields.size > MAX_FIELDS) return null
                val keys = fields.filter { Modifier.isStatic(it.modifiers) && it.type == keyType }
                // Canonical SemanticsProperties defines a family of keys, not an app-owned one-off.
                if (keys.size < 3) return null
                val candidates = if (point == null) keys else keys.filter { it.name == point.keyField }
                return candidates.mapNotNull { field ->
                    val key = runCatching { field.get(null) }.getOrNull() ?: return@mapNotNull null
                    // Real 8.92.1 / 9.13 / 9.15 keys retain two Strings; the other label can be
                    // non-null and is not the name. Their actual toString contract reads only the
                    // true name and survives R8. Do not guess either obfuscated field.
                    if (key.javaClass != keyType || runCatching { key.toString() }.getOrNull() != "AccessibilityKey: $DESCRIPTION") {
                        return@mapNotNull null
                    }
                    HomeChromeSemanticsPoint(holder.name, field.name).takeIf { it.isStructurallyValid() }?.let { it to key }
                }.singleOrNull()
            }

            companion object {
                fun resolve(receiver: Class<*>): Contract? {
                    if (!receiver.isInterface) return null
                    val setter = Lookup.declaredMethods(receiver, makeAccessible = true) {
                        !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterCount == 2 &&
                            it.parameterTypes[1] == Any::class.java && !it.parameterTypes[0].isPrimitive &&
                            !it.parameterTypes[0].isArray && !it.parameterTypes[0].isInterface &&
                            it.parameterTypes[0] != Any::class.java
                    }.singleOrNull() ?: return null
                    val keyType = setter.parameterTypes[0]
                    val fields = Lookup.declaredFields(keyType, makeAccessible = true)
                    if (fields.size > MAX_FIELDS) return null
                    if (fields.none { !Modifier.isStatic(it.modifiers) && it.type == String::class.java }) return null
                    Lookup.methodOrNull(keyType, "toString")?.takeIf {
                        it.declaringClass == keyType && !Modifier.isStatic(it.modifiers) && it.returnType == String::class.java
                    } ?: return null
                    return Contract(setter, keyType)
                }
            }
        }
    }
}
