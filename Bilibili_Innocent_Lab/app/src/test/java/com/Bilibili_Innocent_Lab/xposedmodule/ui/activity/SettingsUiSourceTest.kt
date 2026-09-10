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

    @Test fun `braces inside strings and comments do not end the body`() {
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

    @Test fun `lambda default arguments do not confuse the parameter list`() {
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
    @Test fun `an expression body whose block is nested inside parentheses is fully captured`() {
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
    @Test fun `an expression body continued on the next line is not cut short`() {
        val source = """
            private fun target(spec: Surface): Snapshot? =
                Store.read(this, spec.surface)

            private fun after() = 0
        """.trimIndent()
        val body = SettingsUiSource.functions(source, "target").single()
        assertTrue(body, body.contains("Store.read(this, spec.surface)"))
        assertFalse(body.contains("fun after"))
    }

    @Test fun `a single expression body ends on its own line`() {
        val source = "private fun target() = helper(1, 2)\nprivate fun after() = 0\n"
        val body = SettingsUiSource.functions(source, "target").single()
        // 切片刻意从 `fun` 开始，不含可见性修饰符——这正是它对 private/internal 无感的原因。
        assertEquals("fun target() = helper(1, 2)", body)
    }

    @Test fun `a receiver and any visibility are both accepted`() {
        val extension = "internal fun MainActivity.target() {\n    val moved = 1\n}\n"
        assertTrue(SettingsUiSource.functions(extension, "target").single().contains("val moved = 1"))
        val member = "    private fun target() {\n        val kept = 1\n    }\n"
        assertTrue(SettingsUiSource.functions(member, "target").single().contains("val kept = 1"))
    }

    @Test fun `declared functions ignore nested local functions`() {
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

    @Test fun `a missing or duplicated function fails loudly instead of returning an empty window`() {
        val message = assertThrows(IllegalStateException::class.java) {
            SettingsUiSource.function("thisFunctionDoesNotExistAnywhere")
        }.message.orEmpty()
        assertTrue(message, message.contains("not found"))
        assertEquals(2, SettingsUiSource.functions("fun dup() {\n}\nfun dup() {\n}\n", "dup").size)
    }

    @Test fun `the real presenter is extracted as exactly one function, not the whole file`() {
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
    @Test fun `stripping comments keeps string literals intact`() {
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

    @Test fun `declaredFunctions survives the comment-stripped view of MainActivity`() {
        val raw = SettingsUiSource.mainActivity()
        val stripped = SettingsUiSource.code(raw)
        val onRaw = SettingsUiSource.declaredFunctions(raw, indent = 4).size
        val onStripped = SettingsUiSource.declaredFunctions(stripped, indent = 4).size
        assertTrue("MainActivity should have plenty of members: $onRaw", onRaw > 100)
        assertEquals("stripping comments must not change the member set", onRaw, onStripped)
    }

    @Test fun `MainActivity is always part of the scanned set`() {
        assertTrue(SettingsUiSource.settingsUiFiles().map { it.first }.contains("MainActivity.kt"))
        assertTrue(SettingsUiSource.all().contains("class MainActivity"))
    }
}
