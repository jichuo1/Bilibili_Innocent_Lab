package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarFeatureInstallerTest {

    @Test
    fun verifyHideRulesAndRouteCanonicalization() {
        // never hides every bottom bar entry
        assertTrue(BottomBarFeatureInstaller.canHide(total = 5, matched = 2))
        assertFalse(BottomBarFeatureInstaller.canHide(total = 5, matched = 0))
        assertFalse(BottomBarFeatureInstaller.canHide(total = 5, matched = 5))

        // frame values line up with the tabhost field order so both layers derive one key
        val shape = BottomBarFeatureInstaller.legacyShapeValues(
            name = "会员购",
            icon = "https://i0.hdslb.com/mall.png",
            iconSelected = "https://i0.hdslb.com/mall_s.png",
            id = "177",
            tabId = "mall",
            uri = "bilibili://mall/home"
        )
        assertEquals(
            "会员购" to "https://i0.hdslb.com/mall.png",
            BottomBarFeatureInstaller.shapeKeyParts(shape)
        )
        val noIcon = BottomBarFeatureInstaller.legacyShapeValues("动态", null, null, "2", null, "bilibili://following/home")
        assertEquals("动态" to "bilibili://following/home", BottomBarFeatureInstaller.shapeKeyParts(noIcon))

        // canonical route ignores the host's own normalization differences
        val expected = "bilibili://main/home"
        listOf(
            "bilibili://main/home",
            "bilibili://main/home/",
            " BILIBILI://Main/home/?from=tab#x ",
        ).forEach { assertEquals(it, expected, BottomBarFeatureInstaller.canonicalRoute(it)) }
        assertEquals(
            "bilibili://game_center/home",
            BottomBarFeatureInstaller.canonicalRoute("action://game_center/home/menu?from=bottom")
        )
        assertEquals("bilibili://link/im_home", BottomBarFeatureInstaller.canonicalRoute("action://link/home/menu"))
        listOf("https://i0.hdslb.com/a.png", "http://x/y", "file:///data/skin/a.png", "首页", "", null)
            .forEach { assertNull(it, BottomBarFeatureInstaller.canonicalRoute(it)) }

        // tabhost route is the last route-shaped value so a showing bubble never wins
        val values = listOf(
            "会员购",
            "https://i0.hdslb.com/mall.png",
            "https://i0.hdslb.com/mall_s.png",
            "177",
            "mall",
            "bilibili://mall/activity/618",
            "bilibili://mall/home",
            "file:///data/garb/tail.png",
        )
        assertEquals("bilibili://mall/home", BottomBarFeatureInstaller.routeFromShape(values))
    }

    @Test
    fun verifyFrameAndShapeKeys() {
        // both frames derive the same canonical key and the same legacy alias
        run {
            val tabHost = BottomBarFeatureInstaller.identityFromShape(
                listOf("会员购", "https://i0.hdslb.com/mall.png", "https://i0.hdslb.com/mall_s.png", "177", "mall", "bilibili://mall/home")
            )
            val frame = BottomBarFeatureInstaller.identityFromFrame(
                BottomBarFeatureInstaller.legacyShapeValues(
                    "会员购", "https://i0.hdslb.com/mall.png", "https://i0.hdslb.com/mall_s.png", "177", "mall", "bilibili://mall/home/"
                ),
                "bilibili://mall/home/"
            )
            assertEquals("bottom_tab:uri:bilibili://mall/home", tabHost.canonicalKey)
            assertEquals(tabHost.canonicalKey, frame.canonicalKey)
            assertEquals("bottom_tab:uri:https://i0.hdslb.com/mall.png", tabHost.legacyKey)
            assertEquals(tabHost.legacyKey, frame.legacyKey)

            val entry = tabHost.scanEntry(showing = false)!!
            assertEquals(tabHost.canonicalKey, entry.key)
            assertEquals("bilibili://mall/home", entry.uri)
            assertEquals(setOf(tabHost.legacyKey), entry.aliases)
            assertTrue(tabHost.matches(setOf(tabHost.legacyKey!!)))
            assertTrue(tabHost.matches(setOf(tabHost.canonicalKey!!)))
            assertFalse(tabHost.matches(setOf("bottom_tab:uri:https://i0.hdslb.com/other.png")))
        }

        // h5 bottom tabs keep the legacy key only, exactly as before
        run {
            val identity = BottomBarFeatureInstaller.identityFromShape(
                listOf("商城", "https://i0.hdslb.com/shop.png", "https://mall.bilibili.com/index.html")
            )
            assertNull(identity.canonicalKey)
            val entry = identity.scanEntry(showing = true)!!
            assertEquals("bottom_tab:uri:https://i0.hdslb.com/shop.png", entry.key)
            assertTrue(entry.aliases.isEmpty())
        }
    }

    @Test
    fun verifyPickerSelectionAndSnapshotCodec() {
        // picker confirmation rewrites a legacy selector to the canonical key once
        run {
            val identity = BottomBarFeatureInstaller.identityFromShape(
                listOf("会员购", "https://i0.hdslb.com/mall.png", "bilibili://mall/home")
            )
            val entry = identity.scanEntry(showing = false)!!
            val unrelated = "bottom_tab:uri:bilibili://following/home"
            val stored = setOf(identity.legacyKey!!, unrelated)

            assertTrue(ComponentPickerSelection.isSelected(entry, stored))
            assertEquals(
                setOf(identity.canonicalKey!!, unrelated),
                ComponentPickerSelection.merge(stored, listOf(entry to true))
            )
            assertEquals(setOf(unrelated), ComponentPickerSelection.merge(stored, listOf(entry to false)))
            assertEquals(stored, ComponentPickerSelection.merge(stored, emptyList()))
        }

        // aliases survive the cross-process snapshot codec and bad aliases are rejected
        run {
            val identity = BottomBarFeatureInstaller.identityFromShape(
                listOf("会员购", "https://i0.hdslb.com/mall.png", "bilibili://mall/home")
            )
            val entry = identity.scanEntry(showing = false)!!
            val raw = MineComponentSnapshotCodec.encode(
                "tv.danmaku.bili", setOf("bottom_tab_filter"), listOf(entry),
                surface = MineComponentSnapshotCodec.SURFACE_BOTTOM_BAR
            )
            val decoded = MineComponentSnapshotCodec.decodeOrNull(raw)!!.entries.single()
            assertEquals(entry, decoded)

            val foreign = org.json.JSONObject(entry.toJson().toString()).apply {
                put("aliases", org.json.JSONArray().put("home_tab:id:1"))
            }
            assertNull(MineComponentScanEntry.fromJsonOrNull(foreign))
        }
    }
}
