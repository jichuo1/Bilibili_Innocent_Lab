package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.view.MotionEvent
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/** 安装期只解析稳定业务类及其结构；混淆的服务 owner、字段名不进入候选常量。 */
internal object PlayerSpeedLocator {
    const val LONG_PRESS_LISTENER =
        "com.bilibili.ship.theseus.united.player.TripleSpeedService\$runOldTripleSpeed\$1\$listener\$1"
    const val LONG_PRESS_COROUTINE =
        "com.bilibili.ship.theseus.united.player.TripleSpeedService\$runOldTripleSpeed\$1\$listener\$1\$onLongPress\$1"
    private const val THESEUS2_SPEED_SERVICE = "com.bilibili.ship.theseus2.united.player.TripleSpeedService"
    const val SPEED_MANAGER = "com.bilibili.player.tangram.basic.PlaySpeedManagerImpl"
    const val MUTABLE_FLOW = "kotlinx.coroutines.flow.MutableStateFlow"
    const val STATE_FLOW = "kotlinx.coroutines.flow.StateFlow"
    const val THESEUS_SPEED_WIDGET = "com.bilibili.ship.theseus.united.page.speed.TheseusSpeedFunctionWidget"
    const val MEDIA_RESOURCE = "com.bilibili.lib.media.resource.MediaResource"
    const val RECYCLER_ADAPTER = "androidx.recyclerview.widget.RecyclerView\$Adapter"
    const val SPEED_WIDGET_PKG = "com.bilibili.playerbizcommonv2.widget.speed"
    const val SPEED_UTILS_PKG = "com.bilibili.playerbizcommonv2.utils"
    const val THESEUS_SPEED_PKG = "com.bilibili.ship.theseus.united.page.speed"

    data class LongPressSpeedPoint(val constructor: Constructor<*>, val speedIndex: Int, val speedField: Field)
    data class DefaultSpeedPoint(
        val constructor: Constructor<*>,
        val flows: List<Field>,
        val readValue: Method,
        val writeValue: Method,
        val speedGetters: List<Method>
    )
    data class SpeedMenuFactoryPoint(
        val factoryMethod: Method,
        val optionConstructor: Constructor<*>,
        val optionSpeedField: Field,
        val optionAvailableField: Field
    )
    data class SpeedMenuAdapterPoint(
        val adapterClass: Class<*>,
        val populateMethod: Method,
        val itemsField: Field,
        val itemSpeedField: Field,
        val itemSelectedField: Field,
        val itemAvailableField: Field? = null,
        val speedsArrayField: Field? = null,
        val optionConstructor: Constructor<*>? = null,
        val optionSpeedField: Field? = null,
        val optionAvailableField: Field? = null
    )
    data class SpeedMenuPoint(
        val factory: SpeedMenuFactoryPoint?,
        val adapter: SpeedMenuAdapterPoint?,
        val compactRoute: SpeedMenuCompactRoutePoint? = null
    )
    data class SpeedMenuCompactRoutePoint(val openMethod: Method, val windowDelegateGetter: Method)

    fun longPress(loader: ClassLoader): Method? {
        val owner = KavaMemberLookup.classOrNull(loader, LONG_PRESS_LISTENER) ?: return null
        return KavaMemberLookup.methodOrNull(owner, "onLongPress", classOf<MotionEvent>())
            ?.takeIf { !it.isStatic && it.returnType == classOf<Boolean>() }
    }

    fun theseus2LongPressService(loader: ClassLoader): Class<*>? = KavaMemberLookup.classOrNull(loader, THESEUS2_SPEED_SERVICE)

    fun theseus2LongPress(loader: ClassLoader, service: Class<*>): Method? {
        val launch = KavaMemberLookup.classOrNull(loader, "${service.name}\$beginTemporarySpeed\$1") ?: return null
        val continuation = KavaMemberLookup.classOrNull(loader, "kotlin.coroutines.Continuation") ?: return null
        val candidates = (service.declaredClasses.asList() + listOfNotNull(KavaMemberLookup.classOrNull(loader, "${service.name}\$a")))
            .distinct().filter { it.name.startsWith("${service.name}\$") }
        return theseus2LongPress(service, launch, continuation, candidates)
    }

