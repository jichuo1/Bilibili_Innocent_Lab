package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 网关手写 Parcel 事务时唯一没有类型保护的就是事务码。
 *
 * `IXposedService$Stub` 继承 `android.os.Binder`，JVM 单测加载不了它，所以这里不走反射，
 * 而是把运行期类路径上的 `.class` 当字节读出来，解析常量池和字段的 `ConstantValue` 属性，
 * 取到 AIDL 生成的 `TRANSACTION_*` 值。升级 `io.github.libxposed:interface` 时，码值或方法集合
 * 变化会让本测试失败，迫使人工复核 wire format，而不是静默打到别的方法上。
 */
class IXposedServiceTransactionCodesTest {
    /** `interface-102.0.0` 的 AIDL 显式编号 + `FIRST_CALL_TRANSACTION`(1)。 */
    private val expected = linkedMapOf(
        "TRANSACTION_getApiVersion" to 2,
        "TRANSACTION_getFrameworkName" to 3,
        "TRANSACTION_getFrameworkVersion" to 4,
        "TRANSACTION_getFrameworkVersionCode" to 5,
        "TRANSACTION_getFrameworkProperties" to 6,
        "TRANSACTION_getScope" to 11,
        "TRANSACTION_requestScope" to 12,
        "TRANSACTION_removeScope" to 13,
        "TRANSACTION_getRunningTargets" to 14,
        "TRANSACTION_hotReloadModule" to 15,
        "TRANSACTION_requestRemotePreferences" to 21,
        "TRANSACTION_updateRemotePreferences" to 22,
        "TRANSACTION_deleteRemotePreferences" to 23,
        "TRANSACTION_listRemoteFiles" to 31,
        "TRANSACTION_openRemoteFile" to 32,
        "TRANSACTION_deleteRemoteFile" to 33
    )

    private fun stubConstants(): Map<String, Int> {
        val resource = "io/github/libxposed/service/IXposedService\$Stub.class"
        val stream = IXposedServiceTransactionCodesTest::class.java.classLoader.getResourceAsStream(resource)
        assertNotNull("$resource must be on the unit-test runtime classpath", stream)
        return stream!!.use { readIntConstants(it.readBytes()) }
            .filterKeys { it.startsWith("TRANSACTION_") }
    }

    @Test
    fun `AIDL transaction table of the pinned libxposed interface is unchanged`() {
        val actual = stubConstants()
        assertEquals(
            "IXposedService methods or numbering changed; re-verify every transaction the gateway issues",
            expected.toSortedMap(),
            actual.toSortedMap()
        )
    }

    @Test
    fun `gateway constants match the generated stub`() {
        val actual = stubConstants()
        assertEquals(
            actual.getValue("TRANSACTION_requestRemotePreferences"),
            NPatchRemoteGateway.TRANSACTION_REQUEST_REMOTE_PREFERENCES
        )
        assertEquals(
            actual.getValue("TRANSACTION_updateRemotePreferences"),
            NPatchRemoteGateway.TRANSACTION_UPDATE_REMOTE_PREFERENCES
        )
    }

    @Test
    fun `class constant reader decodes long and double slots without shifting later fields`() {
        // Hand-assembled class: two constant-pool slots for a long, then an int field with ConstantValue.
        val bytes = syntheticClassWithLongBeforeIntField()
        assertEquals(mapOf("TRANSACTION_x" to 7), readIntConstants(bytes))
    }

    private fun syntheticClassWithLongBeforeIntField(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        DataOutput(out).apply {
            u4(0xCAFEBABE.toInt()); u2(0); u2(52)
            // constant pool: 1=Utf8 "TRANSACTION_x", 2=Utf8 "I", 3=Utf8 "ConstantValue", 4=Long(5) [4,5], 6=Integer(7)
            u2(7)
            u1(1); utf("TRANSACTION_x")
            u1(1); utf("I")
            u1(1); utf("ConstantValue")
            u1(5); u4(0); u4(5)
            u1(3); u4(7)
            u2(0x0021); u2(0); u2(0) // access, this, super
            u2(0) // interfaces
            u2(1) // fields
            u2(0x0018); u2(1); u2(2) // static final, name=#1, desc=#2
            u2(1) // attributes
            u2(3); u4(2); u2(6) // ConstantValue -> #6
            u2(0) // methods
            u2(0) // class attributes
        }
        return out.toByteArray()
    }

    private class DataOutput(private val out: java.io.ByteArrayOutputStream) {
        private val data = java.io.DataOutputStream(out)
        fun u1(v: Int) = data.writeByte(v)
        fun u2(v: Int) = data.writeShort(v)
        fun u4(v: Int) = data.writeInt(v)
        fun utf(s: String) = data.writeUTF(s)
    }

    internal companion object {
        /** 只返回 `static final int`（描述符 `I`）且带 `ConstantValue` 的字段。 */
        fun readIntConstants(classBytes: ByteArray): Map<String, Int> {
            val input = DataInputStream(ByteArrayInputStream(classBytes))
            check(input.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
            input.readUnsignedShort()
            input.readUnsignedShort()
            val count = input.readUnsignedShort()
            val utf8 = arrayOfNulls<String>(count)
            val ints = arrayOfNulls<Int>(count)
            var index = 1
            while (index < count) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()
                    3 -> ints[index] = input.readInt()
                    4 -> input.readInt()
                    5, 6 -> { input.readLong(); index++ }
                    7, 8, 16, 19, 20 -> input.readUnsignedShort()
                    9, 10, 11, 12, 17, 18 -> input.readInt()
                    15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                    else -> error("unsupported constant pool tag $tag at #$index")
                }
                index++
            }
            input.readUnsignedShort() // access
            input.readUnsignedShort() // this
            input.readUnsignedShort() // super
            repeat(input.readUnsignedShort()) { input.readUnsignedShort() }
            val result = linkedMapOf<String, Int>()
            repeat(input.readUnsignedShort()) {
                input.readUnsignedShort()
                val name = utf8[input.readUnsignedShort()]
                val descriptor = utf8[input.readUnsignedShort()]
                repeat(input.readUnsignedShort()) {
                    val attribute = utf8[input.readUnsignedShort()]
                    val length = input.readInt()
                    if (attribute == "ConstantValue" && descriptor == "I" && length == 2) {
                        val value = ints[input.readUnsignedShort()]
                        if (name != null && value != null) result[name] = value
                    } else {
                        input.skipBytes(length)
                    }
                }
            }
            return result
        }
    }
}
