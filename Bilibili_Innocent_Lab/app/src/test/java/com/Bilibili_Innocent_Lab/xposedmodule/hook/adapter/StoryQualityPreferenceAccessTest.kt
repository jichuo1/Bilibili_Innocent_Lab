package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import kotlin.jvm.internal.DefaultConstructorMarker
import org.junit.Assert.*
import org.junit.Test

class StoryQualityPreferenceAccessTest {
    interface IndirectInterface {
        object Automatic : IndirectParent()
        class Selected(val qn: Int) : IndirectInterface
    }
    open class IndirectParent : IndirectInterface

    interface ExtraMarker
    interface AdditionalInterface {
        object Automatic : AdditionalInterface, ExtraMarker
        class Selected(val qn: Int) : AdditionalInterface
    }

    interface ChangedInterface {
        object Automatic : ChangedInterface
        class Selected(qn: Int) : ChangedInterface { val value = qn + 1 }
    }
    abstract class Plain {
        object Automatic : Plain()
        class Selected(val qn: Int) : Plain()
        class Unrelated
    }

    // 字母名故意与已知宿主相反，避免按 $a/$b 猜 Auto/Fixed。
    abstract class Reversed {
        class a(val value: Int) : Reversed()
        object b : Reversed()
    }

    abstract class Bridged {
        object Automatic : Bridged()
        class Selected private constructor(val qn: Int) : Bridged() {
            constructor(qn: Int, marker: DefaultConstructorMarker?) : this(qn)
        }
    }

    abstract class MarkerOnly {
        object Automatic : MarkerOnly()
        class Selected(val qn: Int, marker: DefaultConstructorMarker?) : MarkerOnly()
    }

    // 9.16 R8 将无状态 sealed 基类改成接口，两个策略仍保持原有语义。
    sealed interface Optimized {
        object Automatic : Optimized
        class Selected(val qn: Int) : Optimized
    }

    interface InheritedState {
        object Automatic : InheritedState
        open class Parent(val hidden: Int)
        class Selected(val qn: Int) : Parent(1), InheritedState
    }

    abstract class ChangedQn {
        object Automatic : ChangedQn()
        class Selected(qn: Int) : ChangedQn() { val value = qn + 1 }
    }

    abstract class InconsistentBridge {
        object Automatic : InconsistentBridge()
        class Selected private constructor(val qn: Int) : InconsistentBridge() {
            constructor(qn: Int, marker: DefaultConstructorMarker?) : this(qn + 1)
        }
    }

    abstract class MultipleFixed {
        object Automatic : MultipleFixed()
        class First(val qn: Int) : MultipleFixed()
        class Second(val qn: Int) : MultipleFixed()
    }

    abstract class MultipleAuto {
        object First : MultipleAuto()
        object Second : MultipleAuto()
        class Selected(val qn: Int) : MultipleAuto()
    }

    abstract class UnknownSubtype {
        object Automatic : UnknownSubtype()
        class Selected(val qn: Int) : UnknownSubtype()
        class Unknown(val flag: Boolean) : UnknownSubtype()
    }

    abstract class WrongField {
        object Automatic : WrongField()
        class Selected(val qn: Long) : WrongField()
    }

    abstract class WrongFactory {
        object Automatic : WrongFactory()
        class Selected(text: String) : WrongFactory() { val qn = text.length }
    }

    abstract class MutableQn {
        object Automatic : MutableQn()
        class Selected(var qn: Int) : MutableQn()
    }

    private val loader = javaClass.classLoader!!
    private fun resolve(base: Class<*>, hostLoader: ClassLoader = loader) =
        StoryQualityPreferenceAccess.resolve(hostLoader, base)

    @Test fun `resolves actual host interface and private marker factory without guessing suffixes`() {
        val access = requireNotNull(resolve(com.bilibili.player.tangram.basic.d::class.java))
        assertSame(com.bilibili.player.tangram.basic.d.a.a, access.auto)
        for (qn in listOf(0, 16, 32, 64, 80, 127)) {
            val fixed = requireNotNull(access.fixed(qn))
            assertEquals(qn, access.qualityOf(fixed))
            assertFalse(access.isAuto(fixed))
        }
        assertNull(access.fixed(-1))
    }

    @Test fun `rejects indirect implementations of an interface family`() {
        assertNull(resolve(IndirectInterface::class.java))
    }

    @Test fun `rejects additional interface contracts on family members`() {
        assertNull(resolve(AdditionalInterface::class.java))
    }

    @Test fun `interface constructors still must preserve the requested quality`() {
        assertNull(resolve(ChangedInterface::class.java))
    }

    @Test fun `resolves structural subtypes and round trips supported qualities`() {
        val access = requireNotNull(resolve(Plain::class.java))
        assertSame(Plain.Automatic, access.auto)
        assertTrue(access.isAuto(Plain.Automatic))
        for (qn in listOf(0, 16, 32, 64, 74, 80, 112, 116, 120, 127)) {
            val selected = requireNotNull(access.fixed(qn))
            assertEquals(qn, access.qualityOf(selected))
            assertFalse(access.isAuto(selected))
        }
        assertNull(access.fixed(-1))
        assertNull(access.qualityOf(access.auto))
        assertNull(access.qualityOf(Any()))
        assertNull(access.qualityOf(null))
    }

    @Test fun `does not assign semantics by obfuscated suffix`() {
        val access = requireNotNull(resolve(Reversed::class.java))
        assertSame(Reversed.b, access.auto)
        assertEquals(80, access.qualityOf(access.fixed(80)))
    }

    @Test fun `accepts private int factory plus a consistent public marker bridge`() {
        val access = requireNotNull(resolve(Bridged::class.java))
        assertEquals(116, access.qualityOf(access.fixed(116)))
        assertSame(Bridged.Automatic, access.auto)
    }

    @Test fun `supports marker only factory from the actual host loader`() {
        val access = requireNotNull(resolve(MarkerOnly::class.java))
        assertEquals(120, access.qualityOf(access.fixed(120)))
        val hidesMarker = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "kotlin.jvm.internal.DefaultConstructorMarker") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(resolve(MarkerOnly::class.java, hidesMarker))
    }

    @Test fun `supports R8 interface strategy with direct stateless implementations`() {
        val access = requireNotNull(resolve(Optimized::class.java))
        assertSame(Optimized.Automatic, access.auto)
        assertEquals(80, access.qualityOf(access.fixed(80)))
        assertNull(resolve(InheritedState::class.java))
    }

    @Test fun `rejects factories that fail the quality round trip`() {
        assertNull(resolve(ChangedQn::class.java))
        assertNull(resolve(InconsistentBridge::class.java))
    }

    @Test fun `rejects ambiguous or unknown strategy families`() {
        assertNull(resolve(MultipleFixed::class.java))
        assertNull(resolve(MultipleAuto::class.java))
        assertNull(resolve(UnknownSubtype::class.java))
        assertNull(resolve(Any::class.java))
    }

    @Test fun `rejects wrong field constructor and mutable quality abi`() {
        assertNull(resolve(WrongField::class.java))
        assertNull(resolve(WrongFactory::class.java))
        assertNull(resolve(MutableQn::class.java))
    }

    @Test fun `rejects a base or subtype that cannot resolve in the host loader`() {
        val rejectsBase = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == Plain::class.java.name) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val rejectsChild = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == Plain.Selected::class.java.name) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(resolve(Plain::class.java, rejectsBase))
        assertNull(resolve(Plain::class.java, rejectsChild))
    }
}
