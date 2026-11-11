package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatPlayerSpeedFactory
import FlatPlayerSpeedOption
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.*
import com.bilibili.lib.media.resource.MediaResource
import com.bilibili.playerbizcommonv2.widget.speed.RenamedSpeedAdapter
import com.bilibili.playerbizcommonv2.widget.speed.OtherRenamedSpeedAdapter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedMenuAdapterTest {
    private val loader = javaClass.classLoader!!
    private val method = RenamedSpeedAdapter::class.java.getDeclaredMethod("populate", Float::class.javaPrimitiveType)

    @Test fun `semantic candidate discovers a renamed adapter and cached member is revalidated`() {
        assertNull(PlayerSpeedLocator.speedMenu(loader))
        val selected = requireNotNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(listOf(method))))
        assertEquals(method.declaringClass.name, selected.adapter?.className)
        val restored = requireNotNull(PlayerSpeedMenuPoints.fromJson(JSONObject(selected.toJson().toString())))
        assertEquals(selected, restored)
        assertEquals(method, PlayerSpeedMenuAdapter.resolve(loader, restored)?.adapter?.populateMethod)
        assertFalse(PlayerSpeedMenuAdapter.needsQuery(loader, restored, enabled = true, attempted = false))
        val stale = restored.copy(adapter = restored.adapter!!.copy(methodName = "obsolete"))
        assertNull(PlayerSpeedMenuAdapter.resolve(loader, stale))
        assertTrue(PlayerSpeedMenuAdapter.needsQuery(loader, stale, enabled = true, attempted = false))
    }

    @Test fun `disabled complete and negative cached states do not trigger a session query`() {
        var calls = 0
        val session = DexAssistSession(DexAssistEngine { calls++; DexAssistResult.Candidates(listOf(method)) },
            listOf("test.apk"), loader, setOf(PlayerSpeedMenuAdapter.query))
        assertFalse(PlayerSpeedMenuAdapter.needsQuery(loader, null, enabled = false, attempted = false))
        assertFalse(PlayerSpeedMenuAdapter.needsQuery(loader, null, enabled = true, attempted = true))
        assertTrue(PlayerSpeedMenuAdapter.needsQuery(loader, null, enabled = true, attempted = false))
        assertNull(PlayerSpeedMenuAdapter.locate(loader, session, null, enabled = false, planned = true))
        assertNull(PlayerSpeedMenuAdapter.locate(loader, session, null, enabled = true, planned = false))
        assertEquals(0, calls)
        val found = requireNotNull(PlayerSpeedMenuAdapter.locate(loader, session, null, enabled = true, planned = true))
        assertEquals(method, PlayerSpeedMenuAdapter.resolve(loader, found)?.adapter?.populateMethod)
        assertEquals(1, calls)
    }

    @Test fun `ambiguous oversized and unavailable results fail closed`() {
        val other = OtherRenamedSpeedAdapter::class.java.getDeclaredMethod("populate", Float::class.javaPrimitiveType)
        assertNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(listOf(method, other))))
        assertNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(List(33) { method })))
        val session = DexAssistSession(DexAssistEngine { DexAssistResult.Unavailable(DexAssistResult.Reason.NATIVE_UNAVAILABLE) },
            listOf("test.apk"), loader, setOf(PlayerSpeedMenuAdapter.query))
        assertNull(PlayerSpeedMenuAdapter.locate(loader, session, null, enabled = true, planned = true))
    }

    @Test fun `cache rejects foreign owners wildcard signatures and incomplete factory models`() {
        val valid = PlayerSpeedMenuPoints(adapter = HookPoint(method.declaringClass.name, method.name, listOf("float")))
        assertTrue(valid.isStructurallyValid())
        val bad = listOf(
            valid.copy(adapter = valid.adapter!!.copy(className = "java.lang.Runtime")),
            valid.copy(adapter = valid.adapter!!.copy(paramClassNames = null)),
            valid.copy(adapter = valid.adapter!!.copy(viewField = "items")),
            valid.copy(adapter = valid.adapter!!.copy(paramClassNames = emptyList())),
            valid.copy(factory = HookPoint("${PlayerSpeedLocator.SPEED_UTILS_PKG}.Renamed", "build",
                listOf(PlayerSpeedLocator.MEDIA_RESOURCE, "java.lang.Integer", "boolean", "int"))),
            valid.copy(compactOwner = "java.lang.Runtime"))
        for (points in bad) {
            assertFalse(points.isStructurallyValid())
            assertNull(PlayerSpeedMenuPoints.fromJson(points.toJson()))
        }
    }

    @Test fun `root package three parameter factory requires its linked option model`() {
        val factory = FlatPlayerSpeedFactory::class.java.getDeclaredMethod("options", MediaResource::class.java,
            Int::class.javaObjectType, Int::class.javaPrimitiveType)
        assertNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(listOf(factory))))
        val points = requireNotNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(listOf(factory),
            relatedClasses = mapOf(factory to listOf(FlatPlayerSpeedOption::class.java)))))
        assertEquals(points, PlayerSpeedMenuPoints.fromJson(points.toJson()))
        assertEquals(factory, PlayerSpeedMenuAdapter.resolve(loader, points)?.factory?.factoryMethod)
        assertNull(PlayerSpeedMenuPoints.fromJson(points.copy(factory = points.factory!!.copy(paramClassNames =
            listOf(PlayerSpeedLocator.MEDIA_RESOURCE, "java.lang.Integer", "boolean", "int"))).toJson()))
    }
}
