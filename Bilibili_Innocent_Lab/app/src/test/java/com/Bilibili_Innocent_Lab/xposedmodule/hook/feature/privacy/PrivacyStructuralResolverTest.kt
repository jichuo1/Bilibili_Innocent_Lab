package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.*
import org.junit.Test

class PrivacyStructuralResolverTest {
    @Suppress("unused")
    open class Base {
        fun parentOnly(): Long = 0L
        open fun shadowed(): String = "base"
    }
    @Suppress("unused")
    class Host : Base() {
        var flag: Boolean = false
        var tag: String = ""
        var other: String = ""
        override fun shadowed(): String = "child"
        fun uniqueLong(): Long = 1L
        fun takesString(value: String): Unit = Unit
        fun ambiguousA(): Boolean = true
        fun ambiguousB(): Boolean = false
    }

    @Test fun stableNameResolvesExactMethodAndField() {
        assertNotNull(PrivacyStructuralResolver.method(Host::class.java, "takesString", String::class.java))
        assertNull(PrivacyStructuralResolver.method(Host::class.java, "takesString"))
        assertNotNull(PrivacyStructuralResolver.field(Host::class.java, "flag"))
        assertNull(PrivacyStructuralResolver.field(Host::class.java, "missing"))
    }

    @Test fun uniqueMethodMatchesTheSingleShapeAndFailsClosedWhenAmbiguous() {
        val only = PrivacyStructuralResolver.uniqueMethod(Host::class.java) {
            it.returnType == java.lang.Long.TYPE && it.parameterCount == 0
        }
        assertEquals("uniqueLong", only?.name)
        // Two zero-arg boolean methods -> ambiguous -> null, never a guessed pick.
        assertNull(PrivacyStructuralResolver.uniqueMethod(Host::class.java) {
            it.returnType == java.lang.Boolean.TYPE && it.parameterCount == 0
        })
        // No match -> null.
        assertNull(PrivacyStructuralResolver.uniqueMethod(Host::class.java) {
            it.returnType == java.lang.Double.TYPE
        })
    }

    @Test fun uniqueFieldMatchesShapeAndFailsClosedWhenAmbiguous() {
        assertEquals("flag", PrivacyStructuralResolver.uniqueField(Host::class.java) {
            it.type == java.lang.Boolean.TYPE
        }?.name)
        // tag and other are both String -> ambiguous.
        assertNull(PrivacyStructuralResolver.uniqueField(Host::class.java) { it.type == String::class.java })
    }

    @Test fun superclassScopeIsOptIn() {
        assertNull(PrivacyStructuralResolver.uniqueMethod(Host::class.java, includeSuperclasses = false) {
            it.name == "parentOnly"
        })
        assertNotNull(PrivacyStructuralResolver.uniqueMethod(Host::class.java, includeSuperclasses = true) {
            it.name == "parentOnly"
        })
    }

    @Test fun typeResolvesByStableName() {
        assertEquals(Host::class.java, PrivacyStructuralResolver.type(javaClass.classLoader!!, Host::class.java.name))
        assertNull(PrivacyStructuralResolver.type(javaClass.classLoader!!, "does.not.Exist"))
    }
}
