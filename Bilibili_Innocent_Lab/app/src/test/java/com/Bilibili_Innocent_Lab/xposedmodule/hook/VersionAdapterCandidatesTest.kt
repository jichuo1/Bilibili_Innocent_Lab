package com.Bilibili_Innocent_Lab.xposedmodule.hook

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PointFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import tv.danmaku.bili.update.model.BiliUpgradeInfo

class VersionAdapterCandidatesTest {
    class Network { fun a(context: Context?): BiliUpgradeInfo? = null }
    class WrongNetwork { fun a(context: Context?): String = "" }
    class Quality { companion object { @JvmStatic fun a(): Int = 32 } }
    class WrongQuality { companion object { @JvmStatic fun a(): String = "" } }
    class Network919 { fun a(context: Context?): BiliUpgradeInfo? = null }
    class Quality919 { companion object { @JvmStatic fun a(): Int = 32 } }
    class AmbiguousQuality {
        companion object {
            @JvmStatic fun a(): Int = 32
            @JvmStatic fun c(): Int = 64
        }
    }
    interface Supplier { fun a(context: Context?): BiliUpgradeInfo? }
    class BridgedNetwork : Supplier {
        override fun a(context: Context?): BiliUpgradeInfo? = null
        fun c(context: Context?): BiliUpgradeInfo? = null
    }

    private val parent = javaClass.classLoader!!

