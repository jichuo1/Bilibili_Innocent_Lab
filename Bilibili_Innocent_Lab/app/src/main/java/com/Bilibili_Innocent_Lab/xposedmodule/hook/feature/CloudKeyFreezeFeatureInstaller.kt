package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookParam
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 冻结指定云控键：用户写下 `键名=取值`，宿主读到这些键时就拿到固定值，其它云控键不碰。
 *
 * 宿主的页面与按钮开关散落在约 4000 个云控读取点里，首页框架那种"一个键一个读取点"的
 * 专用开关管不过来。本功能只在**读取边界**上按键名查表，规则的解析与类型匹配见
 * [CloudKeyFreezeRules] / [CloudKeyRuleResolver]。
 *
 * **读取边界**（2026-10-31 对 9.1.0–9.17.0 的 dexq 核对）：
 * - 设备决策：`DeviceDecision#getBoolean / dd`（门面，名字稳定），以及它背后 `GDeviceDecision`
 *   的实现类；旧 blconfig 的 `OverrideFF` 在"新初始化"下把读取直接转给实现类，不经门面；
 * - 跨平台：`IDeviceDecisionKt#getBool / getString`（`KConfig.ff / config` 最终汇入这里）；
 * - 旧 blconfig：`ConfigManager#isHitFF / getConfig` 只是 `Contract.get` 的薄封装，
 *   真正的 `Contract.get` 调用有约 1400 处，所以还要挂 `OverrideFF / OverrideConfig /
 *   TypedContract` 以及 `TypedContract` 的 FF 子类（名字被混淆，运行时按结构识别）。
 * `isHitFF(key)` 实际是 `ab().get(key, true)`：没有云控值时视为命中，所以它的"默认值"是 true。
 *
 * **为什么 `after` 而不是 `before` 直接短路**：宿主的原实现仍会执行（读取埋点、曝光上报
 * 不丢），只在返回前把结果换掉；代价是每次读取多跑一遍原逻辑，与首页框架开关一致。
 *
 * **热路径提醒**：这些入口在启动期被调用成千上万次，一次读取可能连过 3–4 个钩子。
 * 回调体只做一次 [HashMap] 查表，未命中立即返回；不要在这里加解析、日志或集合分配。
 * 规则表在安装时一次建好，之后只读，因此无需同步。
 */
