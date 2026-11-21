package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.ConcurrentHashMap

/** 宿主页面是否允许开启预见式返回的判定结果分类。 */
internal enum class HostPredictiveBackReason {
    ELIGIBLE,
    MISSING_COMPONENT_ACTIVITY_BASE,
    CUSTOM_ON_BACK_PRESSED,
    CUSTOM_ON_KEY_DOWN,
    CUSTOM_ON_KEY_UP,
    CUSTOM_ON_KEY_LONG_PRESS,
    CUSTOM_DISPATCH_KEY_EVENT,
    HIERARCHY_UNVERIFIED
}

/** 不可变分类结果：缓存只存该值对象与类名键，不持有任何宿主对象引用。 */
internal data class HostPredictiveBackDecision(
    val eligible: Boolean,
    val reason: HostPredictiveBackReason
)

/** 沿宿主 Activity 继承链定位到的返回/按键方法最近声明类（全限定类名）。 */
internal data class BackMethodOwners(
    val onBackPressed: String? = null,
    val onKeyDown: String? = null,
    val onKeyUp: String? = null,
    val onKeyLongPress: String? = null,
    val dispatchKeyEvent: String? = null
)

/**
 * 宿主页面预见式返回可行性策略（纯元数据判定，不依赖 DEX 适配与强引用缓存）。
 *
 * 原则：
 * 1. 只放行继承自 AndroidX ComponentActivity 的标准页面（可参与统一分发链）。
 * 2. 若页面及其业务基类重写了 `onBackPressed` / `onKeyDown` / `onKeyUp` /
 *    `onKeyLongPress` / `dispatchKeyEvent`，且声明类不在已验证的直通基类集合内，
 *    保持宿主原有返回处理（例如退出全屏、WebView 后退、双击退出首页），避免被系统默认关闭回调绕过。
 * 3. 任何反射检查异常按保守拒绝处理（fail-closed），不误伤页面返回交互。
 */
