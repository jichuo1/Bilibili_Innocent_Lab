package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isAbstract
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isPublic
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/** Resolve typed page-delivery boundaries, never global JSON/LiveData or numeric view types. */
internal object SearchHomeRecommendLocator {
    const val SQUARE = "com.bilibili.search2.api.SearchSquareType"
    const val GUESS = "com.bilibili.search2.api.SearchReferral\$Guess"
    const val DISCOVER = "com.bilibili.search2.discover."
    const val STATE = "com.bilibili.search2.main.data."

    /** 一级候选：宿主 discover 包的稳定短名（R8 混淆池 a-z + 未混淆的 ViewModel 名）。 */
    private val ROOT_NAMES: List<String> =
        ('a'..'z').map { DISCOVER + it } + DISCOVER.plus("SearchDiscoverViewModel")

    /**
     * 二级后缀：投递宿主是外层类的匿名内部类时，只能按二进制名枚举后加载。
     * 实测形态为单字母（`discover.r$a`、`SearchDiscoverViewModel$a`）；数字后缀覆盖
     * Java 编译器风格的 `$1`。同一外层类的嵌套类超过 26 个时 R8 才会产出双字符后缀，
     * 27 个宿主样本均未出现；真出现时由静态探测脚本的双口径对拍暴露。
     *
     * 代价：约 20 个根类 × 36 个后缀 ≈ 700 次类加载探测，其中绝大多数未命中并抛
     * ClassNotFoundException。每个宿主进程只在安装期执行一次，且结果进入类缓存；
     * quickLocate 快路径与 Hook 回调都不会触发这里。
     */
    private val NESTED_SUFFIXES: List<String> =
        ('a'..'z').map { it.toString() } + ('0'..'9').map { it.toString() }

    data class Delivery(val method: Method, val refresh: Method?, val owner: Field, val sections: Field,
        val cache: DiscoveryCache?)
    data class DiscoveryCache(val field: Field, val read: Method, val write: Method,
        val values: Field, val feedback: Field, val constructor: Constructor<*>)
    data class StatePoint(val constructor: Constructor<*>, val sections: Field)
    data class Points(val square: Class<*>, val type: Method, val delivery: Delivery?, val state: StatePoint?,
        val stateExpected: Boolean,
        /** 投递候选数，仅用于区分「宿主没有该结构」与「候选歧义」，不参与功能判定。 */
        val deliveryCandidateCount: Int = 0)

    fun locate(loader: ClassLoader, cached: PageBoundaryPoints? = null): Points? = runCatching {
        val square = Lookup.classOrNull(loader, SQUARE) ?: return null
        val type = Lookup.methodOrNull(square, "getType")
            ?.takeIf { !it.isStatic && it.returnType == classOf<String>() } ?: return null
        val roots = ROOT_NAMES.mapNotNull { Lookup.classOrNull(loader, it) }

        // 投递宿主是页面 ViewModel 里的回调实现：可能是顶级类（8.97.0+，即根类自身），也可能是
        // 匿名内部类（8.84.0–8.96.0 实测为 discover.r$a / SearchDiscoverViewModel$a）。
        // 匿名内部类取不到：Android 平台不存在 dalvik.annotation.InnerClasses 注解，匿名类也不
        // 进入外层类的 MemberClasses 注解，因此 Class.getDeclaredClasses() 对它稳定返回空，
        // 只能按 Outer$Suffix 有界枚举后加载。
        //
        // 不做「先枚举少量外层类、命中即返回」的短路：短路只在候选分布与预期完全一致时才等价，
        // 一旦宿主多出别的候选，它既可能漏掉真正的投递宿主，也可能让安装落到错误的候选上。
        // 枚举范围必须与候选集口径保持一致。
        val enumerated = roots.flatMap { root ->
            NESTED_SUFFIXES.mapNotNull { Lookup.classOrNull(loader, root.name + '$' + it) }
        }
        // declaredClasses 零成本，作为命名成员类的补充；它与后缀枚举会重叠，必须去重，
        // 否则同一个 Class 出现两次会被 singleOrNull 误判成候选歧义。
        val declared = roots.flatMap { root ->
            runCatching { root.declaredClasses.toList() }.getOrDefault(emptyList())
        }
        val cachedDelivery = cached?.searchDelivery?.takeIf { it.startsWith(DISCOVER) }
            ?.let { Lookup.classOrNull(loader, it) }
        val callbacks = (roots + enumerated + declared + listOfNotNull(cachedDelivery)).distinct()
            .filter { !it.isInterface && !it.isAbstract }
            .mapNotNull(::delivery)

        val states = (('a'..'z').mapNotNull { Lookup.classOrNull(loader, STATE + it) } +
            listOfNotNull(cached?.searchState?.takeIf { it.startsWith(STATE) }?.let { Lookup.classOrNull(loader, it) })).distinct()
            .filter { !it.isInterface && !it.isAbstract }.mapNotNull(::state)
        Points(square, type, callbacks.singleOrNull(), states.singleOrNull(),
            Lookup.classOrNull(loader, "com.bilibili.search2.main.MainSearchViewModel") != null,
            callbacks.size)
    }.getOrNull()

