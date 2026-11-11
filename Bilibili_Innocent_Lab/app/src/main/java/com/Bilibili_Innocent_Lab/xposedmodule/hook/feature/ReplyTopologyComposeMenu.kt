package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.SystemClock
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.lang.reflect.Method

/** 一次更多操作的身份；来源与菜单均弱持有，旧回调不能跨操作／窗口重用。 */
internal class ReplyTopologyComposeMenuContext<A : Any, S : Any> {
    class Frame<A : Any, S : Any>(anchor: A, root: A, window: Any, val seed: S,
        val time: Long, val sourceCurrent: () -> Boolean) {
        val anchor = WeakReference(anchor)
        val root = WeakReference(root)
        val window = WeakReference(window)
        var menu = WeakReference<A>(null)
        var menuRoot = WeakReference<A>(null)
        var menuWindow = WeakReference<Any>(null)
        var menuBound = false
        var menuWindowBound = false
        var source = WeakReference<List<*>>(null)
        var replacement = WeakReference<List<*>>(null)
        var legacyRow: Pair<Method, String>? = null
        var legacyModifier: Any? = null
    }
    private var frame: Frame<A, S>? = null

    fun current(): Frame<A, S>? = frame
    fun clear() { frame = null }

    fun remember(anchor: A, root: A, window: Any?, seed: S?, time: Long,
        sourceCurrent: () -> Boolean = { true }) {
        frame = if (seed == null || window == null) null
        else Frame(anchor, root, window, seed, time, sourceCurrent)
    }

    fun acceptsSource(current: Frame<A, S>, root: A, window: Any?, now: Long): Boolean {
        if (frame !== current) return false
        val valid = current.anchor.get() != null && current.root.get() === root &&
            window != null && current.window.get() === window && now - current.time in 0L..30_000L &&
            runCatching(current.sourceCurrent).getOrDefault(false)
        if (!valid) clear()
        return valid
    }

    fun bindMenu(current: Frame<A, S>, menu: A, root: A, window: Any?): Boolean {
        if (frame !== current) return false
        if (current.menuBound && (current.menu.get() !== menu || current.menuRoot.get() !== root)) return false
        if (current.menuWindowBound && current.menuWindow.get() !== window) return false
        if (!current.menuBound) {
            current.menu = WeakReference(menu)
            current.menuRoot = WeakReference(root)
            current.menuBound = true
        }
        // Popup 可以先 composition 再 attach；首次取得 token 后锁定，不允许换窗。
        if (!current.menuWindowBound && window != null) {
            current.menuWindow = WeakReference(window)
            current.menuWindowBound = true
        }
        return true
    }

    fun ownsMenu(current: Frame<A, S>, menu: A): Boolean =
        frame === current && current.menuBound && current.menu.get() === menu
}

/** 在宿主原生 Compose 菜单追加一项，不接管三点按钮、不依赖自由复制开关。 */
internal class ReplyTopologyComposeMenu(private val open: (View, ReplyTopologySeed) -> Unit) {
    private val context = ReplyTopologyComposeMenuContext<View, ReplyTopologySeed>()
    private val injecting = ThreadLocal.withInitial { false }

    fun clear() = context.clear()

    fun remember(anchor: View, seed: ReplyTopologySeed?, sourceCurrent: () -> Boolean = { true }) {
        clear()
        if (seed == null || !anchor.isAttachedToWindow || !anchor.isShown) return
        context.remember(anchor, anchor.rootView, anchor.windowToken, seed,
            SystemClock.uptimeMillis(), sourceCurrent)
    }

    private fun activeFrame(): ReplyTopologyComposeMenuContext.Frame<View, ReplyTopologySeed>? {
        val current = context.current() ?: return null
        val anchor = current.anchor.get()
        if (anchor == null || !anchor.isAttachedToWindow || !anchor.isShown) {
            clear()
            return null
        }
        return current.takeIf { context.acceptsSource(it, anchor.rootView, anchor.windowToken, SystemClock.uptimeMillis()) }
    }

