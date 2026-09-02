package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「隐藏」标志对 **原始与装箱** 两种字段声明的写入。
 *
 * 旧实现写作 `Boolean::class.javaPrimitiveType, Boolean::class.java`，看似覆盖了两者，
 * 但 Kotlin 的 `Boolean::class.java` 本身就是原始类型，两个分支条件完全重复；宿主若把
 * `visible` 声明为 `java.lang.Boolean`/`Integer`（FastJSON 反序列化的可空字段常如此），
 * 隐藏操作会被静默跳过，组件仍然显示。
 *
 * 断言一律经反射读写，避免 Kotlin 在源码层把包装类型映射回原始类型而让测试失真。
 */
class MineComponentHiddenFlagWriterTest {

    @Suppress("unused")
    private class Item {
        @JvmField var primitiveVisible: Boolean = true
        @JvmField var boxedVisible: java.lang.Boolean? = null
        @JvmField var primitiveCount: Int = 1
        @JvmField var boxedCount: java.lang.Integer? = null
        @JvmField var text: String? = "keep"
    }

    private fun fieldOf(name: String) = Item::class.java.getDeclaredField(name)

    /** 前置条件：固件确实同时提供了原始与装箱字段，否则后面的断言毫无意义。 */
    @Test
    fun `fixture really declares both primitive and boxed fields`() {
        assertTrue(fieldOf("primitiveVisible").type.isPrimitive)
        assertTrue(fieldOf("primitiveCount").type.isPrimitive)
        assertEquals(java.lang.Boolean::class.java, fieldOf("boxedVisible").type)
        assertEquals(java.lang.Integer::class.java, fieldOf("boxedCount").type)
    }

    @Test
    fun `writes the hidden value to primitive and boxed fields alike`() {
        val item = Item()

        assertTrue(MineComponentHiddenFlagWriter.apply(item, fieldOf("primitiveVisible")))
        assertEquals(false, fieldOf("primitiveVisible").get(item))

        assertTrue(MineComponentHiddenFlagWriter.apply(item, fieldOf("boxedVisible")))
        assertEquals(false, fieldOf("boxedVisible").get(item))

        assertTrue(MineComponentHiddenFlagWriter.apply(item, fieldOf("primitiveCount")))
        assertEquals(0, fieldOf("primitiveCount").get(item))

        assertTrue(MineComponentHiddenFlagWriter.apply(item, fieldOf("boxedCount")))
        assertEquals(0, fieldOf("boxedCount").get(item))
    }

    /** 无法表达「隐藏」语义的类型必须原样保留，不猜测写入。 */
    @Test
    fun `leaves unsupported field types untouched`() {
        val item = Item()

        assertFalse(MineComponentHiddenFlagWriter.apply(item, fieldOf("text")))
        assertEquals("keep", fieldOf("text").get(item))

        assertFalse(MineComponentHiddenFlagWriter.apply(item, null))
        assertNull(MineComponentHiddenFlagWriter.hiddenValueFor(String::class.java))
    }

    @Test
    fun `resolves a hidden value for both representations of each supported type`() {
        listOf("primitiveVisible", "boxedVisible").forEach { name ->
            assertEquals(false, MineComponentHiddenFlagWriter.hiddenValueFor(fieldOf(name).type))
        }
        listOf("primitiveCount", "boxedCount").forEach { name ->
            assertEquals(0, MineComponentHiddenFlagWriter.hiddenValueFor(fieldOf(name).type))
        }
    }
}
