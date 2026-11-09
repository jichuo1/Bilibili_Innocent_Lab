package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.os.Bundle
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal interface GuestFollowCountSource {
    data class Snapshot(val count: Int, val token: Any)
    fun observe(callback: (Snapshot) -> Unit)
    fun read(callback: (Snapshot) -> Unit)
    fun accepts(snapshot: Snapshot): Boolean
}

private class LibraryFollowCountSource(private val library: GuestLibraryRuntime) : GuestFollowCountSource {
    private fun snapshot(collections: LocalVirtualLibrary.Collections): GuestFollowCountSource.Snapshot? =
        library.currentToken()?.let { GuestFollowCountSource.Snapshot(collections.follows.size, it) }

    override fun observe(callback: (GuestFollowCountSource.Snapshot) -> Unit) {
        library.observeCollections { snapshot(it)?.let(callback) }
    }

    override fun read(callback: (GuestFollowCountSource.Snapshot) -> Unit) {
        library.readCollections { snapshot(it)?.let(callback) }
    }

    override fun accepts(snapshot: GuestFollowCountSource.Snapshot): Boolean =
        (snapshot.token as? GuestLibraryRuntime.Token)?.let(library::accepts) == true
}

/** Keeps the host's Compose page state reactive without retaining its Fragment, Activity or View. */
internal class GuestMineFollowCount(
    private val source: GuestFollowCountSource,
    private val isGuest: () -> Boolean,
    private val names: Names = Names()
) {
    constructor(library: GuestLibraryRuntime, isGuest: () -> Boolean) :
        this(LibraryFollowCountSource(library), isGuest)

    internal data class Names(
        val fragment: String = "tv.danmaku.bili.ui.main2.mineV2.HomeUserCenterFragmentV2",
        val viewModel: String = "tv.danmaku.bili.ui.main2.mineV2.MineUserCenterViewModelV2",
        val account: String = "tv.danmaku.bili.ui.main2.api.AccountMineV2",
        val flow: String = "kotlinx.coroutines.flow.MutableStateFlow"
    )

    fun install(env: HookEnvironment) {
        val loader = requireNotNull(env.classLoader)
        val fragment = requireNotNull(Lookup.classOrNull(loader, names.fragment))
        val viewModel = requireNotNull(Lookup.classOrNull(loader, names.viewModel))
        val account = requireNotNull(Lookup.classOrNull(loader, names.account))
        val flowContract = requireNotNull(Lookup.classOrNull(loader, names.flow))
        val getModel = requireNotNull(Lookup.declaredMethods(fragment, makeAccessible = true) {
            !it.isStatic && it.parameterCount == 0 && it.returnType == viewModel
        }.singleOrNull())
        val created = requireNotNull(Lookup.methodOrNull(fragment, "onViewCreated", classOf<View>(), classOf<Bundle>()))
        val selected = Lookup.methodOrNull(fragment, "onPageSelected", classOf<Map<*, *>>())
        val getValue = requireNotNull(Lookup.methodOrNull(flowContract, "getValue"))
        val compareAndSet = requireNotNull(Lookup.methodOrNull(flowContract, "compareAndSet", classOf<Any>(), classOf<Any>()))
            .takeIf { it.returnType == classOf<Boolean>() } ?: error("StateFlow.compareAndSet shape")
        val flowFields = Lookup.declaredFields(viewModel, makeAccessible = true) {
            !it.isStatic && flowContract.isAssignableFrom(it.type)
        }
        require(flowFields.isNotEmpty())
        val accountPlan = requireNotNull(GuestMineFollowStateAdapter.accountPlan(account))
        val lock = Any()
        val flows = WeakHashMap<Any, GuestMineFollowStateAdapter>()
        val setters = mutableSetOf<Method>()
        val stateTypes = ConcurrentHashMap.newKeySet<Class<*>>()
        val applied = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val bindingUnavailable = AtomicBoolean(false)
        val copyUnavailable = AtomicBoolean(false)
        var latest: GuestFollowCountSource.Snapshot? = null

        fun reportFailure() {
            if (failed.compareAndSet(false, true)) {
                env.reportRuntimeEvidence("guest_browsing", FeatureRuntimeStage.ERROR)
                runCatching { env.reportStatus("guest_mine_follow_count_error", "unavailable") }
            }
        }
        fun reportUnavailable(flag: AtomicBoolean, key: String) {
            if (flag.compareAndSet(false, true)) runCatching { env.reportStatus(key, "unavailable") }
        }
        fun guest(): Boolean = runCatching(isGuest).getOrDefault(false)
        fun valid(snapshot: GuestFollowCountSource.Snapshot): Boolean =
            guest() && runCatching { source.accepts(snapshot) }.getOrDefault(false)
        fun reportApplied() {
            if (applied.compareAndSet(false, true)) runCatching { env.reportStatus("guest_mine_follow_count_applied", "seen") }
        }
        fun update(flow: Any, adapter: GuestMineFollowStateAdapter, snapshot: GuestFollowCountSource.Snapshot) {
            repeat(3) {
                if (!valid(snapshot)) return
                val old = getValue.invoke(flow) ?: return
                val replacement = adapter.rewrite(old, snapshot.count) ?: run {
                    reportUnavailable(copyUnavailable, "guest_mine_follow_count_copy")
                    return
                }
                if (replacement === old || !valid(snapshot)) return
                if (compareAndSet.invoke(flow, old, replacement) == true) {
                    reportApplied()
                    return
                }
            }
        }
        fun publish(snapshot: GuestFollowCountSource.Snapshot) {
            if (!valid(snapshot)) return
            synchronized(lock) { latest = snapshot }
            val active = synchronized(lock) { flows.entries.map { it.key to it.value } }
            active.forEach { (flow, adapter) -> runCatching { update(flow, adapter, snapshot) }.onFailure { reportFailure() } }
        }
        fun bind(target: Any?) {
            if (target == null || !guest()) return
            val model = getModel.invoke(target) ?: return
            val candidates = flowFields.mapNotNull { field ->
                val flow = field.get(model) ?: return@mapNotNull null
                val state = getValue.invoke(flow) ?: return@mapNotNull null
                GuestMineFollowStateAdapter.resolve(state.javaClass, accountPlan)?.let { flow to it }
            }
            val (flow, adapter) = candidates.singleOrNull() ?: run {
                reportUnavailable(bindingUnavailable, "guest_mine_follow_count_binding")
                return
            }
            val setter = requireNotNull(Lookup.inheritedMethodOrNull(flow.javaClass, "setValue", classOf<Any>()))
                .takeIf { !it.isStatic && it.returnType == Void.TYPE } ?: error("StateFlow.setValue shape")
            synchronized(lock) {
                if (setter !in setters) {
                    env.registrar.exact("guest.mine.follow_count.flow.${setter.declaringClass.name}",
                        setter.declaringClass, setter.name, *setter.parameterTypes) {
                        before {
                            val state = argOrNull(0) ?: return@before
                            if (state.javaClass !in stateTypes) return@before
                            val targetFlow = instance ?: return@before
                            val targetAdapter = synchronized(lock) { flows[targetFlow] } ?: return@before
                            val snapshot = synchronized(lock) { latest } ?: return@before
                            if (!valid(snapshot)) return@before
                            val replacement = targetAdapter.rewrite(state, snapshot.count) ?: run {
                                reportUnavailable(copyUnavailable, "guest_mine_follow_count_copy")
                                return@before
                            }
                            if (replacement !== state && valid(snapshot)) {
                                args[0] = replacement
                                reportApplied()
                            }
                        }
                    }
                    setters += setter
                }
                stateTypes += adapter.stateType
                flows[flow] = adapter
            }
            runCatching { env.reportStatus("guest_mine_follow_count_flow", "installed") }
            source.read(::publish)
        }
        source.observe(::publish)
        env.registrar.exact("guest.mine.follow_count.created", fragment, created.name, *created.parameterTypes) {
            after { if (!hasThrowable) runCatching { bind(instance) }.onFailure { reportFailure() } }
        }
        if (selected != null) env.registrar.exact("guest.mine.follow_count.selected", fragment, selected.name, *selected.parameterTypes) {
            after { if (!hasThrowable) runCatching { bind(instance) }.onFailure { reportFailure() } }
        }
    }
}

