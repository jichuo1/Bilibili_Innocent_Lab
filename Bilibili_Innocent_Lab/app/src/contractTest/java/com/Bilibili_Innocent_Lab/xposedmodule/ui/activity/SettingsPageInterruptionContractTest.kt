package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageInterruptionContractTest {
    @Test
    fun settingsHomePresenterForwardsPositionAndDisposesTextChain() {
        val home = LceMotionSource.read("ui/activity/SettingsHomePresenter.kt")
        assertTrue(home.contains("textChain?.onPositionChanged()"))
        assertTrue(home.after("fun dispose() {").contains("textChain?.dispose()"))
    }
}
