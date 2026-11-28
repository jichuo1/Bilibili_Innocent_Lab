package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主背景渲染的源码契约护栏：
 * - 交互绘制不在调用线程生成冷 tile，多个 Scene 共享并发生成且任务表与线程池保持有界；
 * - LayerDrawable 先算中性化计划、确有改动再复制，且随后释放宿主 Drawable 引用。
 */
class HostBackgroundRenderContractTest {

    @Test
    fun `cold tiles render offscreen synchronously, join atomically under lock, and stay bounded`() {
        val scene = SourceContract.read("hook/hostui/background/HostBackgroundScene.kt")
        val drawable = SourceContract.read("hook/hostui/background/HostBackgroundDrawable.kt")

        val drawableDraw = drawable.after("override fun draw(canvas: Canvas) {").before("if (shaderNight != night) rebuild()")
        assertTrue(drawableDraw.contains("synchronous = !cacheEnabled)"))
        assertFalse("软件 Canvas 不再等于可阻塞", drawableDraw.contains("isHardwareAccelerated"))

        val sceneDraw = scene.after("fun draw(canvas: Canvas").before("paint.shader = null")
        val sync = sceneDraw.after("if (synchronous) {").before("} else requestMissing(keys, retarget)")
        assertTrue(sync.contains("render(key)"))
        assertTrue(sceneDraw.contains("} else requestMissing(keys, retarget)"))
        // render 只会出现在精确分支和工作线程 drain 里，交互路径不直接调用。
        val request = scene.after("private fun requestMissing(").before("/** 主线程：一个 tile 生成完成")
        assertFalse(request.contains("render("))

        val locked = request.after("synchronized(cacheLock) {\n            for (key in missing) {")
        assertTrue(locked.indexOf("rasters.get(key)") in 0 until locked.indexOf("flights.request(key, subscription)"))
        // Scene 自己的锁不在持有 cacheLock 时获取（反向顺序会死锁）。
        assertFalse(locked.before("if (hits.isNotEmpty())").contains("synchronized(lock)"))

        val drain = scene.after("private fun drain() {").before("internal fun render(key: Key)")
        assertTrue(drain.contains("flights.take { it.scene.get() != null }"))
        assertTrue(drain.contains("flights.finish(key)"))
        assertTrue(drain.contains("if (subscribers.isEmpty()) return"))
        assertTrue(drain.contains("""main.post(HostThreadGuard.runnable("host.background.scene.publish")"""))
        // 失败（位图为 null）也走 finish，不残留任务。
        assertTrue(drain.indexOf("}.getOrNull()") < drain.indexOf("flights.finish(key)"))
        val onTile = scene.after("private fun onTile(").before("companion object")
        assertTrue(onTile.contains("if (key in wanted)"))
        assertTrue(onTile.contains("version.incrementAndGet()"))

        assertTrue(drawable.contains("scene?.contentVersion"))
        assertEquals(1, Regex("""fun onTile\(""").findAll(scene).count())
        assertTrue(scene.contains("internal val contentVersion get() = version.get()"))

        assertTrue(scene.contains("Executors.newFixedThreadPool(2)"))
        assertTrue(scene.contains("TileFlightTable<Key, Subscription>()"))
        val table = SourceContract.read("hook/hostui/background/TileFlightTable.kt")
        assertTrue(table.contains("const val DEFAULT_CAPACITY = 32"))
        assertTrue(table.contains("if (flights.size >= capacity)"))
    }

    @Test
    fun `layer neutral plan is computed before copying and releases host drawable references`() {
        val source = SourceContract.read("hook/hostui/background/HostBackgroundPages.kt")
        val branch = source
            .after("is LayerDrawable -> {\n                if (!nativeContainers")
            .before("is GradientDrawable -> {\n                // 宿主的纯色矩形入口")

        assertEquals(1, Regex("""newDrawable\(\)""").findAll(branch).count())
        assertTrue(branch.indexOf("HostLayerNeutralPlan.of(") in 0 until branch.indexOf("newDrawable()"))
        // 无计划即返回：返回发生在复制之前。
        assertTrue(branch.indexOf("} ?: return") in 0 until branch.indexOf("newDrawable()"))

        for (read in listOf("getLayerHeight", "getLayerWidth", "getLayerInset", "numberOfLayers")) {
            assertFalse("复制分支不应再自行读取 $read", branch.contains(read))
        }
        assertTrue(branch.contains("applyNeutralPlan(copy, plan)"))
        assertTrue(branch.contains("view.background = copy"))

        assertTrue(branch.contains("finally { reads.release() }"))
        assertTrue(source.after("fun release()").before("private fun layers()").contains("layers = null"))
    }
}
