package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocatorTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocatorTest.KotlinReaders
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStylePoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentBindProbe.Companion.NO_TOKEN
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentBindQueue.Entry
import java.lang.management.ManagementFactory
import java.lang.reflect.Method
import java.util.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BvToAvFeatureInstallerTest {

    fun `recognizes only well formed bv ids`() {
        assertTrue(BvToAvFeatureInstaller.looksLikeBvId("BV1xx411c7mD"))
        assertFalse(BvToAvFeatureInstaller.looksLikeBvId("BV1xx411c7m"))
        assertFalse(BvToAvFeatureInstaller.looksLikeBvId("av170001"))
        assertFalse(BvToAvFeatureInstaller.looksLikeBvId(""))
        assertFalse(BvToAvFeatureInstaller.looksLikeBvId(null))
    }

    fun `picks the av id regardless of parameter order`() {
        assertEquals(
            "170001",
            BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", "170001", "BV1xx411c7mD")
        )
        assertEquals(
            "170001",
            BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", "BV1xx411c7mD", "170001")
        )
        assertEquals(
            "av170001",
            BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", "av170001", null)
        )
    }

    fun `keeps the host value when there is no usable av id`() {
        assertNull(BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", null, null))
        assertNull(BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", "  ", ""))
        assertNull(
            BvToAvFeatureInstaller.preferredAvId("BV1xx411c7mD", "BV1xx411c7mD", "BV1yy411c7mD")
        )
    }

    fun `does nothing when the host already returns an av id`() {
        assertNull(BvToAvFeatureInstaller.preferredAvId("170001", "170001", "BV1xx411c7mD"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (BvToAvFeatureInstallerTest) -> Unit) {
            val s = BvToAvFeatureInstallerTest()
            block(s)
        }
        runIsolated { s -> s.`recognizes only well formed bv ids`() }
        runIsolated { s -> s.`picks the av id regardless of parameter order`() }
        runIsolated { s -> s.`keeps the host value when there is no usable av id`() }
        runIsolated { s -> s.`does nothing when the host already returns an av id`() }
    
        CloudKeyFreezeFeatureInstallerCases().verifyAllCases()
        CloudKeyFreezeRulesCases().verifyAllCases()
        CloudKeyScanCollectorCases().verifyAllCases()
        CloudKeyScanSnapshotCases().verifyAllCases()
        CommentBindBudgetCases().verifyAllCases()
        CommentBindProbeCases().verifyAllCases()
        CommentClassicStyleFeatureInstallerCases().verifyAllCases()
    }
}

private class CloudKeyFreezeFeatureInstallerCases {
    // region host shapes (signatures mirror 9.17.0; bodies never run, the registrar supplies the original result)

    class FakeDeviceDecision {
        fun getBoolean(key: String, default: Boolean): Boolean = default
        fun getBoolean(key: String, default: Boolean, extra: Function1<String, Map<String, String>>?): Boolean = default
        fun dd(key: String, default: String?): String? = default
        fun dd(key: String, default: String?, extra: Function1<String, Map<String, String>>?): String? = default
        fun getDdVersion(): Long = 0L
    }

    interface Gdd
    class ImplA : Gdd {
        fun getBoolean(key: String, default: Boolean): Boolean = default
        fun getBoolean(key: String, default: Boolean, extra: Function1<String, Map<String, String>>?): Boolean = default
        fun dd(key: String, default: String?, extra: Function1<String, Map<String, String>>?): String? = default
    }
    class ImplB : Gdd {
        fun getBoolean(key: String, default: Boolean, extra: Function1<String, Map<String, String>>?): Boolean = default
    }
    class NotAnImpl {
        fun getBoolean(key: String, default: Boolean, extra: Function1<String, Map<String, String>>?): Boolean = default
    }

    object FakeKotlinDd {
        @JvmStatic fun getBool(receiver: Any?, key: String, default: Boolean, context: Any?): Boolean = default
        @JvmStatic fun getString(receiver: Any?, key: String, default: String?, context: Any?): String? = default
        @JvmStatic fun `getBool$default`(receiver: Any?, key: String, default: Boolean, context: Any?, mask: Int, marker: Any?): Boolean = default
    }

    class FakeConfigCompanion {
        fun isHitFF(key: String): Boolean = true
        fun getConfig(key: String, default: String): String = default
        fun ab(): Any? = null
    }

    object FakeConfigManager {
        @JvmStatic fun isHitFF(key: String): Boolean = true
        @JvmStatic fun getConfig(key: String, default: String): String = default
    }

    open class FakeTypedContract { open fun get(key: String, default: Any?): Any? = default }
    class FakeOverrideFf {
        fun get(key: String, default: Any?): Any? = default
        fun getWithDefault(key: String, default: Boolean): Boolean = default
    }
    class FakeOverrideConfig { fun get(key: String, default: Any?): Any? = default }
    interface FakeFeatureFlag { fun getWithDefault(key: String, default: Boolean): Boolean }
    class FakeLegacyFf : FakeTypedContract(), FakeFeatureFlag {
        override fun get(key: String, default: Any?): Any? = default
        override fun getWithDefault(key: String, default: Boolean): Boolean = default
    }
    class FakeLegacyConfig : FakeTypedContract()

    interface TypedContractShape<T> { fun get(key: String, default: T?): T }

    /** 编译器会为它生成合成桥接方法 `Object get(String, Object)`，宿主里接口调用正是落在这种方法上。 */
    class BridgedOverrideFf : TypedContractShape<Boolean> {
        override fun get(key: String, default: Boolean?): Boolean = default ?: true
        fun getWithDefault(key: String, default: Boolean): Boolean = default
    }

    // endregion

    private val classes = mapOf<String, Class<*>>(
        "com.bilibili.lib.dd.DeviceDecision" to FakeDeviceDecision::class.java,
        "com.bilibili.lib.dd.GDeviceDecision" to Gdd::class.java,
        "com.bilibili.lib.dd.a" to ImplA::class.java,
        "com.bilibili.lib.dd.b" to ImplB::class.java,
        "com.bilibili.lib.dd.c" to NotAnImpl::class.java,
        "kntr.base.dd.IDeviceDecisionKt" to FakeKotlinDd::class.java,
        "com.bilibili.lib.blconfig.ConfigManager\$Companion" to FakeConfigCompanion::class.java,
        "com.bilibili.lib.blconfig.ConfigManager" to FakeConfigManager::class.java,
        "com.bilibili.lib.blconfig.internal.OverrideFF" to FakeOverrideFf::class.java,
        "com.bilibili.lib.blconfig.internal.OverrideConfig" to FakeOverrideConfig::class.java,
        "com.bilibili.lib.blconfig.internal.TypedContract" to FakeTypedContract::class.java,
        "com.bilibili.lib.blconfig.FeatureFlagContract" to FakeFeatureFlag::class.java,
        "com.bilibili.lib.blconfig.internal.p" to FakeLegacyFf::class.java,
        "com.bilibili.lib.blconfig.internal.q" to FakeLegacyConfig::class.java
    )

    private fun loader(present: Map<String, Class<*>> = classes) =
        object : ClassLoader(CloudKeyFreezeFeatureInstallerCases::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                present[name] ?: if (name.startsWith("com.bilibili.") || name.startsWith("kntr.")) {
                    throw ClassNotFoundException(name)
                } else {
                    super.loadClass(name, resolve)
                }
        }

    private class Recorder {
        val statuses = mutableListOf<String>()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val infoLogs = mutableMapOf<String, String>()
        val errorLogs = mutableMapOf<String, String>()
    }

    private fun environment(
        recorder: Recorder = Recorder(),
        registrar: HookRegistrar = TestHookRegistrar,
        process: String = "tv.danmaku.bili",
        loader: ClassLoader? = loader(),
        scan: ((String, ScanSnapshotContent) -> Boolean)? = null
    ) = HookEnvironment(
        processName = process,
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { key, message -> recorder.infoLogs[key] = message },
        logError = { key, message -> recorder.errorLogs[key] = message },
        reportStatus = { _, status -> recorder.statuses += status },
        writeScanSnapshot = scan,
        runtimeEvidence = { id, stage, _ ->
            assertEquals(CloudKeyFreezeFeatureInstaller.ID, id)
            recorder.stages += stage
        }
    )

    private fun installer(
        rules: String = "ff_a=false",
        observe: Boolean = false,
        enabled: Boolean = true,
        picked: String = "",
        scanFlushMs: Long = 10L
    ) = CloudKeyFreezeFeatureInstaller(enabled, rules, observe, picked, scanFlushMs)

    private fun PlayerPortTestRegistrar.idOf(owner: Class<*>, name: String, parameterCount: Int): String =
        hooks.entries.single {
            val member = it.value.member as Method
            member.declaringClass == owner && member.name == name && member.parameterCount == parameterCount
        }.key

    private fun PlayerPortTestRegistrar.hasHook(owner: Class<*>, name: String, parameterCount: Int): Boolean =
        hooks.values.any {
            val member = it.member as Method
            member.declaringClass == owner && member.name == name && member.parameterCount == parameterCount
        }

    // region installation

    fun `every read family is hooked and wrapper lookalikes are left alone`() {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        val result = installer().install(environment(recorder, registrar))
        val installed = result as FeatureInstallResult.Installed
        assertTrue(installed.complete)
        assertEquals(registrar.hooks.size, installed.hookCount)

        // 门面：两个布尔重载 + 两个字符串重载；getDdVersion 不是读取入口
        assertEquals(2, registrar.hooks.values.count { (it.member as Method).declaringClass == FakeDeviceDecision::class.java && it.member.name == "getBoolean" })
        assertEquals(2, registrar.hooks.values.count { (it.member as Method).declaringClass == FakeDeviceDecision::class.java && it.member.name == "dd" })
        assertFalse(registrar.hasHook(FakeDeviceDecision::class.java, "getDdVersion", 0))
        // 实现类：只挂 3 参重载（门面与新初始化都汇到这里），结构不符的同包类不碰
        assertTrue(registrar.hasHook(ImplA::class.java, "getBoolean", 3))
        assertFalse(registrar.hasHook(ImplA::class.java, "getBoolean", 2))
        assertTrue(registrar.hasHook(ImplA::class.java, "dd", 3))
        assertTrue(registrar.hasHook(ImplB::class.java, "getBoolean", 3))
        assertFalse(registrar.hooks.values.any { (it.member as Method).declaringClass == NotAnImpl::class.java })
        // 跨平台入口：只挂 getBool / getString，不挂 $default 桩
        assertTrue(registrar.hasHook(FakeKotlinDd::class.java, "getBool", 4))
        assertTrue(registrar.hasHook(FakeKotlinDd::class.java, "getString", 4))
        assertFalse(registrar.hasHook(FakeKotlinDd::class.java, "getBool\$default", 6))
        // 旧 blconfig：封装、三个具名合约类以及被识别出的 FF 子类
        assertTrue(registrar.hasHook(FakeConfigCompanion::class.java, "isHitFF", 1))
        assertTrue(registrar.hasHook(FakeConfigManager::class.java, "getConfig", 2))
        assertFalse(registrar.hasHook(FakeConfigCompanion::class.java, "ab", 0))
        assertTrue(registrar.hasHook(FakeOverrideFf::class.java, "getWithDefault", 2))
        assertTrue(registrar.hasHook(FakeOverrideFf::class.java, "get", 2))
        assertTrue(registrar.hasHook(FakeOverrideConfig::class.java, "get", 2))
        assertTrue(registrar.hasHook(FakeTypedContract::class.java, "get", 2))
        assertTrue(registrar.hasHook(FakeLegacyFf::class.java, "getWithDefault", 2))
        assertTrue(registrar.hasHook(FakeLegacyFf::class.java, "get", 2))
        assertFalse(registrar.hooks.values.any { (it.member as Method).declaringClass == FakeLegacyConfig::class.java })

        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), recorder.stages)
        assertTrue(recorder.statuses.single().startsWith("installed:rules=1,families=dd,dd-impl,kotlin-dd,config,contract,contract-ff"))
    }

    fun `the compiler generated bridge that interface calls land on is hooked and works`() {
        val registrar = PlayerPortTestRegistrar()
        val bridged = classes + ("com.bilibili.lib.blconfig.internal.OverrideFF" to BridgedOverrideFf::class.java)
        installer("ff_pin=false").install(environment(registrar = registrar, loader = loader(bridged)))
        val bridge = registrar.hooks.entries.single {
            val member = it.value.member as Method
            member.declaringClass == BridgedOverrideFf::class.java && member.name == "get" &&
                member.returnType == Any::class.java
        }
        assertTrue((bridge.value.member as Method).isSynthetic)
        assertEquals(false, registrar.invoke(bridge.key, args = arrayOf("ff_pin", true)) { true })
        assertEquals(true, registrar.invoke(bridge.key, args = arrayOf("ff_other", true)) { true })
    }

    fun `disabled other process and empty rules register nothing`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Skipped("disabled"), installer(enabled = false).install(environment(registrar = registrar)))
        assertEquals(
            FeatureInstallResult.Skipped("non-main-process"),
            installer().install(environment(registrar = registrar, process = "tv.danmaku.bili:web"))
        )
        val recorder = Recorder()
        assertEquals(FeatureInstallResult.Skipped("no-rules"), installer("").install(environment(recorder, registrar)))
        assertEquals(listOf("no-rules"), recorder.statuses)
        assertTrue(registrar.hooks.isEmpty())
    }

    fun `rules that are all refused leave nothing to install and say why`() {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        val result = installer("grpc.x=true\ninfra.y=false\nnot a rule").install(environment(recorder, registrar))
        assertEquals(FeatureInstallResult.Skipped("no-rules"), result)
        assertTrue(registrar.hooks.isEmpty())
        val message = recorder.errorLogs.getValue("cloud_key_freeze_rejected")
        assertTrue(message, "1 条规则格式不对" in message && "2 条属于受保护类别" in message)
    }

    fun `observation alone installs the hooks even without rules`() {
        val registrar = PlayerPortTestRegistrar()
        val result = installer(rules = "", observe = true).install(environment(registrar = registrar))
        assertTrue(result is FeatureInstallResult.Installed)
        assertTrue(registrar.hooks.isNotEmpty())
    }

    fun `a host without any known boundary fails closed`() {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        assertEquals(
            FeatureInstallResult.Skipped("missing-config-boundary"),
            installer().install(environment(recorder, registrar, loader = loader(emptyMap())))
        )
        assertEquals(FeatureInstallResult.Skipped("missing-class-loader"), installer().install(environment(loader = null)))
        assertEquals(listOf("missing-config-boundary"), recorder.statuses)
        assertTrue(registrar.hooks.isEmpty())
    }

    fun `a host with only the facade still installs and reports its families`() {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        val only = mapOf<String, Class<*>>("com.bilibili.lib.dd.DeviceDecision" to FakeDeviceDecision::class.java)
        val result = installer().install(environment(recorder, registrar, loader = loader(only)))
        assertEquals(FeatureInstallResult.Installed(4), result)
        assertEquals("installed:rules=1,families=dd", recorder.statuses.single())
    }

    fun `one failed registration is partial and all failed is skipped`() {
        val partial = PlayerPortTestRegistrar(failId = "cloud_key_freeze.dd.0")
        val recorder = Recorder()
        val result = installer().install(environment(recorder, partial)) as FeatureInstallResult.Installed
        assertFalse(result.complete)
        assertEquals(partial.hooks.size, result.hookCount)
        assertTrue(recorder.statuses.single().startsWith("partial:${partial.hooks.size}/${partial.hooks.size + 1}"))

        val failing = object : HookRegistrar by TestHookRegistrar {
            override fun exact(
                id: String,
                owner: Class<*>,
                methodName: String,
                vararg parameterTypes: Class<*>,
                block: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.() -> Unit
            ) = error("simulated registration failure")
        }
        assertEquals(FeatureInstallResult.Skipped("registration-failed"), installer().install(environment(registrar = failing)))
    }

    // endregion

    // region behaviour on the read path

    private fun installed(rules: String, observe: Boolean = false): Pair<PlayerPortTestRegistrar, Recorder> {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        assertTrue(installer(rules, observe).install(environment(recorder, registrar)) is FeatureInstallResult.Installed)
        return registrar to recorder
    }

    fun `a boolean rule pins device decision reads and leaves other keys alone`() {
        val (registrar, recorder) = installed("ff_pin=false\nff_on=true")
        val id = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        assertEquals(false, registrar.invoke(id, args = arrayOf("ff_pin", true)) { true })
        assertEquals(true, registrar.invoke(id, args = arrayOf("ff_on", false)) { false })
        assertEquals(true, registrar.invoke(id, args = arrayOf("ff_other", false)) { true })
        assertEquals(false, registrar.invoke(id, args = arrayOf("ff_other", true)) { false })
        assertEquals(true, registrar.invoke(id, args = arrayOf(null, true)) { true })
        assertEquals(
            listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED),
            recorder.stages
        )
        assertEquals("applied", recorder.statuses.last())
    }

    fun `a value the cloud already left at the pinned value is observed but not applied`() {
        val (registrar, recorder) = installed("ff_pin=false")
        val id = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 3)
        assertEquals(false, registrar.invoke(id, args = arrayOf("ff_pin", true, null)) { false })
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED), recorder.stages)
    }

    fun `a string rule pins dd reads and a boolean rule on the same key does not touch them`() {
        val (registrar, _) = installed("live.cfg=s:fixed\nlive.flag=false")
        val id = registrar.idOf(FakeDeviceDecision::class.java, "dd", 2)
        assertEquals("fixed", registrar.invoke(id, args = arrayOf("live.cfg", "d")) { "cloud" })
        assertEquals("fixed", registrar.invoke(id, args = arrayOf("live.cfg", null)) { null })
        assertEquals("cloud", registrar.invoke(id, args = arrayOf("live.flag", "d")) { "cloud" })
    }

    fun `use default returns what the host call site passed for every read kind`() {
        val (registrar, _) = installed("live.k=default")
        val bool = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        assertEquals(true, registrar.invoke(bool, args = arrayOf("live.k", true)) { false })
        val text = registrar.idOf(FakeDeviceDecision::class.java, "dd", 2)
        assertEquals("d", registrar.invoke(text, args = arrayOf("live.k", "d")) { "cloud" })
        assertNull(registrar.invoke(text, args = arrayOf("live.k", null)) { "cloud" })
        val generic = registrar.idOf(FakeOverrideConfig::class.java, "get", 2)
        assertEquals("d", registrar.invoke(generic, args = arrayOf("live.k", "d")) { "cloud" })
        assertEquals("cloud", registrar.invoke(generic, args = arrayOf("live.k", null)) { "cloud" })
    }

    fun `static kotlin entry points read the key from the second argument`() {
        val (registrar, _) = installed("ff_pin=false\nlive.cfg=s:fixed")
        val bool = registrar.idOf(FakeKotlinDd::class.java, "getBool", 4)
        assertEquals(false, registrar.invoke(bool, args = arrayOf(Any(), "ff_pin", true, null)) { true })
        assertEquals(true, registrar.invoke(bool, args = arrayOf("ff_pin", "x", true, null)) { true })
        val text = registrar.idOf(FakeKotlinDd::class.java, "getString", 4)
        assertEquals("fixed", registrar.invoke(text, args = arrayOf(Any(), "live.cfg", "d", null)) { "cloud" })
    }

    fun `is hit ff without a default argument treats no cloud value as hit`() {
        val (registrar, _) = installed("ff_pin=default\nff_off=false")
        val id = registrar.idOf(FakeConfigCompanion::class.java, "isHitFF", 1)
        assertEquals(true, registrar.invoke(id, args = arrayOf("ff_pin")) { false })
        assertEquals(false, registrar.invoke(id, args = arrayOf("ff_off")) { true })
        assertEquals(true, registrar.invoke(id, args = arrayOf("ff_unlisted")) { true })
    }

    fun `generic contracts rewrite by the runtime type of the read`() {
        val (registrar, _) = installed("ff_pin=false\nlive.cfg=s:fixed")
        val ff = registrar.idOf(FakeLegacyFf::class.java, "get", 2)
        assertEquals(false, registrar.invoke(ff, args = arrayOf("ff_pin", true)) { true })
        assertEquals(true, registrar.invoke(ff, args = arrayOf("live.cfg", true)) { true })
        val withDefault = registrar.idOf(FakeLegacyFf::class.java, "getWithDefault", 2)
        assertEquals(false, registrar.invoke(withDefault, args = arrayOf("ff_pin", true)) { true })
        val config = registrar.idOf(FakeOverrideConfig::class.java, "get", 2)
        assertEquals("fixed", registrar.invoke(config, args = arrayOf("live.cfg", "d")) { "cloud" })
        assertEquals("cloud", registrar.invoke(config, args = arrayOf("ff_pin", "d")) { "cloud" })
    }

    fun `a read that throws keeps its exception and is not rewritten`() {
        val (registrar, _) = installed("ff_pin=false")
        val id = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        assertThrows(IllegalStateException::class.java) {
            registrar.invoke(id, args = arrayOf("ff_pin", true)) { error("boom") }
        }
    }

    fun `non string keys are ignored without error`() {
        val (registrar, recorder) = installed("ff_pin=false")
        val id = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        assertEquals(true, registrar.invoke(id, args = arrayOf(42, true)) { true })
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), recorder.stages)
    }

    // endregion

    // region observation

    fun `observation logs each key once with a boolean value but never a string value`() {
        val (registrar, recorder) = installed("", observe = true)
        val bool = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        val text = registrar.idOf(FakeDeviceDecision::class.java, "dd", 2)
        registrar.invoke(bool, args = arrayOf("ff_seen", false)) { true }
        registrar.invoke(bool, args = arrayOf("ff_seen", false)) { false }
        registrar.invoke(text, args = arrayOf("live.secret_key", "d")) { "SECRET-VALUE" }
        val logs = recorder.infoLogs
        assertEquals("[BIL] 云控读取 key=ff_seen kind=BOOL value=true", logs["cloud_key_freeze_seen_ff_seen"])
        assertEquals("[BIL] 云控读取 key=live.secret_key kind=TEXT", logs["cloud_key_freeze_seen_live.secret_key"])
        assertTrue(logs.values.none { "SECRET-VALUE" in it })
    }

    fun `observation alone counts as observed runtime evidence but never as applied`() {
        val (registrar, recorder) = installed("", observe = true)
        val bool = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        registrar.invoke(bool, args = arrayOf("ff_seen", false)) { true }
        registrar.invoke(bool, args = arrayOf("ff_seen_too", false)) { true }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED), recorder.stages)
    }

    fun `observation stops registering new keys at the cap`() {
        val (registrar, recorder) = installed("", observe = true)
        val bool = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        repeat(450) { registrar.invoke(bool, args = arrayOf("ff_$it", false)) { true } }
        assertEquals(400, recorder.infoLogs.keys.count { it.startsWith("cloud_key_freeze_seen_") })
    }

    fun `observation is silent when it is not requested`() {
        val (registrar, recorder) = installed("ff_pin=false")
        val bool = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        registrar.invoke(bool, args = arrayOf("ff_seen", false)) { true }
        assertTrue(recorder.infoLogs.keys.none { it.startsWith("cloud_key_freeze_seen_") })
    }

    // endregion

    // region picked pins and the scan

    fun `pins from the scan panel freeze like rules and a manual rule on the same key wins`() {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        val result = installer(rules = "ff_both=true", picked = "ff_picked=false\nff_both=false")
            .install(environment(recorder, registrar))
        assertTrue(result is FeatureInstallResult.Installed)
        assertTrue(recorder.statuses.single().startsWith("installed:rules=2,"))
        val id = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        assertEquals(false, registrar.invoke(id, args = arrayOf("ff_picked", true)) { true })
        assertEquals(true, registrar.invoke(id, args = arrayOf("ff_both", false)) { false })
        assertEquals(true, registrar.invoke(id, args = arrayOf("ff_other", true)) { true })
    }

    fun `pins alone are enough to install and protected or malformed pins are refused`() {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        val result = installer(rules = "", picked = "ff_ok=true\ngrpc.x=true\nnot a pin")
            .install(environment(recorder, registrar))
        assertTrue(result is FeatureInstallResult.Installed)
        val message = recorder.errorLogs.getValue("cloud_key_freeze_rejected")
        assertTrue(message, "1 条规则格式不对" in message && "1 条属于受保护类别" in message)
        assertEquals(
            FeatureInstallResult.Skipped("no-rules"),
            installer(rules = "", picked = "grpc.x=true").install(environment(registrar = PlayerPortTestRegistrar()))
        )
    }

    private fun awaitScan(
        published: java.util.concurrent.BlockingQueue<Pair<String, ScanSnapshotContent>>,
        until: (Map<String, MineComponentScanEntry>) -> Boolean
    ): Pair<String, ScanSnapshotContent> {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            val next = published.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
            if (until(next.second.entries.associateBy { it.id!! })) return next
        }
        throw AssertionError("scan snapshot with the expected content was not published")
    }

    fun `the scan publishes unfrozen boolean keys and marks flapping ones while skipping the rest`() {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        val published = java.util.concurrent.LinkedBlockingQueue<Pair<String, ScanSnapshotContent>>()
        val result = installer(rules = "", observe = true, picked = "ff_frozen=true").install(
            environment(recorder, registrar, scan = { surface, content -> published.add(surface to content); true })
        )
        assertTrue(result is FeatureInstallResult.Installed)
        assertTrue("collecting" in recorder.statuses)
        val bool = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        val text = registrar.idOf(FakeDeviceDecision::class.java, "dd", 2)
        registrar.invoke(bool, args = arrayOf("ff_free", true)) { false }
        // 已固定的键：此刻看到的可能已是被内层钩子换过的值，不能当作云控原值记录。
        registrar.invoke(bool, args = arrayOf("ff_frozen", false)) { false }
        registrar.invoke(bool, args = arrayOf("ff_flip", true)) { true }
        registrar.invoke(bool, args = arrayOf("ff_flip", true)) { false }
        registrar.invoke(bool, args = arrayOf("grpc.timeout", true)) { true }
        registrar.invoke(text, args = arrayOf("live.secret_key", "d")) { "SECRET-VALUE" }

        val (surface, content) = awaitScan(published) { it["ff_flip"]?.value == CloudKeyScan.VALUE_MIXED && "ff_free" in it }
        assertEquals(MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS, surface)
        assertEquals("tv.danmaku.bili", content.processName)
        val entries = content.entries.associateBy { it.id!! }
        assertEquals(setOf("ff_free", "ff_flip"), entries.keys)
        assertEquals(CloudKeyScan.VALUE_FALSE, entries.getValue("ff_free").value)
        assertFalse(entries.getValue("ff_flip").selectable)
        assertTrue(content.entries.none { "SECRET-VALUE" in it.toJson().toString() })
    }

    fun `the scan is not started and nothing is published when observation is off`() {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        val published = java.util.concurrent.LinkedBlockingQueue<Pair<String, ScanSnapshotContent>>()
        installer(rules = "ff_pin=false").install(
            environment(recorder, registrar, scan = { surface, content -> published.add(surface to content); true })
        )
        val bool = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        registrar.invoke(bool, args = arrayOf("ff_free", true)) { false }
        assertNull(published.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertFalse("collecting" in recorder.statuses)
    }

    // endregion

    fun verifyAllCases() {
        fun runIsolated(block: (CloudKeyFreezeFeatureInstallerCases) -> Unit) {
            val s = CloudKeyFreezeFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`every read family is hooked and wrapper lookalikes are left alone`() }
        runIsolated { s -> s.`the compiler generated bridge that interface calls land on is hooked and works`() }
        runIsolated { s -> s.`disabled other process and empty rules register nothing`() }
        runIsolated { s -> s.`rules that are all refused leave nothing to install and say why`() }
        runIsolated { s -> s.`observation alone installs the hooks even without rules`() }
        runIsolated { s -> s.`a host without any known boundary fails closed`() }
        runIsolated { s -> s.`a host with only the facade still installs and reports its families`() }
        runIsolated { s -> s.`one failed registration is partial and all failed is skipped`() }
        runIsolated { s -> s.`a boolean rule pins device decision reads and leaves other keys alone`() }
        runIsolated { s -> s.`a value the cloud already left at the pinned value is observed but not applied`() }
        runIsolated { s -> s.`a string rule pins dd reads and a boolean rule on the same key does not touch them`() }
        runIsolated { s -> s.`use default returns what the host call site passed for every read kind`() }
        runIsolated { s -> s.`static kotlin entry points read the key from the second argument`() }
        runIsolated { s -> s.`is hit ff without a default argument treats no cloud value as hit`() }
        runIsolated { s -> s.`generic contracts rewrite by the runtime type of the read`() }
        runIsolated { s -> s.`a read that throws keeps its exception and is not rewritten`() }
        runIsolated { s -> s.`non string keys are ignored without error`() }
        runIsolated { s -> s.`observation logs each key once with a boolean value but never a string value`() }
        runIsolated { s -> s.`observation alone counts as observed runtime evidence but never as applied`() }
        runIsolated { s -> s.`observation stops registering new keys at the cap`() }
        runIsolated { s -> s.`observation is silent when it is not requested`() }
        runIsolated { s -> s.`pins from the scan panel freeze like rules and a manual rule on the same key wins`() }
        runIsolated { s -> s.`pins alone are enough to install and protected or malformed pins are refused`() }
        runIsolated { s -> s.`the scan publishes unfrozen boolean keys and marks flapping ones while skipping the rest`() }
        runIsolated { s -> s.`the scan is not started and nothing is published when observation is off`() }
    }
}

