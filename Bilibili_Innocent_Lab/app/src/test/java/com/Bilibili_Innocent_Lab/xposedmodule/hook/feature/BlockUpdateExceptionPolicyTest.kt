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

    class FakeUpdateSupplier {
        fun checkUpdate() = Unit
    }

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

    fun `update block registers with deliver-to-host so the host actually receives the exception`() {
        val result = BlockUpdateFeatureInstaller(enabled = true, point = point)
            .install(environment())

        assertTrue(result is FeatureInstallResult.Installed)
        assertEquals(listOf("update.block.check"), ids)
        assertEquals(listOf(HookExceptionPolicy.DELIVER_TO_HOST), policies)
        assertTrue("success" in statuses)
    }

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

    /**
     * 关键回归：supplier 层定位落空时，**调用方层仍然要装上**。
     *
     * 这是"不同版本效果不好"的结构成因——原实现在 `point == null` 时直接 return，
     * 把两道防线串联成"与"，VersionAdapter 一失手整个功能就没了。
     * 现在两道互不知情：`adapted` 一次都没注册（`policies` 为空），
     * 但三个未混淆的调用方入口照样短路。
     */
    fun `the caller layer still installs when the supplier point is missing`() {
        val callerIds = mutableListOf<String>()
        val recording = object : HookRegistrar by this.registrar {
            override fun exact(
                id: String,
                owner: Class<*>,
                methodName: String,
                vararg parameterTypes: Class<*>,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                callerIds += id
            }
        }
        val result = BlockUpdateFeatureInstaller(enabled = true, point = null)
            .install(environment(recording))

        assertEquals(FeatureInstallResult.Installed(3, complete = false), result)
        assertEquals(
            BlockUpdateFeatureInstaller.CALLER_METHODS.map { "update.block.caller.$it" },
            callerIds
        )
        assertTrue("caller-only" in statuses)
        // supplier 层一次都没注册。
        assertTrue(policies.isEmpty())
    }

    /** 强更恢复刻意不在屏蔽表里：它返回 bolts.Task，语义是"恢复未完成的强更"。 */
    fun `the force update recovery entry is deliberately left alone`() {
        assertTrue(
            BlockUpdateFeatureInstaller.EXCLUDED_FORCE_UPDATE !in
                BlockUpdateFeatureInstaller.CALLER_METHODS
        )
        assertEquals(
            listOf("checkUpdateInStartup", "checkUpdateAndShowDialog", "checkInternalUpdateFlag"),
            BlockUpdateFeatureInstaller.CALLER_METHODS
        )
    }

    /** 宿主没有 UpdateHelper 时只装 supplier 层，且不记 error（预期降级）。 */
    fun `a host without the update helper keeps the supplier layer only`() {
        val bare = object : ClassLoader(null) {}
        val errors = mutableListOf<String>()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = bare,
            hookPoints = HookPointRegistry(loader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { key, _ -> errors += key },
            reportStatus = { _, status -> statuses += status }
        )
        val result = BlockUpdateFeatureInstaller(enabled = true, point = point).install(env)
        assertEquals(FeatureInstallResult.Installed(1, complete = false), result)
        assertEquals(listOf("update.block.check"), ids)
        assertTrue(errors.none { it.startsWith("block_update_caller") })
    }

    fun `installer retries supplier layer idempotently without duplicating caller hooks`() {
        val portRegistrar = PlayerPortTestRegistrar()
        val adaptedAttempts = mutableListOf<String>()
        val trackingRegistrar = object : HookRegistrar by portRegistrar {
            override fun adapted(
                id: String,
                point: VersionAdapter.HookPoint,
                exceptionPolicy: HookExceptionPolicy,
                block: ModernMemberHookCreator.() -> Unit
            ) {
                adaptedAttempts += id
            }
        }
        val stages = mutableListOf<FeatureRuntimeStage>()
        val retryStatuses = linkedMapOf<String, String>()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = trackingRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> retryStatuses[k] = v },
            runtimeEvidence = { _, stage, _ -> stages += stage }
        )
        var supplierPoint: VersionAdapter.HookPoint? = null
        val installer = BlockUpdateFeatureInstaller(
            enabled = true,
            point = null,
            pointProvider = { supplierPoint }
        )

        val run1 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(3, run1.hookCount)
        assertEquals(false, run1.complete)
        assertTrue(installer.requiresAdaptationRetry)
        assertEquals("caller-only", retryStatuses["block_update_status"])
        assertEquals(3, portRegistrar.registrationAttempts.size)
        assertTrue(adaptedAttempts.isEmpty())

        supplierPoint = VersionAdapter.HookPoint(FakeUpdateSupplier::class.java.name, "checkUpdate", emptyList())
        val run2 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(4, run2.hookCount)
        assertTrue(run2.complete)
        assertEquals(false, installer.requiresAdaptationRetry)
        assertEquals("success", retryStatuses["block_update_status"])
        assertEquals(3, portRegistrar.registrationAttempts.size)
        assertEquals(listOf("update.block.check"), adaptedAttempts)
        assertTrue(FeatureRuntimeStage.ADAPTED in stages)

        val run3 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(4, run3.hookCount)
        assertTrue(run3.complete)
        assertEquals(3, portRegistrar.registrationAttempts.size)
        assertEquals(listOf("update.block.check"), adaptedAttempts)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (BlockUpdateExceptionPolicyTest) -> Unit) {
            val s = BlockUpdateExceptionPolicyTest()
            block(s)
        }
        runIsolated { s -> s.`update block registers with deliver-to-host so the host actually receives the exception`() }
        runIsolated { s -> s.`disabled and non-main-process paths never register anything`() }
        runIsolated { s -> s.`the caller layer still installs when the supplier point is missing`() }
        runIsolated { s -> s.`the force update recovery entry is deliberately left alone`() }
        runIsolated { s -> s.`a host without the update helper keeps the supplier layer only`() }
        runIsolated { s -> s.`installer retries supplier layer idempotently without duplicating caller hooks`() }
    }
}
