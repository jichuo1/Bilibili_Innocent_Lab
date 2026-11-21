package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator.Family
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommentClassicStyleLocatorTest {
    interface Decision
    class Context
    class Legacy
    class Native {
        fun read(key: String, fallback: Boolean): Boolean = fallback
        fun unrelated(key: String): Boolean = true
        fun wrong(key: Int, fallback: Boolean): Boolean = fallback
    }
    class OtherNative { fun read(key: String, fallback: Boolean): Boolean = fallback }
    class KotlinReaders { companion object {
        @JvmStatic fun getBool(decision: Decision?, key: String, fallback: Boolean, context: Context?): Boolean = fallback
        @JvmStatic fun `getBool$default`(decision: Decision?, key: String, fallback: Boolean, context: Context?, mask: Int, marker: Any?): Boolean = fallback
        @JvmStatic fun bad(decision: Any?, key: String, fallback: Boolean, context: Context?): Boolean = fallback
    } }

    companion object {
        fun loader(kotlin: Boolean = false, hideNative: Boolean = false, missingKotlin: Boolean = false): ClassLoader =
            object : ClassLoader(CommentClassicStyleLocatorTest::class.java.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                    CommentClassicStyleLocator.NATIVE_ENTRY -> Legacy::class.java
                    CommentClassicStyleLocator.NATIVE_OWNER -> if (hideNative) throw ClassNotFoundException(name) else super.loadClass(name, resolve)
                    CommentClassicStyleLocator.COMPOSE_ENTRY -> if (kotlin) Legacy::class.java else throw ClassNotFoundException(name)
                    CommentClassicStyleLocator.KOTLIN_OWNER -> if (kotlin && !missingKotlin) KotlinReaders::class.java else throw ClassNotFoundException(name)
                    else -> super.loadClass(name, resolve)
                }
            }
        fun point(owner: Class<*>, name: String) = owner.declaredMethods.single { it.name == name && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .let { HookPoint(it.declaringClass.name, it.name, it.parameterTypes.map(Class<*>::getName)) }
    }

    fun `semantic candidate selection rejects merged lambda and multiple owners`() {
        val native = Native::class.java.declaredMethods.toList()
        assertEquals(listOf("read"), CommentClassicStyleLocator.select(native, Family.NATIVE).map { it.name })
        assertTrue(CommentClassicStyleLocator.select(native + OtherNative::class.java.declaredMethods, Family.NATIVE).isEmpty())
        assertEquals(setOf("getBool", "getBool\$default"), CommentClassicStyleLocator.select(KotlinReaders::class.java.declaredMethods.toList(), Family.KOTLIN).map { it.name }.toSet())
    }

    fun `known kotlin key index and default wrapper shapes are kept`() {
        assertEquals(1, Family.KOTLIN.keyIndex)
        assertEquals(2, CommentClassicStyleLocator.direct(loader(kotlin = true), Family.KOTLIN).size)
        assertFalse(CommentClassicStyleLocator.matchesKey("comment.kntr.enabled_extra"))
        assertFalse(CommentClassicStyleLocator.matchesKey("comment.kntr.future.enabled"))
        assertTrue(CommentClassicStyleLocator.matchesKey("comment.kntr.story.enabled"))
        val methods = KotlinReaders::class.java.declaredMethods.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val wrapper = methods.single { it.name == "getBool\$default" }
        assertTrue(CommentClassicStyleLocator.isDefaultDelegate(wrapper, methods.single { it.name == "getBool" }))
        assertFalse(CommentClassicStyleLocator.isDefaultDelegate(wrapper, methods.single { it.name == "bad" }))
    }

    fun `per family cache fills only missing direct readers and is verified again`() {
        val k = listOf(point(KotlinReaders::class.java, "getBool"))
        val cache = CommentClassicStylePoints(emptyList(), k)
        assertEquals(1, CommentClassicStyleLocator.readers(loader(kotlin = true, missingKotlin = true), cache, Family.KOTLIN).size)
        assertEquals(2, CommentClassicStyleLocator.readers(loader(kotlin = true), cache, Family.KOTLIN).size)
        assertTrue(CommentClassicStyleLocator.resolve(loader(), k.map { it.copy(paramClassNames = listOf("bad")) }, Family.KOTLIN).isEmpty())
        val live = CommentClassicStylePoints(emptyList(), k)
        val native = HookPoint("native", "read", listOf("java.lang.String", "boolean"))
        assertEquals(CommentClassicStylePoints(listOf(native), k), CommentClassicStyleLocator.merge(live, CommentClassicStylePoints(listOf(native), emptyList())))
    }

    fun `cache is optional bounded and round trips without experiment values`() {
        val points = CommentClassicStylePoints(emptyList(), listOf(point(KotlinReaders::class.java, "getBool")))
        assertEquals(points, CommentClassicStylePoints.fromJson(points.toJson()))
        assertNull(CommentClassicStylePoints.fromJson(JSONObject().put("native", JSONArray()).put("kotlin", "bad")))
        assertNull(CommentClassicStylePoints.fromJson(points.toJson().put("kotlin", JSONArray((0..16).map { points.kotlinReaders[0].toJson() }))))
    }

    fun `disabled and direct paths never invoke the dex engine while missing families share a pass`() {
        var batches = 0
        val engine = DexAssistEngine { request ->
            batches++
            DexAssistResult.Candidates(if (request.query == Family.NATIVE.query) Native::class.java.declaredMethods.toList()
                else KotlinReaders::class.java.declaredMethods.toList())
        }
        val direct = loader(kotlin = true)
        val session = DexAssistSession(engine, listOf("base.apk"), direct, Family.entries.map { it.query }.toSet())
        CommentClassicStyleLocator.locate(direct, session, true)
        assertEquals(0, batches)
        val missing = loader(kotlin = true, hideNative = true, missingKotlin = true)
        val fallback = DexAssistSession(engine, listOf("base.apk"), missing, Family.entries.map { it.query }.toSet())
        CommentClassicStyleLocator.locate(missing, fallback, false)
        assertEquals(0, batches)
        val points = CommentClassicStyleLocator.locate(missing, fallback, true)
        assertEquals(2, batches) // 默认引擎逐查询；Session 的 resolveAll 仍只调用一趟。
        assertEquals(1, points.nativeReaders.size)
        assertEquals(2, points.kotlinReaders.size)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentClassicStyleLocatorTest) -> Unit) {
            val s = CommentClassicStyleLocatorTest()
            block(s)
        }
        runIsolated { s -> s.`semantic candidate selection rejects merged lambda and multiple owners`() }
        runIsolated { s -> s.`known kotlin key index and default wrapper shapes are kept`() }
        runIsolated { s -> s.`per family cache fills only missing direct readers and is verified again`() }
        runIsolated { s -> s.`cache is optional bounded and round trips without experiment values`() }
        runIsolated { s -> s.`disabled and direct paths never invoke the dex engine while missing families share a pass`() }
    }
}