private class CloudKeyFreezeRulesCases {
    private fun parse(raw: String?) = CloudKeyFreezeRules.parse(raw)

    fun `blank input has no rules and nothing rejected`() {
        for (raw in listOf(null, "", "   ", "\n\n", "# only a comment")) {
            assertEquals(CloudKeyRuleSet.EMPTY, parse(raw))
        }
    }

    fun `each value form parses to its rule`() {
        val set = parse(
            """
            ff_a=true
            ff_b = FALSE
            ff_c=default
            live.text=s:hello world
            live.empty=s:
            """.trimIndent()
        )
        assertEquals(
            mapOf(
                "ff_a" to CloudKeyRule.Bool(true),
                "ff_b" to CloudKeyRule.Bool(false),
                "ff_c" to CloudKeyRule.UseDefault,
                "live.text" to CloudKeyRule.Text("hello world"),
                "live.empty" to CloudKeyRule.Text("")
            ),
            set.rules
        )
        assertEquals(0, set.rejected)
        assertEquals(0, set.protectedRejected)
    }

    fun `semicolons and full width semicolons separate entries and comments are skipped`() {
        val set = parse("ff_a=true; ff_b=false；ff_c=default\n# ff_d=true\n\n  ff_e=true  ")
        assertEquals(listOf("ff_a", "ff_b", "ff_c", "ff_e"), set.rules.keys.toList())
    }

