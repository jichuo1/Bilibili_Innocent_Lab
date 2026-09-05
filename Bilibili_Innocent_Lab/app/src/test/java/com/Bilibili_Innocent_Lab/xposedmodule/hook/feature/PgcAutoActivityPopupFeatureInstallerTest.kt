package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bilibili.ship.theseus.ogv.activity.OgvActivityHalfScreenPopup
import com.bilibili.ship.theseus.ogv.activity.OgvActivityVo
import com.bilibili.ship.theseus.ogv.activity.OgvActivityVo_JsonDescriptor
import io.github.libxposed.api.XposedInterface
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Executable

class PgcAutoActivityPopupFeatureInstallerTest {
    private val loader = javaClass.classLoader!!
    private val points = requireNotNull(VersionAdapter.locatePgcAutoActivityPopup(loader))
    private val method = OgvActivityVo_JsonDescriptor::class.java.getDeclaredMethod(
        "constructWith", Array<Any?>::class.java
    )
    private val descriptor = OgvActivityVo_JsonDescriptor()
    private val statuses = mutableListOf<String>()
    private val stages = mutableListOf<FeatureRuntimeStage>()
    private val errors = mutableListOf<String>()
    private var creator: ModernMemberHookCreator? = null
    private var registrations = 0
    private val registrar = object : HookRegistrar by TestHookRegistrar {
        override fun adapted(
            id: String, point: VersionAdapter.HookPoint, block: ModernMemberHookCreator.() -> Unit
        ) {
            registrations++
            assertEquals(points.construct, point)
            creator = ModernMemberHookCreator(method).apply(block)
        }
    }
    private val environment = HookEnvironment(
        processName = "tv.danmaku.bili", classLoader = loader,
        hookPoints = HookPointRegistry(loader), registrar = registrar,
        logInfo = { _, _ -> }, logError = { id, _ -> errors += id },
        reportStatus = { _, status -> statuses += status },
        runtimeEvidence = { id, stage, _ ->
            assertEquals(PgcAutoActivityPopupFeatureInstaller.ID, id)
            stages += stage
        }
    )

    private fun install(env: HookEnvironment = environment) =
        PgcAutoActivityPopupFeatureInstaller(true, points).install(env)

    private fun values(half: OgvActivityHalfScreenPopup? = OgvActivityHalfScreenPopup("url", "id")) =
        arrayOf<Any?>(
            12, OgvActivityVo.InviteDrawer(), OgvActivityVo.InviteWin(), listOf(Any()),
            OgvActivityVo.Countdown(), OgvActivityVo.IndependentWin(), OgvActivityVo.FloatLayer(),
            half, OgvActivityVo.FloatBall()
        )

    private fun invoke(
        values: Any?,
        original: (Array<Any?>) -> Any? = { descriptor.constructWith(it[0] as Array<*>) }
    ): Any? {
        val chain = TestChain(method, descriptor, arrayOf(values), original)
        val result = requireNotNull(creator).invoke(chain)
        assertEquals(1, chain.calls)
        return result
    }

