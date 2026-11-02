package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InsightThumbnailIndexTest {
    private fun data() = JSONObject().put("img_x_len", 2).put("img_y_len", 2)
        .put("image", JSONArray().put("//bimp.hdslb.com/a.jpg").put("//bimp.hdslb.com/b.jpg"))
    @Test fun `binary header is removed and chapter seconds select the correct sprite and cell`() {
        val seconds = listOf(0, 5, 10, 15, 20, 25)
        val bytes = (listOf(0) + seconds).flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()
        val index = InsightThumbnailIndex.parse(data(), bytes)
        assertEquals(seconds, index.times)
        assertEquals(InsightThumbnailIndex.Cell(0, 0, 0), index.cell(0))
        assertEquals(InsightThumbnailIndex.Cell(0, 1, 0), index.cell(9))
        assertEquals(InsightThumbnailIndex.Cell(0, 1, 1), index.cell(15))
        assertEquals(InsightThumbnailIndex.Cell(1, 0, 0), index.cell(22))
        assertEquals(InsightThumbnailIndex.Cell(1, 1, 0), index.cell(100))
    }
    @Test fun `inline preview indexes and malformed or unsafe sources are bounded`() {
        val data = data().put("index", JSONArray(listOf(0, 12, 40)))
        assertEquals(InsightThumbnailIndex.Cell(0, 0, 1), InsightThumbnailIndex.parse(data, byteArrayOf()).cell(40))
        for (invalid in listOf(data().put("img_x_len", 0), data().put("image", JSONArray().put("https://evil.test/image")),
            data().put("index", JSONArray(listOf(10, 0))))) {
            assertTrue(runCatching { InsightThumbnailIndex.parse(invalid, byteArrayOf(0, 0, 0, 0)) }.isFailure)
        }
        assertTrue(runCatching { InsightThumbnailIndex.parse(data(), byteArrayOf(0, 0, 1)) }.isFailure)
    }
}
