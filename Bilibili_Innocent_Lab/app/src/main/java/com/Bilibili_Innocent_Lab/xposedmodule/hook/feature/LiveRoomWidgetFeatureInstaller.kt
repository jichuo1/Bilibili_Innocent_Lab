package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.MotionEvent
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/**
 * 直播间的两个交互小件：禁止上下滑动换房、双击改为暂停/继续。
 *
 * 两项互相独立，各算一个覆盖单位；一项定位失败不影响另一项。
 *
 * **翻页器类名是混淆的，禁止写死**：这里从稳定类
 * `LiveVerticalPagerView` 出发，取它声明的、类型是宿主 RecyclerView 子类的字段，用那个字段
 * 的类型当翻页器实现类。候选不唯一就按缺失处理，不猜。
 *
 * **版本覆盖（2026-09-06 离线核对 9.7.0–9.11.0 五版）**：翻页器候选每一版都恰好一个，
 * 但实现类名逐版漂移（`IM.h` → `KM.h` → `LM.g` → `LM.h` → `MM.g`），字段名也从 `a` 变过 `b`，
 * 这正是不能写死名字、只能按结构定位的直接证据；播放桥返回类型同样漂移
 * （`p5.b` → `q5.b`），但 `isPlaying`/`pause`/`resume` 三个方法五版齐全。
 *
 * 双击暂停走 `LiveRoomPlayerContainerView#onDoubleTap`：`before` 里通过宿主自己的播放桥
 * （`getPlayerCommonBridge`，私有但名字未混淆）切换播放状态，然后消费掉这次双击，避免同时
 * 触发原本的点赞。桥或其方法解析不到时**不注册**这一项，绝不让双击变成空操作。
 */
