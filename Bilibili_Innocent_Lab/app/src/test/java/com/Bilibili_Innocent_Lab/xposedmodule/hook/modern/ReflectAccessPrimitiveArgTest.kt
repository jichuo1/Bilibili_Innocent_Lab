package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 [ReflectAccess] 对原始类型参数的方法匹配。
 *
 * `compatible()` 依赖 **装箱** 类型做 `isInstance` 判定，而 `classOf<java.lang.Boolean>()`
 * 默认解析为原始类型 `boolean`，必须显式传 `primitiveType = false`。原始类型的
 * `isInstance` 恒为 false，漏传会让所有原始类型参数的方法匹配失效。本测试同时锁定这条
 * 差异，避免后来者"顺手"省掉这个参数。
 */
class ReflectAccessPrimitiveArgTest {

    @Suppress("unused")
    private class Target {
        fun withBoolean(value: Boolean): String = "bool:$value"
        fun withInt(value: Int): String = "int:$value"
        fun withLong(value: Long): String = "long:$value"
        fun withDouble(value: Double): String = "double:$value"
        fun withChar(value: Char): String = "char:$value"
    }

    @Test
    fun `resolves methods whose parameters are primitives`() {
        val target = Target()
        assertEquals("bool:true", ReflectAccess.callMethod(target, "withBoolean", true))
        assertEquals("int:7", ReflectAccess.callMethod(target, "withInt", 7))
        assertEquals("long:8", ReflectAccess.callMethod(target, "withLong", 8L))
        assertEquals("double:1.5", ReflectAccess.callMethod(target, "withDouble", 1.5))
        assertEquals("char:x", ReflectAccess.callMethod(target, "withChar", 'x'))
    }

    /**
     * classOf 默认解析为原始类型，装箱类型必须显式传 `primitiveType = false`。
     * 两者不可混用：原始类型 Class 的 `isInstance` 恒为 false。
     */
    @Test
    fun `classOf needs an explicit flag to resolve the boxed type`() {
        assertTrue(classOf<java.lang.Boolean>().isPrimitive)
        assertEquals(java.lang.Boolean.TYPE, classOf<java.lang.Boolean>())
        assertEquals(java.lang.Boolean::class.java, classOf<java.lang.Boolean>(primitiveType = false))
        assertTrue(!classOf<java.lang.Boolean>(primitiveType = false).isPrimitive)
    }

    /** Kotlin 的 `Boolean::class.java` 也是原始类型，与 `javaPrimitiveType` 完全相同。 */
    @Test
    fun `kotlin class java is the primitive type not the boxed one`() {
        assertEquals(Boolean::class.javaPrimitiveType, Boolean::class.java)
        assertEquals(Int::class.javaPrimitiveType, Int::class.java)
        assertTrue(Boolean::class.java.isPrimitive)
    }
}