    private fun mapped(names: Map<String, Class<*>>) = object : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = names[name] ?: super.loadClass(name, resolve)
    }

    private class HostLoader(
        network: Class<*>,
        quality: Class<*>,
        verified: Map<String, Class<*>> = emptyMap()
    ) : ClassLoader(VersionAdapterCandidatesTest::class.java.classLoader) {
        private val classes = mapOf("Bx1.c" to network, "tz1.h" to quality) + verified
        val requested = mutableSetOf<String>()
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            requested += name
            return classes[name] ?: super.loadClass(name, resolve)
        }
    }

    @Test
    fun candidates915And916PrecedenceAndFallback() {
        // 915: new 915 ABI takes precedence while unrelated new owner falls through to 914
        for ((network, quality) in listOf("ss1.c" to "ju1.h", "rs1.c" to "iu1.h")) {
            val host = mapped(mapOf(network to Network::class.java, quality to Quality::class.java))
            assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(host)?.className)
            assertEquals(Quality::class.java.name, VersionAdapter.locateDefaultVideoQuality(host)?.defaultQualityMethod?.className)
        }
        val collision = mapped(
            mapOf(
                "rs1.c" to WrongNetwork::class.java,
                "ss1.c" to WrongNetwork::class.java,
                "iu1.h" to WrongQuality::class.java,
                "ju1.h" to WrongQuality::class.java
            )
        )
        assertEquals("Dr1.c", VersionAdapter.locateBlockUpdate(collision)?.className)
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(collision)?.defaultQualityMethod?.className)

        // 916: network boundary and default quality precede old compatible owners
        fun host916(network: Class<*>, quality: Class<*>) = mapped(mapOf("Gs1.c" to network, "xu1.h" to quality))
        val valid916 = host916(Network::class.java, Quality::class.java)
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(valid916)?.className)
        assertEquals(
            Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(valid916)?.defaultQualityMethod?.className
        )

        val wrong916 = host916(WrongNetwork::class.java, WrongQuality::class.java)
        assertEquals("Dr1.c", VersionAdapter.locateBlockUpdate(wrong916)?.className)
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(wrong916)?.defaultQualityMethod?.className)

        assertEquals(
            "ut1.h",
            VersionAdapter.locateDefaultVideoQuality(host916(Network::class.java, AmbiguousQuality::class.java))
                ?.defaultQualityMethod?.className
        )
    }

    @Test
    fun candidates917PrecedenceFallbackAndBridgeLeaf() {
        // 917 direct implementation owners precede compatible older owners
        val directLoader = HostLoader(Network::class.java, Quality::class.java)
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(directLoader)?.className)
        assertEquals(
            Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(directLoader)?.defaultQualityMethod?.className
        )
        assertFalse("The cache wrapper is not a network candidate", "Bx1.a" in directLoader.requested)

        // same new names with incompatible signatures retain the old fallback
        val wrongLoader = HostLoader(WrongNetwork::class.java, WrongQuality::class.java)
        assertEquals("Dr1.c", VersionAdapter.locateBlockUpdate(wrongLoader)?.className)
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(wrongLoader)?.defaultQualityMethod?.className)

        // ambiguous new quality methods retain the old unique implementation
        val ambigLoader = HostLoader(Network::class.java, AmbiguousQuality::class.java)
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(ambigLoader)?.defaultQualityMethod?.className)

        // network bridge keeps the existing unique leaf selection
        val bridgeLoader = HostLoader(BridgedNetwork::class.java, Quality::class.java)
        assertEquals("c", VersionAdapter.locateBlockUpdate(bridgeLoader)?.methodName)
    }

    @Test
    fun verified919To921BuildsAndMissingDiagnostics() {
        // 919 verified media owners are used only for their APK build codes
        val loader919 = HostLoader(
            WrongNetwork::class.java,
            WrongQuality::class.java,
            mapOf("gwx1" to Network::class.java, "rnr1" to Quality::class.java)
        )
        for (version in listOf(9190200, 9190400)) {
            assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(loader919, version)?.className)
            assertEquals(
                Quality::class.java.name,
                VersionAdapter.locateDefaultVideoQuality(loader919, hostVersionCode = version)?.defaultQualityMethod?.className
            )
        }
        assertNull(VersionAdapter.locateBlockUpdate(loader919, 9180300))
        assertNull(VersionAdapter.locateDefaultVideoQuality(loader919, hostVersionCode = 9180300)?.defaultQualityMethod)

        // 920 verified media owners resolve per build and never leak across builds
        val builds920 = mapOf(
            9200100 to ("lr72" to "mg12"),
            9200200 to ("rt72" to "oh12"),
            9200300 to ("jv72" to "wj12")
        )
        builds920.forEach { (version, owners) ->
            val loader = HostLoader(
                WrongNetwork::class.java,
                WrongQuality::class.java,
                mapOf(owners.first to Network::class.java, owners.second to Quality::class.java)
            )
            assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(loader, version)?.className)
            assertEquals(
                Quality::class.java.name,
                VersionAdapter.locateDefaultVideoQuality(loader, hostVersionCode = version)?.defaultQualityMethod?.className
            )
        }
        val cross919To920 = HostLoader(
            WrongNetwork::class.java,
            WrongQuality::class.java,
            mapOf(
                "gwx1" to Network919::class.java,
                "rnr1" to Quality919::class.java,
                "lr72" to Network::class.java,
                "mg12" to Quality::class.java
            )
        )
        assertEquals(Network919::class.java.name, VersionAdapter.locateBlockUpdate(cross919To920, 9190400)?.className)
        assertEquals(
            Quality919::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(cross919To920, hostVersionCode = 9190400)?.defaultQualityMethod?.className
        )
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(cross919To920, 9200100)?.className)
        assertEquals(
            Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(cross919To920, hostVersionCode = 9200100)?.defaultQualityMethod?.className
        )

        // 921 verified media owners resolve per build and never leak across builds
        val builds921 = mapOf(
            9210100 to ("jg72" to "t612"),
            9210200 to ("dd72" to "l212"),
            9210300 to ("dd72" to "l212")
        )
        builds921.forEach { (version, owners) ->
            val loader = HostLoader(
                WrongNetwork::class.java,
                WrongQuality::class.java,
                mapOf(owners.first to Network::class.java, owners.second to Quality::class.java)
            )
            assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(loader, version)?.className)
            assertEquals(
                Quality::class.java.name,
                VersionAdapter.locateDefaultVideoQuality(loader, hostVersionCode = version)?.defaultQualityMethod?.className
            )
        }
        val cross921 = HostLoader(
            WrongNetwork::class.java,
            WrongQuality::class.java,
            mapOf(
                "jg72" to Network919::class.java,
                "t612" to Quality919::class.java,
                "dd72" to Network::class.java,
                "l212" to Quality::class.java
            )
        )
        assertEquals(Network919::class.java.name, VersionAdapter.locateBlockUpdate(cross921, 9210100)?.className)
        assertEquals(
            Quality919::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(cross921, hostVersionCode = 9210100)?.defaultQualityMethod?.className
        )
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(cross921, 9210200)?.className)
        assertEquals(
            Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(cross921, hostVersionCode = 9210200)?.defaultQualityMethod?.className
        )
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(cross921, 9210300)?.className)
        assertEquals(
            Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(cross921, hostVersionCode = 9210300)?.defaultQualityMethod?.className
        )

        // verified owner failure is reported missing not not applicable
        val plan = AdaptPlan(1L, setOf(PointFamily.BLOCK_UPDATE, PointFamily.PLAYER_QUALITY), emptySet())
        val located = VersionAdapterTest().result().copy(blockUpdate = null, playerQuality = null)
        val cases = listOf(
            9200100 to VerifiedOnlyLoader(mapOf("lr72" to WrongNetwork::class.java, "mg12" to WrongQuality::class.java)),
            9200100 to VerifiedOnlyLoader(emptyMap()),
            9210100 to VerifiedOnlyLoader(mapOf("jg72" to WrongNetwork::class.java, "t612" to WrongQuality::class.java)),
            9210200 to VerifiedOnlyLoader(mapOf("dd72" to WrongNetwork::class.java, "l212" to WrongQuality::class.java)),
            9210200 to VerifiedOnlyLoader(emptyMap()),
            9210300 to VerifiedOnlyLoader(mapOf("dd72" to WrongNetwork::class.java, "l212" to WrongQuality::class.java)),
            9210300 to VerifiedOnlyLoader(emptyMap())
        )
        for ((versionCode, loader) in cases) {
            val withVersion = VersionAdapter.buildDiagnostics(loader, plan, null, located, "", versionCode)
                .associateBy { it.id }
            assertEquals(AdaptState.MISSING, withVersion.getValue("update.block").state)
            assertEquals(AdaptState.MISSING, withVersion.getValue("player.default_quality").state)

            val withoutVersion = VersionAdapter.buildDiagnostics(loader, plan, null, located, "")
                .associateBy { it.id }
            assertEquals(AdaptState.NOT_APPLICABLE, withoutVersion.getValue("update.block").state)
            assertEquals(AdaptState.NOT_APPLICABLE, withoutVersion.getValue("player.default_quality").state)
            assertNull(VersionAdapter.locateBlockUpdate(loader, versionCode))
            assertNull(VersionAdapter.locateDefaultVideoQuality(loader, hostVersionCode = versionCode)?.defaultQualityMethod)
        }
    }
}

/** 只解析指定 owner、隐藏旧候选表夹具的宿主：模拟 9.20 上旧混淆短名已随 R8 消失。 */
private class VerifiedOnlyLoader(private val owners: Map<String, Class<*>>) :
    ClassLoader(VersionAdapterCandidatesTest::class.java.classLoader) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        owners[name]?.let { return it }
        if (LEGACY_OWNER_PACKAGES.any(name::startsWith)) throw ClassNotFoundException(name)
        return super.loadClass(name, resolve)
    }

    private companion object {
        val LEGACY_OWNER_PACKAGES = listOf(
            "Dr1.", "ar1.", "gr1.", "qq1.", "mq1.", "vd6.", "Ip1.", "rs1.",
            "ut1.", "Rs1.", "Xs1.", "is1.", "es1.", "Ar1.", "Jq1.", "gh6.",
            "com.bilibili.playerbizcommon.utils."
        )
    }
}