    fun `only the first equals sign splits so text values may contain one`() {
        assertEquals(CloudKeyRule.Text("a=b"), parse("k.v=s:a=b").rules.getValue("k.v"))
    }

    fun `malformed entries are counted and dropped`() {
        val set = parse(
            listOf(
                "no_equals",
                "=true",
                "ff a=true",
                "ff_ok=maybe",
                "ff_ok2=1",
                "a".repeat(CloudKeyFreezeRules.MAX_KEY_LENGTH + 1) + "=true",
                "ff_text=s:" + "x".repeat(CloudKeyFreezeRules.MAX_TEXT_LENGTH + 1),
                "kéy=true",
                "ff_good=true"
            ).joinToString("\n")
        )
        assertEquals(listOf("ff_good"), set.rules.keys.toList())
        assertEquals(8, set.rejected)
    }

    fun `keys are exact and case sensitive and a text value of the longest allowed size is accepted`() {
        val set = parse("Live.A=true\nlive.a=false\nk=s:" + "x".repeat(CloudKeyFreezeRules.MAX_TEXT_LENGTH))
        assertEquals(CloudKeyRule.Bool(true), set.rules["Live.A"])
        assertEquals(CloudKeyRule.Bool(false), set.rules["live.a"])
        assertEquals(CloudKeyFreezeRules.MAX_TEXT_LENGTH, (set.rules.getValue("k") as CloudKeyRule.Text).value.length)
    }

    fun `a repeated key keeps the last value`() {
        val set = parse("ff_a=true\nff_b=true\nff_a=false")
        assertEquals(CloudKeyRule.Bool(false), set.rules["ff_a"])
        assertEquals(listOf("ff_b", "ff_a"), set.rules.keys.toList())
    }

    fun `protected categories are refused whatever the case`() {
        val set = parse(
            "infra.blconfig.new.init=true\nGRPC.x=false\nnet.timeout=default\nlogin.mode=true\n" +
                "risk.check=false\nflowcontrol.q=true\nblrouter.pureurl=s:x\nsecurity.a=true\nff_login_page=true"
        )
        assertEquals(listOf("ff_login_page"), set.rules.keys.toList())
        assertEquals(8, set.protectedRejected)
        assertEquals(0, set.rejected)
    }

    fun `a prefix is protected only at a segment boundary`() {
        assertFalse(CloudKeyFreezeRules.isProtected("network_state_card"))
        assertFalse(CloudKeyFreezeRules.isProtected("login_banner"))
        assertFalse(CloudKeyFreezeRules.isProtected("dd.use_new_home_frame"))
        assertTrue(CloudKeyFreezeRules.isProtected("net.anything"))
        assertTrue(CloudKeyFreezeRules.isProtected("Infra.AnyThing"))
    }

    fun `the rule count is capped and later entries are counted as rejected`() {
        val raw = (1..CloudKeyFreezeRules.MAX_RULES + 5).joinToString("\n") { "ff_$it=true" }
        val set = parse(raw)
        assertEquals(CloudKeyFreezeRules.MAX_RULES, set.rules.size)
        assertEquals(5, set.rejected)
        // 超限之后再次出现的已有键仍可更新，不算新增条目。
        val updated = parse(raw + "\nff_1=false")
        assertEquals(CloudKeyRule.Bool(false), updated.rules["ff_1"])
        assertEquals(5, updated.rejected)
    }

