package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.LegacyStoryDanmakuAccess

/**
 * 隐藏 Story 竖屏流的互动图标（每个图标单独开关），见 [StoryActionIcon]。
 *
 * 每个组件类挂两处：
 * - 构造之后 `post` 一次折叠——布局填充完、第一帧之前生效，滑到下一条时不会先闪出来；
 * - `onStart(int)` 之后再折叠一次——页面每次被激活都会走，宿主在绑定数据时改回的可见性被压回去。
 * 两处都只按类名判断归属，不读任何宿主数据。
 */
internal class StoryActionIconsFeatureInstaller(
    private val hidden: Set<StoryActionIcon>,
    /** 单测替换：把宿主实例变成可折叠的盒子；真机走 [ViewActionIconBox]。 */
    private val boxOf: (Any) -> ActionIconBox? = { (it as? View)?.let(::ViewActionIconBox) },
    /** 单测替换：构造后延迟到下一帧执行；真机走 `View.post`。 */
    private val postToView: (Any, () -> Unit) -> Unit = { target, block -> (target as? View)?.post { block() } },
    private val legacyResolver: (ClassLoader) -> LegacyStoryDanmakuAccess? = { LegacyStoryDanmakuAccess.resolve(it) }
) : FeatureInstaller {

    override val id: String = ID

    /** 已排过构造后折叠的实例；弱引用，不让组件活过它的页面。 */
    private val posted: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    private val legacyReady = java.util.concurrent.atomic.AtomicBoolean(false)
    private val legacyRegistered = hashSetOf<String>()
    override val capabilityIds: List<String> get() = hidden.map { it.capabilityId }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (hidden.isEmpty()) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        var total = 0
        var expected = 0
        val degraded = mutableListOf<String>()
        StoryActionIcon.entries.filter { it in hidden }.forEach { icon ->
            var installedForIcon = 0
            var expectedForIcon = 0
            var primaryInstalled = false
            val hasWidgets = icon.widgetClasses.any { KavaMemberLookup.classOrNull(loader, it) != null }
            val legacyOwner = if (icon == StoryActionIcon.DANMAKU_TOGGLE)
                KavaMemberLookup.classOrNull(loader, LegacyStoryDanmakuAccess.OWNER) else null
            if (!hasWidgets && legacyOwner == null) {
                // 新旧载体均不存在：没有可定位的图标，不把某一种组件类缺失直接当成成功。
                environment.reportCapability(icon.capabilityId, FeatureInstallResult.Skipped("not-applicable-host"))
                return@forEach
            }
            if (legacyOwner != null) {
                val legacy = legacyResolver(loader)
                val legacyAccessorPresent = KavaMemberLookup.declaredMethods(legacyOwner) {
                    it.name == "getMDanmakuToggle" && it.parameterCount == 0
                }.isNotEmpty()
                // 新版可能已移除旧访问器；有新版 widget 时不把已删除的旧形态算成缺失。
                if (legacy != null || legacyAccessorPresent || !hasWidgets) {
                    expectedForIcon += 1
                    if (legacy != null && installLegacyDanmaku(environment, legacy)) {
                        installedForIcon += 1
                        primaryInstalled = true
                    }
                }
            }
            icon.widgetClasses.forEachIndexed { index, className ->
                val widget = KavaMemberLookup.classOrNull(loader, className) ?: return@forEachIndexed
                expectedForIcon += 1
                if (installWidget(environment, icon, widget)) {
                    installedForIcon += 1
                    if (index == 0) primaryInstalled = true
                }
            }
            if (!primaryInstalled) degraded += icon.name.lowercase()
            environment.reportCapabilityCoverage(
                icon.capabilityId,
                primaryInstalled,
                installedForIcon,
                expectedForIcon.coerceAtLeast(1)
            )
            total += installedForIcon
            expected += expectedForIcon.coerceAtLeast(1)
        }
        if (total == 0) return missing(environment, "missing-host-structure")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val complete = degraded.isEmpty() && total == expected
        val status = if (complete) "success" else "partial:$total/$expected" +
            degraded.takeIf { it.isNotEmpty() }?.joinToString(prefix = ";missing=", separator = ",").orEmpty()
        environment.reportStatus(CHANNEL_STATUS, status)
        if (complete) {
            environment.logInfo("story_action_icons_ok", "[BIL] 竖屏页互动图标隐藏已安装，widgets=$total")
        } else {
            environment.logError("story_action_icons_partial", "[BIL] 竖屏页互动图标隐藏部分安装，status=$status")
        }
        return FeatureInstallResult.Installed(total, complete = complete)
    }

    private fun installLegacyDanmaku(environment: HookEnvironment, access: LegacyStoryDanmakuAccess): Boolean = runCatching {
        val icon = StoryActionIcon.DANMAKU_TOGGLE
        fun collapseController(controller: Any) {
            val toggle = runCatching { access.toggle(controller) }.getOrElse { throwable ->
                environment.reportRuntimeEvidence(icon.capabilityId, FeatureRuntimeStage.ERROR)
                environment.logError("story_action_legacy_danmaku_read", "[BIL] 旧版竖屏弹幕开关读取失败: $throwable")
                return
            }
            toggle?.let { collapse(environment, icon, it) }
        }
        fun register(key: String, method: java.lang.reflect.Method,
                     block: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.() -> Unit) {
            if (key in legacyRegistered) return
            environment.registrar.exact(key, method.declaringClass, method.name, *method.parameterTypes, block = block)
            legacyRegistered += key
        }
        register("$ID.legacy_danmaku.setter", access.setter) {
            after {
                if (hasThrowable || !legacyReady.get()) return@after
                argOrNull(0)?.let { collapse(environment, icon, it) }
                val controller = instance ?: return@after
                if (synchronized(posted) { posted.add(controller) }) {
                    postToView(controller) { collapseController(controller) }
                }
            }
        }
        register("$ID.legacy_danmaku.bind", access.bind) {
            after { if (!hasThrowable && legacyReady.get()) instance?.let(::collapseController) }
        }
        legacyReady.set(true)
        true
    }.onFailure { throwable ->
        legacyReady.set(false)
        environment.logError("story_action_legacy_danmaku", "[BIL] 旧版竖屏弹幕开关 Hook 注册失败: $throwable")
    }.getOrDefault(false)

    /** 一个组件类：`onStart(int)` 必须装上才算这个类可用；构造后的提前折叠是锦上添花。 */
    private fun installWidget(environment: HookEnvironment, icon: StoryActionIcon, widget: Class<*>): Boolean {
        val onStart = KavaMemberLookup.declaredMethods(widget, makeAccessible = true) {
            !it.isStatic && it.name == ON_START && it.parameterTypes.contentEquals(arrayOf(classOf<Int>()))
        }.singleOrNull() ?: run {
            environment.logError("story_action_icons_${icon.name.lowercase()}_shape",
                "[BIL] 竖屏页互动图标 ${widget.simpleName} 缺少 onStart(int)")
            return false
        }
        val installed = runCatching {
            environment.registrar.exact(
                "$ID.${widget.simpleName}.onStart",
                onStart.declaringClass,
                onStart.name,
                *onStart.parameterTypes
            ) {
                after { instance?.let { collapse(environment, icon, it) } }
            }
        }.onFailure { throwable ->
            environment.logError("story_action_icons_${widget.simpleName}",
                "[BIL] 竖屏页互动图标 Hook 注册失败(${widget.simpleName}#onStart): $throwable")
        }.isSuccess
        if (!installed) return false
        KavaMemberLookup.declaredConstructors(widget) { constructor ->
            constructor.parameterTypes.firstOrNull()?.name == CONTEXT_CLASS
        }.forEachIndexed { index, constructor ->
            runCatching {
                environment.registrar.constructor("$ID.${widget.simpleName}.init.$index", constructor) {
                    after {
                        val target = instance ?: return@after
                        // 宿主的构造函数互相委托，一次填充会触发多次；每个实例只排一次。
                        if (!synchronized(posted) { posted.add(target) }) return@after
                        postToView(target) { collapse(environment, icon, target) }
                    }
                }
            }
        }
        return true
    }

    private fun collapse(environment: HookEnvironment, icon: StoryActionIcon, target: Any) {
        val box = runCatching { boxOf(target) }.getOrNull() ?: return
        environment.reportRuntimeEvidence(icon.capabilityId, FeatureRuntimeStage.OBSERVED)
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
        val changed = runCatching { StoryActionIconCollapse.collapse(box) }.getOrElse { throwable ->
            environment.reportRuntimeEvidence(icon.capabilityId, FeatureRuntimeStage.ERROR)
            environment.logError("story_action_icons_collapse", "[BIL] 竖屏页互动图标折叠失败: $throwable")
            false
        }
        if (changed) {
            environment.reportRuntimeEvidence(icon.capabilityId, FeatureRuntimeStage.APPLIED)
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
        }
    }

    private fun missing(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("story_action_icons_missing", "[BIL] 竖屏页互动图标隐藏适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "story_action_icons"
        private const val CHANNEL_STATUS = "story_action_icons_status"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val ON_START = "onStart"
        private const val CONTEXT_CLASS = "android.content.Context"
    }
}

/** [ActionIconBox] 到真 `View` 的适配；只做属性转发，不做判断。 */
private class ViewActionIconBox(private val view: View) : ActionIconBox {
    override var gone: Boolean
        get() = view.isGone
        set(value) { if (value) view.isGone = true }

    override var width: Int
        get() = view.layoutParams?.width ?: 0
        set(value) { view.layoutParams?.let { it.width = value; view.layoutParams = it } }

    override var height: Int
        get() = view.layoutParams?.height ?: 0
        set(value) { view.layoutParams?.let { it.height = value; view.layoutParams = it } }

    override var margins: IntArray
        get() = (view.layoutParams as? ViewGroup.MarginLayoutParams)
            ?.let { intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) } ?: IntArray(4)
        set(value) {
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.setMargins(value[0], value[1], value[2], value[3])
                view.layoutParams = it
            }
        }

    override var alpha: Float
        get() = view.alpha
        set(value) { view.alpha = value }

    override var clickable: Boolean
        get() = view.isClickable
        set(value) { view.isClickable = value }
}
