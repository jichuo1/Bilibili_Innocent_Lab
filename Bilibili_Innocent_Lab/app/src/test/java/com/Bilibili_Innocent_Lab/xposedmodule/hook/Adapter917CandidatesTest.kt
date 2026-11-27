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

class Adapter917CandidatesTest {
    class Network { fun a(context: Context?): BiliUpgradeInfo? = null }
    class WrongNetwork { fun a(context: Context?): String = "" }
    class Quality { companion object { @JvmStatic fun a(): Int = 32 } }
    class WrongQuality { companion object { @JvmStatic fun a(): String = "" } }
    class Network919 { fun a(context: Context?): BiliUpgradeInfo? = null }
    class Quality919 { companion object { @JvmStatic fun a(): Int = 32 } }
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

    fun `917 direct implementation owners precede compatible older owners`() {
        val loader = HostLoader(Network::class.java, Quality::class.java)
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(loader)?.className)
        assertEquals(Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(loader)?.defaultQualityMethod?.className)
        assertFalse("The cache wrapper is not a network candidate", "Bx1.a" in loader.requested)
    }

    fun `same new names with incompatible signatures retain the old fallback`() {
        val loader = HostLoader(WrongNetwork::class.java, WrongQuality::class.java)
        assertEquals("Dr1.c", VersionAdapter.locateBlockUpdate(loader)?.className)
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(loader)?.defaultQualityMethod?.className)
    }

    fun `ambiguous new quality methods retain the old unique implementation`() {
        val loader = HostLoader(Network::class.java, AmbiguousQuality::class.java)
        assertEquals("ut1.h", VersionAdapter.locateDefaultVideoQuality(loader)?.defaultQualityMethod?.className)
    }

    fun `network bridge keeps the existing unique leaf selection`() {
        val loader = HostLoader(BridgedNetwork::class.java, Quality::class.java)
        assertEquals("c", VersionAdapter.locateBlockUpdate(loader)?.methodName)
    }

    fun `verified media owners are used only for their APK build codes`() {
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

    fun `920 verified media owners resolve per build and never leak across builds`() {
        // 远端 dexq 逐构建验真：block update=*72#a(Context)BiliUpgradeInfo，默认画质=*12#a()I。
        val builds = mapOf(
            9200100 to ("lr72" to "mg12"),
            9200200 to ("rt72" to "oh12"),
            9200300 to ("jv72" to "wj12"),
        )
        builds.forEach { (version, owners) ->
            val loader = HostLoader(WrongNetwork::class.java, WrongQuality::class.java,
                mapOf(owners.first to Network::class.java, owners.second to Quality::class.java))
            assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(loader, version)?.className)
            assertEquals(Quality::class.java.name,
                VersionAdapter.locateDefaultVideoQuality(loader, hostVersionCode = version)?.defaultQualityMethod?.className)
        }
        // 双向不串：同一 loader 同时持有 9.19 与 9.20 的短名（各接独立正确夹具），
        // 各版本必须只选自己的 owner——若 9.20 误用 9.19 名字会解析到 Network919 而断言失败。
        val crossLoader = HostLoader(WrongNetwork::class.java, WrongQuality::class.java,
            mapOf("gwx1" to Network919::class.java, "rnr1" to Quality919::class.java,
                "lr72" to Network::class.java, "mg12" to Quality::class.java))
        assertEquals(Network919::class.java.name, VersionAdapter.locateBlockUpdate(crossLoader, 9190400)?.className)
        assertEquals(Quality919::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(crossLoader, hostVersionCode = 9190400)?.defaultQualityMethod?.className)
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(crossLoader, 9200100)?.className)
        assertEquals(Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(crossLoader, hostVersionCode = 9200100)?.defaultQualityMethod?.className)
    }

    fun `921 verified media owners resolve per build and never leak across builds`() {
        val builds = mapOf(
            9210100 to ("jg72" to "t612"),
            9210200 to ("dd72" to "l212"),
            9210300 to ("dd72" to "l212"),
        )
        builds.forEach { (version, owners) ->
            val loader = HostLoader(WrongNetwork::class.java, WrongQuality::class.java,
                mapOf(owners.first to Network::class.java, owners.second to Quality::class.java))
            assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(loader, version)?.className)
            assertEquals(Quality::class.java.name,
                VersionAdapter.locateDefaultVideoQuality(loader, hostVersionCode = version)?.defaultQualityMethod?.className)
        }
        val crossLoader = HostLoader(WrongNetwork::class.java, WrongQuality::class.java,
            mapOf(
                "jg72" to Network919::class.java, "t612" to Quality919::class.java,
                "dd72" to Network::class.java, "l212" to Quality::class.java
            ))
        assertEquals(Network919::class.java.name, VersionAdapter.locateBlockUpdate(crossLoader, 9210100)?.className)
        assertEquals(Quality919::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(crossLoader, hostVersionCode = 9210100)?.defaultQualityMethod?.className)
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(crossLoader, 9210200)?.className)
        assertEquals(Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(crossLoader, hostVersionCode = 9210200)?.defaultQualityMethod?.className)
        assertEquals(Network::class.java.name, VersionAdapter.locateBlockUpdate(crossLoader, 9210300)?.className)
        assertEquals(Quality::class.java.name,
            VersionAdapter.locateDefaultVideoQuality(crossLoader, hostVersionCode = 9210300)?.defaultQualityMethod?.className)
    }

    /**
     * 9.20/9.21 走已验真分支：owner 只按登记名查一次，类缺失或签名不符都直接返回 null、不回候选表。
     * 两种失败都必须记 MISSING，不能因为旧候选表里没有这些短名就记成「宿主无此结构」。
     */
    fun `verified owner failure is reported missing not not applicable`() {
        val plan = AdaptPlan(1L, setOf(PointFamily.BLOCK_UPDATE, PointFamily.PLAYER_QUALITY), emptySet())
        val located = VersionAdapterTest().result().copy(blockUpdate = null, playerQuality = null)
        // 类在但 ABI 漂移，和 owner 短名整体漂移（类都加载不到）都要算适配失败。
        val cases = listOf(
            9200100 to VerifiedOnlyLoader(mapOf("lr72" to WrongNetwork::class.java, "mg12" to WrongQuality::class.java)),
            9200100 to VerifiedOnlyLoader(emptyMap()),
            9210100 to VerifiedOnlyLoader(mapOf("jg72" to WrongNetwork::class.java, "t612" to WrongQuality::class.java)),
            9210200 to VerifiedOnlyLoader(mapOf("dd72" to WrongNetwork::class.java, "l212" to WrongQuality::class.java)),
            9210200 to VerifiedOnlyLoader(emptyMap()),
            9210300 to VerifiedOnlyLoader(mapOf("dd72" to WrongNetwork::class.java, "l212" to WrongQuality::class.java)),
            9210300 to VerifiedOnlyLoader(emptyMap()),
        )
        for ((versionCode, loader) in cases) {
            val withVersion = VersionAdapter.buildDiagnostics(loader, plan, null, located, "", versionCode)
                .associateBy { it.id }
            assertEquals(AdaptState.MISSING, withVersion.getValue("update.block").state)
            assertEquals(AdaptState.MISSING, withVersion.getValue("player.default_quality").state)

            // 反证：不带版本码时看不到已验真 owner，旧候选表也没有这些短名——那正是被修复的误判。
            val withoutVersion = VersionAdapter.buildDiagnostics(loader, plan, null, located, "")
                .associateBy { it.id }
            assertEquals(AdaptState.NOT_APPLICABLE, withoutVersion.getValue("update.block").state)
            assertEquals(AdaptState.NOT_APPLICABLE, withoutVersion.getValue("player.default_quality").state)
            assertNull(VersionAdapter.locateBlockUpdate(loader, versionCode))
            assertNull(VersionAdapter.locateDefaultVideoQuality(loader, hostVersionCode = versionCode)?.defaultQualityMethod)
        }
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (Adapter917CandidatesTest) -> Unit) {
            val s = Adapter917CandidatesTest()
            block(s)
        }
        runIsolated { s -> s.`917 direct implementation owners precede compatible older owners`() }
        runIsolated { s -> s.`same new names with incompatible signatures retain the old fallback`() }
        runIsolated { s -> s.`ambiguous new quality methods retain the old unique implementation`() }
        runIsolated { s -> s.`network bridge keeps the existing unique leaf selection`() }
        runIsolated { s -> s.`verified media owners are used only for their APK build codes`() }
        runIsolated { s -> s.`920 verified media owners resolve per build and never leak across builds`() }
        runIsolated { s -> s.`921 verified media owners resolve per build and never leak across builds`() }
        runIsolated { s -> s.`verified owner failure is reported missing not not applicable`() }
    }
}

/** 只解析指定 owner、隐藏旧候选表夹具的宿主：模拟 9.20 上旧混淆短名已随 R8 消失。 */
private class VerifiedOnlyLoader(private val owners: Map<String, Class<*>>) :
    ClassLoader(Adapter917CandidatesTest::class.java.classLoader) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        owners[name]?.let { return it }
        if (LEGACY_OWNER_PACKAGES.any(name::startsWith)) throw ClassNotFoundException(name)
        return super.loadClass(name, resolve)
    }

    private companion object {
        /** 测试源集里真实存在夹具的旧候选 owner 包；末项是画质候选表末尾的稳定包装层。 */
        val LEGACY_OWNER_PACKAGES = listOf(
            "Dr1.", "ar1.", "gr1.", "qq1.", "mq1.", "vd6.", "Ip1.", "rs1.",
            "ut1.", "Rs1.", "Xs1.", "is1.", "es1.", "Ar1.", "Jq1.", "gh6.",
            "com.bilibili.playerbizcommon.utils."
        )
    }
}
