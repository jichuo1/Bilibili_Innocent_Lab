package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashAdFeatureInstallerTest {

    private val statuses = mutableListOf<Pair<String, String>>()
    private val errors = mutableListOf<String>()
    private val infos = mutableListOf<String>()
    private val ids = mutableListOf<String>()

    private fun environment(
        process: String = "tv.danmaku.bili",
        loader: ClassLoader? = javaClass.classLoader
    ) = HookEnvironment(
        processName = process,
        classLoader = loader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = recordingRegistrar(ids),
        logInfo = { key, _ -> infos += key },
        logError = { key, _ -> errors += key },
        reportStatus = { channel, status -> statuses += channel to status }
    )

    private fun listPoints(vararg getters: String) = VersionAdapter.SplashAdPoints(
        getters.map {
            VersionAdapter.HookPoint(
                "tv.danmaku.bili.splash.ad.model.SplashListResponse", it, emptyList()
            )
        }
    )

    private fun status(channel: String) = statuses.lastOrNull { it.first == channel }?.second

    @Test fun `installs both the list layer and the decision layer`() {
        val result = SplashAdFeatureInstaller(
            enabled = true,
            points = listPoints("getSplashList", "getStrategyList")
        ).install(environment())

        // 两个列表 getter + 一个决策点
        assertEquals(FeatureInstallResult.Installed(3), result)
        assertEquals("success", status("splash_ad_purify_status"))
        assertEquals("success", status("splash_decision_status"))
        assertTrue("splash.decision.isEmptyAd" in ids)
    }

    /**
     * 关键回归：两道防线必须**互不知情**。
     *
     * 早期实现在 `points == null` 时直接 return，把两道串联成"与"——
     * VersionAdapter 一旦定位落空，连未混淆的决策层也装不上。
     */
    @Test fun `the decision layer still installs when the list layer has no adapter point`() {
        val result = SplashAdFeatureInstaller(enabled = true, points = null)
            .install(environment())

        assertEquals(FeatureInstallResult.Installed(1), result)
        assertEquals("decision-only", status("splash_ad_purify_status"))
        assertEquals("success", status("splash_decision_status"))
        assertTrue("splash_purify_list_absent" in infos)
        // 列表层缺适配不是错误，是预期降级。
        assertTrue(errors.isEmpty())
    }

    /**
     * 反向：宿主没有 `SplashOrder#isEmptyAd`（8.84.0–8.97.0 那 12 版就是这样）时，
     * 只装列表层，且**不记 error**——那是预期降级，不是故障。
     */
    @Test fun `the list layer still installs when the host has no decision point`() {
        val bare = object : ClassLoader(null) {}
        val result = SplashAdFeatureInstaller(
            enabled = true,
            points = listPoints("getSplashList")
        ).install(environment(loader = bare))

        assertEquals(FeatureInstallResult.Installed(1), result)
        assertEquals("success", status("splash_ad_purify_status"))
        assertEquals("not-applicable-host", status("splash_decision_status"))
        assertTrue("splash_decision_absent" in infos)
        assertFalse(errors.any { it.startsWith("splash_decision") })
    }

    @Test fun `skips entirely when both layers are unavailable`() {
        val bare = object : ClassLoader(null) {}
        val result = SplashAdFeatureInstaller(enabled = true, points = null)
            .install(environment(loader = bare))
        assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), result)
        assertTrue("splash_purify_missing" in errors)
    }

    @Test fun `skips without touching the host when disabled or off process`() {
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            SplashAdFeatureInstaller(enabled = false, points = null).install(environment())
        )
        assertEquals("disabled", status("splash_decision_status"))
        assertTrue(ids.isEmpty())

        statuses.clear()
        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            SplashAdFeatureInstaller(enabled = true, points = null)
                .install(environment(process = "tv.danmaku.bili:download"))
        )
        assertTrue(statuses.isEmpty())
        assertTrue(ids.isEmpty())
    }

    /**
     * 决策层只许改 `isEmptyAd`，不许碰 `isAd` / `isAdLoc`。
     *
     * 后两者的语义是"**这**是不是广告"，把真广告标成"不是广告"会污染 `ad_cb`
     * 曝光上报；`isEmptyAd -> true` 的语义是"没有广告可展"，上报链自然静默。
     */
    @Test fun `the decision layer never rewrites the is-ad predicates`() {
        SplashAdFeatureInstaller(enabled = true, points = null).install(environment())
        assertEquals(listOf("splash.decision.isEmptyAd"), ids)
        assertFalse(ids.any { it.contains("isAd") && !it.contains("isEmptyAd") })
    }
}

/** 记录注册到的 hook id，用来断言"到底挂了哪些点"。其余方法交给 TestHookRegistrar。 */
internal fun recordingRegistrar(sink: MutableList<String>): HookRegistrar =
    object : HookRegistrar by TestHookRegistrar {
        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            sink += id
        }

        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            sink += id
        }
    }
