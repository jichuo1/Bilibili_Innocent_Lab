package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tv.danmaku.bili.update.model.BiliUpgradeInfo

class Adapter917CandidatesTest {
    class Network { fun a(context: Context?): BiliUpgradeInfo? = null }
    class WrongNetwork { fun a(context: Context?): String = "" }
    class Quality { companion object { @JvmStatic fun a(): Int = 32 } }
    class WrongQuality { companion object { @JvmStatic fun a(): String = "" } }
    class AmbiguousQuality { companion object {
        @JvmStatic fun a(): Int = 32
        @JvmStatic fun c(): Int = 64
    } }
    interface Supplier { fun a(context: Context?): BiliUpgradeInfo? }
    class BridgedNetwork : Supplier {
        override fun a(context: Context?): BiliUpgradeInfo? = null
        fun c(context: Context?): BiliUpgradeInfo? = null
    }

    private class HostLoader(
        network: Class<*>,
        quality: Class<*>,
        verified: Map<String, Class<*>> = emptyMap()
    ) : ClassLoader(Adapter917CandidatesTest::class.java.classLoader) {
        private val classes = mapOf("Bx1.c" to network, "tz1.h" to quality) + verified
        val requested = mutableSetOf<String>()
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            requested += name
            return classes[name] ?: super.loadClass(name, resolve)
        }
    }

    @Test fun `917 direct implementation owners precede compatible older owners`() {
        val loader = HostLoader(Network::class.java, Quality::class.java)
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(loader)?.className)
        assertEquals(Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(loader)?.defaultQualityMethod?.className)
        assertFalse("The cache wrapper is not a network candidate", "Bx1.a" in loader.requested)
    }

    @Test fun `same new names with incompatible signatures retain the old fallback`() {
        val loader = HostLoader(WrongNetwork::class.java, WrongQuality::class.java)
        assertEquals("Dr1.c", VersionAdapter.locateBlockUpdate(loader)?.className)
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(loader)?.defaultQualityMethod?.className)
    }

    @Test fun `ambiguous new quality methods retain the old unique implementation`() {
        val loader = HostLoader(Network::class.java, AmbiguousQuality::class.java)
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(loader)?.defaultQualityMethod?.className)
    }

    @Test fun `network bridge keeps the existing unique leaf selection`() {
        val loader = HostLoader(BridgedNetwork::class.java, Quality::class.java)
        assertEquals("c", VersionAdapter.locateBlockUpdate(loader)?.methodName)
    }

    @Test fun `verified media owners are used only for their APK build codes`() {
        val loader = HostLoader(WrongNetwork::class.java, WrongQuality::class.java,
            mapOf("gwx1" to Network::class.java, "rnr1" to Quality::class.java))
        for (version in listOf(9190200, 9190400)) {
            assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(loader, version)?.className)
            assertEquals(Quality::class.java.name,
                VersionAdapter.locateDefaultVideoQuality(loader, hostVersionCode = version)?.defaultQualityMethod?.className)
        }
        assertNull(VersionAdapter.locateBlockUpdate(loader, 9180300))
        assertNull(VersionAdapter.locateDefaultVideoQuality(loader, hostVersionCode = 9180300)?.defaultQualityMethod)
    }
}
