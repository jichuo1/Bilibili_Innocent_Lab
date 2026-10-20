package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.content.Context
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardHookCoverage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 仅接入已确认的分隔线绘制入口；不删除 ItemDecoration，不改变布局偏移或卡片阴影。 */
internal object HostVideoCardDividerHooks {
    fun install(environment: HookEnvironment, id: String, replacesSeparators: (ViewGroup) -> Boolean,
        cached: HookPoint? = null): HostCardHookCoverage {
        val loader = environment.classLoader ?: return HostCardHookCoverage.NONE
        fun type(name: String) = KavaMemberLookup.classOrNull(loader, name)
        val recycler = type("androidx.recyclerview.widget.RecyclerView")
        val state = type("androidx.recyclerview.widget.RecyclerView\$State")
        val decoration = type("androidx.recyclerview.widget.RecyclerView\$ItemDecoration")
        if (recycler == null || state == null || decoration == null) return HostCardHookCoverage(
            0, 1, 0, mapOf("divider" to "missing-recycler-abi"))
        val targets = listOf(
            // 历史、收藏详情以及使用宿主通用装饰器的视频列表。
            Triple("native", "tv.danmaku.bili.widget.recycler.DividerDecoration", "onDrawOver"),
            // 热门的旧式 Feed 装饰器在卡片 margin 外绘制上下分隔线。
            Triple("feed", "yn3.b", "onDraw"),
            // 播放列表通用分隔器按配置选择前景或背景绘制。
            Triple("playlist_under", "gt3.b", "onDraw"),
            Triple("playlist_over", "gt3.b", "onDrawOver"),
            // 收藏列表的 onDrawOver 委托给 onDraw，拦截后者即可覆盖两条路径。
            Triple("playset", "com.bilibili.playset.decoration.FlexibleDividerDecoration", "onDraw"),
            Triple("androidx", "androidx.recyclerview.widget.DividerItemDecoration", "onDraw")
        )
        var installed = 0
        var expected = 0
        var ready = 0
        val states = linkedMapOf<String, String>()
        fun register(key: String, draw: Method?) {
            expected++
            if (draw == null) { states[key] = "missing-entry"; return }
            runCatching {
                environment.registrar.exact("$id.$key.divider", draw.declaringClass, draw.name, *draw.parameterTypes) {
                    before {
                        val list = argOrNull(1) as? ViewGroup ?: return@before
                        if (replacesSeparators(list)) result = null
                    }
                }
            }.onSuccess {
                installed++; ready++; states[key] = "installed"
            }.onFailure {
                states[key] = "registration-failed"
                environment.logError("${id}_${key}_divider_error", "[BIL] 卡片分隔线入口注册失败: $it")
            }
        }
        // 两版完整 raw 和 PegasusFragment 的实际 addItemDecoration 调用均指向单列底线层。
        // 不碰同类 onDraw 的双列阴影，也不以 widget.c 的空 onDrawOver 冒充恢复。
        val pegasusPoint = pegasusBoundary(loader, cached)
        if (type("com.bilibili.pegasus.PegasusFragment") != null || pegasusPoint != null) {
            register("pegasus", pegasusPoint)
        }

        for ((key, className, method) in targets) {
            val owner = type(className) ?: continue
            if (!decoration.isAssignableFrom(owner)) continue
            register(key, declaredDraw(owner, method, Canvas::class.java, recycler, state))
        }
        environment.logInfo("${id}_divider_layers", "[BIL] 卡片分隔线层=$ready/$expected: $states")
        return HostCardHookCoverage(installed, expected, ready, states)
    }

    internal fun pegasusBoundary(loader: ClassLoader, cached: HookPoint?): Method? {
        val direct = listOf("com.bilibili.pegasus.widget.j", "com.bilibili.pegasus.widget.l")
            .mapNotNull { KavaMemberLookup.classOrNull(loader, it) }.flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, true) { verifiedPegasus(loader, it) }
            }
        val stored = cached?.takeIf { it.viewField == null }?.let { point ->
            KavaMemberLookup.classOrNull(loader, point.className)?.let { owner ->
                KavaMemberLookup.declaredMethods(owner, true) { it.name == point.methodName &&
                    it.parameterTypes.map(Class<*>::getName) == point.paramClassNames && verifiedPegasus(loader, it) }.singleOrNull()
            }
        }
        return (direct + listOfNotNull(stored)).distinct().singleOrNull()
    }

    internal fun verifiedPegasus(loader: ClassLoader, method: Method): Boolean {
        if (method.name != "onDrawOver" || !method.declaringClass.name.startsWith("com.bilibili.pegasus.widget.")) return false
        val decoration = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$ItemDecoration") ?: return false
        val recycler = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView") ?: return false
        val state = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$State") ?: return false
        return pegasus(method.declaringClass, decoration, Canvas::class.java, recycler, state,
            Context::class.java, Paint::class.java, RectF::class.java) == method
    }

    internal fun pegasus(owner: Class<*>, decoration: Class<*>, canvas: Class<*>, recycler: Class<*>,
        state: Class<*>, context: Class<*>, paint: Class<*>, rect: Class<*>): Method? {
        if (!decoration.isAssignableFrom(owner)) return null
        val fields = owner.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        // The general c decorator has two Lazy dimensions. Its paint counts otherwise
        // collide with the real Pegasus bottom-line layer.
        if (fields.any { it.type.name == "kotlin.Lazy" }) return null
        if (fields.count { it.type == paint } != 2 || fields.count { it.type == rect } != 1 ||
            fields.count { it.type == context } != 1 || fields.count { it.type == Boolean::class.javaPrimitiveType } != 1) return null
        if (owner.declaredConstructors.none { constructor ->
                val parameters = constructor.parameterTypes
                parameters.contentEquals(arrayOf(context)) || parameters.size == 2 &&
                    parameters[0] == context && parameters[1].name == "androidx.window.core.layout.WindowSizeClass"
            }) return null
        return declaredDraw(owner, "onDrawOver", canvas, recycler, state)
    }

    private fun declaredDraw(owner: Class<*>, name: String, canvas: Class<*>, recycler: Class<*>, state: Class<*>): Method? =
        KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            it.name == name && !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(canvas, recycler, state))
        }.singleOrNull()
}
