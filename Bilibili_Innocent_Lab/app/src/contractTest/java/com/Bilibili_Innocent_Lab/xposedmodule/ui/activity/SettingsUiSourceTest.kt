package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SettingsUiSource] 自己的门禁。
 *
 * 它现在是好几条"真机教训"结构断言的取值方式，一旦它悄悄返回空串或整份文件，
 * 那些断言会**照样变绿**。所以这里用合成源码把切割规则钉死。
 */
class SettingsUiSourceTest {

    fun `braces inside strings and comments do not end the body`() {
        val source = """
            private fun target(count: Int = 1) {
                val text = "not a } close brace {"
                // } neither is this {
                /* } nor this { */
                val raw = ${'"'}${'"'}${'"'} } still not { ${'"'}${'"'}${'"'}
                val marker = count
            }
            private fun after() {
                val other = 0
            }
        """.trimIndent()
        val body = SettingsUiSource.functions(source, "target").single()
        assertTrue(body.contains("val marker = count"))
        // 字符串字面量必须原样保留：门禁要断言资源名这类字面量。
        assertTrue(body.contains("not a } close brace {"))
        assertFalse("must not run into the following declaration", body.contains("val other = 0"))
        // 这段合成源码里注释/字符串带的花括号都是成对的，所以整体必须配平。
        assertEquals(body.count { it == '{' }, body.count { it == '}' })
    }

    fun `lambda default arguments do not confuse the parameter list`() {
        val source = """
            private fun target(
                onDone: () -> Unit = {},
                style: Int = 0
            ) {
                val inside = onDone
            }
        """.trimIndent()
        val body = SettingsUiSource.functions(source, "target").single()
        assertTrue(body.contains("val inside = onDone"))
        assertTrue(body.trimEnd().endsWith("}"))
    }

    /**
     * 回归：表达式体里代码块**内嵌在括号中**（`= getString( when { … } )`）。
     * 只配对花括号会停在 `when` 的 `}`，把结尾的 `)` 漏在外面——搬迁脚本就这么翻过车。
     */
    fun `an expression body whose block is nested inside parentheses is fully captured`() {
        val source = """
            private fun target(enabled: Boolean): String = getString(
                when {
                    enabled -> R.string.a
                    else -> R.string.b
                }
            )

            private fun after() = 0
        """.trimIndent()
        val body = SettingsUiSource.functions(source, "target").single()
        assertTrue(body.trimEnd().endsWith(")"))
        assertEquals(body.count { it == '(' }, body.count { it == ')' })
        assertFalse(body.contains("fun after"))
    }

    /** 回归：首行括号已配平但以 `=` 结尾，表达式在下一行——不能就此收尾。 */
    fun `an expression body continued on the next line is not cut short`() {
        val source = """
            private fun target(spec: Surface): Snapshot? =
                Store.read(this, spec.surface)

            private fun after() = 0
        """.trimIndent()
        val body = SettingsUiSource.functions(source, "target").single()
        assertTrue(body, body.contains("Store.read(this, spec.surface)"))
        assertFalse(body.contains("fun after"))
    }

    fun `a single expression body ends on its own line`() {
        val source = "private fun target() = helper(1, 2)\nprivate fun after() = 0\n"
        val body = SettingsUiSource.functions(source, "target").single()
        // 切片刻意从 `fun` 开始，不含可见性修饰符——这正是它对 private/internal 无感的原因。
        assertEquals("fun target() = helper(1, 2)", body)
    }

    fun `a receiver and any visibility are both accepted`() {
        val extension = "internal fun MainActivity.target() {\n    val moved = 1\n}\n"
        assertTrue(SettingsUiSource.functions(extension, "target").single().contains("val moved = 1"))
        val member = "    private fun target() {\n        val kept = 1\n    }\n"
        assertTrue(SettingsUiSource.functions(member, "target").single().contains("val kept = 1"))
    }

