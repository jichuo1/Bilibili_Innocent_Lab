package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 官方进度预览图的索引与宫格；bin 首个 uint16 为头部，后续为大端秒数，与宿主解析一致。 */
internal data class InsightThumbnailIndex(val times: List<Int>, val columns: Int, val rows: Int, val images: List<String>) {
    data class Cell(val page: Int, val x: Int, val y: Int)
    fun cell(second: Int): Cell {
        require(second >= 0 && times.isNotEmpty())
        // 选择不晚于章首的最近画面，不把下一段的画面借给上一段。
        val index = times.indexOfLast { it <= second }.coerceAtLeast(0)
        val perPage = columns * rows
        return Cell(index / perPage, index % perPage % columns, index % perPage / columns)
    }
    companion object {
        fun parse(data: JSONObject, binary: ByteArray): InsightThumbnailIndex {
            val columns = data.getInt("img_x_len"); val rows = data.getInt("img_y_len")
            require(columns in 1..20 && rows in 1..20)
            val urls = data.getJSONArray("image")
            require(urls.length() in 1..512)
            val images = (0 until urls.length()).map { InsightContextClient.subtitleUrl(urls.getString(it)) ?: error("thumbnail-cdn") }
            val inline = data.optJSONArray("index")
            val times = if (inline != null && inline.length() > 0) (0 until inline.length()).map { inline.getInt(it) }
                else {
                    require(binary.size in 4..200_000 && binary.size % 2 == 0)
                    (2 until binary.size step 2).map { ((binary[it].toInt() and 255) shl 8) or (binary[it + 1].toInt() and 255) }
                }
            require(times.isNotEmpty() && times.first() >= 0 && times.zipWithNext().all { (a, b) -> b >= a })
            require(times.size <= images.size * columns * rows)
            return InsightThumbnailIndex(times, columns, rows, images)
        }
    }
}

/** 后台裁剪官方预览画面；只向原生 cover 字段提供本地图片 URI，不 seek 播放器或读取登录态。 */
internal class InsightChapterCovers(private val directory: File, private val trace: (String) -> Unit = {}) {
    private val pending = mutableSetOf<InsightVideo>()
    private val retryAt = object : LinkedHashMap<InsightVideo, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<InsightVideo, Long>?) = size > 32
    }
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "BIL-InsightCovers").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    fun uri(video: InsightVideo, second: Int): String? = file(video, second).takeIf { it.isFile && it.length() in 1..200_000 }
        ?.let { Uri.fromFile(it).toString() }
    fun schedule(video: InsightVideo, entry: InsightCache.Entry) {
        if (entry.output.chapters.isEmpty() || entry.output.chapters.all { uri(video, it.start) != null }) return
        synchronized(pending) {
            if ((retryAt[video] ?: 0) > System.currentTimeMillis() || !pending.add(video)) return
        }
        runCatching { worker.execute {
            try {
                prepare(video, entry.output.chapters)
                synchronized(pending) { retryAt.remove(video) }
                trace("covers success count=${entry.output.chapters.size}")
            } catch (error: Exception) {
                synchronized(pending) { retryAt[video] = System.currentTimeMillis() + 300_000 }
                trace("covers failure reason=${error.javaClass.simpleName}")
            } finally { synchronized(pending) { pending.remove(video) } }
        } }.onFailure { synchronized(pending) { pending.remove(video) } }
    }
    private fun file(video: InsightVideo, second: Int) = File(directory, "${video.key}-$second.jpg")
    private fun prepare(video: InsightVideo, chapters: List<InsightChapter>) {
        val deadline = System.nanoTime() + 45_000_000_000L
        fun read(address: String, limit: Int): ByteArray {
            check(System.nanoTime() < deadline)
            return InsightContextClient.getPublicBytes(address, limit)
        }
        val root = JSONObject(read("https://api.bilibili.com/x/player/videoshot?bvid=${video.bvid}&cid=${video.cid}&index=1", 512_000).toString(Charsets.UTF_8))
        require(root.getInt("code") == 0)
        val data = root.getJSONObject("data")
        val inline = data.optJSONArray("index")
        val binary = if (inline != null && inline.length() > 0) byteArrayOf() else {
            val address = InsightContextClient.subtitleUrl(data.getString("pvdata")) ?: error("thumbnail-index-cdn")
            read(address, 200_000)
        }
        val index = InsightThumbnailIndex.parse(data, binary)
        check(directory.isDirectory || directory.mkdirs())
        val needed = chapters.filter { uri(video, it.start) == null }.groupBy { index.cell(it.start).page }
        for ((page, sections) in needed) {
            val bytes = read(index.images[page], 12 * 1024 * 1024)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in index.columns..16_000 && bounds.outHeight in index.rows..16_000 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (bounds.outWidth / inSampleSize > index.columns * 240 || bounds.outHeight / inSampleSize > index.rows * 150) inSampleSize *= 2
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            // BitmapFactory 的解码上限与采样率在解码前校验；BetterAndroid 未提供宫格裁剪能力。
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("thumbnail-decode")
            try {
                for (chapter in sections) {
                    val cell = index.cell(chapter.start)
                    val left = cell.x * bitmap.width / index.columns; val top = cell.y * bitmap.height / index.rows
                    val right = (cell.x + 1) * bitmap.width / index.columns; val bottom = (cell.y + 1) * bitmap.height / index.rows
                    val frame = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    try {
                        val temporary = File.createTempFile("cover-", ".tmp", directory)
                        try {
                            temporary.outputStream().use { check(frame.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
                            Files.move(temporary.toPath(), file(video, chapter.start).toPath(), StandardCopyOption.REPLACE_EXISTING)
                        } finally { temporary.delete() }
                    } finally { if (frame !== bitmap) frame.recycle() }
                }
            } finally { bitmap.recycle() }
        }
        // 图片与模型结果分开存储，限制总量；命中时无需再访问预览接口。
        directory.listFiles()?.filter { it.extension == "jpg" }?.sortedByDescending { it.lastModified() }
            ?.drop(512)?.forEach { it.delete() }
    }
}
