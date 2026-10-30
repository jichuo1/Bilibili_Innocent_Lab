package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.FollowFeedPoints

import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal class FollowFeedFeatureInstaller(private val enabled: Boolean, private val points: FollowFeedPoints? = null,
    private val material: HostCardMaterial = HostCardMaterial.ORIGINAL) : FeatureInstaller {
    override val id = "follow_feed_style"

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-classloader")
        val adapted = FollowFeedAdapter.resolve(loader, points)
            ?: FollowFeedAdapter.resolve(loader, FollowFeedAdapter.direct(loader))
            ?: return FeatureInstallResult.Skipped("missing-follow-contract")
        val host = adapted.host
        val fragment = KavaMemberLookup.classOrNull(loader, FollowFeedAdapter.FRAGMENT)
            ?: return FeatureInstallResult.Skipped("missing-follow-fragment")
        val recycler = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView")
            ?: return FeatureInstallResult.Skipped("missing-recycler")
        val adapter = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$Adapter")
            ?: return FeatureInstallResult.Skipped("missing-adapter")
        val holder = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$ViewHolder")
            ?: return FeatureInstallResult.Skipped("missing-holder")
        val itemView = KavaMemberLookup.fieldOrNull(holder, "itemView", includeSuperclasses = true)
            ?: throw NoSuchFieldException("itemView")
        val getView = inheritedMethod(fragment, "getView")
        // 已读两家族源码：旧k/新f仅派发模块背景绘制。保留整个 ItemDecoration，
        // 尤其 getItemOffsets 和 onDrawOver；未知版本没有这条契约时整体安全跳过。
        val paint = adapted.paint
        val painter = paint.declaringClass
        // 在任何注入前确认关键边界，不在注册一半后才发现不可恢复的布局能力缺失。
        declaredMethod(recycler, "getItemDecorInsetsForChild", View::class.java)
        declaredMethod(recycler, "dispatchChildAttached", View::class.java)
        declaredMethod(fragment, "onViewCreated", View::class.java, Bundle::class.java)
        declaredMethod(fragment, "onDestroyView")
        val headerPadding = adapted.header
        val personalOwner = KavaMemberLookup.classOrNull(loader,
            "com.bilibili.bplus.followinglist.quick.consume.VideoQuickConsumeFragment")
        val personalExpected = personalOwner != null || points?.personalDecorationClass != null ||
            points?.personalSeparatorClass != null || adapted.points.personalDecorationClass != null ||
            adapted.points.personalSeparatorClass != null
        val personalFragment = personalOwner?.takeIf { type -> runCatching {
            declaredMethod(type, "onViewCreated", View::class.java, Bundle::class.java).returnType == Void.TYPE &&
                declaredMethod(type, "onDestroyView").returnType == Void.TYPE &&
                declaredMethod(type, "setUserVisibleCompat", Boolean::class.javaPrimitiveType!!).returnType == Void.TYPE &&
                View::class.java.isAssignableFrom(inheritedMethod(type, "getView").returnType) &&
                inheritedMethod(type, "getUserVisibleHint").returnType == Boolean::class.javaPrimitiveType &&
                listOf("onPause", "onResume").all { inheritedMethod(type, it).returnType == Void.TYPE }
        }.getOrDefault(false)
        }
        val separatorDraw = adapted.separator
        val personalSupported = personalFragment != null && separatorDraw != null && adapted.personalDecoration != null
        val failed = AtomicBoolean(false)
        val applied = AtomicBoolean(false)
        val reportedModelSkips = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val controller = FollowFeedController(host, compactHeaderSupported = headerPadding != null, material = material, report = {
            // 日志入口按 key 去重；每个固定生命周期阶段各输出一次，不泄漏业务身份。
            environment.logInfo("follow_feed_diagnostics_${it.substringBefore(':')}", it)
            if (applied.compareAndSet(false, true)) environment.reportRuntimeEvidence(id, FeatureRuntimeStage.APPLIED)
        }, error = {
            if (failed.compareAndSet(false, true)) environment.logError("follow_feed_error",
                "[BIL] 关注页样式安全跳过异常边界: ${it.javaClass.simpleName}")
        })
        fun registerPage(type: Class<*>, key: String, personalFeed: Boolean) {
            val pageView = inheritedMethod(type, "getView")
            val visibleHint = if (personalFeed) inheritedMethod(type, "getUserVisibleHint") else null
            environment.registrar.exact("$key.create", type, "onViewCreated", View::class.java, Bundle::class.java) {
                after {
                    if (hasThrowable) return@after
                    val root = argOrNull(0) as? View ?: return@after
                    val target = instance
                    root.post {
                        if (root.isAttachedToWindow) {
                            controller.attach(root, personalFeed)
                            if (visibleHint != null) controller.visibility(root, visibleHint.invoke(target) == true)
                        }
                    }
                }
            }
            environment.registrar.exact("$key.destroy", type, "onDestroyView") {
                before { (instance?.let { pageView.invoke(it) } as? View)?.let(controller::close) }
            }
            for (event in listOf("onPause", "onResume")) {
                val method = inheritedMethod(type, event)
                environment.registrar.exact("$key.$event", method.declaringClass, event) {
                    after {
                        val target = instance?.takeIf { type.isInstance(it) } ?: return@after
                        val root = pageView.invoke(target) as? View ?: return@after
                        if (event == "onPause") controller.pause(root) else controller.resume(root, personalFeed)
                    }
                }
            }
            if (personalFeed) environment.registrar.exact("$key.visibility", type,
                "setUserVisibleCompat", Boolean::class.javaPrimitiveType!!) {
                after {
                    val root = instance?.let { pageView.invoke(it) } as? View ?: return@after
                    controller.visibility(root, argOrNull(0) == true)
                }
            }
        }
        registerPage(fragment, "follow_feed", personalFeed = false)
        if (personalSupported) {
            // 视频与全部动态都使用这个 Fragment；仅接入已审阅的 RelativeLayout 列表。
            registerPage(requireNotNull(personalFragment), "follow_feed.personal", personalFeed = true)
            val separator = requireNotNull(separatorDraw)
            environment.registrar.exact("follow_feed.personal.separator", separator.declaringClass,
                separator.name, *separator.parameterTypes) {
                before {
                    val list = argOrNull(1) as? ViewGroup ?: return@before
                    if (controller.replacesPersonalSeparators(list)) result = null
                }
            }
        }
        if (headerPadding != null) environment.registrar.exact("follow_feed.header_padding", fragment,
            headerPadding.name, *headerPadding.parameterTypes) {
            before {
                val root = instance?.let { getView.invoke(it) } as? View ?: return@before
                val requested = argOrNull(0) as? Int ?: return@before
                args[0] = controller.headerPadding(root, requested)
            }
        }
        environment.registrar.exact("follow_feed.bind", adapter, "bindViewHolder", holder, Int::class.javaPrimitiveType!!) {
            after {
                if (hasThrowable) return@after
                val bound = argOrNull(0) ?: return@after
                val root = itemView.get(bound) as? View ?: return@after
                controller.bind(root, bound, argOrNull(1) as? Int ?: return@after)
                host.unavailableReason?.let { reason ->
                    reportModelSkip(environment, reportedModelSkips, reason)
                }
            }
        }
        environment.registrar.exact("follow_feed.insets", recycler, "getItemDecorInsetsForChild", View::class.java) {
            after {
                val root = argOrNull(0) as? View ?: return@after
                val rect = result as? android.graphics.Rect ?: return@after
                controller.insets(root, rect)
            }
        }
        environment.registrar.exact("follow_feed.child_attach", recycler, "dispatchChildAttached", View::class.java) {
            after { (argOrNull(0) as? View)?.let(controller::childAttached) }
        }
        environment.registrar.exact("follow_feed.module_background", painter, paint.name, *paint.parameterTypes) {
            before {
                val decorator = instance ?: return@before
                if (!adapted.homeDecoration.isInstance(decorator) &&
                    adapted.personalDecoration?.isInstance(decorator) != true) return@before
                val list = argOrNull(1) as? ViewGroup ?: return@before
                val row = argOrNull(2) as? View ?: return@before
                if (controller.replacesModuleBackground(list, row)) result = null
            }
        }
        val pool = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$Recycler")
        if (pool != null && KavaMemberLookup.methodOrNull(pool, "recycleViewHolderInternal", holder) != null) {
            environment.registrar.exact("follow_feed.recycle", pool, "recycleViewHolderInternal", holder) {
                before { argOrNull(0)?.let { itemView.get(it) as? View }?.let(controller::recycle) }
            }
        }
        environment.logInfo("follow_feed_installed", "[BIL] 关注页凝光接入完成（卡片=${material.value}，状态栏按需融合，UP 主页=$personalSupported，正文内视频=${host.embeddedVideoSupported}）")
        return FeatureInstallResult.Installed((if (headerPadding == null) 9 else 10) + if (personalSupported) 6 else 0,
            complete = host.embeddedVideoSupported && headerPadding != null &&
                (!personalExpected || personalSupported))
    }

    companion object {
        /** 关键边界缺失时照旧抛出，由安装框架记为失败，不降级成静默跳过。 */
        private fun declaredMethod(owner: Class<*>, name: String, vararg parameters: Class<*>): Method =
            KavaMemberLookup.methodOrNull(owner, name, *parameters) ?: throw NoSuchMethodException("${owner.name}.$name")

        private fun inheritedMethod(owner: Class<*>, name: String): Method =
            KavaMemberLookup.inheritedMethodOrNull(owner, name) ?: throw NoSuchMethodException("${owner.name}.$name")

        /** 独立skip诊断不经过controller.report，不能把预算/坏描述误标成APPLIED。 */
        internal fun reportModelSkip(environment: HookEnvironment, reported: MutableSet<String>, reason: String) {
            if (!reported.add(reason)) return
            environment.reportStatus("follow_feed_model", reason)
            environment.logInfo("follow_feed_model_$reason", "[BIL] 关注卡片保持宿主样式: $reason")
        }
    }
}
