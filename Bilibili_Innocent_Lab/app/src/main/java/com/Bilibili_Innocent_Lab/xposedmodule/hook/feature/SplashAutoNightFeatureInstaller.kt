package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.BrandSplashKntrAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.ComposeChangedMask
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.util.concurrent.atomic.AtomicInteger

/**
 * 让开屏页底色跟随系统深色模式。
 *
 * 宿主的品牌开屏底色固定为白色，夜里从深色首页切过去会闪一下白屏。这里只在开屏 Fragment
 * 的 `onViewCreated` 之后以及 KMP Compose `BrandSplashPage` 的根 Modifier 处补一层跟随系统
 * 深浅色的背景色：深色模式黑、浅色模式白。
 *
 * 只改背景，不碰开屏图、logo、时长和跳过按钮——那些属于开屏广告净化的范围，两个功能互不
 * 依赖也互不覆盖。
 *
 * **版本覆盖（2026-09-06 离线核对 9.7.0–9.11.0 五版，并扩展支持 KMP `BrandSplashPage`）**：
 * `onViewCreated(View, Bundle)` 与 `splash_container` 资源名在原生页稳定；现代 KMP 页在
 * `args[2]`（Modifier）附加 `BackgroundKt.background` 并更新 `$changed` 参数槽。
 *
 * 资源 id 按名解析并缓存：宿主 `R$id` 数值每版都漂，名字稳定（[SPLASH_CONTAINER_ID_NAME]）。
 * 解析不到时退回 Fragment 根视图，仍然是一次性的、有界的降级。
 */