    fun `declared functions ignore nested local functions`() {
        val source = """
            internal fun MainActivity.showSomething() {
                val dialog = Dialog(this)
                fun decide(enabled: Boolean) {
                    val nested = enabled
                }
                presentModalDialog(dialog, container)
            }
        """.trimIndent()
        val declared = SettingsUiSource.declaredFunctions(source, indent = 0)
        assertEquals(listOf("showSomething"), declared.map { it.first })
        // 关键：局部 fun 不能把外层函数体截断，否则"每个弹窗都要调用 presentModalDialog"
        // 这条断言会在搬迁后误报。
        assertTrue(declared.single().second.contains("presentModalDialog(dialog, container)"))
    }

    fun `a missing or duplicated function fails loudly instead of returning an empty window`() {
        val message = assertThrows(IllegalStateException::class.java) {
            SettingsUiSource.function("thisFunctionDoesNotExistAnywhere")
        }.message.orEmpty()
        assertTrue(message, message.contains("not found"))
        assertEquals(2, SettingsUiSource.functions("fun dup() {\n}\nfun dup() {\n}\n", "dup").size)
    }

    fun `the real presenter is extracted as exactly one function, not the whole file`() {
        val present = SettingsUiSource.function("presentSizedModalDialog")
        val whole = SettingsUiSource.mainActivity()
        assertTrue(present.contains("dialog.show()"))
        assertTrue(present.contains("registerBackCallback()"))
        assertTrue("extracted ${present.length} of ${whole.length} chars",
            present.length < whole.length / 4)
        assertTrue(present.trimEnd().endsWith("}"))
        // 相邻函数不能被卷进来（原来的窗口靠"到下一个函数为止"，邻居一变就超收）。
        assertFalse(present.contains("fun createModalContainer"))
    }

    /**
     * 回归：`code()` 必须认识字符串字面量。
     *
     * 只找 `//` 就抹的版本会把 `"https://…"` 里的 `//` 当行注释、连右引号一起抹掉，
     * 之后 `mask()` 从一个未闭合的字符串一路吞下去，整份文件的结构消失 ——
     * `declaredFunctions` 从 125 个降到 0 个，而依赖它的断言全在空列表上"通过"。
     */
    fun `stripping comments keeps string literals intact`() {
        val source = """
            private fun target() {
                val url = "https://example.com/a/b" // 真注释要抹掉
                val kept = 1
            }
        """.trimIndent()
        val code = SettingsUiSource.code(source)
        assertEquals("length must stay aligned", source.length, code.length)
        assertTrue(code, code.contains("\"https://example.com/a/b\""))
        assertFalse("the real comment must be gone", code.contains("真注释"))
        // 关键：抹完之后还能正常切函数。
        assertTrue(SettingsUiSource.functions(code, "target").single().contains("val kept = 1"))
    }

    /**
     * **去注释不得改变函数集合** —— 对扫描集里的每一个文件都成立。
     *
     * `> 100` 这条**不是**"MainActivity 必须保留 100 个成员"的结构约束，
     * 而是上面那组等式的**样本量下限**：语料太小时 `assertEquals` 会在空集上
     * 平凡通过，护栏静默失效。同一个模式见 `AdvancedCategoryTreeTest` 的
     * `check(builders.size >= 14)`。
     *
     * 2026-09-15 改口径：原来只看 MainActivity 单文件，于是"外移越多、样本越小"，
     * 逻辑分卷搬到第 3 个就会跌破 100——**而被测性质一点没变弱**。
     * 现在等式逐文件检查（比原来更严：分卷也纳入了），
     * 下限改为对准工具实际扫描的整个语料。外移只是把成员换个文件放，
     * 语料总量不该因此缩水。
     */
    fun `declaredFunctions survives the comment-stripped view of every scanned file`() {
        var total = 0
        SettingsUiSource.settingsUiFiles().forEach { (name, raw) ->
            val stripped = SettingsUiSource.code(raw)
            // 缩进 4 = 类成员，缩进 0 = 分卷里的顶层扩展函数，两种都要覆盖。
            listOf(0, 4).forEach { indent ->
                val onRaw = SettingsUiSource.declaredFunctions(raw, indent)
                val onStripped = SettingsUiSource.declaredFunctions(stripped, indent)
                assertEquals(
                    "$name (indent=$indent): stripping comments must not change the member set",
                    onRaw.map { it.first },
                    onStripped.map { it.first }
                )
                total += onRaw.size
            }
        }
        assertTrue("the scanned surface should declare plenty of members: $total", total > 100)
    }

