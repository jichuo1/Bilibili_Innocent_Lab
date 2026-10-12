package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentComposeViewAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.commentOwnerActivity
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

internal object HostBackgroundPageHooks {
    fun install(environment: HookEnvironment, pages: HostBackgroundPages): Int {
        val loader = environment.classLoader ?: return 0
        val nativeCount = installNative(environment, pages)
        val fragment = KavaMemberLookup.classOrNull(loader, "androidx.fragment.app.Fragment") ?: return nativeCount
        // 框架分发点不依赖子类调用 super.onViewCreated；部分 Toolbar 页面会截断父回调。
        val created = KavaMemberLookup.methodOrNull(fragment, "performViewCreated")
            ?: KavaMemberLookup.methodOrNull(fragment, "onViewCreated", View::class.java, Bundle::class.java) ?: return nativeCount
        val getView = KavaMemberLookup.methodOrNull(fragment, "getView") ?: return nativeCount
        environment.registrar.exact("host_background.page", created.declaringClass, created.name, *created.parameterTypes) {
            after {
                if (hasThrowable) return@after
                val type = instance?.javaClass ?: return@after
                var current: Class<*>? = type
                var kind: HostBackgroundPagePolicy.Kind? = null
                while (current != null && kind == null) {
                    kind = HostBackgroundPagePolicy.kind(current.name)
                    current = current.superclass
                }
                val pageKind = kind ?: return@after
                val root = (if (created.parameterCount == 0) getView.invoke(instance) else argOrNull(0)) as? ViewGroup ?: return@after
                // 等子类 onViewCreated 和宿主 Fragment 事务完成，适配器尚无内容也能铺背景。
                root.post { pages.attach(root, pageKind) }
            }
        }
        var count = nativeCount + 1
        val recycler = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView")
        val attached = recycler?.let { KavaMemberLookup.methodOrNull(it, "onAttachedToWindow") }
        if (attached != null) {
            environment.registrar.exact("host_background.list_attached", attached.declaringClass, attached.name, *attached.parameterTypes) {
                after {
                    if (hasThrowable) return@after
                    val list = instance as? ViewGroup ?: return@after
                    list.post { if (list.isAttachedToWindow) pages.attachHomeList(list) }
                }
            }
            count++
        }
        return count + installCommentLayers(environment, pages) + installComposeTheme(environment, pages) + installCompose(environment)
    }

    private fun installComposeTheme(environment: HookEnvironment, pages: HostBackgroundPages): Int = runCatching {
        if (!pages.requiresNight) return@runCatching 0
        val loader = environment.classLoader ?: return@runCatching 0
        val access = CommentComposeViewAccess.resolve(loader) ?: return@runCatching 0
        val owner = KavaMemberLookup.classOrNull(loader, "com.bilibili.compose.theme.BiliThemeKt") ?: return@runCatching 0
        val strategy = KavaMemberLookup.classOrNull(loader, "com.bilibili.compose.theme.ThemeStrategy") ?: return@runCatching 0
        // 安装发生在 Application.attach；这里只解析字段，不能触发依赖 Foundation 的主题枚举初始化。
        val night = KavaMemberLookup.fieldOrNull(strategy, "ForceNight") ?: return@runCatching 0
        val method = owner.declaredMethods.singleOrNull { it.name == "BiliTheme" && it.parameterCount == 6 && it.parameterTypes[0] == strategy } ?: return@runCatching 0
        val activities = HashMap<Class<*>, Boolean>()
        environment.registrar.exact("host_background.compose_night", method.declaringClass, method.name, *method.parameterTypes) {
            before {
                val defaults = argOrNull(5) as? Int ?: return@before
                if (defaults and 1 == 0 && (argOrNull(0) as? Enum<*>)?.name != "FollowApp") return@before
                val composer = argOrNull(3) ?: return@before
                val view = runCatching { access.read(composer) }.getOrNull() ?: return@before
                if (HostBackgroundPages.protectedAncestor(view)) return@before
                val activity = commentOwnerActivity(view.context) ?: return@before
                if (activity.packageName != "tv.danmaku.bili") return@before
                if (!activities.getOrPut(activity.javaClass) {
                        HostBackgroundPagePolicy.nativeActivity(generateSequence(activity.javaClass as Class<*>?) { it.superclass }.map { it.name }.toList(), activity.packageName)
                    }) return@before
                // FollowApp 的 Compose 状态流可能仍缓存日间色；显式指定夜空页面主题，不写宿主偏好。
                args[0] = night.get(null) ?: return@before
                args[4] = (argOrNull(4) as? Int ?: 0) and 14.inv()
                args[5] = defaults and 1.inv()
            }
        }
        1
    }.getOrElse {
        environment.logError("host_background_compose_night_error", "[BIL] Compose 夜空主题接入失败: $it")
        0
    }