    @Test
    fun `one descriptor hook removes only the automatic half before model construction`() {
        assertEquals(FeatureInstallResult.Installed(1), install())
        assertEquals(1, registrations)
        assertEquals(listOf("success"), statuses)
        val original = values()
        val result = invoke(original) as OgvActivityVo
        assertNull(result.half)
        assertNotNull(original[7])
        assertEquals(original[0], result.activityId)
        assertSame(original[1], result.inviteDrawer)
        assertSame(original[2], result.inviteWin)
        assertSame(original[3], result.container)
        assertSame(original[4], result.countdown)
        assertSame(original[5], result.independentWin)
        assertSame(original[6], result.floatLayer)
        assertSame(original[8], result.floatBall)
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), stages)
        invoke(values())
        assertEquals(2, stages.size)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `disabled feature and non-main process install no hooks`() {
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            PgcAutoActivityPopupFeatureInstaller(false, points).install(environment)
        )
        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            install(environment.copy(processName = "tv.danmaku.bili:web"))
        )
        assertEquals(0, registrations)
        val manual = values()
        assertSame(manual[7], (descriptor.constructWith(manual) as OgvActivityVo).half)
    }

    @Test
    fun `missing stale and failed registration never report installed`() {
        assertEquals(
            FeatureInstallResult.Skipped("missing-adapter-point"),
            PgcAutoActivityPopupFeatureInstaller(true, null).install(environment)
        )
        assertEquals(
            FeatureInstallResult.Skipped("missing-verified-nullable-structure"),
            PgcAutoActivityPopupFeatureInstaller(true, points.copy(popupIndex = 0)).install(environment)
        )
        assertEquals(0, registrations)
        val broken = object : HookRegistrar by TestHookRegistrar {
            override fun adapted(
                id: String, point: VersionAdapter.HookPoint, block: ModernMemberHookCreator.() -> Unit
            ) = throw IllegalStateException("fixture registration failure")
        }
        assertEquals(FeatureInstallResult.Skipped("registration-failed"), install(environment.copy(registrar = broken)))
        assertTrue(stages.isEmpty())
        assertFalse("success" in statuses)
    }

    @Test
    fun `no half and bad input pass through with bounded diagnostics and no applied evidence`() {
        install()
        val absent = values(null)
        invoke(absent) { arguments ->
            assertSame(absent, arguments[0])
            descriptor.constructWith(arguments[0] as Array<*>)
        }
        val wrongType = values().apply { this[7] = Any() }
        repeat(3) {
            listOf(null, emptyArray<Any>(), wrongType).forEach { input ->
                assertEquals("unchanged", invoke(input) { arguments ->
                    assertSame(input, arguments[0])
                    "unchanged"
                })
            }
        }
        assertTrue(stages.isEmpty())
        assertEquals(2, errors.size)
    }

    @Test
    fun `failed or ineffective original construction is never counted as applied`() {
        install()
        val failure = IllegalStateException("original failure")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            invoke(values()) { throw failure }
        })
        val unchanged = descriptor.constructWith(values())
        invoke(values()) { unchanged }
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED), stages)
        assertEquals(1, errors.size)
    }

    @Test
    fun `diagnostic callback failure cannot affect original construction or filtering`() {
        install(environment.copy(runtimeEvidence = { _, _, _ -> error("diagnostics only") }))
        assertNull((invoke(values()) as OgvActivityVo).half)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `sanitized capture replay filters one half and preserves five absent responses`() {
        install()
        val samples = JSONArray(requireNotNull(
            loader.getResourceAsStream("pgc-activity/receive-sanitized.json")
        ).bufferedReader().use { it.readText() })
        var nonNullInputs = 0
        for (index in 0 until samples.length()) {
            val data = samples.getJSONObject(index).getJSONObject("data")
            val half = data.optJSONObject("play_half_container")?.let {
                nonNullInputs++
                OgvActivityHalfScreenPopup(it.getString("h5url"), it.getString("win_id"))
            }
            val input = values(half)
            val output = invoke(input) as OgvActivityVo
            assertNull(output.half)
            assertSame(input[3], output.container)
            assertSame(input[6], output.floatLayer)
            assertSame(half, input[7])
        }
        assertEquals(6, samples.length())
        assertEquals(1, nonNullInputs)
        assertEquals(listOf(FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), stages)
    }

    private class TestChain(
        private val member: Executable, private val receiver: Any,
        private val arguments: Array<Any?>, private val original: (Array<Any?>) -> Any?
    ) : XposedInterface.Chain {
        var calls = 0
        override fun getExecutable(): Executable = member
        override fun getThisObject(): Any = receiver
        override fun getArgs(): List<Any?> = arguments.toList()
        override fun getArg(index: Int): Any? = arguments[index]
        override fun proceed(): Any? = proceed(arguments)
        override fun proceed(args: Array<Any?>): Any? { calls++; return original(args) }
        override fun proceedWith(thisObject: Any): Any? = proceed()
        override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? = proceed(args)
    }
}