internal class CloudKeyFreezeFeatureInstaller(
    private val enabled: Boolean,
    private val rules: String,
    private val observe: Boolean
) : FeatureInstaller {

    override val id: String = ID

    /** 一个被挂钩的读取入口；`defaultIndex < 0` 表示该入口没有默认值参数。 */
    private class Target(
        val family: String,
        val method: Method,
        val kind: CloudKeyReadKind,
        val keyIndex: Int,
        val defaultIndex: Int,
        val constantDefault: Any? = null
    ) {
        val hasDefault: Boolean get() = defaultIndex >= 0 || constantDefault != null
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val ruleSet = CloudKeyFreezeRules.parse(rules)
        if (ruleSet.rejected + ruleSet.protectedRejected > 0) {
            environment.logError(
                "cloud_key_freeze_rejected",
                "[BIL] 冻结云控键: ${ruleSet.rejected} 条规则格式不对或超限，" +
                    "${ruleSet.protectedRejected} 条属于受保护类别(网络/登录/风控/初始化)已拒绝"
            )
        }
        if (ruleSet.isEmpty && !observe) {
            environment.reportStatus(CHANNEL_STATUS, "no-rules")
            return FeatureInstallResult.Skipped("no-rules")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val targets = collectTargets(loader)
        if (targets.isEmpty()) return missing(environment, "missing-config-boundary")

        val gate = Gate(environment, ruleSet.rules, observe)
        var installed = 0
        targets.forEachIndexed { index, target ->
            if (register(environment, gate, "cloud_key_freeze.${target.family}.$index", target)) {
                installed += 1
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")

        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val families = targets.map(Target::family).distinct().joinToString(",")
        val complete = installed == targets.size
        environment.reportStatus(
            CHANNEL_STATUS,
            (if (complete) "installed" else "partial:$installed/${targets.size}") +
                ":rules=${ruleSet.rules.size},families=$families"
        )
        if (complete) {
            environment.logInfo(
                "cloud_key_freeze_ok",
                "[BIL] 冻结云控键已安装，rules=${ruleSet.rules.size}, hooks=$installed, families=$families"
            )
        } else {
            environment.logError(
                "cloud_key_freeze_partial",
                "[BIL] 冻结云控键部分安装，hooks=$installed/${targets.size}, families=$families"
            )
        }
        return FeatureInstallResult.Installed(installed, complete = complete)
    }

    private fun register(
        environment: HookEnvironment,
        gate: Gate,
        hookId: String,
        target: Target
    ): Boolean = runCatching {
        val method = target.method
        environment.registrar.exact(hookId, method.declaringClass, method.name, *method.parameterTypes) {
            after { gate.onRead(this, target) }
        }
        true
    }.getOrElse { throwable ->
        environment.logError(
            "cloud_key_freeze_register_${target.family}",
            "[BIL] 冻结云控键 Hook 注册失败(${target.family}): $throwable"
        )
        false
    }

    /**
     * 回调的共享状态。只读规则表在构造时定型；唯一可变的是观测去重集合，且有上限。
     */
    private class Gate(
        private val environment: HookEnvironment,
        private val table: Map<String, CloudKeyRule>,
        private val observe: Boolean
    ) {
        private val seen = ConcurrentHashMap<String, Boolean>()
        private val firstObserved = AtomicBoolean(false)
        private val firstApplied = AtomicBoolean(false)

        fun onRead(param: ModernHookParam, target: Target) {
            if (param.hasThrowable) return
            val key = param.argOrNull(target.keyIndex) as? String ?: return
            if (observe) observeKey(key, target.kind, param.result)
            val rule = table[key]
            // 观测模式下读到任意键、或读到规则里的键，都说明读取入口确实在宿主里被走到了。
            if ((rule != null || observe) && firstObserved.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
            }
            if (rule == null) return
            val original = param.result
            val default = if (target.defaultIndex >= 0) {
                param.argOrNull(target.defaultIndex)
            } else {
                target.constantDefault
            }
            val replacement = CloudKeyRuleResolver.resolve(
                rule, target.kind, original, default, target.hasDefault
            )
            if (replacement === CloudKeyRuleResolver.UNCHANGED || replacement == original) return
            param.result = replacement
            if (firstApplied.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                environment.reportStatus(CHANNEL_STATUS, "applied")
            }
        }

        /**
         * 只记键名和布尔值：字符串云控值里可能带地址、盐或密钥(例如上报盐值)，不进日志。
         * 去重集合到上限后不再登记，避免宿主读到海量动态键时无限增长。
         */
        private fun observeKey(key: String, kind: CloudKeyReadKind, original: Any?) {
            if (seen.size >= MAX_OBSERVED_KEYS || seen.putIfAbsent(key, true) != null) return
            val value = if (original is Boolean) " value=$original" else ""
            environment.logInfo("cloud_key_freeze_seen_$key", "[BIL] 云控读取 key=$key kind=$kind$value")
        }
    }

    // region 读取入口发现

    private fun collectTargets(loader: ClassLoader): List<Target> {
        val targets = ArrayList<Target>()
        val seenMethods = HashSet<Method>()
        fun add(target: Target) {
            if (seenMethods.add(target.method)) targets += target
        }

        val facade = KavaMemberLookup.classOrNull(loader, DEVICE_DECISION_CLASS)
        facade?.let { owner -> deviceDecisionTargets(FAMILY_DD, owner, threeArgumentOnly = false).forEach(::add) }
        discoverDeviceDecisionImplementations(loader, facade).forEach { owner ->
            deviceDecisionTargets(FAMILY_DD_IMPL, owner, threeArgumentOnly = true).forEach(::add)
        }
        KavaMemberLookup.classOrNull(loader, KOTLIN_DD_CLASS)?.let { owner ->
            kotlinDeviceDecisionTargets(owner).forEach(::add)
        }
        KavaMemberLookup.classOrNull(loader, CONFIG_MANAGER_COMPANION_CLASS)?.let { owner ->
            configManagerTargets(owner).forEach(::add)
        }
        KavaMemberLookup.classOrNull(loader, CONFIG_MANAGER_CLASS)?.let { owner ->
            configManagerTargets(owner).forEach(::add)
        }
        CONTRACT_CLASSES.forEach { name ->
            KavaMemberLookup.classOrNull(loader, name)?.let { owner ->
                contractTargets(FAMILY_CONTRACT, owner).forEach(::add)
            }
        }
        discoverFeatureFlagContracts(loader).forEach { owner ->
            contractTargets(FAMILY_CONTRACT_FF, owner).forEach(::add)
        }
        return targets
    }

    /**
     * 不过滤合成方法：`Contract<T>` 的实现类里，接口调用真正落到的 `get(String, Object)` 往往
     * 就是编译器生成的桥接方法(带 `ACC_BRIDGE | ACC_SYNTHETIC`)，真正的类型化实现被改名后
     * 只由它转发。挂在桥接方法上才能覆盖约 1400 处 `Contract.get` 调用。
     */
    private fun methodsOf(owner: Class<*>, predicate: (Method) -> Boolean): List<Method> =
        KavaMemberLookup.declaredMethods(owner, makeAccessible = true, predicate)

    private fun deviceDecisionTargets(
        family: String,
        owner: Class<*>,
        threeArgumentOnly: Boolean
    ): List<Target> {
        fun argumentsFit(method: Method): Boolean {
            val count = method.parameterCount
            return if (threeArgumentOnly) count == 3 else count == 2 || count == 3
        }
        val bools = methodsOf(owner) {
            !it.isStatic && it.name == "getBoolean" && it.returnType == classOf<Boolean>() &&
                argumentsFit(it) && it.parameterTypes[0] == classOf<String>() &&
                it.parameterTypes[1] == classOf<Boolean>()
        }.map { Target(family, it, CloudKeyReadKind.BOOL, keyIndex = 0, defaultIndex = 1) }
        val texts = methodsOf(owner) {
            !it.isStatic && it.name == "dd" && it.returnType == classOf<String>() &&
                argumentsFit(it) && it.parameterTypes[0] == classOf<String>() &&
                it.parameterTypes[1] == classOf<String>()
        }.map { Target(family, it, CloudKeyReadKind.TEXT, keyIndex = 0, defaultIndex = 1) }
        return bools + texts
    }

    /** `IDeviceDecisionKt` 的扩展函数是静态方法，接收者占第 0 位，所以键在 1、默认值在 2。 */
    private fun kotlinDeviceDecisionTargets(owner: Class<*>): List<Target> {
        val bools = methodsOf(owner) {
            it.isStatic && it.name == "getBool" && it.returnType == classOf<Boolean>() &&
                it.parameterCount == 4 && it.parameterTypes[1] == classOf<String>() &&
                it.parameterTypes[2] == classOf<Boolean>()
        }.map { Target(FAMILY_KOTLIN_DD, it, CloudKeyReadKind.BOOL, keyIndex = 1, defaultIndex = 2) }
        val texts = methodsOf(owner) {
            it.isStatic && it.name == "getString" && it.returnType == classOf<String>() &&
                it.parameterCount == 4 && it.parameterTypes[1] == classOf<String>() &&
                it.parameterTypes[2] == classOf<String>()
        }.map { Target(FAMILY_KOTLIN_DD, it, CloudKeyReadKind.TEXT, keyIndex = 1, defaultIndex = 2) }
        return bools + texts
    }

    private fun configManagerTargets(owner: Class<*>): List<Target> {
        val hits = methodsOf(owner) {
            it.name == "isHitFF" && it.returnType == classOf<Boolean>() &&
                it.parameterCount == 1 && it.parameterTypes[0] == classOf<String>()
        }.map {
            // isHitFF(key) 实际是 ab().get(key, true)：没有云控值就算命中。
            Target(FAMILY_CONFIG, it, CloudKeyReadKind.BOOL, keyIndex = 0, defaultIndex = -1, constantDefault = true)
        }
        val configs = methodsOf(owner) {
            it.name == "getConfig" && it.returnType == classOf<String>() &&
                it.parameterCount == 2 && it.parameterTypes[0] == classOf<String>() &&
                it.parameterTypes[1] == classOf<String>()
        }.map { Target(FAMILY_CONFIG, it, CloudKeyReadKind.TEXT, keyIndex = 0, defaultIndex = 1) }
        return hits + configs
    }

    /** `Contract#get(String, T)` 的泛型擦除签名，以及 `FeatureFlagContract#getWithDefault`。 */
    private fun contractTargets(family: String, owner: Class<*>): List<Target> {
        val generic = methodsOf(owner) {
            !it.isStatic && it.name == "get" && it.returnType == classOf<Any>() &&
                it.parameterCount == 2 && it.parameterTypes[0] == classOf<String>() &&
                it.parameterTypes[1] == classOf<Any>()
        }.map { Target(family, it, CloudKeyReadKind.OBJECT, keyIndex = 0, defaultIndex = 1) }
        val withDefault = methodsOf(owner) {
            !it.isStatic && it.name == "getWithDefault" && it.returnType == classOf<Boolean>() &&
                it.parameterCount == 2 && it.parameterTypes[0] == classOf<String>() &&
                it.parameterTypes[1] == classOf<Boolean>()
        }.map { Target(family, it, CloudKeyReadKind.BOOL, keyIndex = 0, defaultIndex = 1) }
        return generic + withDefault
    }

    /**
     * `GDeviceDecision` 的实现类名字被混淆，但都在同一个包里且是单字母叶名：按"实现了接口、
     * 不是接口、不是门面"的结构条件识别，而不是认某个具体名字。
     */
    private fun discoverDeviceDecisionImplementations(
        loader: ClassLoader,
        facade: Class<*>?
    ): List<Class<*>> {
        val contract = KavaMemberLookup.classOrNull(loader, GDEVICE_DECISION_CLASS) ?: return emptyList()
        return scanLeafClasses(loader, DEVICE_DECISION_PACKAGE).filter { candidate ->
            candidate != facade && !candidate.isInterface && contract.isAssignableFrom(candidate)
        }
    }

    /** `TypedContract` 的 FF 子类(名字被混淆)：继承 `TypedContract` 且实现 `FeatureFlagContract`。 */
    private fun discoverFeatureFlagContracts(loader: ClassLoader): List<Class<*>> {
        val base = KavaMemberLookup.classOrNull(loader, TYPED_CONTRACT_CLASS) ?: return emptyList()
        val flag = KavaMemberLookup.classOrNull(loader, FEATURE_FLAG_CONTRACT_CLASS) ?: return emptyList()
        return scanLeafClasses(loader, BLCONFIG_INTERNAL_PACKAGE).filter { candidate ->
            candidate.superclass == base && flag.isAssignableFrom(candidate)
        }
    }

    private fun scanLeafClasses(loader: ClassLoader, packageName: String): List<Class<*>> =
        LEAF_NAMES.mapNotNull { leaf -> KavaMemberLookup.classOrNull(loader, "$packageName.$leaf") }

    // endregion

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("cloud_key_freeze_missing", "[BIL] 冻结云控键适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "cloud_key_freeze"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "cloud_key_freeze_status"
        private const val MAX_OBSERVED_KEYS = 400

        private const val DEVICE_DECISION_PACKAGE = "com.bilibili.lib.dd"
        private const val DEVICE_DECISION_CLASS = "$DEVICE_DECISION_PACKAGE.DeviceDecision"
        private const val GDEVICE_DECISION_CLASS = "$DEVICE_DECISION_PACKAGE.GDeviceDecision"
        private const val KOTLIN_DD_CLASS = "kntr.base.dd.IDeviceDecisionKt"
        private const val BLCONFIG_PACKAGE = "com.bilibili.lib.blconfig"
        private const val BLCONFIG_INTERNAL_PACKAGE = "$BLCONFIG_PACKAGE.internal"
        private const val CONFIG_MANAGER_CLASS = "$BLCONFIG_PACKAGE.ConfigManager"
        private const val CONFIG_MANAGER_COMPANION_CLASS = "$CONFIG_MANAGER_CLASS\$Companion"
        private const val TYPED_CONTRACT_CLASS = "$BLCONFIG_INTERNAL_PACKAGE.TypedContract"
        private const val FEATURE_FLAG_CONTRACT_CLASS = "$BLCONFIG_PACKAGE.FeatureFlagContract"
        private val CONTRACT_CLASSES = listOf(
            "$BLCONFIG_INTERNAL_PACKAGE.OverrideFF",
            "$BLCONFIG_INTERNAL_PACKAGE.OverrideConfig",
            TYPED_CONTRACT_CLASS
        )

        private const val FAMILY_DD = "dd"
        private const val FAMILY_DD_IMPL = "dd-impl"
        private const val FAMILY_KOTLIN_DD = "kotlin-dd"
        private const val FAMILY_CONFIG = "config"
        private const val FAMILY_CONTRACT = "contract"
        private const val FAMILY_CONTRACT_FF = "contract-ff"

        /** R8 混淆后的单字母叶名，`a`–`z` 与 `A`–`Z`。 */
        private val LEAF_NAMES: List<String> =
            ('a'..'z').map(Char::toString) + ('A'..'Z').map(Char::toString)
    }
}