internal class HostPredictiveBackPolicy(
    private val maxCacheSize: Int = MAX_CACHE_SIZE
) {
    private val cache = ConcurrentHashMap<String, HostPredictiveBackDecision>()

    fun cachedDecisionOrNull(className: String): HostPredictiveBackDecision? = cache[className]

    fun cacheSize(): Int = cache.size

    fun classify(activityClass: Class<*>): HostPredictiveBackDecision {
        val key = activityClass.name
        cache[key]?.let { return it }

        val decision = inspectClassHierarchy(activityClass)
        if (maxCacheSize > 0) {
            if (cache.size >= maxCacheSize) {
                cache.clear()
            }
            cache[key] = decision
        }
        return decision
    }

    private fun inspectClassHierarchy(activityClass: Class<*>): HostPredictiveBackDecision {
        return try {
            val hierarchy = collectHierarchy(activityClass)
            val superclassNames = hierarchy.map { it.name }
            var onBackPressedOwner: String? = null
            var onKeyDownOwner: String? = null
            var onKeyUpOwner: String? = null
            var onKeyLongPressOwner: String? = null
            var dispatchKeyEventOwner: String? = null

            for (cls in hierarchy) {
                if (onBackPressedOwner != null &&
                    onKeyDownOwner != null &&
                    onKeyUpOwner != null &&
                    onKeyLongPressOwner != null &&
                    dispatchKeyEventOwner != null
                ) {
                    break
                }
                for (method in cls.declaredMethods) {
                    when (method.name) {
                        METHOD_ON_BACK_PRESSED -> {
                            if (onBackPressedOwner == null && method.parameterCount == 0) {
                                onBackPressedOwner = cls.name
                            }
                        }
                        METHOD_ON_KEY_DOWN -> {
                            if (onKeyDownOwner == null && isIntKeyEventSignature(method.parameterTypes)) {
                                onKeyDownOwner = cls.name
                            }
                        }
                        METHOD_ON_KEY_UP -> {
                            if (onKeyUpOwner == null && isIntKeyEventSignature(method.parameterTypes)) {
                                onKeyUpOwner = cls.name
                            }
                        }
                        METHOD_ON_KEY_LONG_PRESS -> {
                            if (onKeyLongPressOwner == null && isIntKeyEventSignature(method.parameterTypes)) {
                                onKeyLongPressOwner = cls.name
                            }
                        }
                        METHOD_DISPATCH_KEY_EVENT -> {
                            if (dispatchKeyEventOwner == null && isKeyEventSignature(method.parameterTypes)) {
                                dispatchKeyEventOwner = cls.name
                            }
                        }
                    }
                }
            }

            classifyHierarchy(
                superclassNames = superclassNames,
                owners = BackMethodOwners(
                    onBackPressed = onBackPressedOwner,
                    onKeyDown = onKeyDownOwner,
                    onKeyUp = onKeyUpOwner,
                    onKeyLongPress = onKeyLongPressOwner,
                    dispatchKeyEvent = dispatchKeyEventOwner
                )
            )
        } catch (error: VirtualMachineError) {
            throw error
        } catch (_: Throwable) {
            HostPredictiveBackDecision(
                eligible = false,
                reason = HostPredictiveBackReason.HIERARCHY_UNVERIFIED
            )
        }
    }

    private fun collectHierarchy(start: Class<*>): List<Class<*>> {
        val result = ArrayList<Class<*>>(12)
        var current: Class<*>? = start
        var steps = 0
        while (current != null && steps < MAX_HIERARCHY_DEPTH) {
            result.add(current)
            if (current.name == PLATFORM_ACTIVITY_NAME) break
            current = current.superclass
            steps++
        }
        return result
    }

    companion object {
        const val MAX_CACHE_SIZE = 512
        const val MAX_HIERARCHY_DEPTH = 32
        const val MAX_CONTEXT_UNWRAP_DEPTH = 16

        const val PLATFORM_ACTIVITY_NAME = "android.app.Activity"
        const val COMPONENT_ACTIVITY_NAME = "androidx.activity.ComponentActivity"

        private const val METHOD_ON_BACK_PRESSED = "onBackPressed"
        private const val METHOD_ON_KEY_DOWN = "onKeyDown"
        private const val METHOD_ON_KEY_UP = "onKeyUp"
        private const val METHOD_ON_KEY_LONG_PRESS = "onKeyLongPress"
        private const val METHOD_DISPATCH_KEY_EVENT = "dispatchKeyEvent"
        private const val KEY_EVENT_CLASS_NAME = "android.view.KeyEvent"

        /**
         * 已通过 9.14.0/9.20.0 静态反编译核实为纯透传（或仅调用 super/Fragment pop）的 `onBackPressed` 基类。
         * 新增或未验证的类默认不列入，确保自定义返回逻辑不被系统默认 finish 绕过。
         */
        val ALLOWED_ON_BACK_PRESSED_OWNERS: Set<String> = setOf(
            "android.app.Activity",
            "androidx.core.app.ComponentActivity",
            "androidx.activity.ComponentActivity",
            "androidx.fragment.app.FragmentActivity",
            "androidx.appcompat.app.AppCompatActivity",
            "com.bilibili.lib.ui.BaseAppCompatActivity",
            "com.bilibili.lib.ui.BaseToolbarActivity",
            "com.bilibili.biligame.widget.BaseTranslucentActivity"
        )

        /** 仅系统与 AndroidX 框架基类自身允许声明底层按键分发方法，其余一律视为存在定制拦截。 */
        val ALLOWED_KEY_EVENT_OWNERS: Set<String> = setOf(
            "android.app.Activity",
            "androidx.core.app.ComponentActivity",
            "androidx.activity.ComponentActivity",
            "androidx.fragment.app.FragmentActivity",
            "androidx.appcompat.app.AppCompatActivity"
        )

        private fun isIntKeyEventSignature(paramTypes: Array<Class<*>>): Boolean =
            paramTypes.size == 2 &&
                paramTypes[0] == Integer.TYPE &&
                paramTypes[1].name == KEY_EVENT_CLASS_NAME

        private fun isKeyEventSignature(paramTypes: Array<Class<*>>): Boolean =
            paramTypes.size == 1 &&
                paramTypes[0].name == KEY_EVENT_CLASS_NAME

        /** 基于类名集合的纯逻辑分类函数，便于 JVM 侧直接构造任意宿主层级样本测试。 */
        fun classifyHierarchy(
            superclassNames: List<String>,
            owners: BackMethodOwners
        ): HostPredictiveBackDecision {
            if (COMPONENT_ACTIVITY_NAME !in superclassNames) {
                return HostPredictiveBackDecision(
                    eligible = false,
                    reason = HostPredictiveBackReason.MISSING_COMPONENT_ACTIVITY_BASE
                )
            }
            if (owners.onBackPressed != null && owners.onBackPressed !in ALLOWED_ON_BACK_PRESSED_OWNERS) {
                return HostPredictiveBackDecision(
                    eligible = false,
                    reason = HostPredictiveBackReason.CUSTOM_ON_BACK_PRESSED
                )
            }
            if (owners.onKeyDown != null && owners.onKeyDown !in ALLOWED_KEY_EVENT_OWNERS) {
                return HostPredictiveBackDecision(
                    eligible = false,
                    reason = HostPredictiveBackReason.CUSTOM_ON_KEY_DOWN
                )
            }
            if (owners.onKeyUp != null && owners.onKeyUp !in ALLOWED_KEY_EVENT_OWNERS) {
                return HostPredictiveBackDecision(
                    eligible = false,
                    reason = HostPredictiveBackReason.CUSTOM_ON_KEY_UP
                )
            }
            if (owners.onKeyLongPress != null && owners.onKeyLongPress !in ALLOWED_KEY_EVENT_OWNERS) {
                return HostPredictiveBackDecision(
                    eligible = false,
                    reason = HostPredictiveBackReason.CUSTOM_ON_KEY_LONG_PRESS
                )
            }
            if (owners.dispatchKeyEvent != null && owners.dispatchKeyEvent !in ALLOWED_KEY_EVENT_OWNERS) {
                return HostPredictiveBackDecision(
                    eligible = false,
                    reason = HostPredictiveBackReason.CUSTOM_DISPATCH_KEY_EVENT
                )
            }
            return HostPredictiveBackDecision(
                eligible = true,
                reason = HostPredictiveBackReason.ELIGIBLE
            )
        }

        /** 沿 ContextWrapper 解包至所属 Activity；带步数上限与自引用检测防止死循环。 */
        fun unwrapActivity(context: Context?): Activity? {
            var current = context
            var steps = 0
            while (current != null && steps < MAX_CONTEXT_UNWRAP_DEPTH) {
                if (current is Activity) return current
                if (current !is ContextWrapper) return null
                val next = current.baseContext
                if (next == null || next === current) return null
                current = next
                steps++
            }
            return current as? Activity
        }

        /** 计算启用系统返回回调后的 `ActivityInfo.privateFlags` 新值，清除显式禁用位并置启用位。 */
        fun computeEnabledPrivateFlags(
            currentFlags: Int,
            enableBit: Int,
            disableBit: Int
        ): Int = (currentFlags and disableBit.inv()) or enableBit
    }
}
