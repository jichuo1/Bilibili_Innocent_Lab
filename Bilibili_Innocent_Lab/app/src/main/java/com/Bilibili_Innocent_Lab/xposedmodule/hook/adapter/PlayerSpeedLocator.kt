package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.view.MotionEvent
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/** 安装期只解析稳定业务类及其结构；混淆的服务 owner、字段名不进入候选常量。 */
internal object PlayerSpeedLocator {
    const val LONG_PRESS_LISTENER =
        "com.bilibili.ship.theseus.united.player.TripleSpeedService\$runOldTripleSpeed\$1\$listener\$1"
    const val LONG_PRESS_COROUTINE =
        "com.bilibili.ship.theseus.united.player.TripleSpeedService\$runOldTripleSpeed\$1\$listener\$1\$onLongPress\$1"
    const val SPEED_MANAGER = "com.bilibili.player.tangram.basic.PlaySpeedManagerImpl"
    const val MUTABLE_FLOW = "kotlinx.coroutines.flow.MutableStateFlow"
    const val STATE_FLOW = "kotlinx.coroutines.flow.StateFlow"

    data class LongPressSpeedPoint(val constructor: Constructor<*>, val speedIndex: Int, val speedField: Field)
    data class DefaultSpeedPoint(
        val constructor: Constructor<*>,
        val flows: List<Field>,
        val readValue: Method,
        val writeValue: Method,
        val speedGetters: List<Method>
    )

    fun longPress(loader: ClassLoader): Method? {
        val owner = KavaMemberLookup.classOrNull(loader, LONG_PRESS_LISTENER) ?: return null
        return KavaMemberLookup.methodOrNull(owner, "onLongPress", classOf<MotionEvent>())
            ?.takeIf { !it.isStatic && it.returnType == classOf<Boolean>() }
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
}