    internal fun theseus2LongPress(service: Class<*>, launch: Class<*>, continuation: Class<*>,
                                   candidates: List<Class<*>>): Method? {
        val constructors = KavaMemberLookup.declaredConstructors(launch) {
            it.parameterTypes.contentEquals(arrayOf(service, Float::class.javaPrimitiveType, Any::class.java, continuation))
        }
        if (constructors.size != 1) return null
        return candidates.mapNotNull { port ->
            if (port === launch || !Modifier.isFinal(port.modifiers) ||
                KavaMemberLookup.declaredConstructors(port) { it.parameterTypes.contentEquals(arrayOf(service)) }.size != 1 ||
                KavaMemberLookup.declaredFields(port) { !it.isStatic && it.type === service }.size != 1) return@mapNotNull null
            KavaMemberLookup.declaredMethods(port, makeAccessible = true) {
                !it.isStatic && !it.isBridge && !Modifier.isAbstract(it.modifiers) && it.parameterCount == 0 &&
                    it.returnType == Float::class.javaObjectType
            }.singleOrNull()
        }.singleOrNull()
    }

    /** Exact APK builds where the start port passes this single result to both playback and UI. */
    fun theseus2LongPressCalculator(loader: ClassLoader, hostVersionCode: Int): Method? {
        val (ownerName, playableName) = when (hostVersionCode) {
            9180100, 9180300 -> "UC0.a" to "com.bilibili.ship.theseus2.keel.player.m"
            9180200 -> "UC0.a" to "com.bilibili.ship.theseus2.keel.player.n"
            9190200, 9190400 -> "rv71" to "gcg2"
            else -> return null
        }
        val owner = KavaMemberLookup.classOrNull(loader, ownerName) ?: return null
        val playable = KavaMemberLookup.classOrNull(loader, playableName)?.takeIf { it.isInterface } ?: return null
        return KavaMemberLookup.methodOrNull(owner, "a", playable, Float::class.javaObjectType)?.takeIf {
            it.declaringClass === owner && it.isStatic && Modifier.isFinal(it.modifiers) &&
                !it.isSynthetic && !it.isBridge && it.returnType == classOf<Float>()
        }
    }

    fun longPressSpeed(loader: ClassLoader): LongPressSpeedPoint? {
        val owner = KavaMemberLookup.classOrNull(loader, LONG_PRESS_COROUTINE) ?: return null
        val continuation = KavaMemberLookup.classOrNull(loader, "kotlin.coroutines.Continuation") ?: return null
        val speed = KavaMemberLookup.fieldOrNull(owner, "\$speed")
            ?.takeIf { !it.isStatic && it.type == classOf<Float>() } ?: return null
        val constructor = KavaMemberLookup.declaredConstructors(owner, makeAccessible = true) {
            it.parameterTypes.count { type -> type == classOf<Float>() } == 1 &&
                it.parameterTypes.lastOrNull() == continuation
        }.singleOrNull() ?: return null
        return LongPressSpeedPoint(constructor, constructor.parameterTypes.indexOf(classOf<Float>()), speed)
    }

    fun defaultSpeed(loader: ClassLoader): DefaultSpeedPoint? {
        val owner = KavaMemberLookup.classOrNull(loader, SPEED_MANAGER) ?: return null
        val mutableFlow = KavaMemberLookup.classOrNull(loader, MUTABLE_FLOW) ?: return null
        val stateFlow = KavaMemberLookup.classOrNull(loader, STATE_FLOW) ?: return null
        return defaultSpeed(owner, mutableFlow, stateFlow)
    }