    private fun acceptsClick(current: ReplyTopologyComposeMenuContext.Frame<View, ReplyTopologySeed>, menu: View,
        menuCanBeDismissed: Boolean = false): Boolean {
        if (activeFrame() !== current || !context.ownsMenu(current, menu)) return false
        // 新气泡菜单先 requestDismiss 再执行 entry.action；来源评论仍有效时允许完成这次点击。
        if (menuCanBeDismissed && !menu.isAttachedToWindow) return true
        return menu.isAttachedToWindow && menu.isShown && menu.windowToken != null &&
            context.bindMenu(current, menu, menu.rootView, menu.windowToken)
    }

    fun install(environment: HookEnvironment): FeatureInstallResult {
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val owner = KavaMemberLookup.classOrNull(loader, "kntr.common.comment.common.ui.moremenu.MoreMenuBubbleKt")
            ?: return installLegacy(environment, loader)
        val row = KavaMemberLookup.declaredMethods(owner).singleOrNull { method ->
            method.isStatic && method.returnType == Void.TYPE &&
                method.parameterTypes.map { it.name } == listOf("java.util.List", "kotlin.jvm.functions.Function1",
                    "androidx.compose.runtime.Composer", "int")
        } ?: return FeatureInstallResult.Skipped("ambiguous-compose-menu")
        val item = KavaMemberLookup.declaredMethods(owner).mapNotNull { method ->
            val params = method.parameterTypes
            if (params.size < 4 || params.getOrNull(1)?.name != "kotlin.jvm.functions.Function0") null
            else params[0].takeIf {
                it.name.startsWith("kntr.common.comment.common.ui.moremenu.") ||
                    // 9.19.0: row d casts each item to v0f1 and calls this exact renderer b.
                    (row.name == "d" && method.name == "b" && method.isStatic && method.returnType == Void.TYPE &&
                        params.map(Class<*>::getName) == listOf("v0f1", "kotlin.jvm.functions.Function0",
                            "androidx.compose.ui.Modifier", "androidx.compose.runtime.Composer", "int"))
            }
        }.distinct().singleOrNull() ?: return FeatureInstallResult.Skipped("missing-compose-menu-entry")
        val fields = KavaMemberLookup.declaredFields(item, makeAccessible = true)
            .filterNot { it.isStatic }.sortedBy { it.name }
        if (fields.firstOrNull()?.type != classOf<String>() ||
            fields.lastOrNull()?.type?.name != "kotlin.jvm.functions.Function0"
        ) return FeatureInstallResult.Skipped("missing-compose-menu-entry-shape")
        val constructor = KavaMemberLookup.declaredConstructors(item).singleOrNull {
            it.parameterTypes.contentEquals(fields.map { field -> field.type }.toTypedArray())
        } ?: return FeatureInstallResult.Skipped("missing-compose-menu-entry-constructor")
        val function = fields.last().type
        val unit = KavaMemberLookup.classOrNull(loader, "kotlin.Unit")?.let {
            KavaMemberLookup.fieldOrNull(it, "INSTANCE")?.get(null)
        } ?: return FeatureInstallResult.Skipped("missing-host-unit")
        val viewAccess = CommentComposeViewAccess.resolve(loader)
            ?: return FeatureInstallResult.Skipped("missing-local-view")
        return runCatching {
            environment.registrar.exact("reply.topology.compose.menu", owner, row.name, *row.parameterTypes) {
                before {
                    val current = activeFrame() ?: return@before
                    val anchor = current.anchor.get() ?: return@before
                    val menuView = argOrNull(2)?.let(viewAccess::read) ?: return@before
                    val ownerActivity = commentOwnerActivity(anchor.context) ?: return@before
                    if (commentOwnerActivity(menuView.context) !== ownerActivity) return@before
                    if (!context.bindMenu(current, menuView, menuView.rootView, menuView.windowToken)) return@before
                    val source = argOrNull(0) as? List<*> ?: return@before
                    if (source.isEmpty() || source.size > 16 || source.any { !item.isInstance(it) }) return@before
                    val old = current.source.get()
                    if (old != null && old !== source) return@before
                    val merged = current.replacement.get() ?: run {
                        val sample = source.first() ?: return@before
                        val values = fields.map { it.get(sample) }.toTypedArray()
                        values[0] = InjectedUiLocale.messages().replyTopologyEntryLabel
                        val menuAnchor = WeakReference(menuView)
                        values[values.lastIndex] = Proxy.newProxyInstance(loader, arrayOf(function)) { proxy, method, args ->
                            when (method.name) {
                                "invoke" -> {
                                    HostThreadGuard.run("reply_topology.compose_menu_click") {
                                        val menu = menuAnchor.get() ?: return@run
                                        if (!acceptsClick(current, menu, menuCanBeDismissed = true)) return@run
                                        val view = current.anchor.get() ?: return@run
                                        open(view, current.seed)
                                    }
                                    unit
                                }
                                "equals" -> proxy === args?.getOrNull(0)
                                "hashCode" -> System.identityHashCode(proxy)
                                "toString" -> "InnocentReplyTopologyAction"
                                else -> hostProxyDefaultValue(method.returnType)
                            }
                        }
                        val entry = constructor.newInstance(*values)
                        (source + entry).also {
                            current.source = WeakReference(source)
                            current.replacement = WeakReference(it)
                        }
                    }
                    args[0] = merged
                    // 与 NativeFeedbackPanel 相同：参数 0 的 changed 槽是 1–3 位，保留 force 位。
                    args[3] = (argOrNull(3) as Int) and 0b1110.inv()
                    environment.reportRuntimeEvidence(CommentTopologyFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED)
                }
            }
            FeatureInstallResult.Installed(1)
        }.getOrElse { FeatureInstallResult.Skipped("compose-menu-registration-failed") }
    }

