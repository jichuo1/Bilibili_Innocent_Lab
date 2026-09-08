package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏蔽官方更新是全仓唯一“必须把异常交回宿主”的 Hook。
 *
 * 框架 `ExceptionMode.PROTECTIVE` 的契约是“捕获 hooker 异常并按没装 Hook 继续”，因此
 * 只要这条注册退回默认策略，`ModernHookParam.throwable` 就永远到不了宿主，屏蔽会**静默**
 * 失效（宿主更新检查照常返回）。本用例把策略钉死，防止以后被顺手改回默认值。
 */
class BlockUpdateExceptionPolicyTest {

    private val loader = javaClass.classLoader!!
    private val point = VersionAdapter.HookPoint(
        className = "tv.danmaku.bili.update.internal.UpdateChecker",
        methodName = "check",
        paramClassNames = null
    )
    private val statuses = mutableListOf<String>()
    private val policies = mutableListOf<HookExceptionPolicy>()
    private val ids = mutableListOf<String>()

    private val registrar = object : HookRegistrar by TestHookRegistrar {
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            ids += id
            policies += exceptionPolicy
        }
    }

    private fun environment(registrar: HookRegistrar = this.registrar) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, status -> statuses += status }
    )

    @Test
    fun `update block registers with deliver-to-host so the host actually receives the exception`() {
        val result = BlockUpdateFeatureInstaller(enabled = true, point = point)
            .install(environment())

        assertTrue(result is FeatureInstallResult.Installed)
        assertEquals(listOf("update.block.check"), ids)
        assertEquals(listOf(HookExceptionPolicy.DELIVER_TO_HOST), policies)
        assertTrue("success" in statuses)
    }

    @Test
    fun `disabled and non-main-process paths never register anything`() {
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            BlockUpdateFeatureInstaller(enabled = false, point = point).install(environment())
        )
        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            BlockUpdateFeatureInstaller(enabled = true, point = point)
                .install(environment().copy(processName = "tv.danmaku.bili:web"))
        )
        assertTrue(policies.isEmpty())
    }

    @Test
    fun `missing adapter point is skipped before any registration`() {
        assertEquals(
            FeatureInstallResult.Skipped("missing-adapter-point"),
            BlockUpdateFeatureInstaller(enabled = true, point = null).install(environment())
        )
        assertTrue(policies.isEmpty())
    }
}