internal class LiveRoomWidgetFeatureInstaller(
    private val blockRoomSwitch: Boolean,
    private val doubleTapPause: Boolean
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!blockRoomSwitch && !doubleTapPause) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")

        var installed = 0
        var expected = 0

        if (blockRoomSwitch) {
            expected += 1
            if (installRoomSwitchBlock(environment, loader)) installed += 1
        }
        if (doubleTapPause) {
            expected += 1
            if (installDoubleTapPause(environment, loader)) installed += 1
        }

        if (installed == 0) return missing(environment, "no-live-room-hook-point")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (installed == expected) "success" else "partial:$installed/$expected"
        environment.reportStatus(CHANNEL_STATUS, status)
        if (status == "success") {
            environment.logInfo(
                "live_room_widgets_ok",
                "[BIL] 直播间小件已安装，switch=$blockRoomSwitch，doubleTap=$doubleTapPause"
            )
        } else {
            environment.logError(
                "live_room_widgets_partial",
                "[BIL] 直播间小件部分安装，status=$status"
            )
        }
        return FeatureInstallResult.Installed(installed)
    }

    /** 翻页器拦不到触摸就换不了房；直接让 `onInterceptTouchEvent` 恒返回 false。 */
    private fun installRoomSwitchBlock(
        environment: HookEnvironment,
        loader: ClassLoader
    ): Boolean {
        val pagerHost = KavaMemberLookup.classOrNull(loader, LIVE_VERTICAL_PAGER_CLASS)
            ?: run {
                environment.logError(
                    "live_room_pager_host_missing",
                    "[BIL] 直播间翻页容器类缺失，禁止换房未安装"
                )
                return false
            }
        val recyclerView = KavaMemberLookup.classOrNull(loader, HOST_RECYCLER_VIEW_CLASS)
            ?: run {
                environment.logError(
                    "live_room_recycler_missing",
                    "[BIL] 宿主 RecyclerView 类缺失，禁止换房未安装"
                )
                return false
            }
        // 刻意排除字段类型正好是 RecyclerView 本身的情况：那会让我们去 Hook 平台基类的
        // onInterceptTouchEvent，全应用每个列表都被波及。只接受宿主自己的子类实现。
        val candidates = KavaMemberLookup.declaredFields(pagerHost) { field ->
            !field.isStatic && field.type isSubclassOf recyclerView &&
                field.type != recyclerView
        }.map { it.type }.distinct()
        val pagerClass = candidates.singleOrNull() ?: run {
            environment.logError(
                "live_room_pager_ambiguous",
                "[BIL] 直播间翻页器候选不唯一(${candidates.size})，禁止换房未安装"
            )
            return false
        }
        val intercept = KavaMemberLookup.methodOrNull(
            pagerClass,
            "onInterceptTouchEvent",
            classOf<MotionEvent>()
        )?.takeIf { !it.isStatic && it.returnType == classOf<Boolean>() } ?: run {
            environment.logError(
                "live_room_intercept_missing",
                "[BIL] 直播间翻页器缺少 onInterceptTouchEvent，禁止换房未安装"
            )
            return false
        }

        // onInterceptTouchEvent 每个 MOVE 事件都会进来，是货真价实的触摸热路径：
        // 运行证据只在第一次上报，之后每次回调只剩一次 volatile 读。
        val evidenceReported = java.util.concurrent.atomic.AtomicBoolean(false)
        return runCatching {
            environment.registrar.exact(
                "live.room.block_switch",
                intercept.declaringClass,
                intercept.name,
                *intercept.parameterTypes
            ) {
                before {
                    // 只拦截"是否把手势收走"这一个判断；触摸事件本身仍交给子视图正常分发。
                    result = false
                    if (evidenceReported.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
            }
            true
        }.getOrElse { throwable ->
            environment.logError(
                "live_room_block_switch_register",
                "[BIL] 直播间禁止换房 Hook 注册失败: $throwable"
            )
            false
        }
    }

    private fun installDoubleTapPause(
        environment: HookEnvironment,
        loader: ClassLoader
    ): Boolean {
        val container = KavaMemberLookup.classOrNull(loader, LIVE_PLAYER_CONTAINER_CLASS)
            ?: run {
                environment.logError(
                    "live_room_container_missing",
                    "[BIL] 直播间播放容器类缺失，双击暂停未安装"
                )
                return false
            }
        val onDoubleTap = KavaMemberLookup.declaredMethods(container, makeAccessible = true) {
            !it.isStatic && it.name == "onDoubleTap" && it.parameterCount == 1 &&
                it.parameterTypes[0] == classOf<MotionEvent>() &&
                it.returnType == classOf<Boolean>()
        }.singleOrNull() ?: run {
            environment.logError(
                "live_room_double_tap_missing",
                "[BIL] 直播间播放容器缺少 onDoubleTap，双击暂停未安装"
            )
            return false
        }
        val bridgeGetter = KavaMemberLookup.methodOrNull(container, PLAYER_BRIDGE_GETTER)
            ?.takeIf { !it.isStatic && it.parameterCount == 0 && !it.returnType.isPrimitive }
            ?: run {
                environment.logError(
                    "live_room_bridge_missing",
                    "[BIL] 直播间播放桥缺失，双击暂停未安装"
                )
                return false
            }
        // 三个控制方法都声明在播放桥的接口类型上，直接按返回类型解析，不依赖运行期实现类。
        val bridgeType = bridgeGetter.returnType
        val isPlaying = KavaMemberLookup.methodOrNull(bridgeType, "isPlaying")
            ?.takeIf { it.parameterCount == 0 && it.returnType == classOf<Boolean>() }
        val pause = voidNoArg(bridgeType, "pause")
        val resume = voidNoArg(bridgeType, "resume")
        if (isPlaying == null || pause == null || resume == null) {
            environment.logError(
                "live_room_bridge_members_missing",
                "[BIL] 直播间播放桥缺少播放控制方法，双击暂停未安装"
            )
            return false
        }

        return runCatching {
            environment.registrar.exact(
                "live.room.double_tap_pause",
                onDoubleTap.declaringClass,
                onDoubleTap.name,
                *onDoubleTap.parameterTypes
            ) {
                before {
                    val target = instance ?: return@before
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    val bridge = runCatching { bridgeGetter.invoke(target) }.getOrNull()
                        ?: return@before
                    val playing = runCatching { isPlaying.invoke(bridge) }.getOrNull() as? Boolean
                        ?: return@before
                    val toggled = runCatching {
                        if (playing) pause.invoke(bridge) else resume.invoke(bridge)
                        true
                    }.getOrDefault(false)
                    // 切换失败就放行原逻辑，保持宿主原有的双击行为，不制造"点了没反应"。
                    if (!toggled) return@before
                    result = true
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }
            }
            true
        }.getOrElse { throwable ->
            environment.logError(
                "live_room_double_tap_register",
                "[BIL] 直播间双击暂停 Hook 注册失败: $throwable"
            )
            false
        }
    }

    private fun voidNoArg(owner: Class<*>, name: String): Method? =
        KavaMemberLookup.methodOrNull(owner, name)?.takeIf { method ->
            method.parameterCount == 0 && method.returnType == Void.TYPE
        }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "live_room_widgets_missing",
            "[BIL] 直播间小件适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "live_room_widgets"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "live_room_widgets_status"
        private const val LIVE_VERTICAL_PAGER_CLASS =
            "com.bilibili.bililive.room.ui.roomv3.vertical.widget.LiveVerticalPagerView"
        private const val LIVE_PLAYER_CONTAINER_CLASS =
            "com.bilibili.bililive.room.ui.roomv3.player.container.LiveRoomPlayerContainerView"
        private const val HOST_RECYCLER_VIEW_CLASS =
            "androidx.recyclerview.widget.RecyclerView"
        private const val PLAYER_BRIDGE_GETTER = "getPlayerCommonBridge"
    }
}
