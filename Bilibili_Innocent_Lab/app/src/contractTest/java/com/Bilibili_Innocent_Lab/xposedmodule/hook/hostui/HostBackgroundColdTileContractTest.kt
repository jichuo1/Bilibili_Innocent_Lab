package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 性能报告 P1-03：交互绘制不在调用线程生成 tile，多个 Scene 对同键冷 miss 共享一次生成。
 * 任务表本身由 TileFlightTableTest 覆盖，真实位图与线程由 HostBackgroundColdTileInstrumentedTest 覆盖；这里锁定接线。
 */
class HostBackgroundColdTileContractTest {
    private val scene = SourceContract.read("hook/hostui/background/HostBackgroundScene.kt")
    private val drawable = SourceContract.read("hook/hostui/background/HostBackgroundDrawable.kt")

    @Test fun onlyOffscreenExactDrawablesRenderSynchronously() {
        val draw = drawable.after("override fun draw(canvas: Canvas) {").before("if (shaderNight != night) rebuild()")
        assertTrue(draw.contains("synchronous = !cacheEnabled)"))
        assertFalse("软件 Canvas 不再等于可阻塞", draw.contains("isHardwareAccelerated"))
    }

    @Test fun synchronousGenerationStaysInsideTheExactBranchAndInteractiveDrawsSubscribe() {
        val draw = scene.after("fun draw(canvas: Canvas").before("paint.shader = null")
        val sync = draw.after("if (synchronous) {").before("} else requestMissing(keys, retarget)")
        assertTrue(sync.contains("render(key)"))
        assertTrue(draw.contains("} else requestMissing(keys, retarget)"))
        // render 只会出现在精确分支和工作线程 drain 里，交互路径不直接调用。
        val request = scene.after("private fun requestMissing(").before("/** 主线程：一个 tile 生成完成")
        assertFalse(request.contains("render("))
    }

    @Test fun cacheCheckJoinAndRegisterAreAtomicUnderTheSharedLock() {
        val request = scene.after("private fun requestMissing(").before("/** 主线程：一个 tile 生成完成")
        val locked = request.after("synchronized(cacheLock) {\n            for (key in missing) {")
        assertTrue(locked.indexOf("rasters.get(key)") in 0 until locked.indexOf("flights.request(key, subscription)"))
        // Scene 自己的锁不在持有 cacheLock 时获取（反向顺序会死锁）。
        assertFalse(locked.before("if (hits.isNotEmpty())").contains("synchronized(lock)"))
    }

    @Test fun workerPublishesOnlyToLiveSubscribersAndFlightsAreAlwaysFinished() {
        val drain = scene.after("private fun drain() {").before("internal fun render(key: Key)")
        assertTrue(drain.contains("flights.take { it.scene.get() != null }"))
        assertTrue(drain.contains("flights.finish(key)"))
        assertTrue(drain.contains("if (subscribers.isEmpty()) return"))
        assertTrue(drain.contains("main.post {"))
        // 失败（位图为 null）也走 finish，不残留任务。
        assertTrue(drain.indexOf("}.getOrNull()") < drain.indexOf("flights.finish(key)"))
        val onTile = scene.after("private fun onTile(").before("companion object")
        assertTrue(onTile.contains("if (key in wanted)"))
        assertTrue(onTile.contains("version.incrementAndGet()"))
    }

    @Test fun samplingStateAdvancesWithEveryPublishedTile() {
        assertTrue(drawable.contains("scene?.contentVersion"))
        assertEquals(1, Regex("""fun onTile\(""").findAll(scene).count())
        assertTrue(scene.contains("internal val contentVersion get() = version.get()"))
    }

    @Test fun poolAndTableStayBounded() {
        assertTrue(scene.contains("Executors.newFixedThreadPool(2)"))
        assertTrue(scene.contains("TileFlightTable<Key, Subscription>()"))
        val table = SourceContract.read("hook/hostui/background/TileFlightTable.kt")
        assertTrue(table.contains("const val DEFAULT_CAPACITY = 32"))
        assertTrue(table.contains("if (flights.size >= capacity)"))
    }
}
