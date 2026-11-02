package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.bilibili.ship.theseus.united.page.intro.module.relate.AmbiguousRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.DetailRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.ModernRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.UnverifiedRelateService
import com.bilibili.ship.theseus.united.page.intro.module.relate.r2
import org.junit.Assert.*
import org.junit.Test

class DetailRelateServiceLocatorTest {
    @Test fun verifiedDispatcherWinsOverIdenticallyTypedSpecializedFactories() {
        val factory = requireNotNull(VersionAdapter.selectDetailRelateComponentFactory(ModernRelateService::class.java))
        assertEquals("C0", factory.name)
        assertEquals(listOf(r2::class.java), factory.parameterTypes.toList())
        assertNull(factory.invoke(ModernRelateService(), r2()))
    }

    @Test fun unknownDtoAndUnresolvedSpecializedTiesAreRejected() {
        assertNull(VersionAdapter.selectDetailRelateComponentFactory(UnverifiedRelateService::class.java))
        assertNull(VersionAdapter.selectDetailRelateComponentFactory(AmbiguousRelateService::class.java))
    }

    @Test fun legacyDispatcherRemainsAvailable() {
        assertEquals("d", VersionAdapter.selectDetailRelateComponentFactory(DetailRelateService::class.java)?.name)
    }
}
