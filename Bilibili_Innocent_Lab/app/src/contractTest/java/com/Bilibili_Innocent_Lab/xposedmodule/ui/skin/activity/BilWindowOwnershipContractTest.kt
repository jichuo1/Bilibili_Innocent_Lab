package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

/** Module UI and inset handling share the Activity window owner. */
class BilWindowOwnershipContractTest {
    @Test fun uiBindingBorrowsTheWindowSessionUsedForInsets() {
        val mount = SourceContract.read("ui/meguviz/SettingsScope.kt")
            .after("internal fun SkinnedActivity.setMeguvizContentView")
        assertTrue(mount.contains("AndroidUiBinding.open(moduleWindowSession(), parent)"))
        assertTrue(mount.contains("binding.close()"))

        val activity = SourceContract.read("ui/skin/activity/SkinnedActivity.kt")
        val window = activity.after("internal fun moduleWindowSession()").before("override fun setContentView")
        assertTrue(window.contains("windowSession ?: WindowSession.open"))
        assertTrue(window.contains("BilWindowDelegate(this, content, moduleWindowSession())"))
        assertTrue(activity.after("override fun onDestroy()").contains("windowOwner?.close()"))
    }

    @Test fun settingsPageReceivesInsetOwnershipBeforeReplacingListener() {
        val presenter = SourceContract.read("ui/activity/SettingsHomePresenter.kt")
            .after("private fun installTopInset()")
            .before("private fun applyScrollInsets()")
        val handOff = presenter.indexOf("activity.handOffModuleInsets(shell)")
        val install = presenter.indexOf("ViewCompat.setOnApplyWindowInsetsListener(shell)")
        assertTrue("the root must be handed off before the page installs its listener", handOff >= 0 && install > handOff)

        val activity = SourceContract.read("ui/skin/activity/SkinnedActivity.kt")
        assertTrue(activity.contains("windowDelegate?.handOffInsets(root)"))
        val delegate = SourceContract.read("ui/skin/activity/BilWindowDelegate.kt")
            .after("fun handOffInsets(root: View)")
            .before("override fun close()")
        assertTrue(delegate.contains("rootView !== root"))
        assertTrue(delegate.contains("previous?.handOffToExternalOwner()"))
    }
}
