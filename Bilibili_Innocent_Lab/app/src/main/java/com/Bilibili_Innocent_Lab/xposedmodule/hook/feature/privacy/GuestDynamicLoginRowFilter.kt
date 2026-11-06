package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookParam
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 动态页（关注 Tab）未登录视图的登录引导条：列表数据层过滤。
 *
 * 三版反编译（8.90.2 / 9.0.1 / 9.16.1，captures/compat-incognito/DynamicNotLogin*）确认两代宿主同构：
 * `DynamicNotLoginViewModel$doRequest$2` 每次刷新都以 `mutableListOf(new 登录item)` 组装列表头部，
 * 网络结果 `addAll` 追加，最后对数据 `MediatorLiveData` 直接 `setValue((list, state))`；
 * 观察者把同一个 list 引用交给 adapter。登录引导 item 自身形状稳定：
 * 无参构造器 + 恰好一个自身声明的 boolean 字段（8.90.2 `model.d5`、9.16.1 `xS.H1`，类名逐版混淆）。
 *
 * 本钩不依赖任何混淆名：
 * 1. 挂在 AndroidX 稳定类 `androidx.lifecycle.LiveData.setValue` 上，在观察者收到值之前改写列表；
 * 2. 只认证「值类恰好两个实例字段，且运行时非列表的那个字段（state）位于
 *    `com.bilibili.bplus.followinglist.page.nologin` 包、其类层级声明了 `DataStatus` 字段」的
 *    LiveData 实例（`DynamicNotLoginViewModel` 及其 state 类的包名在 38 版稳定）；
 *    `DataStatus` 字段可能声明在 state 的父类上（9.16.1 的 `nologin.g` 继承声明该字段的
 *    `Qi.b`），故按整条继承链枚举，不能只扫运行时类的 `getDeclaredFields`；
 * 3. 首个认证值从列表头部元素学习登录 item 类，形状（无参构造 + 单 boolean 自有字段）不符则不学不滤；
 * 4. 游客态 `removeIf`；非游客、未学习或读取失败时放行宿主原值。
 *
 * 失败模式全部干净：未认证的 LiveData 不受影响；学习不到时该页保持宿主原样。
 */