    // region resolver

    private val unchanged = CloudKeyRuleResolver.UNCHANGED

    fun `a boolean rule only rewrites boolean reads`() {
        val rule = CloudKeyRule.Bool(false)
        assertEquals(false, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.BOOL, true, true, true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, "x", "d", true))
        assertEquals(false, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, true, null, true))
        assertEquals(false, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, null, true, true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, "x", "d", true))
    }

    fun `a text rule only rewrites string reads`() {
        val rule = CloudKeyRule.Text("fixed")
        assertEquals("fixed", CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, "x", "d", true))
        assertEquals("fixed", CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, null, null, true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.BOOL, true, true, true))
        assertEquals("fixed", CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, "x", null, true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, true, true, true))
    }

    fun `use default hands back what the host call site passed`() {
        val rule = CloudKeyRule.UseDefault
        assertEquals(true, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.BOOL, false, true, true))
        assertEquals("d", CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, "cloud", "d", true))
        assertNull(CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, "cloud", null, true))
        assertEquals(true, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, false, true, true))
    }

    fun `use default never invents a value it cannot return safely`() {
        val rule = CloudKeyRule.UseDefault
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.BOOL, false, null, false))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.BOOL, false, "not a boolean", true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.OBJECT, true, null, true))
        assertSame(unchanged, CloudKeyRuleResolver.resolve(rule, CloudKeyReadKind.TEXT, "x", 5, true))
    }

    // endregion

    // region pins written by the scan panel

    fun `only keys that can be written as a rule are freezable`() {
        assertTrue(CloudKeyFreezeRules.isFreezable("ff_home.tab-1"))
        for (key in listOf("", "has space", "a=b", "中文", "k".repeat(97), "grpc.timeout", "Login.x", "RISK.y")) {
            assertFalse(key, CloudKeyFreezeRules.isFreezable(key))
        }
        assertTrue(CloudKeyFreezeRules.isFreezable("k".repeat(96)))
        assertTrue("a lookalike of a protected prefix is not protected", CloudKeyFreezeRules.isFreezable("login_banner"))
    }

    fun `pins are written sorted one per line and read back unchanged`() {
        val text = CloudKeyFreezeRules.formatPins(mapOf("ff_b" to false, "dd.a" to true, "grpc.x" to true, "bad key" to true))
        assertEquals("dd.a=true\nff_b=false", text)
        assertEquals(mapOf("dd.a" to true, "ff_b" to false), CloudKeyFreezeRules.parsePins(text))
        assertEquals("", CloudKeyFreezeRules.formatPins(emptyMap()))
        assertTrue(CloudKeyFreezeRules.parsePins(null).isEmpty())
    }

    fun `reading pins ignores non boolean rules a person may have typed into the saved text`() {
        val pins = CloudKeyFreezeRules.parsePins("ff_a=true\nff_b=default\nlive.cfg=s:x\nff_c=FALSE\n# note")
        assertEquals(mapOf("ff_a" to true, "ff_c" to false), pins)
    }

    fun `pins have their own limit and the manual limit is unchanged`() {
        val many = (1..CloudKeyFreezeRules.MAX_PICKED + 10).joinToString("\n") { "ff_$it=true" }
        assertEquals(CloudKeyFreezeRules.MAX_PICKED, CloudKeyFreezeRules.parsePins(many).size)
        assertEquals(CloudKeyFreezeRules.MAX_PICKED, CloudKeyFreezeRules.parse(many, CloudKeyFreezeRules.MAX_PICKED).rules.size)
        assertEquals(CloudKeyFreezeRules.MAX_RULES, parse(many).rules.size)
        assertEquals(CloudKeyFreezeRules.MAX_PICKED,
            CloudKeyFreezeRules.formatPins((1..400).associate { "ff_$it" to true }).lines().size)
    }

    fun `overlaying manual rules on pins lets the manual rule win and sums the rejects`() {
        val pins = CloudKeyFreezeRules.parse("ff_a=true\nff_b=true\ngrpc.x=true", CloudKeyFreezeRules.MAX_PICKED)
        val manual = parse("ff_b=false\nff_c=default\nnot a rule")
        val merged = pins.overlaidWith(manual)
        assertEquals(CloudKeyRule.Bool(true), merged.rules["ff_a"])
        assertEquals(CloudKeyRule.Bool(false), merged.rules["ff_b"])
        assertEquals(CloudKeyRule.UseDefault, merged.rules["ff_c"])
        assertEquals(1, merged.rejected)
        assertEquals(1, merged.protectedRejected)
        assertEquals(CloudKeyRuleSet.EMPTY, CloudKeyRuleSet.EMPTY.overlaidWith(CloudKeyRuleSet.EMPTY))
    }

    // endregion

    fun verifyAllCases() {
        fun runIsolated(block: (CloudKeyFreezeRulesCases) -> Unit) {
            val s = CloudKeyFreezeRulesCases()
            block(s)
        }
        runIsolated { s -> s.`blank input has no rules and nothing rejected`() }
        runIsolated { s -> s.`each value form parses to its rule`() }
        runIsolated { s -> s.`semicolons and full width semicolons separate entries and comments are skipped`() }
        runIsolated { s -> s.`only the first equals sign splits so text values may contain one`() }
        runIsolated { s -> s.`malformed entries are counted and dropped`() }
        runIsolated { s -> s.`keys are exact and case sensitive and a text value of the longest allowed size is accepted`() }
        runIsolated { s -> s.`a repeated key keeps the last value`() }
        runIsolated { s -> s.`protected categories are refused whatever the case`() }
        runIsolated { s -> s.`a prefix is protected only at a segment boundary`() }
        runIsolated { s -> s.`the rule count is capped and later entries are counted as rejected`() }
        runIsolated { s -> s.`a boolean rule only rewrites boolean reads`() }
        runIsolated { s -> s.`a text rule only rewrites string reads`() }
        runIsolated { s -> s.`use default hands back what the host call site passed`() }
        runIsolated { s -> s.`use default never invents a value it cannot return safely`() }
        runIsolated { s -> s.`only keys that can be written as a rule are freezable`() }
        runIsolated { s -> s.`pins are written sorted one per line and read back unchanged`() }
        runIsolated { s -> s.`reading pins ignores non boolean rules a person may have typed into the saved text`() }
        runIsolated { s -> s.`pins have their own limit and the manual limit is unchanged`() }
        runIsolated { s -> s.`overlaying manual rules on pins lets the manual rule win and sums the rejects`() }
    }
}

private class CloudKeyScanCollectorCases {
    private class Harness(capacity: Int = 1024) {
        val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val published = mutableListOf<List<MineComponentScanEntry>>()
        val collector = CloudKeyScanCollector(
            capacity = capacity,
            schedule = { delay, task -> scheduled += delay to task },
            publish = { published += it }
        )
        fun runScheduled() {
            val tasks = scheduled.toList(); scheduled.clear()
            tasks.forEach { (_, task) -> task() }
        }
        fun last() = published.last().associate { it.id!! to it }
    }

    fun `new keys are published sorted with their boolean value in one debounced flush`() {
        val h = Harness()
        h.collector.record("ff_b", false)
        h.collector.record("dd.a", true)
        h.collector.record("ff_b", false)
        assertEquals("one flush is scheduled however many keys arrive", 1, h.scheduled.size)
        assertEquals(CloudKeyScanCollector.DEFAULT_FLUSH_DELAY_MS, h.scheduled.single().first)
        assertTrue(h.published.isEmpty())
        h.runScheduled()
        val entries = h.published.single()
        assertEquals(listOf("dd.a", "ff_b"), entries.map { it.id })
        assertEquals(listOf("true", "false"), entries.map { it.value })
        assertTrue(entries.all { it.kind == CloudKeyScan.KIND && it.key == "cloud_key:${it.id}" && it.selectable })
    }

    fun `repeating a known value schedules nothing`() {
        val h = Harness()
        h.collector.record("ff_a", true)
        h.runScheduled()
        repeat(1000) { h.collector.record("ff_a", true) }
        assertTrue(h.scheduled.isEmpty())
        assertEquals(1, h.published.size)
    }

    fun `a key read as both on and off becomes mixed once and never flaps again`() {
        val h = Harness()
        h.collector.record("ff_flap", true)
        h.runScheduled()
        h.collector.record("ff_flap", false)
        assertEquals(1, h.scheduled.size)
        h.runScheduled()
        assertEquals("mixed", h.last().getValue("ff_flap").value)
        assertFalse("a mixed key cannot be newly ticked", h.last().getValue("ff_flap").selectable)
        // 之后无论读到什么都不再触发发布：一个键最多两次落盘。
        repeat(50) { h.collector.record("ff_flap", it % 2 == 0) }
        assertTrue(h.scheduled.isEmpty())
        assertEquals(2, h.published.size)
    }

    fun `keys that cannot be written as a rule never enter the scan`() {
        val h = Harness()
        listOf("grpc.timeout", "Infra.base", "login.x", "has space", "", "a".repeat(97), "中文键").forEach {
            h.collector.record(it, true)
        }
        assertEquals(0, h.collector.size)
        assertTrue(h.scheduled.isEmpty())
        h.collector.record("ff_ok", true)
        assertEquals(1, h.collector.size)
    }

    fun `capacity bounds the scan and later keys are dropped silently`() {
        val h = Harness(capacity = 3)
        (1..10).forEach { h.collector.record("ff_$it", true) }
        assertEquals(3, h.collector.size)
        h.runScheduled()
        assertEquals(3, h.published.single().size)
        // 已登记的键仍能更新状态。
        h.collector.record("ff_1", false)
        h.runScheduled()
        assertEquals("mixed", h.last().getValue("ff_1").value)
    }

    fun `a later change after the flush started schedules the next flush`() {
        val h = Harness()
        h.collector.record("ff_a", true)
        h.runScheduled()
        h.collector.record("ff_b", true)
        assertEquals(1, h.scheduled.size)
        h.runScheduled()
        assertEquals(listOf("ff_a", "ff_b"), h.published.last().map { it.id })
    }

    fun `entries carry only a boolean marker never anything else`() {
        val h = Harness()
        h.collector.record("ff_a", true)
        h.collector.record("ff_b", false)
        h.runScheduled()
        assertTrue(h.published.single().all { it.value in setOf("true", "false", "mixed") })
        assertTrue(h.published.single().all { it.title == null && it.uri == null })
    }

    fun `entryOf refuses an over long key and builds the stable selector key`() {
        assertNull(CloudKeyScan.entryOf("k".repeat(200), CloudKeyScan.VALUE_TRUE))
        val entry = CloudKeyScan.entryOf("ff_x", CloudKeyScan.VALUE_FALSE)!!
        assertEquals("cloud_key:ff_x", entry.key)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CloudKeyScanCollectorCases) -> Unit) {
            val s = CloudKeyScanCollectorCases()
            block(s)
        }
        runIsolated { s -> s.`new keys are published sorted with their boolean value in one debounced flush`() }
        runIsolated { s -> s.`repeating a known value schedules nothing`() }
        runIsolated { s -> s.`a key read as both on and off becomes mixed once and never flaps again`() }
        runIsolated { s -> s.`keys that cannot be written as a rule never enter the scan`() }
        runIsolated { s -> s.`capacity bounds the scan and later keys are dropped silently`() }
        runIsolated { s -> s.`a later change after the flush started schedules the next flush`() }
        runIsolated { s -> s.`entries carry only a boolean marker never anything else`() }
        runIsolated { s -> s.`entryOf refuses an over long key and builds the stable selector key`() }
    }
}

