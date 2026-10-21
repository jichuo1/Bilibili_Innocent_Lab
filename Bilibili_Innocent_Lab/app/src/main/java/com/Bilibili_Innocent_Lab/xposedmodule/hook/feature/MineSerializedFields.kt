package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Maps only requested serialized elements to the exact object's fields. R8 field names and field
 * order are irrelevant: the host serializer supplies each root element's actual value. A unique
 * identity match is required, and ambiguous/null values never authorize a guessed write.
 * No JSON, nested serialization, host instances or credentials are retained.
 */
internal class MineSerializedFields(
    private val owner: Class<*>, private val wanted: Set<String>,
    // A nested model is already proven by its owning root slot; R8 may rename its class while
    // retaining the original serial name. Root models still require their exact serial name.
    private val expectedSerialName: String? = owner.name
) {
    private val fields = Lookup.declaredFields(owner, makeAccessible = true) { !it.isStatic }
    private val found = linkedMapOf<String, Field>()
    private var attempts = 0
    private var captureFailures = 0
    private val ambiguous = HashMap<String, Int>()
    private var absent = emptySet<String>()
    private val codec by lazy { Codec.resolve(owner, expectedSerialName) }

    @Synchronized fun resolve(value: Any): Map<String, Field> {
        val remaining = wanted - found.keys - absent - ambiguous.filterValues { it >= MAX_ATTEMPTS }.keys
        if (!owner.isInstance(value) || captureFailures >= MAX_ATTEMPTS || remaining.isEmpty()) return found.toMap()
        val access = codec ?: run { captureFailures = MAX_ATTEMPTS; return found.toMap() }
        val captures = runCatching { access.capture(value) }.getOrNull()
            ?: run { captureFailures++; return found.toMap() }
        absent = wanted - captures.keys
        var attempted = false
        remaining.forEach { element ->
            val captured = captures[element] ?: return@forEach
            // Default constructors often share one emptyList singleton across several fields.
            // They provide no identifying information and must not exhaust the later real response's
            // retry budget, nor trigger a full field scan on every such empty construction.
            if (captured is List<*> && captured.isEmpty()) return@forEach
            attempted = true
            val matches = fields.filter { field ->
                val current = runCatching { field.get(value) }.getOrNull()
                if (field.type.isPrimitive) current != null && current.javaClass == captured.javaClass && current == captured
                else current === captured
            }
            val match = matches.singleOrNull()
            if (match != null) found[element] = match
            else ambiguous[element] = (ambiguous[element] ?: 0) + 1
        }
        if (attempted) attempts++
        return found.toMap()
    }

    fun values(value: Any): Map<String, Any?> =
        if (!owner.isInstance(value)) emptyMap() else runCatching { codec?.capture(value).orEmpty() }.getOrDefault(emptyMap())

    internal fun attempts(): Int = synchronized(this) { attempts }

    private class Codec(
        val serializer: Any, val descriptor: Any, val serialize: Method,
        val encoder: Class<*>, val composite: Class<*>, val names: Map<Int, String>
    ) {
        fun capture(value: Any): Map<String, Any?> {
            val captured = linkedMapOf<String, Any?>()
            var encoderProxy: Any? = null
            val compositeProxy = Proxy.newProxyInstance(composite.classLoader, arrayOf(composite)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "MineFieldCapture"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "shouldEncodeElementDefault" -> true
                    "encodeInlineElement" -> encoderProxy
                    "endStructure" -> null
                    else -> {
                        if (method.name.startsWith("encode") && args?.getOrNull(0) === descriptor) {
                            val index = args.getOrNull(1) as? Int
                            val name = names[index]
                            if (name != null) captured[name] = args.lastOrNull()
                        }
                        null
                    }
                }
            }
            encoderProxy = Proxy.newProxyInstance(encoder.classLoader, arrayOf(encoder)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "MineRootCapture"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "beginStructure", "beginCollection" -> compositeProxy
                    "encodeInline" -> encoderProxy
                    else -> null
                }
            }
            serialize.invoke(serializer, encoderProxy, value)
            return captured
        }

        companion object {
            fun resolve(owner: Class<*>, expectedSerialName: String?): Codec? = runCatching {
                val loader = owner.classLoader ?: return@runCatching null
                val serializerType = Lookup.classOrNull(loader, "kotlinx.serialization.KSerializer") ?: return@runCatching null
                val encoder = Lookup.classOrNull(loader, "kotlinx.serialization.encoding.Encoder")?.takeIf { it.isInterface }
                    ?: return@runCatching null
                val composite = Lookup.classOrNull(loader, "kotlinx.serialization.encoding.CompositeEncoder")?.takeIf { it.isInterface }
                    ?: return@runCatching null
                val descriptorType = Lookup.classOrNull(loader, "kotlinx.serialization.descriptors.SerialDescriptor")
                    ?: return@runCatching null
                val serializer = Lookup.declaredFields(owner, makeAccessible = true) { it.isStatic }
                    .firstNotNullOfOrNull { field ->
                        val factory = Lookup.declaredMethods(field.type, makeAccessible = true) {
                            !it.isStatic && it.parameterCount == 0 && serializerType.isAssignableFrom(it.returnType)
                        }.singleOrNull() ?: return@firstNotNullOfOrNull null
                        field.get(null)?.let { factory.invoke(it) }
                    } ?: return@runCatching null
                val descriptor = serializerType.getMethod("getDescriptor").invoke(serializer) ?: return@runCatching null
                val serialName = descriptorType.getMethod("getSerialName").invoke(descriptor) as? String
                if (serialName.isNullOrBlank() || (expectedSerialName != null && serialName != expectedSerialName)) return@runCatching null
                val count = descriptorType.getMethod("getElementsCount").invoke(descriptor) as Int
                if (count !in 1..128) return@runCatching null
                val getName = descriptorType.getMethod("getElementName", Int::class.javaPrimitiveType)
                val names = (0 until count).associateWith { normalize(getName.invoke(descriptor, it) as String) }
                if (names.values.distinct().size != count) return@runCatching null
                val serialize = Lookup.methods(serializer.javaClass, includeSuperclasses = true, makeAccessible = true) {
                    !it.isStatic && it.name == "serialize" && it.parameterTypes.contentEquals(arrayOf(encoder, Any::class.java))
                }.singleOrNull() ?: return@runCatching null
                Codec(serializer, descriptor, serialize, encoder, composite, names)
            }.getOrNull()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4
        fun normalize(value: String): String = value.replace("_", "").lowercase(java.util.Locale.ROOT)
    }
}
