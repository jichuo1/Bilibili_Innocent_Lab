package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class HostBackgroundSaveWiringContractTest {
    fun editorOnlyKeepsTheImportedAssetAfterTheRollbackAwareSaveSucceeds() {
        val editor = SourceContract.read("ui/activity/HostBackgroundPresenter.kt")
        val save = editor.after("private fun save()").before("fun close(")
        assertTrue(save.contains("check(HostBackgroundSettingsStore.save(activity.prefs(), config))"))
        val success = save.after("}.onSuccess {").before("}.onFailure")
        assertTrue(success.contains("saved = true"))
        assertTrue(success.contains("HostBackgroundImages.cleanup(activity, config.asset)"))
        assertFalse(save.before("}.onSuccess {").contains("HostBackgroundImages.cleanup("))
        val dispose = editor.after("private fun dispose()")
        assertTrue(dispose.contains("if (!saved) importedAsset?.let"))
    }

    fun queuedPreviewBlocksSaveUntilTheLatestGenerationHasReturned() {
        val editor = SourceContract.read("ui/activity/HostBackgroundPresenter.kt")
        val render = editor.after("private fun renderImage()").before("private fun save()")
        assertTrue(render.before("activity.liquidBackgroundWorker.execute").contains("rendering = true"))
        val returned = render.after("activity.runOnUiThread {")
        assertTrue(returned.before("rendering = false").contains("if (!active || closing || ticket != generation)"))
        val save = editor.after("private fun save()").before("fun close(")
        assertTrue(save.before("val config = draft.normalized()").contains("if (importing || rendering || !active || closing) return"))
    }

    fun committedAssetOwnershipIsTransferredBeforeCallbacksAndClosingIsUnconditional() {
        val editor = SourceContract.read("ui/activity/HostBackgroundPresenter.kt")
        val success = editor.after("private fun save()").after("}.onSuccess {").before("}.onFailure { Toast")
        assertTrue(success.before("HostBackgroundImages.cleanup").contains("importedAsset = null"))
        assertTrue(success.contains("finally { close() }"))
        // 类式编辑器也必须接入共享皮肤容器与锚点 presenter，不能漏出函数枚举护栏。
        assertTrue(editor.contains("private val container = activity.createModalContainer()"))
        assertTrue(editor.contains("activity.presentModalDialog(dialog, container, anchor, onDismissed = ::dispose)"))
        assertFalse(editor.contains("dialog.setOnDismissListener"))
        val close = editor.after("fun close(").before("private fun dispose()")
        assertTrue(close.contains("activity.dismissWithAnimation(dialog, container)"))
        assertFalse(close.before("} else {").contains("dispose()"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (HostBackgroundSaveWiringContractTest) -> Unit) {
            val s = HostBackgroundSaveWiringContractTest()
            block(s)
        }
        runIsolated { s -> s.editorOnlyKeepsTheImportedAssetAfterTheRollbackAwareSaveSucceeds() }
        runIsolated { s -> s.queuedPreviewBlocksSaveUntilTheLatestGenerationHasReturned() }
        runIsolated { s -> s.committedAssetOwnershipIsTransferredBeforeCallbacksAndClosingIsUnconditional() }
    }
}