private class CloudKeyScanSnapshotCases {
    private val surface = MineComponentSnapshotCodec.SURFACE_CLOUD_KEYS

    private fun entry(key: String, value: String = CloudKeyScan.VALUE_TRUE) = checkNotNull(CloudKeyScan.entryOf(key, value))

    private fun entries(count: Int, keyLength: Int = 12) =
        (1..count).map { entry(("ff_" + it.toString().padStart(keyLength - 3, '0'))) }

    fun `the cloud key surface is a registered accumulating surface with larger limits`() {
        assertTrue(surface in MineComponentSnapshotCodec.ALLOWED_SURFACES)
        assertTrue(surface in MineComponentSnapshotCodec.ACCUMULATING_SURFACES)
        assertTrue("cloud_key" in MineComponentSnapshotCodec.ALLOWED_KINDS)
        assertEquals(1024, MineComponentSnapshotCodec.maxEntries(surface))
        assertEquals(256 * 1024, MineComponentSnapshotCodec.maxPayloadBytes(surface))
        assertEquals(256, MineComponentSnapshotCodec.maxEntries(MineComponentSnapshotCodec.SURFACE_HOME_TABS))
        assertEquals(64 * 1024, MineComponentSnapshotCodec.maxPayloadBytes(MineComponentSnapshotCodec.SURFACE_MINE))
    }

    fun `a full scan round trips with the boolean markers intact`() {
        val sent = entries(1024) + emptyList()
        val payload = MineComponentSnapshotCodec.encode("tv.danmaku.bili", setOf(CloudKeyScan.CAPABILITY), sent, surface)
        val decoded = checkNotNull(MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false))
        assertEquals(surface, decoded.surface)
        assertEquals(1024, decoded.entries.size)
        assertEquals(sent, decoded.entries)
        assertTrue(payload.toByteArray().size <= MineComponentSnapshotCodec.maxPayloadBytes(surface))
    }

    fun `a thousand very long keys are shrunk to fit instead of the whole scan being refused`() {
        val longKeys = (1..1024).map { entry("ff_" + it.toString().padStart(6, '0') + "_" + "k".repeat(80)) }
        val payload = MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), longKeys, surface)
        assertTrue("size=${payload.toByteArray().size}", payload.toByteArray().size <= MineComponentSnapshotCodec.maxPayloadBytes(surface))
        val decoded = checkNotNull(MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false))
        assertTrue(decoded.entries.size in 400..1023)
        // 收缩从尾部砍，前面的键原样保留。
        assertEquals(longKeys.take(decoded.entries.size), decoded.entries)
    }

    fun `encoding truncates at the surface limit and decoding refuses more than it`() {
        val payload = MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), entries(1100), surface)
        assertEquals(1024, checkNotNull(MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)).entries.size)
        val forged = JSONObject(payload).apply {
            put("items", JSONArray().also { array -> entries(1025).forEach { array.put(it.toJson()) } })
        }.toString()
        assertNull(MineComponentSnapshotCodec.decodeOrNull(forged, allowLegacy = false))
    }

    fun `the larger limits belong to the cloud key surface only`() {
        val tabs = MineComponentSnapshotCodec.SURFACE_HOME_TABS
        val many = (1..300).map { checkNotNull(MineComponentScanEntry.create("home_tab", "t$it", "id$it", null, true)) }
        val payload = MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), many, tabs)
        assertEquals(256, checkNotNull(MineComponentSnapshotCodec.decodeOrNull(payload, allowLegacy = false)).entries.size)
        val forged = JSONObject(payload).apply {
            put("items", JSONArray().also { array -> many.forEach { array.put(it.toJson()) } })
        }.toString()
        assertNull("257+ entries on another surface stay refused", MineComponentSnapshotCodec.decodeOrNull(forged, allowLegacy = false))
        // 合法 JSON 但超过 64KiB 的非云控面载荷仍被拒；同样大小放在云控面则可以。
        val padded = JSONObject(payload).apply { put("padding", "x".repeat(70_000)) }.toString()
        assertNull(MineComponentSnapshotCodec.decodeOrNull(padded, allowLegacy = false))
        val cloud = JSONObject(MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), entries(5), surface))
            .apply { put("padding", "x".repeat(70_000)) }.toString()
        assertNotNull(MineComponentSnapshotCodec.decodeOrNull(cloud, allowLegacy = false))
        val oversizedCloud = JSONObject(MineComponentSnapshotCodec.encode("tv.danmaku.bili", emptySet(), entries(5), surface))
            .apply { put("padding", "x".repeat(300_000)) }.toString()
        assertNull(MineComponentSnapshotCodec.decodeOrNull(oversizedCloud, allowLegacy = false))
    }

    fun `accumulating unions old and new scans and the new value of a key wins`() {
        val previous = listOf(entry("ff_a", "true"), entry("ff_b", "false"))
        val current = listOf(entry("ff_b", "true"), entry("ff_c", "mixed"))
        val merged = MineComponentSnapshotCodec.accumulate(surface, previous, current)
        assertEquals(listOf("ff_a", "ff_b", "ff_c"), merged.map { it.id })
        assertEquals("true", merged.single { it.id == "ff_b" }.value)
        assertEquals("mixed", merged.single { it.id == "ff_c" }.value)
    }

    fun `the merged scan keeps the current keys and fills the rest from history up to its own cap`() {
        val previous = entries(1024)
        val current = listOf(entry("ff_zzzzzz_new"))
        val merged = MineComponentSnapshotCodec.accumulate(surface, previous, current)
        assertEquals(1024, merged.size)
        assertTrue(merged.any { it.id == "ff_zzzzzz_new" })
    }

    fun `the value marker round trips and a forged or over long one is refused`() {
        val entry = entry("ff_a", "mixed")
        assertEquals(entry, MineComponentScanEntry.fromJsonOrNull(entry.toJson()))
        assertEquals("mixed", MineComponentScanEntry.fromJsonOrNull(entry.toJson())!!.value)
        assertNull(MineComponentScanEntry.fromJsonOrNull(entry.toJson().put("value", "x".repeat(40))))
        assertNull(MineComponentScanEntry.create("cloud_key", null, "ff_a", null, true, value = "x".repeat(40)))
        // 键必须由键名推出，伪造的别名/键不被接受。
        assertNull(MineComponentScanEntry.fromJsonOrNull(entry.toJson().put("key", "cloud_key:other")))
        // 旧条目没有 value 字段，行为不变。
        val tab = checkNotNull(MineComponentScanEntry.create("home_tab", "热门", "hot", null, true))
        assertNull(tab.value)
        assertTrue(!tab.toJson().has("value"))
    }

    fun `a cloud key entry needs a key name and has no title based fallback`() {
        assertNull(MineComponentScanEntry.create("cloud_key", "only a title", null, null, true))
        assertNull(MineComponentScanEntry.create("cloud_key", null, "   ", null, true))
        assertEquals("cloud_key:ff_x", MineComponentScanEntry.create("cloud_key", null, "ff_x", null, true)!!.key)
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CloudKeyScanSnapshotCases) -> Unit) {
            val s = CloudKeyScanSnapshotCases()
            block(s)
        }
        runIsolated { s -> s.`the cloud key surface is a registered accumulating surface with larger limits`() }
        runIsolated { s -> s.`a full scan round trips with the boolean markers intact`() }
        runIsolated { s -> s.`a thousand very long keys are shrunk to fit instead of the whole scan being refused`() }
        runIsolated { s -> s.`encoding truncates at the surface limit and decoding refuses more than it`() }
        runIsolated { s -> s.`the larger limits belong to the cloud key surface only`() }
        runIsolated { s -> s.`accumulating unions old and new scans and the new value of a key wins`() }
        runIsolated { s -> s.`the merged scan keeps the current keys and fills the rest from history up to its own cap`() }
        runIsolated { s -> s.`the value marker round trips and a forged or over long one is refused`() }
        runIsolated { s -> s.`a cloud key entry needs a key name and has no title based fallback`() }
    }
}

private class CommentBindBudgetCases {
    private class FakeClock(var now: Long = 0L) : CommentBindProbe.Clock {
        override fun nanoTime() = now
    }

    private class View(val name: String)

    private var serial = 0L
    private fun entry(view: View, enqueuedAt: Long = 0L) = Entry(view, ++serial, enqueuedAt, view.name)
    private fun CommentBindQueue<String>.names() = snapshot().map { it.payload }
    private fun ms(value: Long) = value * 1_000_000L

    /** 与现有 drainCommentBinds / scheduleCommentBind 内联实现逐行对应的参照物。 */
    private class Reference {
        val list = ArrayList<Entry<String>>()
        fun enqueue(e: Entry<String>) {
            for (i in list.lastIndex downTo 0) if (list[i].key === e.key) list.removeAt(i)
            list.add(e)
            while (list.size > 96) list.removeAt(0)
        }
        fun take(): List<Entry<String>> {
            val take = minOf(list.size, 6)
            val b = ArrayList<Entry<String>>(take)
            val start = list.size - take
            for (i in start until list.size) b.add(list[i])
            list.subList(start, list.size).clear()
            return b
        }
    }

    fun withoutAnyBudgetTheQueueBehavesExactlyLikeTheInlineImplementation() {
        val random = Random(20261029)
        val views = List(40) { View("v$it") }
        val queue = CommentBindQueue<String>()
        val reference = Reference()
        repeat(5_000) {
            if (random.nextInt(10) < 7) {
                val e = entry(views[random.nextInt(views.size)])
                val expected = reference.list.count { it.key === e.key }
                val result = queue.enqueue(e)
                reference.enqueue(e)
                assertEquals(expected, result.replaced)
            } else {
                val got = queue.takeBatch().map { it.generation }
                assertEquals(reference.take().map { it.generation }, got)
            }
            assertEquals(reference.list.map { it.generation }, queue.snapshot().map { it.generation })
            assertTrue(queue.size <= 96)
        }
    }

    fun capacityEvictsTheOldestAndCountsIt() {
        val queue = CommentBindQueue<String>()
        val views = List(100) { View("v$it") }
        var overflow = 0
        views.forEach { overflow += queue.enqueue(entry(it)).overflowDropped }
        assertEquals(96, queue.size)
        assertEquals(4, overflow)
        assertEquals("v4", queue.names().first())
        assertEquals("v99", queue.names().last())
    }

    fun batchIsTheNewestSixInEnqueueOrder() {
        val queue = CommentBindQueue<String>()
        val views = List(10) { View("v$it") }
        views.forEach { queue.enqueue(entry(it)) }
        assertEquals(listOf("v4", "v5", "v6", "v7", "v8", "v9"), queue.takeBatch().map { it.payload })
        assertEquals(listOf("v0", "v1", "v2", "v3"), queue.names())
    }

    fun everyBatchHasAtLeastOneItemEvenWithAZeroBudget() {
        val clock = FakeClock()
        val batch = List(6) { entry(View("v$it")) }
        val processed = ArrayList<String>()
        val out = CommentBindBudget.run(batch, startNs = 0, CommentBindBudget.Policy(0L, Long.MAX_VALUE), clock) { processed += it.payload }
        assertEquals(listOf("v0"), processed)
        assertEquals(1, out.processed)
        assertEquals(listOf("v1", "v2", "v3", "v4", "v5"), out.unprocessed.map { it.payload })
        assertTrue(out.stoppedEarly)
    }

