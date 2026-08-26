package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行期 Hook 点注册与诊断边界。
 *
 * 它只保存逻辑 Hook 点 ID 和 [Method]，不保存 Activity/View/宿主业务实例。
 * 成员枚举与负查询缓存由 [KavaMemberLookup] 统一承担。
 */
internal class HookPointRegistry(
    private val classLoader: ClassLoader?
) {

    enum class State {
        RESOLVED,
        MISSING_CLASS,
        MISSING_PARAMETER_CLASS,
        MISSING_METHOD,
        AMBIGUOUS_METHOD,
        INSTALLED,
        DUPLICATE,
        FAILED
    }

    data class Diagnostic(
        val id: String,
        val state: State,
        val member: String? = null,
        val detail: String? = null
    )

    private data class RegistrationKey(val id: String, val method: Method)

    private val diagnostics = LinkedHashMap<String, Diagnostic>()
    private val registrations = ConcurrentHashMap.newKeySet<RegistrationKey>()

    private fun record(diagnostic: Diagnostic) {
        synchronized(diagnostics) {
            diagnostics[diagnostic.id] = diagnostic
        }
    }

    private fun memberLabel(method: Method): String = buildString {
        append(method.declaringClass.name)
        append('#')
        append(method.name)
        append('(')
        append(method.parameterTypes.joinToString(",") { it.name })
        append(')')
    }

    fun resolveFirst(id: String, className: String, methodName: String): Method? {
        val owner = resolveOwner(id, className) ?: return null
        val method = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            it.name == methodName
        }.firstOrNull()
        if (method == null) {
            record(Diagnostic(id, State.MISSING_METHOD, detail = "$className#$methodName"))
            return null
        }
        record(Diagnostic(id, State.RESOLVED, memberLabel(method)))
        return method
    }

    fun resolveAll(id: String, className: String, methodName: String): List<Method> {
        val owner = resolveOwner(id, className) ?: return emptyList()
        val methods = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            it.name == methodName
        }
        if (methods.isEmpty()) {
            record(Diagnostic(id, State.MISSING_METHOD, detail = "$className#$methodName"))
        } else {
            record(
                Diagnostic(
                    id,
                    State.RESOLVED,
                    member = methods.joinToString("|") { memberLabel(it) },
                    detail = "count=${methods.size}"
                )
            )
        }
        return methods
    }

    fun resolveExact(
        id: String,
        owner: Class<*>,
        methodName: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        val method = KavaMemberLookup.methodOrNull(owner, methodName, *parameterTypes)
        if (method == null) {
            record(Diagnostic(id, State.MISSING_METHOD, detail = "${owner.name}#$methodName"))
            return null
        }
        record(Diagnostic(id, State.RESOLVED, memberLabel(method)))
        return method
    }

    fun resolveAdapted(
        id: String,
        className: String,
        methodName: String,
        parameterClassNames: List<String>?
    ): Method? {
        val owner = resolveOwner(id, className) ?: return null
        if (parameterClassNames == null) {
            val candidates = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
                it.name == methodName
            }
            if (candidates.size != 1) {
                record(
                    Diagnostic(
                        id,
                        if (candidates.isEmpty()) State.MISSING_METHOD else State.AMBIGUOUS_METHOD,
                        detail = "$className#$methodName candidates=${candidates.size}"
                    )
                )
                return null
            }
            val method = candidates.single()
            record(Diagnostic(id, State.RESOLVED, memberLabel(method)))
            return method
        }
        val parameterTypes = ArrayList<Class<*>>(parameterClassNames.size)
        for (parameterClassName in parameterClassNames) {
            val parameterType = resolveParameterClass(parameterClassName)
            if (parameterType == null) {
                record(
                    Diagnostic(
                        id,
                        State.MISSING_PARAMETER_CLASS,
                        detail = parameterClassName
                    )
                )
                return null
            }
            parameterTypes += parameterType
        }
        return resolveExact(id, owner, methodName, *parameterTypes.toTypedArray())
    }

    private fun resolveOwner(id: String, className: String): Class<*>? {
        val owner = KavaMemberLookup.classOrNull(classLoader, className)
        if (owner == null) record(Diagnostic(id, State.MISSING_CLASS, detail = className))
        return owner
    }

    private fun resolveParameterClass(name: String): Class<*>? = when (name) {
        "boolean" -> Boolean::class.javaPrimitiveType
        "byte" -> Byte::class.javaPrimitiveType
        "char" -> Char::class.javaPrimitiveType
        "short" -> Short::class.javaPrimitiveType
        "int" -> Int::class.javaPrimitiveType
        "long" -> Long::class.javaPrimitiveType
        "float" -> Float::class.javaPrimitiveType
        "double" -> Double::class.javaPrimitiveType
        "void" -> Void.TYPE
        else -> KavaMemberLookup.classOrNull(classLoader, name)
    }

    /** 同一逻辑 ID + 同一 Method 只允许安装一次，避免重适配回调重复注册。 */
    fun claim(id: String, method: Method): Boolean {
        val claimed = registrations.add(RegistrationKey(id, method))
        if (!claimed) record(Diagnostic(id, State.DUPLICATE, memberLabel(method)))
        return claimed
    }

    fun markInstalled(id: String, method: Method) {
        record(Diagnostic(id, State.INSTALLED, memberLabel(method)))
    }

    fun markFailed(id: String, method: Method?, throwable: Throwable) {
        record(
            Diagnostic(
                id,
                State.FAILED,
                method?.let(::memberLabel),
                "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}".trim()
            )
        )
    }

    fun snapshot(): List<Diagnostic> = synchronized(diagnostics) {
        diagnostics.values.toList()
    }

    fun summary(): String {
        val snapshot = snapshot()
        val counts = snapshot.groupingBy { it.state }.eachCount()
        return State.entries.joinToString(",") { state ->
            "${state.name.lowercase()}=${counts[state] ?: 0}"
        }
    }
}
