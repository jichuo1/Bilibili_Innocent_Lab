package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class CloudKeyFreezeFeatureInstallerTest {
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
        object : ClassLoader(CloudKeyFreezeFeatureInstallerTest::class.java.classLoader) {
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
        loader: ClassLoader? = loader()
    ) = HookEnvironment(
        processName = process,
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { key, message -> recorder.infoLogs[key] = message },
        logError = { key, message -> recorder.errorLogs[key] = message },
        reportStatus = { _, status -> recorder.statuses += status },
        runtimeEvidence = { id, stage, _ ->
            assertEquals(CloudKeyFreezeFeatureInstaller.ID, id)
            recorder.stages += stage
        }
    )

    private fun installer(rules: String = "ff_a=false", observe: Boolean = false, enabled: Boolean = true) =
        CloudKeyFreezeFeatureInstaller(enabled, rules, observe)

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

    @Test fun `every read family is hooked and wrapper lookalikes are left alone`() {
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

    @Test fun `the compiler generated bridge that interface calls land on is hooked and works`() {
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

    @Test fun `disabled other process and empty rules register nothing`() {
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

    @Test fun `rules that are all refused leave nothing to install and say why`() {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        val result = installer("grpc.x=true\ninfra.y=false\nnot a rule").install(environment(recorder, registrar))
        assertEquals(FeatureInstallResult.Skipped("no-rules"), result)
        assertTrue(registrar.hooks.isEmpty())
        val message = recorder.errorLogs.getValue("cloud_key_freeze_rejected")
        assertTrue(message, "1 条规则格式不对" in message && "2 条属于受保护类别" in message)
    }

    @Test fun `observation alone installs the hooks even without rules`() {
        val registrar = PlayerPortTestRegistrar()
        val result = installer(rules = "", observe = true).install(environment(registrar = registrar))
        assertTrue(result is FeatureInstallResult.Installed)
        assertTrue(registrar.hooks.isNotEmpty())
    }

    @Test fun `a host without any known boundary fails closed`() {
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

    @Test fun `a host with only the facade still installs and reports its families`() {
        val registrar = PlayerPortTestRegistrar()
        val recorder = Recorder()
        val only = mapOf<String, Class<*>>("com.bilibili.lib.dd.DeviceDecision" to FakeDeviceDecision::class.java)
        val result = installer().install(environment(recorder, registrar, loader = loader(only)))
        assertEquals(FeatureInstallResult.Installed(4), result)
        assertEquals("installed:rules=1,families=dd", recorder.statuses.single())
    }

    @Test fun `one failed registration is partial and all failed is skipped`() {
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

    @Test fun `a boolean rule pins device decision reads and leaves other keys alone`() {
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

    @Test fun `a value the cloud already left at the pinned value is observed but not applied`() {
        val (registrar, recorder) = installed("ff_pin=false")
        val id = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 3)
        assertEquals(false, registrar.invoke(id, args = arrayOf("ff_pin", true, null)) { false })
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED), recorder.stages)
    }

    @Test fun `a string rule pins dd reads and a boolean rule on the same key does not touch them`() {
        val (registrar, _) = installed("live.cfg=s:fixed\nlive.flag=false")
        val id = registrar.idOf(FakeDeviceDecision::class.java, "dd", 2)
        assertEquals("fixed", registrar.invoke(id, args = arrayOf("live.cfg", "d")) { "cloud" })
        assertEquals("fixed", registrar.invoke(id, args = arrayOf("live.cfg", null)) { null })
        assertEquals("cloud", registrar.invoke(id, args = arrayOf("live.flag", "d")) { "cloud" })
    }

    @Test fun `use default returns what the host call site passed for every read kind`() {
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

    @Test fun `static kotlin entry points read the key from the second argument`() {
        val (registrar, _) = installed("ff_pin=false\nlive.cfg=s:fixed")
        val bool = registrar.idOf(FakeKotlinDd::class.java, "getBool", 4)
        assertEquals(false, registrar.invoke(bool, args = arrayOf(Any(), "ff_pin", true, null)) { true })
        assertEquals(true, registrar.invoke(bool, args = arrayOf("ff_pin", "x", true, null)) { true })
        val text = registrar.idOf(FakeKotlinDd::class.java, "getString", 4)
        assertEquals("fixed", registrar.invoke(text, args = arrayOf(Any(), "live.cfg", "d", null)) { "cloud" })
    }

    @Test fun `is hit ff without a default argument treats no cloud value as hit`() {
        val (registrar, _) = installed("ff_pin=default\nff_off=false")
        val id = registrar.idOf(FakeConfigCompanion::class.java, "isHitFF", 1)
        assertEquals(true, registrar.invoke(id, args = arrayOf("ff_pin")) { false })
        assertEquals(false, registrar.invoke(id, args = arrayOf("ff_off")) { true })
        assertEquals(true, registrar.invoke(id, args = arrayOf("ff_unlisted")) { true })
    }

    @Test fun `generic contracts rewrite by the runtime type of the read`() {
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

    @Test fun `a read that throws keeps its exception and is not rewritten`() {
        val (registrar, _) = installed("ff_pin=false")
        val id = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        assertThrows(IllegalStateException::class.java) {
            registrar.invoke(id, args = arrayOf("ff_pin", true)) { error("boom") }
        }
    }

    @Test fun `non string keys are ignored without error`() {
        val (registrar, recorder) = installed("ff_pin=false")
        val id = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        assertEquals(true, registrar.invoke(id, args = arrayOf(42, true)) { true })
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), recorder.stages)
    }

    // endregion

    // region observation

    @Test fun `observation logs each key once with a boolean value but never a string value`() {
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

    @Test fun `observation alone counts as observed runtime evidence but never as applied`() {
        val (registrar, recorder) = installed("", observe = true)
        val bool = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        registrar.invoke(bool, args = arrayOf("ff_seen", false)) { true }
        registrar.invoke(bool, args = arrayOf("ff_seen_too", false)) { true }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED), recorder.stages)
    }

    @Test fun `observation stops registering new keys at the cap`() {
        val (registrar, recorder) = installed("", observe = true)
        val bool = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        repeat(450) { registrar.invoke(bool, args = arrayOf("ff_$it", false)) { true } }
        assertEquals(400, recorder.infoLogs.keys.count { it.startsWith("cloud_key_freeze_seen_") })
    }

    @Test fun `observation is silent when it is not requested`() {
        val (registrar, recorder) = installed("ff_pin=false")
        val bool = registrar.idOf(FakeDeviceDecision::class.java, "getBoolean", 2)
        registrar.invoke(bool, args = arrayOf("ff_seen", false)) { true }
        assertTrue(recorder.infoLogs.keys.none { it.startsWith("cloud_key_freeze_seen_") })
    }

    // endregion
}