    fun stopsBetweenItemsOnceTheBudgetIsSpentAndReportsTheRest() {
        val clock = FakeClock()
        val batch = List(6) { entry(View("v$it")) }
        val processed = ArrayList<String>()
        // 每项 3ms，预算 8ms：第 1..3 项后累计 9ms，才在第 4 项前停止。
        val out = CommentBindBudget.run(batch, 0, CommentBindBudget.Policy(ms(8), Long.MAX_VALUE), clock) {
            processed += it.payload; clock.now += ms(3)
        }
        assertEquals(listOf("v0", "v1", "v2"), processed)
        assertEquals(listOf("v3", "v4", "v5"), out.unprocessed.map { it.payload })
    }

    fun aBatchThatFitsTheBudgetIsProcessedCompletely() {
        val clock = FakeClock()
        val batch = List(6) { entry(View("v$it")) }
        val out = CommentBindBudget.run(batch, 0, CommentBindBudget.Policy(ms(100), Long.MAX_VALUE), clock) { clock.now += ms(1) }
        assertEquals(6, out.processed)
        assertFalse(out.stoppedEarly)
    }

    fun anItemOverTheBudgetStillRunsToCompletionBecauseItemsAreNotInterruptible() {
        val clock = FakeClock()
        val batch = List(3) { entry(View("v$it")) }
        val out = CommentBindBudget.run(batch, 0, CommentBindBudget.Policy(ms(2), Long.MAX_VALUE), clock) { clock.now += ms(40) }
        assertEquals("one 40ms item overran a 2ms budget; only a resumable cursor could change that", 1, out.processed)
        assertEquals(ms(40), clock.now)
    }

    fun givenBackItemsKeepLifoPositionBelowTasksQueuedWhileTheBatchRan() {
        val clock = FakeClock()
        val queue = CommentBindQueue<String>()
        List(8) { View("v$it") }.forEach { queue.enqueue(entry(it)) }
        val batch = queue.takeBatch() // v2..v7
        val lateA = View("lateA"); val lateB = View("lateB")
        val out = CommentBindBudget.run(batch, 0, CommentBindBudget.Policy(ms(5), Long.MAX_VALUE), clock) {
            if (it.payload == "v2") { queue.enqueue(entry(lateA)); queue.enqueue(entry(lateB)) } // 处理期间又有新任务入队
            clock.now += ms(3)
        }
        val back = queue.giveBack(out.unprocessed)
        assertEquals(CommentBindQueue.GivenBack(restored = 4, supersededDropped = 0, overflowDropped = 0), back)
        // 归还项在新任务之下、旧队列内容之上；下一批仍是最新的那些。
        assertEquals(listOf("v0", "v1", "v4", "v5", "v6", "v7", "lateA", "lateB"), queue.names())
        assertEquals(listOf("v4", "v5", "v6", "v7", "lateA", "lateB"), queue.takeBatch().map { it.payload })
    }

    fun aNewerTaskForTheSameViewIsNeverOverwrittenByTheReturnedOne() {
        val queue = CommentBindQueue<String>()
        val a = View("a"); val b = View("b"); val c = View("c")
        listOf(a, b, c).forEach { queue.enqueue(entry(it)) }
        val batch = queue.takeBatch()
        val newerB = entry(b) // b 在批处理期间被重新绑定
        queue.enqueue(newerB)
        val back = queue.giveBack(batch.filter { it.key !== a }) // a 已经处理完，b、c 是未处理项
        assertEquals(CommentBindQueue.GivenBack(restored = 1, supersededDropped = 1, overflowDropped = 0), back)
        assertEquals(listOf("c", "b"), queue.names())
        assertSame("the surviving b entry is the newer generation", newerB, queue.snapshot().last())
    }

    fun givingBackRespectsTheCapAndEvictsTheOldest() {
        val queue = CommentBindQueue<String>()
        List(96) { View("v$it") }.forEach { queue.enqueue(entry(it)) }
        val batch = queue.takeBatch() // v90..v95
        List(96) { View("n$it") }.forEach { queue.enqueue(entry(it)) } // 队列重新满到 96，全部比批次新
        assertEquals(96, queue.size)
        val back = queue.giveBack(batch)
        assertEquals(96, queue.size)
        assertEquals("old returned items are the first evicted", 6, back.overflowDropped)
        assertEquals("n0", queue.names().first())
    }

    fun repeatedPartialDrainsAlwaysFinishAndLoseNothing() {
        // 不变量：被处理 + 被淘汰 + 被替换 = 入队总数；每轮至少前进一项，所以终会清空。
        val random = Random(7)
        val clock = FakeClock()
        val queue = CommentBindQueue<String>()
        val views = List(30) { View("v$it") }
        var enqueued = 0; var replaced = 0; var overflow = 0; var superseded = 0; var processed = 0
        repeat(20_000) {
            if (random.nextInt(3) > 0) {
                val r = queue.enqueue(entry(views[random.nextInt(views.size)], enqueuedAt = clock.now.coerceAtLeast(1)))
                enqueued++; replaced += r.replaced; overflow += r.overflowDropped
            } else {
                val batch = queue.takeBatch()
                val budget = ms(random.nextInt(8).toLong())
                val out = CommentBindBudget.run(batch, clock.now, CommentBindBudget.Policy(budget, ms(50)), clock) {
                    clock.now += ms(1 + random.nextInt(3).toLong())
                    if (random.nextInt(4) == 0) queue.enqueue(entry(it.key as View, clock.now)).also { r -> enqueued++; replaced += r.replaced; overflow += r.overflowDropped }
                }
                processed += out.processed
                val back = queue.giveBack(out.unprocessed)
                superseded += back.supersededDropped; overflow += back.overflowDropped
                assertTrue("progress", batch.isEmpty() || out.processed >= 1)
            }
            assertTrue(queue.size <= 96)
            assertEquals("unique key per queued view", queue.snapshot().map { it.key }.toSet().size, queue.size)
            assertEquals("ascending generation order", queue.snapshot().map { it.generation }.sorted(), queue.snapshot().map { it.generation })
        }
        // 清空：每轮至少前进一项，所以有限轮数内结束。
        var guard = 0
        while (queue.size > 0 && guard++ < 1_000) {
            val batch = queue.takeBatch()
            val out = CommentBindBudget.run(batch, clock.now, CommentBindBudget.Policy(0L, Long.MAX_VALUE), clock) { clock.now += ms(1) }
            processed += out.processed
            val back = queue.giveBack(out.unprocessed)
            superseded += back.supersededDropped; overflow += back.overflowDropped
        }
        assertEquals(0, queue.size)
        assertEquals(enqueued, processed + replaced + overflow + superseded)
    }

    fun anOldWaitingItemLiftsTheBudgetSoLatecomersCannotStarveIt() {
        val clock = FakeClock(now = ms(1_000))
        val old = entry(View("old"), enqueuedAt = ms(100)) // 已等 900ms
        val fresh = List(5) { entry(View("f$it"), enqueuedAt = ms(999)) }
        val policy = CommentBindBudget.Policy(budgetNs = ms(1), starvationLimitNs = ms(500))
        val processed = ArrayList<String>()
        val out = CommentBindBudget.run(listOf(old) + fresh, clock.now, policy, clock) { processed += it.payload; clock.now += ms(5) }
        assertTrue(out.starvationOverride)
        assertEquals(6, processed.size)
        assertFalse(out.stoppedEarly)
        // 没有久等的任务：预算照常生效。
        val clock2 = FakeClock(now = ms(1_000))
        val calm = CommentBindBudget.run(fresh, clock2.now, policy, clock2) { clock2.now += ms(5) }
        assertFalse(calm.starvationOverride)
        assertEquals(1, calm.processed)
    }

    fun untimedEntriesNeverTriggerTheStarvationOverride() {
        val clock = FakeClock(now = ms(10_000))
        val batch = List(3) { entry(View("v$it"), enqueuedAt = 0L) }
        val out = CommentBindBudget.run(batch, clock.now, CommentBindBudget.Policy(0L, ms(1)), clock) { }
        assertFalse(out.starvationOverride)
        assertEquals(1, out.processed)
    }

    fun starvationPriorityLooksBeyondTheNewestSixAndCompletesTheOldestItem() {
        val queue = CommentBindQueue<String>()
        queue.enqueue(entry(View("old"), enqueuedAt = ms(100)))
        List(10) { View("f$it") }.forEach { queue.enqueue(entry(it, enqueuedAt = ms(999))) }

        val batch = queue.takeBatch(nowNanos = ms(1_000), starvationLimitNanos = ms(500))
        assertEquals(listOf("old", "f5", "f6", "f7", "f8", "f9"), batch.map { it.payload })
        assertEquals(listOf("f0", "f1", "f2", "f3", "f4"), queue.names())

        val clock = FakeClock(now = ms(1_000))
        val processed = ArrayList<String>()
        val out = CommentBindBudget.run(batch, clock.now, CommentBindBudget.Policy(0L, ms(500)), clock) {
            processed += it.payload
            clock.now += ms(1)
        }
        assertTrue(out.starvationOverride)
        assertEquals(batch.map { it.payload }, processed)
        assertFalse(out.stoppedEarly)
    }

    fun prioritizedSelectionWithoutAStarvedItemMatchesTheOriginalLifoBatch() {
        val queue = CommentBindQueue<String>()
        List(10) { View("v$it") }.forEach { queue.enqueue(entry(it, enqueuedAt = ms(999))) }
        val batch = queue.takeBatch(nowNanos = ms(1_000), starvationLimitNanos = ms(500))
        assertEquals(listOf("v4", "v5", "v6", "v7", "v8", "v9"), batch.map { it.payload })
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentBindBudgetCases) -> Unit) {
            val s = CommentBindBudgetCases()
            block(s)
        }
        runIsolated { s -> s.withoutAnyBudgetTheQueueBehavesExactlyLikeTheInlineImplementation() }
        runIsolated { s -> s.capacityEvictsTheOldestAndCountsIt() }
        runIsolated { s -> s.batchIsTheNewestSixInEnqueueOrder() }
        runIsolated { s -> s.everyBatchHasAtLeastOneItemEvenWithAZeroBudget() }
        runIsolated { s -> s.stopsBetweenItemsOnceTheBudgetIsSpentAndReportsTheRest() }
        runIsolated { s -> s.aBatchThatFitsTheBudgetIsProcessedCompletely() }
        runIsolated { s -> s.anItemOverTheBudgetStillRunsToCompletionBecauseItemsAreNotInterruptible() }
        runIsolated { s -> s.givenBackItemsKeepLifoPositionBelowTasksQueuedWhileTheBatchRan() }
        runIsolated { s -> s.aNewerTaskForTheSameViewIsNeverOverwrittenByTheReturnedOne() }
        runIsolated { s -> s.givingBackRespectsTheCapAndEvictsTheOldest() }
        runIsolated { s -> s.repeatedPartialDrainsAlwaysFinishAndLoseNothing() }
        runIsolated { s -> s.anOldWaitingItemLiftsTheBudgetSoLatecomersCannotStarveIt() }
        runIsolated { s -> s.untimedEntriesNeverTriggerTheStarvationOverride() }
        runIsolated { s -> s.starvationPriorityLooksBeyondTheNewestSixAndCompletesTheOldestItem() }
        runIsolated { s -> s.prioritizedSelectionWithoutAStarvedItemMatchesTheOriginalLifoBatch() }
    }
}

private class CommentBindProbeCases {
    private class FakeClock(var now: Long = 1_000_000L) : CommentBindProbe.Clock {
        var reads = 0
        override fun nanoTime(): Long {
            reads++
            return now
        }
    }

    private val clock = FakeClock()
    private val probe = CommentBindProbe(clock)