    private fun installCommentLayers(environment: HookEnvironment, pages: HostBackgroundPages): Int {
        val loader = environment.classLoader ?: return 0
        var count = 0
        for (name in listOf("CommentMainLayer", "CommentDetailLayer", "CommentDialogLayer")) {
            val layer = KavaMemberLookup.classOrNull(loader, "com.bilibili.app.comment3.ui.view.$name") ?: continue
            // 三种内容层均以无参 View getter 返回 binding 根容器，不依赖混淆字段名。
            val root = layer.declaredMethods.singleOrNull { it.parameterCount == 0 && it.returnType == View::class.java } ?: continue
            root.isAccessible = true
            for (constructor in layer.declaredConstructors) {
                environment.registrar.constructor("host_background.comment_layer.$name.${constructor.parameterCount}", constructor) {
                    after {
                        if (hasThrowable) return@after
                        val view = runCatching { root.invoke(instance) }.getOrNull() as? ViewGroup ?: return@after
                        view.post { pages.attach(view, HostBackgroundPagePolicy.Kind.COMMENT) }
                    }
                }
                count++
            }
        }
        return count
    }

    private fun installNative(environment: HookEnvironment, pages: HostBackgroundPages): Int {
        val resume = KavaMemberLookup.methodOrNull(Activity::class.java, "onPostResume") ?: return 0
        val activities = HashMap<Class<*>, Boolean>()
        val mediaDetails = HashMap<Class<*>, Boolean>()
        environment.registrar.exact("host_background.native_page", resume.declaringClass, resume.name, *resume.parameterTypes) {
            after {
                if (hasThrowable) return@after
                val activity = instance as? Activity ?: return@after
                if (activity.packageName != "tv.danmaku.bili") return@after
                val supported = activities.getOrPut(activity.javaClass) {
                    val names = generateSequence(activity.javaClass as Class<*>?) { it.superclass }.map { it.name }.toList()
                    HostBackgroundPagePolicy.nativeActivity(names, activity.packageName)
                }
                if (!supported) return@after
                val mediaDetail = mediaDetails.getOrPut(activity.javaClass) {
                    HostBackgroundPagePolicy.mediaDetailActivity(generateSequence(activity.javaClass as Class<*>?) { it.superclass }
                        .map { it.name }.toList())
                }
                val root = activity.window?.decorView as? ViewGroup ?: return@after
                root.post {
                    if (!root.isAttachedToWindow) return@post
                    val window = activity.window
                    if (mediaDetail) {
                        pages.attach(root, HostBackgroundPagePolicy.Kind.NATIVE, window)
                        return@post
                    }
                    // 非沉浸式旧页面的 Decor 背景会避开不透明系统栏；先让窗口取消这块白色占位。
                    if (HostBackgroundPagePolicy.neutral(window.statusBarColor)) window.statusBarColor = Color.TRANSPARENT
                    if (HostBackgroundPagePolicy.neutral(window.navigationBarColor)) window.navigationBarColor = Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced = false
                    // 窗口原点涵盖状态栏和原生工具栏，避免窗口白底透出；夜空预设的系统栏文字保持可读。
                    if (pages.requiresNight) root.systemUiVisibility = root.systemUiVisibility and
                        (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
                    pages.attach(root, HostBackgroundPagePolicy.Kind.NATIVE, window)
                }
            }
        }
        return 1
    }

    /** 已接入页的纯色底板透出壁纸；媒体子树、描边和小型控件保持原样。 */
    private fun installCompose(environment: HookEnvironment): Int = runCatching {
        val loader = environment.classLoader ?: return@runCatching 0
        val view = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.platform.AndroidComposeView") ?: return@runCatching 0
        val scope = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.node.LayoutNodeDrawScope") ?: return@runCatching 0
        val colors = KavaMemberLookup.classOrNull(loader, "com.bilibili.compose.theme.BiliColors") ?: return@runCatching 0
        val bg = colors.declaredMethods.singleOrNull {
            it.name.startsWith("getBg1-") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType
        } ?: return@runCatching 0
        val draw = scope.declaredMethods.singleOrNull {
            it.name.startsWith("drawRect-") && it.parameterCount == 7 &&
                it.parameterTypes.take(3).all { p -> p == Long::class.javaPrimitiveType }
        } ?: return@runCatching 0
        val dispatch = KavaMemberLookup.methodOrNull(view, "dispatchDraw", Canvas::class.java) ?: return@runCatching 0
        val backgroundColors = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val fillTypes = HashMap<Class<*>, Boolean>()
        data class DrawPage(val width: Int, val density: Float, val native: Boolean, val preserveCards: Boolean)
        val scopes = ThreadLocal.withInitial { java.util.ArrayDeque<DrawPage>() }
        environment.registrar.exact("host_background.compose_color", bg.declaringClass, bg.name, *bg.parameterTypes) {
            after { if (!hasThrowable) (result as? Long)?.let { backgroundColors.add(it) } }
        }
        environment.registrar.exact("host_background.compose_scope", dispatch.declaringClass, dispatch.name, *dispatch.parameterTypes) {
            before {
                val node = instance as? View
                val kind = node?.let(HostBackgroundPages::kind)
                val supported = node != null && kind != null && kind != HostBackgroundPagePolicy.Kind.DYNAMIC && !HostBackgroundPages.protectedAncestor(node)
                val preserveCards = node?.let { commentOwnerActivity(it.context) }?.javaClass?.name ==
                    HostBackgroundPagePolicy.SIDE_CENTER_ACTIVITY
                scopes.get().push(DrawPage(if (supported) node!!.width else 0, node?.resources?.displayMetrics?.density ?: 1f,
                    kind == HostBackgroundPagePolicy.Kind.NATIVE || kind == HostBackgroundPagePolicy.Kind.COMMENT, preserveCards))
            }
            after {
                val stack = scopes.get()
                if (stack.isNotEmpty()) stack.pop()
                if (stack.isEmpty()) scopes.remove()
            }
        }
        environment.registrar.exact("host_background.compose_fill", draw.declaringClass, draw.name, *draw.parameterTypes) {
            before {
                val page = scopes.get().peek() ?: return@before
                // SrcOver=3；遮罩的 DstIn 等混合方式不能按页面底板跳过。
                if (page.width == 0 || argOrNull(3) != 1f || argOrNull(5) != null || argOrNull(6) != 3) return@before
                val style = argOrNull(4)?.javaClass ?: return@before
                // Fill 在宿主中会被混淆；它是无实例字段的单例，Stroke 则携带线宽等实例字段。
                if (!fillTypes.getOrPut(style) {
                        style.superclass == draw.parameterTypes[4] && style.declaredFields.all { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    }) return@before
                val color = argOrNull(0) as? Long ?: return@before
                // 侧边中心用 Bg2 铺页面，Bg1 绘制内容面板：圆角顶盖和矩形正文是独立节点。
                // 必须一起保留 Bg1，否则只剩白色顶盖，正文和底部填充被壁纸挖空。
                if (page.preserveCards && color in backgroundColors) return@before
                val packed = argOrNull(2) as? Long ?: return@before
                val height = Float.fromBits(packed.toInt())
                if (color !in backgroundColors && !(page.native && HostBackgroundPagePolicy.nativeComposePanel(color, height, page.density))) return@before
                if (HostBackgroundPagePolicy.pageRectangle(Float.fromBits((packed ushr 32).toInt()), height, page.width)) result = null
            }
        }
        3
    }.getOrElse {
        environment.logError("host_background_compose_error", "[BIL] Compose 页面背景接入失败: $it")
        0
    }
}
