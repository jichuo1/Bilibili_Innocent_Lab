package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 运行期 Hook 点注册与诊断边界。
 *
 * 它只保存逻辑 Hook 点 ID 和反射 [Member]，不保存 Activity/View/宿主业务实例。
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
        MISSING_FIELD,
        MISSING_CONSTRUCTOR,
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

    private data class RegistrationKey(val id: String, val member: Member)

    private val diagnostics = LinkedHashMap<String, Diagnostic>()
    private val registrations = ConcurrentHashMap.newKeySet<RegistrationKey>()

    private fun record(diagnostic: Diagnostic) {
        synchronized(diagnostics) {
            diagnostics[diagnostic.id] = diagnostic
        }
    }

    private fun memberLabel(member: Member): String = buildString {
        append(member.declaringClass.name)
        append('#')
        append(if (member is Constructor<*>) "<init>" else member.name)
        if (member !is Field) {
            append('(')
            val parameterTypes = when (member) {
                is Method -> member.parameterTypes
                is Constructor<*> -> member.parameterTypes
                else -> emptyArray()
            }
            append(parameterTypes.joinToString(",") { it.name })
            append(')')
        }
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

    /** 统一解析需要在安装期缓存的宿主类型，并把缺失类型写入同一诊断表。 */
    fun resolveClass(id: String, className: String): Class<*>? = resolveOwner(id, className)

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

    /**
     * 统一解析适配结果中缓存的字段名，并把缺失原因写入同一诊断表。
     * 返回的 [Field] 由 KavaMemberLookup 按 Class/字段名缓存，不持有宿主实例。
     */
    fun resolveField(
        id: String,
        className: String,
        fieldName: String,
        includeSuperclasses: Boolean = false
    ): Field? {
        val owner = resolveOwner(id, className) ?: return null
        return resolveField(id, owner, fieldName, includeSuperclasses)
    }

    fun resolveField(
        id: String,
        owner: Class<*>,
        fieldName: String,
        includeSuperclasses: Boolean = false
    ): Field? {
        val field = KavaMemberLookup.fieldOrNull(owner, fieldName, includeSuperclasses)
        if (field == null) {
            record(Diagnostic(id, State.MISSING_FIELD, detail = "${owner.name}#$fieldName"))
            return null
        }
        record(Diagnostic(id, State.RESOLVED, memberLabel(field)))
        return field
    }

    /** 解析需要在 Hook 回调中直接调用的构造器，并统一记录参数类/构造器缺失。 */
    fun resolveConstructor(
        id: String,
        className: String,
        parameterClassNames: List<String>
    ): Constructor<*>? {
        val owner = resolveOwner(id, className) ?: return null
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
        val constructor = KavaMemberLookup.constructorOrNull(
            owner,
            *parameterTypes.toTypedArray()
        )
        if (constructor == null) {
            record(
                Diagnostic(
                    id,
                    State.MISSING_CONSTRUCTOR,
                    detail = "$className(${parameterClassNames.joinToString(",")})"
                )
            )
            return null
        }
        record(Diagnostic(id, State.RESOLVED, memberLabel(constructor)))
        return constructor
    }

    private fun resolveOwner(id: String, className: String): Class<*>? {
        val owner = KavaMemberLookup.classOrNull(classLoader, className)
        if (owner == null) record(Diagnostic(id, State.MISSING_CLASS, detail = className))
        return owner
    }

    private fun resolveParameterClass(name: String): Class<*>? = when (name) {
        "boolean" -> classOf<Boolean>()
        "byte" -> classOf<Byte>()
        "char" -> classOf<Char>()
        "short" -> classOf<Short>()
        "int" -> classOf<Int>()
        "long" -> classOf<Long>()
        "float" -> classOf<Float>()
        "double" -> classOf<Double>()
        "void" -> Void.TYPE
        else -> KavaMemberLookup.classOrNull(classLoader, name)
    }

    /** 同一逻辑 ID + 同一反射成员只允许安装一次，避免重适配回调重复注册。 */
    fun claim(id: String, member: Member): Boolean {
        val claimed = registrations.add(RegistrationKey(id, member))
        if (!claimed) record(Diagnostic(id, State.DUPLICATE, memberLabel(member)))
        return claimed
    }

    fun markInstalled(id: String, member: Member) {
        record(Diagnostic(id, State.INSTALLED, memberLabel(member)))
    }

    fun markFailed(id: String, member: Member?, throwable: Throwable) {
        record(
            Diagnostic(
                id,
                State.FAILED,
                member?.let(::memberLabel),
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
