package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.bilibili.bson.common.PojoPropertyDescriptor
import com.bilibili.ship.theseus.ogv.activity.OgvActivityHalfScreenPopup
import com.bilibili.ship.theseus.ogv.activity.OgvActivityVo_JsonDescriptor
import org.junit.Assert.*
import org.junit.Test

class PgcAutoActivityPopupLocatorTest {
    private val loader = javaClass.classLoader!!
    private val popup = OgvActivityHalfScreenPopup::class.java
    private fun property(name: String, type: Class<*>, nonNull: Boolean = false) =
        PgcAutoActivityPopupLocator.Property(name, type, nonNull)

    @Test
    fun `locates typed constructor and renamed descriptor field without a model getter`() {
        val point = requireNotNull(VersionAdapter.locatePgcAutoActivityPopup(loader))
        assertEquals(7, point.popupIndex)
        assertEquals(9, point.constructorParameters.size)
        assertEquals("shiftedName", point.propertiesField)
        val runtime = requireNotNull(PgcAutoActivityPopupLocator.resolveRuntime(loader, point))
        assertEquals(point.popupIndex, runtime.popupIndex)
        assertEquals("half", runtime.popupField.name)
    }

    @Test
    fun `a reordered nullable half slot follows the property and parameter contract`() {
        for (index in 0..8) {
            val parameters = MutableList<Class<*>>(9) { String::class.java }
            parameters[index] = popup
            val properties = parameters.mapIndexed { i, type ->
                property(if (i == index) "play_half_container" else "other_$i", type)
            }
            assertEquals(index, PgcAutoActivityPopupLocator.uniquePopupIndex(parameters, popup))
            assertTrue(PgcAutoActivityPopupLocator.matchesNullableHalf(parameters, popup, properties))
        }
    }

    @Test
    fun `rejects missing duplicate renamed required or mismatched half fields`() {
        val parameters = listOf(String::class.java, popup)
        val valid = listOf(property("other", String::class.java), property("play_half_container", popup))
        val invalid = listOf(
            valid.dropLast(1),
            listOf(property("play_half_container", String::class.java), valid[1]),
            listOf(valid[0], property("different_popup", popup)),
            listOf(valid[0], property("play_half_container", popup, nonNull = true)),
            listOf(property("other", Int::class.java), valid[1]),
            valid.reversed()
        )
        invalid.forEach { assertFalse(PgcAutoActivityPopupLocator.matchesNullableHalf(parameters, popup, it)) }
        assertNull(PgcAutoActivityPopupLocator.uniquePopupIndex(listOf(popup, popup), popup))
        assertNull(PgcAutoActivityPopupLocator.uniquePopupIndex(listOf(String::class.java), popup))
    }

    @Test
    fun `cached slot and nullable semantics are revalidated before registration`() {
        val point = requireNotNull(VersionAdapter.locatePgcAutoActivityPopup(loader))
        assertNull(PgcAutoActivityPopupLocator.resolveRuntime(loader, point.copy(popupIndex = 0)))
        val properties = OgvActivityVo_JsonDescriptor.shiftedName
        val original = properties[7]
        try {
            properties[7] = PojoPropertyDescriptor("play_half_container", popup, 5)
            assertNull(PgcAutoActivityPopupLocator.resolveRuntime(loader, point))
        } finally {
            properties[7] = original
        }
    }

    @Test
    fun `missing descriptor does not fall back to an unverified model constructor`() {
        val hidden = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.endsWith("OgvActivityVo_JsonDescriptor")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(VersionAdapter.locatePgcAutoActivityPopup(hidden))
    }

    @Test
    fun `cache round trip preserves shape and rejects an invalid or duplicated index`() {
        val point = requireNotNull(VersionAdapter.locatePgcAutoActivityPopup(loader))
        val restored = VersionAdapter.PgcAutoActivityPopupPoints.fromJson(point.toJson())
        assertEquals(point, restored)
        assertTrue(restored.isValid())
        assertFalse(point.copy(popupIndex = -1).isValid())
        assertFalse(point.copy(popupIndex = 0).isValid())
        assertFalse(point.copy(propertiesField = "").isValid())
        assertFalse(point.copy(constructorParameters = point.constructorParameters + popup.name).isValid())
    }
}