    internal fun defaultSpeed(owner: Class<*>, mutableFlow: Class<*>, stateFlow: Class<*>): DefaultSpeedPoint? {
        if (!mutableFlow.isInterface || !stateFlow.isInterface || !(mutableFlow isSubclassOf stateFlow)) return null
        val constructor = KavaMemberLookup.declaredConstructors(owner, makeAccessible = true) {
            it.parameterCount == 0
        }.singleOrNull() ?: return null
        val flows = KavaMemberLookup.declaredFields(owner, makeAccessible = true) {
            !it.isStatic && it.type isSubclassOf mutableFlow
        }
        // 已验证的管理器只有基础/临时两条 Flow；新增同类型字段时宁可缺失，不猜测用途。
        if (flows.size != 2) return null
        val reader = KavaMemberLookup.methodOrNull(stateFlow, "getValue")
            ?.takeIf { !it.isStatic && it.returnType == classOf<Any>() } ?: return null
        val writer = KavaMemberLookup.methodOrNull(mutableFlow, "setValue", classOf<Any>())
            ?.takeIf { !it.isStatic && it.returnType == Void.TYPE } ?: return null
        val getters = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<Float>()
        }
        // 旧版有基础/合成两个 getter，新版内联了合成 getter；不依赖混淆名挑其中一个。
        if (getters.size !in 1..2) return null
        return DefaultSpeedPoint(constructor, flows, reader, writer, getters)
    }

    fun speedMenu(loader: ClassLoader, cached: PlayerSpeedMenuPoints? = null): SpeedMenuPoint? {
        val hints = cached?.takeIf(PlayerSpeedMenuPoints::isStructurallyValid)
        val theseusWidget = KavaMemberLookup.classOrNull(loader, THESEUS_SPEED_WIDGET)
        val recyclerAdapter = KavaMemberLookup.classOrNull(loader, RECYCLER_ADAPTER)
        val widgetAdapter = theseusWidget?.let { widget ->
            KavaMemberLookup.declaredFields(widget, makeAccessible = true) { field ->
                !field.isStatic && View.OnClickListener::class.java.isAssignableFrom(field.type) &&
                    (field.type.name.startsWith("$SPEED_WIDGET_PKG.") &&
                        (recyclerAdapter == null || recyclerAdapter.isAssignableFrom(field.type)) ||
                        PlayerSpeedMenuAdapter.flattenedOwner(field.type.name) && recyclerAdapter?.isAssignableFrom(field.type) == true)
            }.singleOrNull()?.type
        }
        val adapterCandidates = buildList {
            widgetAdapter?.let(::add)
            for (shortName in listOf("f", "g", "e", "h", "d")) {
                val candidate = KavaMemberLookup.classOrNull(loader, "$SPEED_WIDGET_PKG.$shortName") ?: continue
                if (candidate !in this) add(candidate)
            }
        }
        val optionCandidates = lazy(LazyThreadSafetyMode.NONE) {
            (('a'..'z') + ('A'..'Z')).mapNotNull { ch ->
                KavaMemberLookup.classOrNull(loader, "$SPEED_UTILS_PKG.$ch")
            }
        }
        val mediaResource = KavaMemberLookup.classOrNull(loader, MEDIA_RESOURCE)
        val directFactory = if (mediaResource != null) {
            speedMenuFactory(optionCandidates.value, mediaResource)
        } else null
        val factory = directFactory ?: if (mediaResource != null && hints?.factory != null && hints.optionClass != null) {
            val candidates = listOfNotNull(
                KavaMemberLookup.classOrNull(loader, hints.factory.className),
                KavaMemberLookup.classOrNull(loader, hints.optionClass)
            ).distinct()
            speedMenuFactory(candidates, mediaResource)?.takeIf { hints.factory.matches(it.factoryMethod) }
        } else null
        val directAdapter = adapterCandidates.mapNotNull { candidate ->
            speedMenuAdapter(
                adapterClass = candidate,
                recyclerAdapter = recyclerAdapter,
                knownFactory = factory,
                optionCandidates = { optionCandidates.value }
            )
        }.singleOrNull()
        val adapter = directAdapter ?: hints?.adapter?.takeIf { point ->
            !PlayerSpeedMenuAdapter.flattenedOwner(point.className) || point.className == widgetAdapter?.name
        }?.let { point ->
            KavaMemberLookup.classOrNull(loader, point.className)?.let { owner ->
                speedMenuAdapter(owner, recyclerAdapter, factory) { optionCandidates.value }
                    ?.takeIf { point.matches(it.populateMethod) }
            }
        }
        val resolvedFactory = factory ?: adapter?.let { point ->
            val ctor = point.optionConstructor
            val speedField = point.optionSpeedField
            val availField = point.optionAvailableField
            if (ctor != null && speedField != null && availField != null && mediaResource != null) {
                speedMenuFactoryWithModel(optionCandidates.value, mediaResource, ctor, speedField, availField)
            } else null
        }
        if (resolvedFactory == null && adapter == null) return null
        val compactRoute = if (theseusWidget != null && adapter != null) {
            val functionService = KavaMemberLookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.AbsFunctionWidgetService")
            val manager = KavaMemberLookup.classOrNull(loader, SPEED_MANAGER)
            val keelPlayer = if (manager != null) KavaMemberLookup.declaredFields(theseusWidget) { field ->
                !field.isStatic && field.type.name.startsWith("com.bilibili.ship.theseus.keel.player.") &&
                    KavaMemberLookup.declaredFields(field.type) { !it.isStatic && it.type == manager }.size == 1
            }.singleOrNull()?.type else null
            if (functionService != null && keelPlayer != null) {
                val candidates = ('a'..'z').mapNotNull {
                    KavaMemberLookup.classOrNull(loader, "$THESEUS_SPEED_PKG.$it")
                } + listOfNotNull(hints?.compactOwner?.let { KavaMemberLookup.classOrNull(loader, it) })
                candidates.distinct().mapNotNull { speedMenuCompactRoute(it, functionService, keelPlayer) }.singleOrNull()
            } else null
        } else null
        return SpeedMenuPoint(resolvedFactory, adapter, compactRoute)
    }

    /**
     * 缩略窗的 Compose 面板把相差 < 0.1 的档位同时高亮，并忽略它们之间的点击。
     * 在菜单打开期间临时走宿主原有的原生列表分支；不修改屏幕模式或全局窗口委托。
     * 只匹配同时持有播放器/功能菜单服务访问器的倍速控件，静态入口必须唯一。
     */
    internal fun speedMenuCompactRoute(
        owner: Class<*>, functionService: Class<*>, keelPlayer: Class<*>
    ): SpeedMenuCompactRoutePoint? {
        val methods = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { true }
        if (methods.count { !it.isStatic && it.name == "getFunctionWidgetService" &&
                it.parameterCount == 0 && it.returnType == functionService } != 1) return null
        if (methods.count { !it.isStatic && it.name == "getKeelPlayer" &&
                it.parameterCount == 0 && it.returnType == keelPlayer } != 1) return null
        val windowGetter = methods.singleOrNull {
            !it.isStatic && it.name == "getWindowSizeDelegate" && it.parameterCount == 0 &&
                !it.returnType.isPrimitive && it.returnType != Any::class.java
        } ?: return null
        val open = methods.singleOrNull {
            it.isStatic && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(owner))
        } ?: return null
        return SpeedMenuCompactRoutePoint(open, windowGetter)
    }

    internal fun speedMenuAdapter(
        adapterClass: Class<*>,
        recyclerAdapter: Class<*>? = null,
        knownFactory: SpeedMenuFactoryPoint? = null,
        optionCandidates: () -> List<Class<*>> = { emptyList() }
    ): SpeedMenuAdapterPoint? {
        if (adapterClass.isInterface || Modifier.isAbstract(adapterClass.modifiers)) return null
        if (!View.OnClickListener::class.java.isAssignableFrom(adapterClass)) return null
        if (recyclerAdapter != null && !recyclerAdapter.isAssignableFrom(adapterClass)) return null
        val intConstructor = KavaMemberLookup.declaredConstructors(adapterClass, makeAccessible = true) {
            it.parameterCount == 1 && it.parameterTypes[0] == classOf<Int>()
        }.singleOrNull() ?: return null
        if (intConstructor.parameterCount != 1) return null
        val itemsField = KavaMemberLookup.declaredFields(adapterClass, makeAccessible = true) {
            !it.isStatic && it.type == List::class.java
        }.singleOrNull() ?: return null
        val itemModel = resolveAdapterItemModel(adapterClass, itemsField) ?: return null

        val listPopulate = KavaMemberLookup.declaredMethods(adapterClass, makeAccessible = true) {
            !it.isStatic &&
                it.returnType == Void.TYPE &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == classOf<Float>() &&
                it.parameterTypes[1] == List::class.java
        }.singleOrNull()
        if (listPopulate != null) {
            val genericOptionClass = (listPopulate.genericParameterTypes.getOrNull(1) as? ParameterizedType)
                ?.actualTypeArguments?.singleOrNull() as? Class<*>
            val optionModel = genericOptionClass?.let(::verifyOptionModel)
                ?: knownFactory?.let { OptionModelSpec(it.optionConstructor, it.optionSpeedField, it.optionAvailableField) }
                ?: optionCandidates().mapNotNull(::verifyOptionModel).singleOrNull()
                ?: return null
            return SpeedMenuAdapterPoint(
                adapterClass = adapterClass,
                populateMethod = listPopulate,
                itemsField = itemsField,
                itemSpeedField = itemModel.speedField,
                itemSelectedField = itemModel.selectedField,
                itemAvailableField = itemModel.availableField,
                speedsArrayField = null,
                optionConstructor = optionModel.constructor,
                optionSpeedField = optionModel.speedField,
                optionAvailableField = optionModel.availableField
            )
        }

        val speedsArrayField = KavaMemberLookup.declaredFields(adapterClass, makeAccessible = true) {
            !it.isStatic && it.type == FloatArray::class.java
        }.singleOrNull() ?: return null
        val floatPopulate = KavaMemberLookup.declaredMethods(adapterClass, makeAccessible = true) {
            !it.isStatic &&
                it.returnType == Void.TYPE &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == classOf<Float>()
        }.singleOrNull() ?: return null
        return SpeedMenuAdapterPoint(
            adapterClass = adapterClass,
            populateMethod = floatPopulate,
            itemsField = itemsField,
            itemSpeedField = itemModel.speedField,
            itemSelectedField = itemModel.selectedField,
            itemAvailableField = itemModel.availableField,
            speedsArrayField = speedsArrayField
        )
    }

    internal fun speedMenuFactory(
        candidates: List<Class<*>>,
        mediaResourceClass: Class<*>
    ): SpeedMenuFactoryPoint? {
        val optionModel = candidates.mapNotNull(::verifyOptionModel).singleOrNull() ?: return null
        return speedMenuFactoryWithModel(
            candidates = candidates,
            mediaResourceClass = mediaResourceClass,
            optionConstructor = optionModel.constructor,
            optionSpeedField = optionModel.speedField,
            optionAvailableField = optionModel.availableField
        )
    }

    private fun speedMenuFactoryWithModel(
        candidates: List<Class<*>>,
        mediaResourceClass: Class<*>,
        optionConstructor: Constructor<*>,
        optionSpeedField: Field,
        optionAvailableField: Field
    ): SpeedMenuFactoryPoint? {
        val methods = candidates.mapNotNull { candidate ->
            val method = KavaMemberLookup.declaredMethods(candidate, makeAccessible = true) {
                val params = it.parameterTypes
                it.isStatic && it.returnType == List::class.java && params.getOrNull(0) == mediaResourceClass &&
                    params.getOrNull(1) == Int::class.javaObjectType &&
                    (params.size == 4 && params[2] == classOf<Boolean>() && params[3] == classOf<Int>() ||
                        params.size == 3 && params[2] == classOf<Int>() && PlayerSpeedMenuAdapter.flattenedOwner(candidate.name))
            }.singleOrNull() ?: return@mapNotNull null
            val defaultListField = KavaMemberLookup.declaredFields(candidate, makeAccessible = true) {
                it.isStatic && it.type == List::class.java
            }.singleOrNull() ?: return@mapNotNull null
            val defaultList = runCatching { defaultListField.get(null) as? List<*> }.getOrNull()
                ?: return@mapNotNull null
            if (defaultList.size < 4 || !defaultList.all { it is Float } || 1.0f !in defaultList || 2.0f !in defaultList) {
                return@mapNotNull null
            }
            method
        }
        val factoryMethod = methods.singleOrNull() ?: return null
        return SpeedMenuFactoryPoint(
            factoryMethod = factoryMethod,
            optionConstructor = optionConstructor,
            optionSpeedField = optionSpeedField,
            optionAvailableField = optionAvailableField
        )
    }

    private data class AdapterItemModelSpec(
        val speedField: Field,
        val selectedField: Field,
        val availableField: Field?
    )

    internal data class OptionModelSpec(
        val constructor: Constructor<*>,
        val speedField: Field,
        val availableField: Field
    )

    private fun resolveAdapterItemModel(adapterClass: Class<*>, itemsField: Field): AdapterItemModelSpec? {
        val genericItemClass = (itemsField.genericType as? ParameterizedType)
            ?.actualTypeArguments?.singleOrNull() as? Class<*>
        val candidates = if (genericItemClass != null) {
            listOf(genericItemClass)
        } else {
            adapterClass.declaredClasses.toList()
        }
        return candidates.mapNotNull(::verifyAdapterItemModel).singleOrNull()
    }

    private fun verifyAdapterItemModel(itemClass: Class<*>): AdapterItemModelSpec? {
        if (!Modifier.isStatic(itemClass.modifiers) || itemClass.isInterface || Modifier.isAbstract(itemClass.modifiers)) {
            return null
        }
        val zeroArgCtor = KavaMemberLookup.declaredConstructors(itemClass, makeAccessible = true) {
            it.parameterCount == 0
        }.singleOrNull() ?: return null
        val instanceFields = KavaMemberLookup.declaredFields(itemClass, makeAccessible = true) { !it.isStatic }
        val floatFields = instanceFields.filter { it.type == classOf<Float>() }
        val boolFields = instanceFields.filter { it.type == classOf<Boolean>() }
        if (floatFields.size != 1 || boolFields.size !in 1..2 || instanceFields.size != 1 + boolFields.size) {
            return null
        }
        val speedField = floatFields.single()
        val sample = runCatching { zeroArgCtor.newInstance() }.getOrNull() ?: return null
        if (runCatching { speedField.getFloat(sample) }.getOrNull() != 1.0f) return null
        val falseFields = boolFields.filter { runCatching { !it.getBoolean(sample) }.getOrDefault(false) }
        val trueFields = boolFields.filter { runCatching { it.getBoolean(sample) }.getOrDefault(false) }
        val selectedField = falseFields.singleOrNull() ?: return null
        val availableField = if (boolFields.size == 2) trueFields.singleOrNull() ?: return null else null
        return AdapterItemModelSpec(speedField, selectedField, availableField)
    }

    internal fun verifyOptionModel(modelClass: Class<*>): OptionModelSpec? {
        if (modelClass.isInterface || Modifier.isAbstract(modelClass.modifiers)) return null
        val ctor = KavaMemberLookup.declaredConstructors(modelClass, makeAccessible = true) {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == classOf<Float>() &&
                it.parameterTypes[1] == classOf<Boolean>()
        }.singleOrNull() ?: return null
        if (KavaMemberLookup.declaredConstructors(modelClass, makeAccessible = true) { true }.size != 1) return null
        val instanceFields = KavaMemberLookup.declaredFields(modelClass, makeAccessible = true) { !it.isStatic }
        if (instanceFields.size != 2) return null
        val speedField = instanceFields.singleOrNull { it.type == classOf<Float>() } ?: return null
        val availableField = instanceFields.singleOrNull { it.type == classOf<Boolean>() } ?: return null
        val sample = runCatching { ctor.newInstance(1.5f, true) }.getOrNull() ?: return null
        val ok = runCatching {
            speedField.getFloat(sample) == 1.5f && availableField.getBoolean(sample)
        }.getOrDefault(false)
        if (!ok) return null
        return OptionModelSpec(ctor, speedField, availableField)
    }
}