    fun `MainActivity is always part of the scanned set`() {
        assertTrue(SettingsUiSource.settingsUiFiles().map { it.first }.contains("MainActivity.kt"))
        assertTrue(SettingsUiSource.all().contains("class MainActivity"))
    }

    /**
     * **从 MainActivity 搬出去的代码不许脱离门禁。**
     *
     * 外移的落地形态就是在新文件里写 `fun MainActivity.…` 扩展，
     * 所以"有扩展但不在扫描集"是绕过 `SettingsDialogExtractionTest` 四条结构约束
     * （禁顶层 var / 禁生命周期注册 / 必须是扩展 / SetTextI18n 抑制）的唯一入口。
     *
     * 这条把那个入口焊死：新分卷要么用 `VOLUME_SUFFIXES` 里的后缀命名，
     * 要么显式扩充那份清单，没有第三条路。
     *
     * 2026-09-15 首次跑这条时揪出了 `PendingCompatibilityRetry.kt`——
     * 它本身写得没问题，但那是运气：门禁一直没在看它。
     */
    fun `every extracted MainActivity extension lives in a scanned volume`() {
        assertEquals(
            "these files declare fun MainActivity.<name> but escape the extraction gates; " +
                "rename them to a SettingsUiSource.VOLUME_SUFFIXES suffix (or extend that list)",
            emptyList<String>(),
            SettingsUiSource.unscannedMainActivityExtensions()
        )
    }

    /** 扫描集必须真的比弹窗集大（或相等），且弹窗集仍只认 `Dialogs.kt`。 */
    fun `the volume set is a superset of the dialog set`() {
        val volumes = SettingsUiSource.volumeFileNames
        val dialogs = SettingsUiSource.dialogFileNames
        assertTrue("dialogs must stay inside the scanned volumes", volumes.containsAll(dialogs))
        assertTrue("dialogFileNames must keep its narrow meaning",
            dialogs.all { it.endsWith("Dialogs.kt") })
        assertEquals("the scanned set must not contain duplicates", volumes.distinct(), volumes)
        assertTrue("MainActivity is added separately, never as a volume",
            "MainActivity.kt" !in volumes)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (SettingsUiSourceTest) -> Unit) {
            val s = SettingsUiSourceTest()
            block(s)
        }
        runIsolated { s -> s.`braces inside strings and comments do not end the body`() }
        runIsolated { s -> s.`lambda default arguments do not confuse the parameter list`() }
        runIsolated { s -> s.`an expression body whose block is nested inside parentheses is fully captured`() }
        runIsolated { s -> s.`an expression body continued on the next line is not cut short`() }
        runIsolated { s -> s.`a single expression body ends on its own line`() }
        runIsolated { s -> s.`a receiver and any visibility are both accepted`() }
        runIsolated { s -> s.`declared functions ignore nested local functions`() }
        runIsolated { s -> s.`a missing or duplicated function fails loudly instead of returning an empty window`() }
        runIsolated { s -> s.`the real presenter is extracted as exactly one function, not the whole file`() }
        runIsolated { s -> s.`stripping comments keeps string literals intact`() }
        runIsolated { s -> s.`declaredFunctions survives the comment-stripped view of every scanned file`() }
        runIsolated { s -> s.`MainActivity is always part of the scanned set`() }
        runIsolated { s -> s.`every extracted MainActivity extension lives in a scanned volume`() }
        runIsolated { s -> s.`the volume set is a superset of the dialog set`() }
    }
}
