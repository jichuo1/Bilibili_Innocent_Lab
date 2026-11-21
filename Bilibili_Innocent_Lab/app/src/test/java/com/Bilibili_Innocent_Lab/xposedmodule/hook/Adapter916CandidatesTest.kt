package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.danmaku.bili.update.model.BiliUpgradeInfo

class Adapter916CandidatesTest {
    class Network { fun a(context: Context?): BiliUpgradeInfo? = null }
    class WrongNetwork { fun a(context: Context?): String = "" }
    class Quality { companion object { @JvmStatic fun a(): Int = 32 } }
    class WrongQuality { companion object { @JvmStatic fun a(): String = "" } }
    class AmbiguousQuality { companion object {
        @JvmStatic fun a(): Int = 32
        @JvmStatic fun c(): Int = 64
    } }

    private fun host(network: Class<*>, quality: Class<*>) = object : ClassLoader(javaClass.classLoader!!) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
            "Gs1.c" -> network
            "xu1.h" -> quality
            else -> super.loadClass(name, resolve)
        }
    }

    fun `916 network boundary and default quality precede old compatible owners`() {
        val loader = host(Network::class.java, Quality::class.java)
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(loader)?.className)
        assertEquals(Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(loader)?.defaultQualityMethod?.className)
    }

    fun `same new owner names with the wrong ABI retain the old fallback`() {
        val loader = host(WrongNetwork::class.java, WrongQuality::class.java)
        assertEquals("Dr1.c", VersionAdapter.locateBlockUpdate(loader)?.className)
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(loader)?.defaultQualityMethod?.className)
    }

    fun `multiple default quality signatures do not guess an entry`() {
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(
            host(Network::class.java, AmbiguousQuality::class.java))?.defaultQualityMethod?.className)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (Adapter916CandidatesTest) -> Unit) {
            val s = Adapter916CandidatesTest()
            block(s)
        }
        runIsolated { s -> s.`916 network boundary and default quality precede old compatible owners`() }
        runIsolated { s -> s.`same new owner names with the wrong ABI retain the old fallback`() }
        runIsolated { s -> s.`multiple default quality signatures do not guess an entry`() }
    }
}