    /** 8.96–9.10 的菜单没有 entry 列表，在已验证的纵向菜单行之后追加同型行。 */
    private fun installLegacy(environment: HookEnvironment, loader: ClassLoader): FeatureInstallResult {
        val names = listOf("CommentMoreMenuKt") + ('a'..'z').map(Char::toString) +
            ('A'..'Z').map(Char::toString) + ('a'..'z').map { "${it}0" } + ('A'..'Z').map { "${it}0" }
        val candidates = names.mapNotNull {
            KavaMemberLookup.classOrNull(loader, "kntr.common.comment.page.ui.$it")
        }.flatMap { owner -> KavaMemberLookup.declaredMethods(owner, makeAccessible = true).filter { method ->
            val types = method.parameterTypes.map { it.name }
            method.isStatic && !method.isSynthetic && method.returnType == Void.TYPE &&
                types.size in 8..9 && types[0].startsWith("kntr.common.bilitheme.compose.bottomsheet.") &&
                types.subList(1, 5) == listOf("java.lang.String", "androidx.compose.ui.graphics.painter.Painter",
                    "androidx.compose.ui.Modifier", "androidx.compose.ui.graphics.painter.Painter") &&
                types.takeLast(3) == listOf("androidx.compose.runtime.Composer", "int", "int") &&
                (types.size == 8 || types[5] == "boolean")
        } }
        val row = candidates.singleOrNull() ?: return FeatureInstallResult.Skipped("ambiguous-legacy-compose-menu")
        val modifierClass = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.Modifier")
            ?: return FeatureInstallResult.Skipped("missing-host-modifier")
        val companion = KavaMemberLookup.fieldOrNull(modifierClass, "Companion")?.get(null)
            ?: return FeatureInstallResult.Skipped("missing-host-modifier")
        val function = KavaMemberLookup.classOrNull(loader, "kotlin.jvm.functions.Function0")
            ?: return FeatureInstallResult.Skipped("missing-host-callback")
        val clickOwner = KavaMemberLookup.classOrNull(loader, "androidx.compose.foundation.ClickableKt")
            ?: return FeatureInstallResult.Skipped("missing-host-clickable")
        val clickable = KavaMemberLookup.declaredMethods(clickOwner).singleOrNull {
            val ps = it.parameterTypes
            it.isStatic && it.name.endsWith("\$default") && it.returnType == modifierClass && ps.size == 8 &&
                ps[0] == modifierClass && ps[1] == classOf<Boolean>() && ps[5] == function && ps[6] == classOf<Int>()
        } ?: return FeatureInstallResult.Skipped("ambiguous-host-clickable")
        val unit = KavaMemberLookup.classOrNull(loader, "kotlin.Unit")?.let {
            KavaMemberLookup.fieldOrNull(it, "INSTANCE")?.get(null)
        } ?: return FeatureInstallResult.Skipped("missing-host-unit")
        val views = CommentComposeViewAccess.resolve(loader) ?: return FeatureInstallResult.Skipped("missing-local-view")
        return runCatching {
            environment.registrar.exact("reply.topology.compose.legacy_menu", row.declaringClass, row.name, *row.parameterTypes) {
                after {
                    if (hasThrowable || injecting.get() == true) return@after
                    val current = activeFrame() ?: return@after
                    val anchor = current.anchor.get() ?: return@after
                    val composerIndex = row.parameterCount - 3
                    val menuView = argOrNull(composerIndex)?.let(views::read) ?: return@after
                    val activity = commentOwnerActivity(anchor.context) ?: return@after
                    if (commentOwnerActivity(menuView.context) !== activity) return@after
                    if (!context.bindMenu(current, menuView, menuView.rootView, menuView.windowToken)) return@after
                    val title = argOrNull(1) as? String ?: return@after
                    val key = current.legacyRow
                    if (key != null && key != (row to title)) return@after
                    current.legacyRow = row to title
                    val modifier = current.legacyModifier ?: run {
                        // 旧纵向菜单不带独立 dismiss 回调；挂到它自己的窗口，避免被菜单盖住。
                        val menuAnchor = WeakReference(menuView)
                        val callback = Proxy.newProxyInstance(loader, arrayOf(function)) { proxy, method, values ->
                            when (method.name) {
                                "invoke" -> {
                                    HostThreadGuard.run("reply_topology.compose_legacy_click") {
                                        menuAnchor.get()?.takeIf { acceptsClick(current, it) }
                                            ?.let { open(it, current.seed) }
                                    }
                                    unit
                                }
                                "equals" -> proxy === values?.getOrNull(0)
                                "hashCode" -> System.identityHashCode(proxy)
                                "toString" -> "InnocentReplyTopologyAction"
                                else -> hostProxyDefaultValue(method.returnType)
                            }
                        }
                        clickable.invoke(null, companion, false, null, null, null, callback, 15, null)
                            .also { current.legacyModifier = it }
                    }
                    val values = args.copyOf()
                    values[1] = InjectedUiLocale.messages().replyTopologyEntryLabel
                    values[3] = modifier
                    // 新增调用使用 force + Uncertain；不改原菜单行的 changed/default 参数。
                    values[composerIndex + 1] = 1
                    values[composerIndex + 2] = (values[composerIndex + 2] as Int) and 4.inv()
                    injecting.set(true)
                    try {
                        row.invoke(null, *values)
                        environment.reportRuntimeEvidence(CommentTopologyFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED)
                    } finally {
                        injecting.remove()
                    }
                }
            }
            FeatureInstallResult.Installed(1)
        }.getOrElse { FeatureInstallResult.Skipped("legacy-compose-menu-registration-failed") }
    }
}
