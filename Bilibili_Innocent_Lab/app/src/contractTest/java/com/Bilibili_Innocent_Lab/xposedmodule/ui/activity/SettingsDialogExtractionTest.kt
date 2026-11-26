package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页弹窗外移的结构约束。
 *
 * 弹窗从 MainActivity 搬到同包的 Dialogs 文件里，写成 `MainActivity` 的扩展函数。
 * 这个形态有两个**看不见的**退化方向，编译器不会拦，所以钉在这里。
 */
class SettingsDialogExtractionTest {

    /**
     * 受本组结构约束管辖的文件：**所有外移分卷**，不只是弹窗。
     *
     * 2026-09-15 起从 `dialogFileNames` 换成 `volumeFileNames`：逻辑分卷
     * （Presenter / Controller / Summaries）与弹窗的退化方向一模一样，
     * 没有理由只管后者。换之前 `PendingCompatibilityRetry.kt` 就已经是漏网的
     * ——它写得没问题，但那是运气，门禁根本没在看它。
     */
    private fun volumeFiles(): List<Pair<String, String>> =
        SettingsUiSource.volumeFileNames.map { it to SettingsUiSource.file(it) }

    /** 去注释后的源码：文件头注释解释"为什么不能这么写"时不该被判成违规。 */
    private fun volumeCode(): List<Pair<String, String>> =
        volumeFiles().map { (name, text) -> name to SettingsUiSource.code(text) }

    fun `the dialogs were actually extracted into per-topic files`() {
        // 这条仍按**弹窗**计数：它防的是"弹窗被搬回 MainActivity"，
        // 逻辑分卷的增减不该把这个下限稀释掉。
        val dialogs = SettingsUiSource.dialogFileNames
        assertTrue("no *Dialogs.kt found; did the extraction get reverted?", dialogs.size >= 9)
        val main = SettingsUiSource.mainActivity().lines().size
        // MainActivity 曾经是 13909 行。这条不是为了追求短，而是防止弹窗被搬回去。
        assertTrue("MainActivity is $main lines; dialogs look like they moved back in",
            main < 11000)
    }

    /**
     * **顶层不允许有可变状态。**
     *
     * `private var` 搬到文件级就从"每个 Activity 一份"变成**进程级单例**：
     * Activity 重建后仍残留，两个 Activity 实例还会互相干扰。
     * 所以外移只搬函数，状态一律留在 MainActivity 上（放宽成 internal）。
     */
    fun `extracted dialog files declare no top-level mutable state`() {
        val offenders = mutableListOf<String>()
        volumeCode().forEach { (name, code) ->
            Regex("""(?m)^(?:private |internal |public )*var\s+(\w+)""")
                .findAll(code)
                .forEach { offenders += "$name: var ${it.groupValues[1]}" }
        }
        assertEquals("top-level var is process-global, not per-Activity", emptyList<String>(), offenders)
    }

    /**
     * **生命周期注册不允许外移。**
     *
     * `registerForActivityResult` 必须在 Activity 构造期完成注册，
     * 搬成顶层属性会直接失去注册（点了没反应，且不报错）。
     */
    fun `extracted dialog files do not register lifecycle-scoped callbacks`() {
        val offenders = mutableListOf<String>()
        volumeCode().forEach { (name, code) ->
            listOf("registerForActivityResult", "registerReceiver(", "getOnBackInvokedDispatcher")
                .filter { code.contains(it) }
                .forEach { offenders += "$name: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** 每个外移文件的顶层函数都必须挂在 MainActivity 上（或是不需要 Activity 的纯函数）。 */
    fun `top-level functions are MainActivity extensions or pure helpers`() {
        val offenders = mutableListOf<String>()
        volumeFiles().forEach { (name, text) ->
            SettingsUiSource.declaredFunctions(text, indent = 0).forEach { (fn, body) ->
                val header = body.substringBefore('\n')
                val isExtension = header.contains("fun MainActivity.")
                // 纯函数不得触碰 Activity 上下文。
                val touchesActivity = listOf(
                    "getString(", "getColor(", "resources", "applicationContext",
                    "createModalContainer(", "presentModalDialog("
                ).any { body.contains(it) }
                if (!isExtension && touchesActivity) offenders += "$name: $fn"
            }
        }
        assertEquals("must be declared as fun MainActivity.<name>", emptyList<String>(), offenders)
    }

    /**
     * 文件级 `@file:Suppress` **不会随函数一起搬走**。
     *
     * MainActivity 是文件级抑制 SetTextI18n 的；搬出去的弹窗里凡是拼接过 setText 文案的，
     * 都要自己带上这条抑制，否则门禁里会凭空多出 Warning。
     */
    fun `files that concatenate setText content carry the same file-level suppression`() {
        val offenders = mutableListOf<String>()
        volumeFiles().forEach { (name, text) ->
            val concatenates = Regex("""text = getString\([^)]*\)\s*\+""").containsMatchIn(text) ||
                Regex("""text = [^\n]*\+ getString\(""").containsMatchIn(text)
            if (concatenates && !text.contains("""@file:Suppress("SetTextI18n")""")) {
                offenders += name
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** 共用底座必须集中在 ModalPresenter 且不得被其它分卷分叉：它承载返回手势、形变、气泡与模糊的整条时序。 */
    fun `the shared modal presenter lives exclusively in ModalPresenter`() {
        val presenter = SettingsUiSource.file("ModalPresenter")
        listOf(
            "fun MainActivity.presentSizedModalDialog(",
            "fun MainActivity.presentModalDialog(",
            "fun MainActivity.createModalContainer(",
            "fun MainActivity.dismissWithAnimation("
        ).forEach { assertTrue(it, presenter.contains(it)) }
        val main = SettingsUiSource.mainActivity()
        listOf(
            "fun presentSizedModalDialog(",
            "fun presentModalDialog(",
            "fun createModalContainer(",
            "fun dismissWithAnimation("
        ).forEach { assertFalse("MainActivity must not duplicate $it", main.contains(it)) }
        volumeFiles().filter { (name, _) -> name != "ModalPresenter.kt" }.forEach { (name, text) ->
            assertFalse("$name must not fork the presenter",
                text.contains("fun MainActivity.presentSizedModalDialog("))
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SettingsDialogExtractionTest) -> Unit) {
            val s = SettingsDialogExtractionTest()
            block(s)
        }
        runIsolated { s -> s.`the dialogs were actually extracted into per-topic files`() }
        runIsolated { s -> s.`extracted dialog files declare no top-level mutable state`() }
        runIsolated { s -> s.`extracted dialog files do not register lifecycle-scoped callbacks`() }
        runIsolated { s -> s.`top-level functions are MainActivity extensions or pure helpers`() }
        runIsolated { s -> s.`files that concatenate setText content carry the same file-level suppression`() }
        runIsolated { s -> s.`the shared modal presenter lives exclusively in ModalPresenter`() }
    }
}
