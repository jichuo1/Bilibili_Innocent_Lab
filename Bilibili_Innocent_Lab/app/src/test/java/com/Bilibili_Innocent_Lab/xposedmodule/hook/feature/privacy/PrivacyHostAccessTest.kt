package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyHostAccessTest {
    open class MessageBase {
        fun toBuilder(): Builder = Builder()
        open fun label(): String = "parent"
    }
    class Message : MessageBase() { override fun label(): String = "child" }
    open class BuilderBase { fun build(): String = "serialized" }
    class Builder : BuilderBase() {
        var stamp = -1L
        fun setFts(value: Long): Builder { stamp = value; return this }
    }
    class Ambiguous {
        fun pick(value: CharSequence): String = value.toString()
        fun pick(value: java.io.Serializable): String = value.toString()
    }
    private val access = PrivacyHostAccess(javaClass.classLoader!!)

    @Test fun generatedMessageAndBuilderMethodsMayBeDeclaredOnParents() {
        val builder = access.call(Message(), "toBuilder") as Builder
        assertSame(builder, access.call(builder, "setFts", 0L))
        assertEquals(0L, builder.stamp)
        assertEquals("serialized", access.call(builder, "build"))
        // Exercise the cached lookup as well.
        assertEquals("serialized", access.call(builder, "build"))
    }
    @Test fun anOverrideIsNotMistakenForAnAmbiguousOverload() {
        assertEquals("child", access.call(Message(), "label"))
    }
    @Test fun unrelatedApplicableOverloadsStillFailClosed() {
        assertTrue(runCatching { access.call(Ambiguous(), "pick", "value") }.isFailure)
    }
}
