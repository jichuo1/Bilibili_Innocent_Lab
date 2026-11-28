package com.bilibili.ship.theseus.united.page.intro.module.relate

import com.bilibili.app.gemini.ui.RunningUIComponent as Component

// From DetailRelateService.kt
open class DetailRelateService {
    open fun d(item: D0?): RunningUIComponent = RunningUIComponent()
}

// From ModernRelateServiceFixtures.kt
/** Central dispatcher and specialized factories really share the same signature. */
class ModernRelateService {
    private fun C0(item: r2): Component? = null
    private fun J0(item: r2): Component? = null
    private fun P0(item: r2): Component? = null
}

class AmbiguousRelateService {
    private fun J0(item: r2): Component? = null
    private fun P0(item: r2): Component? = null
}

class UnverifiedRelateItem
class UnverifiedRelateService {
    private fun C0(item: UnverifiedRelateItem): Component? = null
    private fun J0(item: UnverifiedRelateItem): Component? = null
}
