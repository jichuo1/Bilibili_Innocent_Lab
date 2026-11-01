package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyFreezeRules
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CloudKeyScan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentScanEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudKeyPickStateTest {
    private fun scan(vararg pairs: Pair<String, String>) = pairs.map { (key, value) ->
        requireNotNull(CloudKeyScan.entryOf(key, value))
    }

    private fun state(
        entries: List<MineComponentScanEntry> = scan("ff_a" to "true", "ff_b" to "false", "ff_c" to "true"),
        picked: String = "",
        manual: String = ""
    ) = CloudKeyPickState.build(entries, CloudKeyFreezeRules.parsePins(picked), CloudKeyFreezeRules.parse(manual))

    private fun CloudKeyPickState.row(key: String) = rows.single { it.key == key }

    @Test fun `scanned keys start unchecked and default their pin to the value the cloud sends`() {
        val s = state()
        assertEquals(listOf("ff_a", "ff_b", "ff_c"), s.rows.map { it.key })
        assertTrue(s.rows.none { it.checked })
        assertEquals(true, s.row("ff_a").observed); assertEquals(true, s.row("ff_a").pin)
        assertEquals(false, s.row("ff_b").observed); assertEquals(false, s.row("ff_b").pin)
        assertEquals("", s.pinsText())
    }

    @Test fun `ticking pins the current value and saving writes only the ticked rows in rule syntax`() {
        val s = state()
        assertTrue(s.toggle(s.row("ff_c"))); assertTrue(s.toggle(s.row("ff_b")))
        assertEquals("ff_b=false\nff_c=true", s.pinsText())
        assertEquals(mapOf("ff_b" to false, "ff_c" to true), CloudKeyFreezeRules.parsePins(s.pinsText()))
        assertTrue(s.toggle(s.row("ff_b")))
        assertEquals("ff_c=true", s.pinsText())
    }

    @Test fun `a ticked row can be pinned to the opposite value but an unticked one cannot`() {
        val s = state()
        assertFalse(s.flipPin(s.row("ff_a")))
        s.toggle(s.row("ff_a"))
        assertTrue(s.flipPin(s.row("ff_a")))
        assertEquals("ff_a=false", s.pinsText())
        assertTrue(s.flipPin(s.row("ff_a")))
        assertEquals("ff_a=true", s.pinsText())
    }

    @Test fun `saved pins come back ticked and sort above the rest`() {
        val s = state(picked = "ff_c=false")
        assertEquals(listOf("ff_c", "ff_a", "ff_b"), s.rows.map { it.key })
        assertTrue(s.row("ff_c").checked)
        assertEquals("cloud says on, pinned off", true to false, s.row("ff_c").observed to s.row("ff_c").pin)
    }

    @Test fun `a pinned key missing from this scan is still listed so it can be unticked`() {
        val s = state(picked = "ff_gone=true")
        val gone = s.row("ff_gone")
        assertTrue(gone.checked); assertNull(gone.observed); assertFalse(gone.scanned)
        assertTrue(s.toggle(gone))
        assertEquals("", s.pinsText())
    }

    @Test fun `a manual rule on the same key wins and the row is read only but the pick is kept`() {
        val s = state(picked = "ff_a=false", manual = "ff_a=true\nff_b=false")
        val a = s.row("ff_a"); val b = s.row("ff_b")
        assertTrue(a.lockedByManual); assertTrue(b.lockedByManual)
        assertFalse(s.toggle(a)); assertFalse(s.toggle(b)); assertFalse(s.flipPin(a))
        assertEquals("the earlier pick survives until the manual rule is removed", "ff_a=false", s.pinsText())
    }

    @Test fun `a mixed key cannot be newly ticked but an existing pin on it can be removed`() {
        val s = state(entries = scan("ff_m" to "mixed", "ff_n" to "mixed"), picked = "ff_n=true")
        assertFalse(s.row("ff_m").selectable)
        assertFalse(s.toggle(s.row("ff_m")))
        assertNull(s.row("ff_m").observed); assertTrue(s.row("ff_m").mixed)
        assertTrue(s.row("ff_n").checked && s.row("ff_n").selectable)
        assertTrue(s.toggle(s.row("ff_n")))
        assertEquals("", s.pinsText())
    }

    @Test fun `the pin limit stops new ticks but never removing one`() {
        val entries = scan(*(1..CloudKeyFreezeRules.MAX_PICKED + 5).map { "ff_%03d".format(it) to "true" }.toTypedArray())
        val s = state(entries = entries)
        s.rows.take(CloudKeyFreezeRules.MAX_PICKED).forEach { assertTrue(s.toggle(it)) }
        assertTrue(s.full)
        assertFalse(s.toggle(s.rows.last()))
        assertEquals(CloudKeyFreezeRules.MAX_PICKED, s.checkedCount)
        assertTrue(s.toggle(s.rows.first()))
        assertTrue(s.toggle(s.rows.last()))
    }

    @Test fun `search matches any part of the key ignoring case and the filter can show only ticked rows`() {
        val s = state(entries = scan("ff_Home_tab" to "true", "dd.live_room" to "false", "ff_other" to "true"))
        assertEquals(listOf("dd.live_room"), s.visible("LIVE", false).map { it.key })
        assertEquals(listOf("dd.live_room", "ff_Home_tab", "ff_other"), s.visible("  ", false).map { it.key })
        s.toggle(s.row("ff_other"))
        assertEquals(listOf("ff_other"), s.visible("", true).map { it.key })
        assertTrue(s.visible("zzz", false).isEmpty())
    }

    @Test fun `entries of other kinds protected keys and unreadable values never become rows`() {
        val foreign = MineComponentScanEntry.create("home_tab", "热门", "hot", null, showing = true)!!
        val unknown = MineComponentScanEntry.create("cloud_key", null, "ff_x", null, showing = true, value = "text")!!
        val s = state(entries = scan("ff_ok" to "true", "grpc.timeout" to "true") + foreign + unknown)
        assertEquals(listOf("ff_ok"), s.rows.map { it.key })
    }

    @Test fun `saved text is deterministic and parses back to the same pins`() {
        val a = state(); a.toggle(a.row("ff_a")); a.toggle(a.row("ff_b"))
        val b = state(); b.toggle(b.row("ff_b")); b.toggle(b.row("ff_a"))
        assertEquals(a.pinsText(), b.pinsText())
        assertEquals(a.pinsText(), state(picked = a.pinsText()).pinsText())
    }
}