/** Copies only verified host data-class shapes; the original account and page state stay untouched. */
internal class GuestMineFollowStateAdapter private constructor(
    val stateType: Class<*>,
    private val stateFields: List<Field>,
    private val accountField: Field,
    private val stateCopy: Method,
    private val maskIndex: Int,
    private val account: AccountPlan
) {
    internal data class AccountPlan(
        val type: Class<*>,
        val fields: List<Field>,
        val following: Field,
        val copy: Method,
        val lists: List<Field>,
        val defaultMasks: Int
    )

    fun rewrite(state: Any, count: Int): Any? = runCatching {
        require(state.javaClass == stateType && count >= 0)
        val original = accountField.get(state) ?: return@runCatching state
        if (account.following.getLong(original) == count.toLong()) return@runCatching state
        val before = account.following.getLong(original)
        val cloned = if (account.defaultMasks > 0) {
            val args = account.copy.parameterTypes.map(::defaultValue).toTypedArray()
            args[0] = original
            for (index in args.lastIndex - account.defaultMasks until args.lastIndex) args[index] = -1
            account.copy.invoke(null, *args)?.takeIf { it !== original && sameFields(account.fields, original, it) }
        } else account.lists.firstNotNullOfOrNull { field ->
            runCatching { account.copy.invoke(null, original, field.get(original)) }.getOrNull()
                ?.takeIf { it !== original && sameFields(account.fields, original, it) }
        }
        if (cloned == null) return@runCatching null
        account.following.setLong(cloned, count.toLong())
        require(account.following.getLong(original) == before && account.following.getLong(cloned) == count.toLong())
        require(sameFields(account.fields, original, cloned, account.following))
        val arguments = stateCopy.parameterTypes.map(::defaultValue).toTypedArray()
        arguments[0] = state
        arguments[1] = cloned
        arguments[maskIndex] = -2 // Preserve every original property except the first account property.
        val copied = stateCopy.invoke(null, *arguments) ?: return@runCatching null
        require(copied !== state && accountField.get(copied) === cloned && sameFields(stateFields, state, copied, accountField))
        copied
    }.getOrNull()

    internal companion object {
        fun accountPlan(type: Class<*>): AccountPlan? = runCatching {
            val fields = Lookup.declaredFields(type, makeAccessible = true) { !it.isStatic }
            val following = requireNotNull(Lookup.fieldOrNull(type, "s"))
                .takeIf { !it.isStatic && it.type == classOf<Long>() } ?: return@runCatching null
            val lists = fields.filter { classOf<List<*>>().isAssignableFrom(it.type) }
            val methods = Lookup.declaredMethods(type, makeAccessible = true) { it.isStatic && it.returnType == type }
            val compact = methods.singleOrNull { it.parameterTypes.toList() == listOf(type, classOf<List<*>>()) }
            if (compact != null && lists.isNotEmpty()) return@runCatching AccountPlan(type, fields, following, compact, lists, 0)

            // Older Kotlin data classes retain copy$default with one mask per 32 properties. With all bits set,
            // every argument comes from the original. Verify the resulting fields before changing the copied count.
            val masks = (fields.size + 31) / 32
            val fieldTypes = fields.groupingBy { it.type }.eachCount()
            val defaultCopy = methods.singleOrNull { method ->
                val p = method.parameterTypes
                method.isSynthetic && masks > 0 && p.size == fields.size + masks + 2 && p[0] == type &&
                    p.last() == classOf<Any>() &&
                    p.slice(p.lastIndex - masks until p.lastIndex).all { it == classOf<Int>() } &&
                    p.slice(1..fields.size).groupingBy { it }.eachCount() == fieldTypes
            } ?: return@runCatching null
            AccountPlan(type, fields, following, defaultCopy, emptyList(), masks)
        }.getOrNull()

        fun resolve(type: Class<*>, account: AccountPlan): GuestMineFollowStateAdapter? = runCatching {
            val fields = Lookup.declaredFields(type, makeAccessible = true) { !it.isStatic }
            if (fields.size > 30) return@runCatching null // More than one Kotlin copy mask is not supported.
            val accountField = fields.filter { it.type == account.type }.singleOrNull() ?: return@runCatching null
            val copies = Lookup.declaredMethods(type, makeAccessible = true) { method ->
                val p = method.parameterTypes
                method.isStatic && method.returnType == type && p.size >= 3 && p[0] == type && p[1] == account.type &&
                    (p.last() == classOf<Int>() || (p.size >= 4 && p.last() == classOf<Any>() && p[p.lastIndex - 1] == classOf<Int>()))
            }
            val copy = copies.singleOrNull() ?: return@runCatching null
            val p = copy.parameterTypes
            val mask = if (p.last() == classOf<Int>()) p.lastIndex else p.lastIndex - 1
            GuestMineFollowStateAdapter(type, fields, accountField, copy, mask, account)
        }.getOrNull()

        private fun sameFields(fields: List<Field>, first: Any, second: Any, except: Field? = null): Boolean = fields.all { field ->
            field == except || if (field.type.isPrimitive) field.get(first) == field.get(second) else field.get(first) === field.get(second)
        }

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }
}