    internal fun delivery(owner: Class<*>): Delivery? = runCatching {
        val history = Lookup.methodOrNull(owner, "getHistoryList")
            ?.takeIf { !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<List<*>>() } ?: return null
        if (history.isAbstract) return null
        val members = Lookup.declaredMethods(owner, true) {
            !it.isStatic && !it.isAbstract && it.returnType == Void.TYPE &&
                it.parameterCount == 1 && it.parameterTypes[0] == classOf<List<*>>()
        }
        val receive = members.filter { typedList(it.genericParameterTypes[0], SQUARE) }.singleOrNull() ?: return null
        val refresh = members.filter { typedList(it.genericParameterTypes[0], GUESS) }.singleOrNull()
        val carriers = Lookup.declaredFields(owner, true) {
            !it.isStatic && it.type.name.startsWith(DISCOVER)
        }.mapNotNull { field -> squareField(field.type)?.let { field to it } }
        val carrier = carriers.singleOrNull() ?: return null
        Delivery(receive, refresh, carrier.first, carrier.second, discoveryCache(carrier.first.type))
    }.getOrNull()

    internal fun state(owner: Class<*>): StatePoint? = runCatching {
        val field = squareField(owner) ?: return null
        val ctor = Lookup.declaredConstructors(owner, true) {
            it.parameterTypes.contentEquals(arrayOf(classOf<List<*>>())) &&
                typedList(it.genericParameterTypes[0], SQUARE)
        }.singleOrNull() ?: return null
        StatePoint(ctor, field)
    }.getOrNull()

    private fun squareField(owner: Class<*>): Field? = Lookup.declaredFields(owner, true) {
        !it.isStatic && it.type == classOf<List<*>>() && typedList(it.genericType, SQUARE)
    }.singleOrNull()

    private fun discoveryCache(owner: Class<*>): DiscoveryCache? = Lookup.declaredFields(owner, true) {
        !it.isStatic
    }.mapNotNull { field -> runCatching {
        // R8 widens LiveData.setValue to public in some hosts. Its read-only alias is
        // still not a separate mutable carrier; counting it would make the pair ambiguous.
        if (!writableObservable(field.type)) return@runCatching null
        val generic = field.genericType as? ParameterizedType ?: return@runCatching null
        val payload = generic.actualTypeArguments.singleOrNull() as? Class<*> ?: return@runCatching null
        if (!payload.name.startsWith(DISCOVER)) return@runCatching null
        val values = Lookup.declaredFields(payload, true) { !it.isStatic && typedList(it.genericType, GUESS) }.singleOrNull()
            ?: return@runCatching null
        val feedback = Lookup.declaredFields(payload, true) {
            !it.isStatic && it.type.name == "com.bilibili.search2.api.NegativeFeedback"
        }.singleOrNull() ?: return@runCatching null
        val ctor = Lookup.declaredConstructors(payload, true) {
            val types = it.parameterTypes
            types.size in setOf(3,5) && types[0] == classOf<List<*>>() && types[1] == classOf<String>() &&
                types[2] == feedback.type && types.drop(3).all { t -> t == classOf<Long>() }
        }.singleOrNull() ?: return@runCatching null
        val read = Lookup.inheritedMethodOrNull(field.type, "getValue")
            ?.takeIf { !it.isStatic && it.isPublic } ?: return@runCatching null
        val write = Lookup.inheritedMethodOrNull(field.type, "setValue", classOf<Any>())
            ?.takeIf { !it.isStatic && it.isPublic && it.returnType == Void.TYPE } ?: return@runCatching null
        DiscoveryCache(field,read,write,values,feedback,ctor)
    }.getOrNull() }.singleOrNull()

    internal fun writableObservable(type: Class<*>): Boolean = type.name != "androidx.lifecycle.LiveData"

    internal fun typedList(type: Type, element: String): Boolean {
        val list = type as? ParameterizedType ?: return false
        if (list.rawType != classOf<List<*>>() || list.actualTypeArguments.size != 1) return false
        val arg = list.actualTypeArguments[0]
        return when (arg) {
            is Class<*> -> arg.name == element
            is WildcardType -> arg.lowerBounds.isEmpty() && (arg.upperBounds.singleOrNull() as? Class<*>)?.name == element
            else -> false
        }
    }
}