    private fun ms(value: Long) = value * 1_000_000L

    /** 一个 item：[nodes] 个节点、[bindable] 个候选，耗时 [durationMs]。 */
    private fun item(nodes: Int, bindable: Int, durationMs: Long, valid: Boolean = true, enqueuedAt: Long = 0L) {
        val token = probe.beginItem()
        repeat(nodes) { probe.countNode() }
        probe.noteBindable(bindable)
        clock.now += ms(durationMs)
        probe.endItem(token, enqueuedAt, valid)
    }

    fun isOffByDefaultAndEveryEntryPointIsInert() {
        assertFalse(probe.enabled)
        assertEquals(0L, probe.stamp())
        probe.onEnqueue(replaced = 2, overflowed = 3, queueLength = 50)
        probe.onDeferred(CommentBindProbe.DEFER_SCROLL)
        val drain = probe.beginDrain()
        val itemToken = probe.beginItem()
        probe.noteStale()
        probe.noteDetached()
        probe.endItem(itemToken, 0L, valid = true)
        probe.endDrain(drain, queueBefore = 10, taken = 6, queueAfter = 4)
        assertEquals(NO_TOKEN, drain)
        assertEquals(NO_TOKEN, itemToken)
        assertEquals("a disabled probe never reads the clock", 0, clock.reads)
        assertNull(probe.takeReportIfDue(queueEmpty = true))
        val s = probe.snapshot()
        assertFalse(s.enabled)
        assertEquals(0L, s.drains + s.items + s.enqueued + s.staleGenerationItems + s.detachedItems + s.overflowDropped)
        assertTrue(s.recentDrains.isEmpty() && s.recentItems.isEmpty())
    }

    fun recordsOneDrainWithItsItems() {
        probe.enabled = true
        val queued = probe.stamp() // 入队时刻 = 1_000_000
        probe.onEnqueue(replaced = 0, overflowed = 0, queueLength = 8)
        clock.now += ms(3)
        val drain = probe.beginDrain()
        item(nodes = 120, bindable = 30, durationMs = 2, enqueuedAt = queued)
        item(nodes = 400, bindable = 90, durationMs = 7, enqueuedAt = queued)
        item(nodes = 15, bindable = 0, durationMs = 1, valid = false, enqueuedAt = queued)
        probe.endDrain(drain, queueBefore = 8, taken = 6, queueAfter = 2)

        val s = probe.snapshot()
        assertEquals(1L, s.drains)
        assertEquals(3L, s.items)
        assertEquals(2L, s.boundItems)
        assertEquals(1L, s.invalidItems)
        assertEquals(535L, s.totalNodes)
        assertEquals(400, s.maxNodes)
        assertEquals(ms(7), s.maxItemNs)
        assertEquals(ms(10), s.maxDrainNs)
        assertEquals(8, s.maxQueueLength)
        val d = s.recentDrains.single()
        assertEquals(CommentBindProbe.DrainSample(
            durationNs = ms(10), maxItemNs = ms(7), queueBefore = 8, taken = 6,
            bound = 2, invalid = 1, detached = 0, stale = 0, nodes = 535, queueAfter = 2
        ), d)
        assertEquals(listOf(120, 400, 15), s.recentItems.map { it.nodes })
        assertEquals(listOf(30, 90, 0), s.recentItems.map { it.bindable })
        assertEquals(listOf(true, true, false), s.recentItems.map { it.valid })
        assertEquals(listOf(ms(2), ms(7), ms(1)), s.recentItems.map { it.durationNs })
        // 等待 = 入队到 item 完成：3ms 排队 + 之前各 item 的耗时。
        assertEquals(listOf(ms(5), ms(12), ms(13)), s.recentItems.map { it.waitNs })
    }

    fun staleAndDetachedItemsAreCountedPerDrainWithoutItemSamples() {
        probe.enabled = true
        val drain = probe.beginDrain()
        probe.noteStale()
        probe.noteStale()
        probe.noteDetached()
        item(nodes = 10, bindable = 4, durationMs = 1)
        probe.endDrain(drain, queueBefore = 4, taken = 4, queueAfter = 0)
        val s = probe.snapshot()
        assertEquals(2L, s.staleGenerationItems)
        assertEquals(1L, s.detachedItems)
        assertEquals("only the item that walked a tree is an item sample", 1, s.recentItems.size)
        val d = s.recentDrains.single()
        assertEquals(2, d.stale); assertEquals(1, d.detached); assertEquals(1, d.bound)
        // 下一次 drain 的逐批累加从零开始，旧批的丢弃数不会漏进来。
        val next = probe.beginDrain()
        probe.endDrain(next, queueBefore = 0, taken = 0, queueAfter = 0)
        val last = probe.snapshot().recentDrains.last()
        assertEquals(0, last.stale); assertEquals(0, last.detached); assertEquals(0, last.bound)
        assertEquals("cumulative counters keep the totals", 2L, probe.snapshot().staleGenerationItems)
    }

    fun enqueueAccountingCoversReplacementsOverflowAndPeakQueueLength() {
        probe.enabled = true
        probe.onEnqueue(replaced = 0, overflowed = 0, queueLength = 40)
        probe.onEnqueue(replaced = 1, overflowed = 0, queueLength = 40)
        probe.onEnqueue(replaced = 0, overflowed = 2, queueLength = 96)
        probe.onEnqueue(replaced = 0, overflowed = 0, queueLength = 10)
        val s = probe.snapshot()
        assertEquals(4L, s.enqueued)
        assertEquals(1L, s.replacedDuplicates)
        assertEquals(2L, s.overflowDropped)
        assertEquals(96, s.maxQueueLength)
    }

    fun deferralsAreCountedByReason() {
        probe.enabled = true
        repeat(3) { probe.onDeferred(CommentBindProbe.DEFER_SCROLL) }
        probe.onDeferred(CommentBindProbe.DEFER_TOUCH)
        repeat(2) { probe.onDeferred(CommentBindProbe.DEFER_QUIET) }
        probe.onDeferred(99) // 未知原因不计入任何一类
        val s = probe.snapshot()
        assertEquals(3L, s.deferredByScroll); assertEquals(1L, s.deferredByTouch); assertEquals(2L, s.deferredByQuiet)
    }

    fun waitIsUnknownWhenTheItemWasQueuedBeforeTheProbeWasEnabled() {
        probe.enabled = true
        val drain = probe.beginDrain()
        item(nodes = 5, bindable = 1, durationMs = 1, enqueuedAt = 0L)
        probe.endDrain(drain, 1, 1, 0)
        val s = probe.snapshot()
        assertEquals(-1L, s.recentItems.single().waitNs)
        assertEquals("unknown waits are excluded from the wait series", 0, s.waitNs.count)
    }

    fun nodeCountsOutsideAnItemNeverLeakIntoTheNextItem() {
        probe.enabled = true
        // 触摸夺回等非 drain 路径也会走整树收集并调用 countNode。
        repeat(500) { probe.countNode() }
        probe.noteBindable(77)
        val drain = probe.beginDrain()
        item(nodes = 3, bindable = 1, durationMs = 1)
        probe.endDrain(drain, 1, 1, 0)
        val sample = probe.snapshot().recentItems.single()
        assertEquals(3, sample.nodes); assertEquals(1, sample.bindable)
    }

    fun ringsStayBoundedAndKeepTheNewestSamplesOldestFirst() {
        probe.enabled = true
        val drains = CommentBindProbe.DRAIN_CAPACITY * 3 + 5
        for (i in 1..drains) {
            val drain = probe.beginDrain()
            item(nodes = i, bindable = 1, durationMs = 1)
            probe.endDrain(drain, queueBefore = i, taken = 1, queueAfter = 0)
        }
        val s = probe.snapshot()
        assertEquals(drains.toLong(), s.drains)
        assertEquals(drains.toLong(), s.items)
        assertEquals(CommentBindProbe.DRAIN_CAPACITY, s.recentDrains.size)
        // 窗口 = 最近 64 次，最旧在前、最新在后。
        assertEquals((drains - CommentBindProbe.DRAIN_CAPACITY + 1..drains).toList(), s.recentDrains.map { it.queueBefore })
        assertEquals(drains, s.maxNodes)
        // item 环更大：全部 197 个 item 都还在窗口内。
        assertEquals(drains, s.recentItems.size)
        val itemOverflow = CommentBindProbe.ITEM_CAPACITY * 2 + 3
        repeat(itemOverflow) { item(nodes = 1, bindable = 1, durationMs = 1) }
        assertEquals(CommentBindProbe.ITEM_CAPACITY, probe.snapshot().recentItems.size)
        assertEquals(drains + itemOverflow.toLong(), probe.snapshot().items)
    }

    fun nearestRankPercentiles() {
        val hundred = CommentBindProbe.series(LongArray(100) { (100 - it).toLong() }) // 乱序输入
        assertEquals(CommentBindProbe.Series(100, 50, 95, 99, 100), hundred)
        assertEquals(CommentBindProbe.Series(1, 7, 7, 7, 7), CommentBindProbe.series(longArrayOf(7)))
        assertEquals(CommentBindProbe.Series(0, 0, 0, 0, 0), CommentBindProbe.series(LongArray(0)))
        // 少量样本时 p95/p99 都落到最大值：不会越界，也不会插值出没发生过的数。
        assertEquals(CommentBindProbe.Series(3, 2, 3, 3, 3), CommentBindProbe.series(longArrayOf(3, 1, 2)))
    }

    fun seriesAreComputedOverTheRecordedWindow() {
        probe.enabled = true
        val drain = probe.beginDrain()
        for (d in 1..10) item(nodes = d * 10, bindable = 1, durationMs = d.toLong())
        probe.endDrain(drain, 10, 6, 4)
        val s = probe.snapshot()
        assertEquals(CommentBindProbe.Series(10, ms(5), ms(10), ms(10), ms(10)), s.itemNs)
        assertEquals(CommentBindProbe.Series(10, 50, 100, 100, 100), s.itemNodes)
        assertEquals(1, s.drainNs.count)
        assertEquals(ms(55), s.drainNs.max)
    }

    fun disablingStopsRecordingButKeepsTheDataUntilReset() {
        probe.enabled = true
        val first = probe.beginDrain()
        item(nodes = 9, bindable = 2, durationMs = 1)
        probe.endDrain(first, 1, 1, 0)
        probe.enabled = false
        val readsBefore = clock.reads
        assertEquals(NO_TOKEN, probe.beginDrain())
        assertEquals(readsBefore, clock.reads)
        assertEquals(1L, probe.snapshot().drains)
        assertFalse(probe.snapshot().enabled)

        probe.reset()
        val s = probe.snapshot()
        assertEquals(0L, s.drains + s.items + s.totalNodes)
        assertTrue(s.recentDrains.isEmpty() && s.recentItems.isEmpty())
        probe.enabled = true
        val again = probe.beginDrain()
        probe.endDrain(again, 0, 0, 0)
        assertEquals(1L, probe.snapshot().drains)
    }

    fun aDrainStartedWhileDisabledIsNotHalfRecordedAfterEnabling() {
        val drain = probe.beginDrain() // 关闭
        probe.enabled = true
        probe.endDrain(drain, queueBefore = 5, taken = 5, queueAfter = 0)
        val itemToken = NO_TOKEN
        probe.endItem(itemToken, 0L, valid = true)
        assertEquals(0L, probe.snapshot().drains + probe.snapshot().items)
    }

