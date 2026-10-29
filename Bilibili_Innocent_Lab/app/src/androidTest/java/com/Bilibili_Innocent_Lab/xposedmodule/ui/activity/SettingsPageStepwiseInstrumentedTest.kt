package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.Bilibili_Innocent_Lab.xposedmodule.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 性能报告 P1-02：设置页按步骤构建。
 * - 空闲预建（每帧一个单元）与同步补完得到逐节点相同的页面；
 * - 预建不会留下永久未完成的页面；
 * - 记录每个单元的耗时分布（emulator 数据，不是真机结论）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsPageStepwiseInstrumentedTest {
    private class Capture(val signatures: List<List<String>>, val units: List<List<Long>>)

    private fun signature(view: View, depth: Int, masked: Set<String>, out: MutableList<String>) {
        // 异步的 RIFE 模型状态行（“检查中…”→ 解析后的状态）两次采样时刻不同，只遮蔽这一类文案，其余文本照常逐节点比较。
        val text = if (view is TextView) view.text.toString().let { if (it in masked) "<frame-model-status>" else it } else ""
        val checked = if (view is CompoundButton) "|checked=${view.isChecked}" else ""
        out += "${"  ".repeat(depth)}${view.javaClass.simpleName}|vis=${view.visibility}|enabled=${view.isEnabled}$checked|$text"
        if (view is ViewGroup) for (index in 0 until view.childCount) signature(view.getChildAt(index), depth + 1, masked, out)
    }

    private fun capture(activity: MainActivity): Capture {
        val home = requireNotNull(activity.settingsHome) { "settings home is installed" }
        val masked = listOf(R.string.player_frame_model_checking, R.string.player_frame_model_ready, R.string.player_frame_model_missing,
            R.string.player_frame_model_framework_unavailable, R.string.player_frame_model_framework_pending,
            R.string.player_frame_model_framework_unsupported, R.string.player_frame_model_invalid,
            R.string.player_frame_model_remote_failed, R.string.player_frame_model_local_failed,
            R.string.player_frame_model_publishing, R.string.player_frame_model_download_failed).map(activity::getString).toSet()
        val signatures = (1..4).map { page -> ArrayList<String>().also { signature(home.pageRoot(page), 0, masked, it) } }
        return Capture(signatures, (1..4).map(home::pageUnitNanos))
    }

    @Test fun idlePrewarmOneUnitPerFrameBuildsTheSamePagesAsSynchronousCompletion() {
        var synchronous: Capture? = null
        var installed = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val home = activity.settingsHome
                installed = home != null
                if (home != null) {
                    (1..4).forEach(home::ensurePage)
                    synchronous = capture(activity)
                }
            }
        }
        // 模块 UI 被用户条款门禁挡住时设置页不会安装；测试不会替用户接受条款，此时跳过（跳过不算通过）。
        assumeTrue("settings UI is gated by the module terms on this device", installed)
        var stepwise: Capture? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 90_000
            var complete = false
            while (!complete) {
                check(SystemClock.elapsedRealtime() < deadline) { "idle prewarm never completed every page" }
                SystemClock.sleep(200)
                scenario.onActivity { activity ->
                    val home = requireNotNull(activity.settingsHome)
                    complete = (1..4).all { home.pageUnitNanos(it).isNotEmpty() }
                    if (complete) stepwise = capture(activity)
                }
            }
        }
        val sync = requireNotNull(synchronous)
        val idle = requireNotNull(stepwise)
        for (page in 0 until 4) {
            assertEquals("page ${page + 1} node count", sync.signatures[page].size, idle.signatures[page].size)
            val firstDifference = sync.signatures[page].indices.firstOrNull { sync.signatures[page][it] != idle.signatures[page][it] }
            assertTrue("page ${page + 1} differs at node $firstDifference: ${firstDifference?.let { sync.signatures[page][it] }} vs ${firstDifference?.let { idle.signatures[page][it] }}",
                firstDifference == null)
        }
        // 单元数：净化 = 壳 + 9 个分类 + 收尾；增强 = 壳 + 6 个 + 收尾；美化 = 1 + 收尾；模块 = 4 + 收尾。
        val expectedUnits = listOf(11, 8, 2, 5)
        for (page in 0 until 4) {
            assertEquals("page ${page + 1} units (sync)", expectedUnits[page], sync.units[page].size)
            assertEquals("page ${page + 1} units (idle)", expectedUnits[page], idle.units[page].size)
        }
        fun report(label: String, units: List<List<Long>>) {
            units.forEachIndexed { index, list ->
                val sorted = list.sorted()
                Log.i("PageSteps", "emulator $label page ${index + 1}: units=${list.size} total=${list.sum() / 1_000_000}ms " +
                    "max=${sorted.last() / 1_000_000}ms median=${sorted[sorted.size / 2] / 1_000_000}ms " +
                    "all=${list.joinToString("/") { (it / 1_000_000).toString() }}")
            }
        }
        report("sync", sync.units)
        report("idle", idle.units)
    }
}