internal class GuestDynamicLoginRowFilter(
    private val enabled: Boolean,
    private val isGuest: () -> Boolean,
    private val liveDataClass: Class<*>?
) {
    private val verifiedLiveDatas = java.util.WeakHashMap<Any, Boolean>()
    private val pairShapeCache = ConcurrentHashMap<Class<*>, PairValueShape?>()
    private val stateShapeCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val loginItemShapeCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val guestStateReported = java.util.concurrent.atomic.AtomicBoolean(false)
    private var learnedItemClass: Class<*>? = null

    private data class PairValueShape(val fields: List<Field>)

    fun install(environment: HookEnvironment): Boolean {
        val owner = liveDataClass ?: return false
        val setValue = Lookup.methodOrNull(owner, "setValue", Any::class.java) ?: return false
        val registered = runCatching {
            environment.registrar.exact(
                "${GuestBrowsingFeatureInstaller.ID}.dynamic-login-row-data",
                setValue.declaringClass, "setValue", Any::class.java
            ) {
                before { onSetValue(environment, this) }
            }
        }.isSuccess
        if (registered) {
            environment.reportStatus("guest_browsing_dynamic_login_row", "installed")
        }
        return registered
    }

    /**
     * [candidate] 是只看入参的廉价预筛（进程内所有 LiveData.setValue 都会经过这里），
     * 任何反射读取失败都按放行处理，不影响宿主发布。
     */
    private fun onSetValue(environment: HookEnvironment, param: ModernHookParam) {
        if (!enabled) return
        val liveData = param.instance ?: return
        val value = param.argOrNull(0) ?: return
        runCatching {
            if (verifiedLiveDatas.containsKey(liveData)) {
                filterLearnedRows(environment, value)
                return
            }
            if (!looksLikePairValue(value)) return
            if (!stateBelongsToNotLoginPage(value)) return
            verifiedLiveDatas[liveData] = true
            filterLearnedRows(environment, value)
        }
    }

    /** 值类形状：恰好两个自身声明的实例字段（泛型列表 + state），按类缓存。 */
    private fun looksLikePairValue(value: Any): Boolean =
        pairShapeOf(value.javaClass) != null

    private fun pairShapeOf(type: Class<*>): PairValueShape? = pairShapeCache.computeIfAbsent(type) {
        val fields = Lookup.declaredFields(it, makeAccessible = true) { field ->
            !Modifier.isStatic(field.modifiers) && !field.isSynthetic
        }
        if (fields.size != 2) null else PairValueShape(fields)
    }

    /**
     * state 认证：非列表字段（构造时传入的 state）的运行时类在 nologin 包内，且其类层级
     * 声明了 `DataStatus` 字段。DataStatus 与 nologin 包名在 38 版归档样本均稳定存在。
     *
     * 字段按整条继承链枚举：8.90.2/9.0.1 的 nologin state 自身声明 `DataStatus`，
     * 而 9.16.1 的结果 state `nologin.g` 只声明一个 boolean，`DataStatus` 在父类 `Qi.b`
     * 上；只扫 `getDeclaredFields` 会漏判后者。`makeAccessible = false`：这里只读字段类型，
     * 不取值，无需开放访问。
     */
    private fun stateBelongsToNotLoginPage(value: Any): Boolean {
        val shape = pairShapeOf(value.javaClass) ?: return false
        val state = shape.fields.asSequence()
            .mapNotNull { runCatching { it.get(value) }.getOrNull() }
            .filterNotNull()
            .firstOrNull { it !is List<*> } ?: return false
        return stateShapeCache.computeIfAbsent(state.javaClass) { type ->
            type.name.startsWith(NOLOGIN_PREFIX) &&
                Lookup.fields(type, includeSuperclasses = true) { field ->
                    field.type.name == DATA_STATUS
                }.isNotEmpty()
        }
    }

    private fun filterLearnedRows(environment: HookEnvironment, value: Any) {
        val guest = runCatching { isGuest() }.getOrElse {
            if (guestStateReported.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(
                    GuestBrowsingFeatureInstaller.ID, FeatureRuntimeStage.ERROR
                )
                runCatching {
                    environment.logError(
                        "${GuestBrowsingFeatureInstaller.ID}_dynamic-login-row-guest-state",
                        "[BIL] 动态登录条过滤读取游客状态失败，已放行宿主: $it"
                    )
                }
            }
            return
        }
        if (!guest) return
        val list = currentValueList(value) ?: return
        val target = learnedItemClass ?: learnFromHead(list, environment) ?: return
        val removed = list.removeAll { it?.javaClass == target }
        if (removed) {
            environment.reportStatus("guest_browsing_dynamic_login_row_applied", "seen")
            environment.reportRuntimeEvidence(
                GuestBrowsingFeatureInstaller.ID, FeatureRuntimeStage.APPLIED
            )
        }
    }

    /** 列表字段：两个实例字段中运行时值为 [List] 的那个；不可变或缺失时跳过。 */
    private fun currentValueList(value: Any): MutableList<*>? {
        val shape = pairShapeOf(value.javaClass) ?: return null
        val list = shape.fields.asSequence()
            .mapNotNull { runCatching { it.get(value) }.getOrNull() }
            .filterIsInstance<List<*>>()
            .firstOrNull() ?: return null
        return list as? MutableList<*> ?: null
    }

    /**
     * 从列表头部学习登录 item 类。`doRequest$2` 刷新时头部必是刚 new 的登录 item；
     * 头元素形状不符（例如提取器返回的旧列表首元素是推荐流 item）则本次不学，等待下一个值。
     */
    private fun learnFromHead(list: List<*>, environment: HookEnvironment): Class<*>? {
        val head = list.firstOrNull() ?: return null
        val type = head.javaClass
        if (!loginItemShapeCache.computeIfAbsent(type, ::matchesLoginItemShape)) return null
        learnedItemClass = type
        environment.reportStatus("guest_browsing_dynamic_login_row_learned", "seen")
        return type
    }

    /** 登录 item 形状：恰好一个自身声明的 boolean 实例字段 + 存在无参构造器。 */
    private fun matchesLoginItemShape(type: Class<*>): Boolean {
        val fields = Lookup.declaredFields(type, makeAccessible = true) { field ->
            !Modifier.isStatic(field.modifiers) && !field.isSynthetic
        }
        if (fields.size != 1 || fields[0].type != java.lang.Boolean.TYPE) return false
        return Lookup.declaredConstructors(type, makeAccessible = true) {
            it.parameterCount == 0 && !it.isSynthetic
        }.isNotEmpty()
    }

    private companion object {
        private const val NOLOGIN_PREFIX = "com.bilibili.bplus.followinglist.page.nologin."
        private const val DATA_STATUS = "com.bilibili.app.comm.list.common.data.DataStatus"
    }
}
