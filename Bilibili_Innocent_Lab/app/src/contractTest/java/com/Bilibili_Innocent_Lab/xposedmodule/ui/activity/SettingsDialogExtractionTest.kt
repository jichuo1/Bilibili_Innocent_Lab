package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页弹窗与分卷外移的结构约束。
 *
 * 外移分卷从 MainActivity 搬到同包文件里写成 `MainActivity` 扩展函数；
 * 禁止顶层可变状态、禁止生命周期注册、强制扩展签名与 SetTextI18n 抑制、ModalPresenter 单一底座。
 */
class SettingsDialogExtractionTest {

    private fun volumeFiles(): List<Pair<String, String>> =
        SettingsUiSource.volumeFileNames.map { it to SettingsUiSource.file(it) }

    private fun volumeCode(): List<Pair<String, String>> =
        volumeFiles().map { (name, text) -> name to SettingsUiSource.code(text) }

    @Test
    fun extractedVolumesOwnDialogsPresenterExclusivelyAndStayStatelessWithoutLifecycleHooks() {
        val dialogs = SettingsUiSource.dialogFileNames
        assertTrue("no *Dialogs.kt found; did the extraction get reverted?", dialogs.size >= 9)

        val stateOffenders = mutableListOf<String>()
        volumeCode().forEach { (name, code) ->
            Regex("""(?m)^(?:private |internal |public )*var\s+(\w+)""")
                .findAll(code)
                .forEach { stateOffenders += "$name: var ${it.groupValues[1]}" }
        }
        assertEquals("top-level var is process-global, not per-Activity", emptyList<String>(), stateOffenders)

        val lifecycleOffenders = mutableListOf<String>()
        volumeCode().forEach { (name, code) ->
            listOf("registerForActivityResult", "registerReceiver(", "getOnBackInvokedDispatcher")
                .filter { code.contains(it) }
                .forEach { lifecycleOffenders += "$name: $it" }
        }
        assertEquals(emptyList<String>(), lifecycleOffenders)

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
            assertFalse(
                "$name must not fork the presenter",
                text.contains("fun MainActivity.presentSizedModalDialog(")
            )
        }
    }

    @Test
    fun topLevelFunctionsAreExtensionsOrPureHelpersWithSetTextI18nSuppression() {
        val extOffenders = mutableListOf<String>()
        volumeFiles().forEach { (name, text) ->
            SettingsUiSource.declaredFunctions(text, indent = 0).forEach { (fn, body) ->
                val headerLine = body.lineSequence().firstOrNull { !it.trimStart().startsWith("@") }.orEmpty()
                val isExtension = headerLine.contains("fun MainActivity.")
                val touchesActivity = listOf(
                    "getString(", "getColor(", "resources", "applicationContext",
                    "createModalContainer(", "presentModalDialog("
                ).any { body.contains(it) }
                if (!isExtension && touchesActivity) extOffenders += "$name: $fn"
            }
        }
        assertEquals("must be declared as fun MainActivity.<name>", emptyList<String>(), extOffenders)

        val suppressOffenders = mutableListOf<String>()
        volumeFiles().forEach { (name, text) ->
            val concatenates = Regex("""text = getString\([^)]*\)\s*\+""").containsMatchIn(text) ||
                Regex("""text = [^\n]*\+ getString\(""").containsMatchIn(text)
            if (concatenates && !text.contains("""@file:Suppress("SetTextI18n")""")) {
                suppressOffenders += name
            }
        }
        assertEquals(emptyList<String>(), suppressOffenders)
    }
}