internal class SplashAutoNightFeatureInstaller(
    private val enabled: Boolean,
    private val kntrPageMethod: VersionAdapter.HookPoint? = null
) : FeatureInstaller {

    override val id: String = ID

    /** 0 = 尚未解析；-1 = 解析失败（后续一律用根视图），其余为宿主资源 id。 */
    private val containerId = AtomicInteger(ID_UNRESOLVED)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-class-loader")
        val fragmentClass = KavaMemberLookup.classOrNull(loader, BRAND_SPLASH_FRAGMENT_CLASS)
        val nativeMethod = fragmentClass?.let {
            KavaMemberLookup.methodOrNull(it, "onViewCreated", classOf<View>(), classOf<Bundle>())
                ?.takeIf { m -> !m.isStatic && m.returnType == Void.TYPE }
        }
        val kntrPage = BrandSplashKntrAdapter.directPage(loader)
            ?: BrandSplashKntrAdapter.resolvePage(loader, kntrPageMethod)
        val kntrExpected = BrandSplashKntrAdapter.applicable(loader) || kntrPage != null
        val nativeExpected = fragmentClass != null || !kntrExpected
        if (nativeMethod == null && kntrPage == null) {
            return missing(
                environment,
                if (fragmentClass == null && !kntrExpected) "missing-splash-fragment" else "missing-on-view-created"
            )
        }

        val expected = (if (nativeExpected) 1 else 0) + (if (kntrExpected) 1 else 0)
        var installed = 0
        if (nativeMethod != null && installNativeHook(environment, nativeMethod)) {
            installed++
        }
        if (kntrPage != null && installKntrPageHook(environment, loader, kntrPage)) {
            installed++
        }
        if (installed == 0) return missing(environment, "registration-failed")

        val complete = installed >= expected
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(CHANNEL_STATUS, if (complete) "success" else "partial:$installed/$expected")
        environment.logInfo("splash_auto_night_ok", "[BIL] 开屏页自动亮暗已安装($installed/$expected)")
        return FeatureInstallResult.Installed(installed, complete = complete)
    }

    private fun installNativeHook(
        environment: HookEnvironment,
        method: java.lang.reflect.Method
    ): Boolean = runCatching {
        environment.registrar.exact(
            "splash.auto_night.on_view_created",
            method.declaringClass,
            method.name,
            *method.parameterTypes
        ) {
            after {
                if (hasThrowable) return@after
                val root = args.getOrNull(0) as? View ?: return@after
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                if (applyNightBackground(root)) {
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }
            }
        }
        true
    }.getOrElse { throwable ->
        environment.logError(
            "splash_auto_night_register",
            "[BIL] 开屏页自动亮暗原生 Hook 注册失败: $throwable"
        )
        false
    }

    private fun installKntrPageHook(
        environment: HookEnvironment,
        loader: ClassLoader,
        page: java.lang.reflect.Method
    ): Boolean = runCatching {
        val abi = brandSplashPageAbi(page.parameterTypes.map { it.name }) ?: return false
        val viewAccess = CommentComposeViewAccess.resolve(loader) ?: return false
        val modifierClass = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.Modifier") ?: return false
        val shapeClass = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.graphics.Shape") ?: return false
        val rectShapeKt = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.graphics.RectangleShapeKt")
            ?: return false
        val rectangleShape = KavaMemberLookup.methodOrNull(rectShapeKt, "getRectangleShape")
            ?.takeIf { it.isStatic && shapeClass.isAssignableFrom(it.returnType) }
            ?.invoke(null) ?: return false
        val colorKt = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.graphics.ColorKt") ?: return false
        val colorFromInt = KavaMemberLookup.methodOrNull(colorKt, "Color", Int::class.javaPrimitiveType!!)
            ?.takeIf { it.isStatic && it.returnType == Long::class.javaPrimitiveType } ?: return false
        val blackColor = colorFromInt.invoke(null, Color.BLACK) as Long
        val whiteColor = colorFromInt.invoke(null, Color.WHITE) as Long
        val backgroundKt = KavaMemberLookup.classOrNull(loader, "androidx.compose.foundation.BackgroundKt")
            ?: return false
        val backgroundMethod = KavaMemberLookup.declaredMethods(backgroundKt, makeAccessible = true) { method ->
            method.isStatic &&
                modifierClass.isAssignableFrom(method.returnType) &&
                (method.name == "background" || method.name.startsWith("background-")) &&
                method.parameterTypes.contentEquals(arrayOf(modifierClass, Long::class.javaPrimitiveType!!, shapeClass))
        }.singleOrNull() ?: return false

        environment.registrar.exact(
            "splash.auto_night.kntr_page",
            page.declaringClass,
            page.name,
            *page.parameterTypes
        ) {
            before {
                runCatching {
                    val composer = argOrNull(abi.composerIndex) ?: return@before
                    val modifier = argOrNull(2)?.takeIf(modifierClass::isInstance) ?: return@before
                    val flags = argOrNull(abi.flagsIndex) as? Int ?: return@before
                    val root = viewAccess.read(composer) ?: return@before
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    val bgColor = if (isNightMode(root)) blackColor else whiteColor
                    val updated = backgroundMethod.invoke(null, modifier, bgColor, rectangleShape) ?: return@before
                    args[2] = updated
                    args[abi.flagsIndex] = ComposeChangedMask.markUncertain(flags, 2)
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                }
            }
        }
        true
    }.getOrElse { throwable ->
        environment.logError(
            "splash_auto_night_kntr_register",
            "[BIL] 开屏页自动亮暗 KMP Hook 注册失败: $throwable"
        )
        false
    }

    private fun isNightMode(view: View): Boolean =
        view.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** @return 是否真的写了背景色。 */
    private fun applyNightBackground(root: View): Boolean {
        val target = resolveContainer(root) ?: root
        return runCatching {
            target.setBackgroundColor(if (isNightMode(root)) Color.BLACK else Color.WHITE)
            true
        }.getOrDefault(false)
    }

    private fun resolveContainer(root: View): View? {
        var resolved = containerId.get()
        if (resolved == ID_UNRESOLVED) {
            resolved = runCatching {
                root.resources.getIdentifier(SPLASH_CONTAINER_ID_NAME, "id", TARGET_PACKAGE)
            }.getOrDefault(0).takeIf { it != 0 } ?: ID_MISSING
            containerId.compareAndSet(ID_UNRESOLVED, resolved)
            resolved = containerId.get()
        }
        if (resolved == ID_MISSING) return null
        return runCatching { root.findViewById<View>(resolved) }.getOrNull()
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError(
            "splash_auto_night_missing",
            "[BIL] 开屏页自动亮暗适配不完整: $reason"
        )
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "splash_auto_night"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "splash_auto_night_status"
        private const val BRAND_SPLASH_FRAGMENT_CLASS =
            "tv.danmaku.bili.ui.splash.brand.ui.BaseBrandSplashFragment"
        private const val SPLASH_CONTAINER_ID_NAME = "splash_container"
        private const val ID_UNRESOLVED = 0
        private const val ID_MISSING = -1
    }
}