    fun reportsAreRateLimitedAndNeverPerItem() {
        probe.enabled = true
        fun oneDrain() {
            val drain = probe.beginDrain()
            item(nodes = 10, bindable = 2, durationMs = 1)
            probe.endDrain(drain, 1, 1, 0)
        }
        assertNull("nothing to report before any drain", probe.takeReportIfDue(queueEmpty = true))
        oneDrain()
        assertNull("a non-empty queue waits for a full batch of drains", probe.takeReportIfDue(queueEmpty = false))
        val first = probe.takeReportIfDue(queueEmpty = true)
        assertNotNull("the first report is not delayed by the interval", first)
        oneDrain()
        assertNull("minimum interval since the last report", probe.takeReportIfDue(queueEmpty = true))
        clock.now += CommentBindProbe.REPORT_MIN_INTERVAL_NS
        assertNotNull(probe.takeReportIfDue(queueEmpty = true))
        assertNull("no new drain since the last report", probe.takeReportIfDue(queueEmpty = true))
        // 队列一直非空时每累计 REPORT_EVERY_DRAINS 次 drain 才报一次。
        clock.now += CommentBindProbe.REPORT_MIN_INTERVAL_NS
        repeat(CommentBindProbe.REPORT_EVERY_DRAINS - 1) { oneDrain() }
        assertNull(probe.takeReportIfDue(queueEmpty = false))
        oneDrain()
        assertNotNull(probe.takeReportIfDue(queueEmpty = false))
    }

    fun reportTextCarriesTheMeasuredFigures() {
        probe.enabled = true
        probe.onEnqueue(replaced = 1, overflowed = 2, queueLength = 12)
        probe.onDeferred(CommentBindProbe.DEFER_SCROLL)
        val drain = probe.beginDrain()
        item(nodes = 250, bindable = 40, durationMs = 4)
        probe.noteStale()
        probe.endDrain(drain, 12, 6, 6)
        val text = probe.report()
        assertTrue(text, text.startsWith("[BIL] 评论绑定探针 drains=1 "))
        assertTrue(text, text.contains("defer(scroll/touch/quiet)=1/0/0"))
        assertTrue(text, text.contains("items=1(bound=1 invalid=0) detached=0 stale=1"))
        assertTrue(text, text.contains("enq=1 replaced=1 overflow=2 queueMax=12 nodes(total/max)=250/250"))
        assertTrue(text, text.contains("drain_ms(n=1) p50=4.00 p95=4.00 p99=4.00 max=4.00"))
        assertTrue(text, text.contains("item_nodes(n=1) p50=250 p95=250 p99=250 max=250"))
        assertTrue("one line, bounded", !text.contains('\n') && text.length < 700)
    }

    fun theRecordingPathDoesNotAllocateWhenEnabled() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("thread allocation counters unavailable", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!
        probe.enabled = true
        fun cycle() {
            probe.onEnqueue(1, 0, 40)
            val drain = probe.beginDrain()
            val a = probe.beginItem()
            probe.countNode(); probe.countNode(); probe.noteBindable(2)
            clock.now += 2_000_000L
            probe.endItem(a, 1L, true)
            probe.noteStale()
            probe.noteDetached()
            probe.endDrain(drain, 8, 3, 5)
            probe.onDeferred(CommentBindProbe.DEFER_SCROLL)
        }
        repeat(30_000) { cycle() } // 预热：JIT、锁膨胀、环形缓冲分配
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(100_000) { cycle() }
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue("allocated $allocated bytes over 100k cycles", allocated < 100_000L) // < 1 字节/周期
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentBindProbeCases) -> Unit) {
            val s = CommentBindProbeCases()
            block(s)
        }
        runIsolated { s -> s.isOffByDefaultAndEveryEntryPointIsInert() }
        runIsolated { s -> s.recordsOneDrainWithItsItems() }
        runIsolated { s -> s.staleAndDetachedItemsAreCountedPerDrainWithoutItemSamples() }
        runIsolated { s -> s.enqueueAccountingCoversReplacementsOverflowAndPeakQueueLength() }
        runIsolated { s -> s.deferralsAreCountedByReason() }
        runIsolated { s -> s.waitIsUnknownWhenTheItemWasQueuedBeforeTheProbeWasEnabled() }
        runIsolated { s -> s.nodeCountsOutsideAnItemNeverLeakIntoTheNextItem() }
        runIsolated { s -> s.ringsStayBoundedAndKeepTheNewestSamplesOldestFirst() }
        runIsolated { s -> s.nearestRankPercentiles() }
        runIsolated { s -> s.seriesAreComputedOverTheRecordedWindow() }
        runIsolated { s -> s.disablingStopsRecordingButKeepsTheDataUntilReset() }
        runIsolated { s -> s.aDrainStartedWhileDisabledIsNotHalfRecordedAfterEnabling() }
        runIsolated { s -> s.reportsAreRateLimitedAndNeverPerItem() }
        runIsolated { s -> s.reportTextCarriesTheMeasuredFigures() }
        runIsolated { s -> s.theRecordingPathDoesNotAllocateWhenEnabled() }
    }
}

private class CommentClassicStyleFeatureInstallerCases {
    private fun environment(
        registrar: HookRegistrar = TestHookRegistrar,
        process: String = "tv.danmaku.bili",
        loader: ClassLoader? = CommentClassicStyleLocatorTest.loader(),
        evidence: (String, FeatureRuntimeStage, Int) -> Unit = { _, _, _ -> }
    ) = HookEnvironment(
        processName = process,
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> },
        runtimeEvidence = evidence
    )

    fun `both style experiments are disabled on every supported query overload`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val result = CommentClassicStyleFeatureInstaller(true).install(environment(registrar) { id, stage, _ ->
            assertEquals(CommentClassicStyleFeatureInstaller.ID, id)
            stages += stage
        })
        assertEquals(FeatureInstallResult.Installed(2, complete = false), result)
        assertEquals(2, registrar.hooks.size)
        for ((id, entry) in registrar.hooks) {
            for (key in listOf("comment.next_appearance", "comment.next_appearance_experiment_3")) {
                val args = arrayOfNulls<Any?>(entry.member.parameterCount)
                args[0] = key
                args[1] = true
                assertEquals(false, registrar.invoke(id, args = args) { error("experiment must be intercepted") })
            }
        }
        assertEquals(4, stages.count { it == FeatureRuntimeStage.OBSERVED })
        assertEquals(4, stages.count { it == FeatureRuntimeStage.APPLIED })
    }

    fun `unrelated decisions and similarly named keys retain original behavior`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        CommentClassicStyleFeatureInstaller(true).install(environment(registrar) { _, stage, _ -> stages += stage })
        for ((id, entry) in registrar.hooks) {
            for (key in listOf(null, "dd_enable_system_media_control", "comment.next_appearance_experiment_30", "comment.share_opt_enabled")) {
                val args = arrayOfNulls<Any?>(entry.member.parameterCount)
                args[0] = key
                args[1] = true
                assertEquals(true, registrar.invoke(id, args = args) { forwarded ->
                    // 内容不变即可：SDK 的可写参数形状在入口持有自己的数组，不再把调用方的数组原样交给下游。
                    assertArrayEquals(args, forwarded)
                    true
                })
            }
        }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), stages)
    }

    fun `disabled and secondary process paths do not register hooks`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(FeatureInstallResult.Skipped("disabled"),
            CommentClassicStyleFeatureInstaller(false).install(environment(registrar)))
        assertEquals(FeatureInstallResult.Skipped("non-main-process"),
            CommentClassicStyleFeatureInstaller(true).install(environment(registrar, process = "tv.danmaku.bili:download")))
        assertTrue(registrar.hooks.isEmpty())
    }

    fun `missing query boundary fails safely and incomplete registration is reported`() {
        assertEquals(FeatureInstallResult.Skipped("missing-config-boundary"),
            CommentClassicStyleFeatureInstaller(true).install(environment(loader = null)))
        assertEquals(FeatureInstallResult.Installed(1, complete = false),
            CommentClassicStyleFeatureInstaller(true).install(environment(PlayerPortTestRegistrar("comment.classic.dd.0"))))
    }

    fun `kotlin direct and default calls disable all four containers and forward unrelated keys`() {
        val registrar = PlayerPortTestRegistrar()
        val installer = CommentClassicStyleFeatureInstaller(true)
        installer.install(environment(registrar, loader = CommentClassicStyleLocatorTest.loader(kotlin = true)))
        assertEquals(4, registrar.hooks.size)
        for ((id, entry) in registrar.hooks.filterKeys { it.startsWith("comment.classic.kotlin.") }) {
            for (key in listOf("comment.kntr.enabled", "comment.kntr.route.enabled", "comment.kntr.landscape.enabled", "comment.kntr.story.enabled")) {
                val args = arrayOfNulls<Any?>(entry.member.parameterCount); args[1] = key; args[2] = true
                assertEquals(false, registrar.invoke(id, args = args) { error("must disable KMP selection") })
            }
            val args = arrayOfNulls<Any?>(entry.member.parameterCount); args[1] = "comment.kntr.enabled_extra"; args[2] = true
            assertEquals(true, registrar.invoke(id, args = args) { true })
        }
        installer.install(environment(registrar, loader = CommentClassicStyleLocatorTest.loader(kotlin = true)))
        assertEquals(4, registrar.hooks.size)
    }

    fun `late adaptation fills missing family without replacing registered hooks or previews`() {
        val registrar = PlayerPortTestRegistrar()
        var cache: CommentClassicStylePoints? = null
        val loader = CommentClassicStyleLocatorTest.loader(kotlin = true, missingKotlin = true)
        val installer = CommentClassicStyleFeatureInstaller(true) { cache }
        installer.install(environment(registrar, loader = loader))
        assertTrue(installer.requiresAdaptationRetry)
        val initial = registrar.hooks.toMap()
        cache = CommentClassicStylePoints(emptyList(), listOf(CommentClassicStyleLocatorTest.point(KotlinReaders::class.java, "getBool")))
        installer.install(environment(registrar, loader = loader))
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(3, registrar.hooks.size)
        initial.forEach { (id, hook) -> assertSame(hook, registrar.hooks[id]) }
    }

    fun `kotlin selection stays open when native style registration is incomplete`() {
        val registrar = PlayerPortTestRegistrar("comment.classic.dd.0")
        CommentClassicStyleFeatureInstaller(true).install(environment(registrar, loader = CommentClassicStyleLocatorTest.loader(kotlin = true)))
        registrar.hooks.filterKeys { it.startsWith("comment.classic.kotlin.") }.forEach { (id, entry) ->
            val args = arrayOfNulls<Any?>(entry.member.parameterCount); args[1] = "comment.kntr.enabled"; args[2] = true
            assertEquals(true, registrar.invoke(id, args = args) { true })
        }
    }

    fun verifyAllCases() {
        fun runIsolated(block: (CommentClassicStyleFeatureInstallerCases) -> Unit) {
            val s = CommentClassicStyleFeatureInstallerCases()
            block(s)
        }
        runIsolated { s -> s.`both style experiments are disabled on every supported query overload`() }
        runIsolated { s -> s.`unrelated decisions and similarly named keys retain original behavior`() }
        runIsolated { s -> s.`disabled and secondary process paths do not register hooks`() }
        runIsolated { s -> s.`missing query boundary fails safely and incomplete registration is reported`() }
        runIsolated { s -> s.`kotlin direct and default calls disable all four containers and forward unrelated keys`() }
        runIsolated { s -> s.`late adaptation fills missing family without replacing registered hooks or previews`() }
        runIsolated { s -> s.`kotlin selection stays open when native style registration is incomplete`() }
    }
}
