package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.content.Context
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation

/** 8.89.0+ 命名协议：bindToView 与三个重用键。 */
interface NamedComponentFixture {
    fun createViewEntry(context: Context, parent: ViewGroup): NamedEntryFixture
    fun bindToView(entry: NamedEntryFixture, continuation: Continuation<*>): Any?
    fun viewReusingKey(): Any
    fun identityEqualityKey(): Any
    fun contentEqualityKey(): Any
}
interface NamedEntryFixture { fun getRoot(): View }

/** 8.84.0–8.88.0 混淆协议：接口与 entry 名称被混淆、bindToView 变为 a，其余成员保留。 */
interface ObfuscatedComponentFixture {
    fun createViewEntry(context: Context, parent: ViewGroup): ObfuscatedEntryFixture
    fun a(entry: ObfuscatedEntryFixture, continuation: Continuation<*>): Any?
    fun viewReusingKey(): Any
    fun identityEqualityKey(): Any
    fun contentEqualityKey(): Any
}
interface ObfuscatedEntryFixture { fun getRoot(): View }
interface UnrelatedMarkerFixture { fun mark(): Int }
abstract class ObfuscatedAnchorFixture : UnrelatedMarkerFixture, ObfuscatedComponentFixture

/** 宿主若再加入抽象成员，Proxy 会把调用交给处理器；必须在安装期拒绝。 */
interface ExtendedComponentFixture {
    fun createViewEntry(context: Context, parent: ViewGroup): NamedEntryFixture
    fun bindToView(entry: NamedEntryFixture, continuation: Continuation<*>): Any?
    fun viewReusingKey(): Any
    fun identityEqualityKey(): Any
    fun contentEqualityKey(): Any
    fun onRecycled(entry: NamedEntryFixture)
}
interface ExtendedEntryFixture { fun getRoot(): View; fun binding(): Any }
interface ExtendedEntryComponentFixture {
    fun createViewEntry(context: Context, parent: ViewGroup): ExtendedEntryFixture
    fun bindToView(entry: ExtendedEntryFixture, continuation: Continuation<*>): Any?
    fun viewReusingKey(): Any
    fun identityEqualityKey(): Any
    fun contentEqualityKey(): Any
}

class InsightComponentContractTest {
    private val loader = javaClass.classLoader!!
    private val continuation = Continuation::class.java
    private fun name(type: Class<*>) = type.name

    @Test fun `named host protocol resolves with bindToView`() {
        val contract = checkNotNull(InsightComponentContract.resolve(loader, continuation, name(NamedComponentFixture::class.java),
            emptyList()))
        assertSame(NamedComponentFixture::class.java, contract.component)
        assertSame(NamedEntryFixture::class.java, contract.entry)
        assertEquals("bindToView", contract.bind.name)
    }

    @Test fun `obfuscated legacy protocol is found structurally from an unobfuscated anchor`() {
        val contract = checkNotNull(InsightComponentContract.resolve(loader, continuation, "missing.UIComponent",
            listOf("missing.Anchor", name(ObfuscatedAnchorFixture::class.java))))
        assertSame(ObfuscatedComponentFixture::class.java, contract.component)
        assertSame(ObfuscatedEntryFixture::class.java, contract.entry)
        assertEquals("a", contract.bind.name)
    }

    @Test fun `unknown abstract members on component or entry are rejected instead of failing at runtime`() {
        assertNull(InsightComponentContract.verify(ExtendedComponentFixture::class.java, continuation))
        assertNull(InsightComponentContract.verify(ExtendedEntryComponentFixture::class.java, continuation))
        assertNull(InsightComponentContract.verify(UnrelatedMarkerFixture::class.java, continuation))
        assertNull(InsightComponentContract.resolve(loader, continuation, "missing.UIComponent", listOf("missing.Anchor")))
    }

    @Test fun `obfuscated bind method reaches the bind branch of the module header`() {
        val contract = checkNotNull(InsightComponentContract.verify(ObfuscatedComponentFixture::class.java, continuation))
        val header = InsightHeaderComponent(contract.component, contract.entry, Unit,
            root = { error("No Android view needed") }, bindName = contract.bind.name, bind = {}).value as ObfuscatedComponentFixture
        assertEquals(InsightHeaderComponent.KEY, header.viewReusingKey())
        val entry = Proxy.newProxyInstance(loader, arrayOf(ObfuscatedEntryFixture::class.java)) { _, _, _ -> null }
            as ObfuscatedEntryFixture
        val failure = assertThrows(IllegalStateException::class.java) { header.a(entry, NoopContinuation) }
        assertEquals("invalid-insight-entry", failure.message)
    }

    private object NoopContinuation : Continuation<Any?> {
        override val context = kotlin.coroutines.EmptyCoroutineContext
        override fun resumeWith(result: Result<Any?>) = Unit
    }
}
