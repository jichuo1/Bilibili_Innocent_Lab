package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

/** 构造字段语义在 20 份 APK 中核对：j 原文、k 译文；o/p 是截断附件，不能拿来复制。 */
internal class ComposeCommentText private constructor(
    private val id: Field,
    private val language: Field,
    private val origin: Field,
    private val translated: Field,
    private val raw: Field,
    private val commentId: Field,
    private val rootId: Field
) {
    fun cardId(model: Any): Long? = id.get(model) as? Long
    fun rootCommentId(model: Any): Long? = ((rootId.get(model) as? Long)?.takeIf { it > 0 }
        ?: commentId.get(model) as? Long)?.takeIf { it > 0 }

    fun read(model: Any): String? {
        val state = language.get(model) as? Enum<*>
        val rich = if (state?.name == "TRANSLATION") translated.get(model) ?: origin.get(model)
        else origin.get(model)
        return rich?.let { raw.get(it) as? String }?.takeIf { it.isNotBlank() }
    }

    companion object {
        private val LANGUAGE_STATES = setOf("ORIGIN", "TRANSLATING", "TRANSLATION")

        fun resolve(type: Class<*>): ComposeCommentText? = runCatching {
            val fields = KavaMemberLookup.declaredFields(type, makeAccessible = true)
                .filterNot { it.isStatic }
            val language = fields.singleOrNull { field ->
                field.type.isEnum &&
                    field.type.enumConstants.orEmpty().map { (it as Enum<*>).name }.toSet() == LANGUAGE_STATES
            } ?: return@runCatching null

            val namedOrigin = KavaMemberLookup.fieldOrNull(type, "j")
            val namedTranslated = namedOrigin?.let {
                KavaMemberLookup.fieldOrNull(type, "k")?.takeIf { f -> f.type == it.type }
            }
            val namedAttachmentsOk = namedOrigin != null && listOf("o", "p").all {
                KavaMemberLookup.fieldOrNull(type, it)?.type == namedOrigin.type
            }
            val (origin, translated) = when {
                namedOrigin != null && namedTranslated != null && namedAttachmentsOk ->
                    namedOrigin to namedTranslated
                namedOrigin != null && namedTranslated != null && stringField(namedOrigin.type) != null ->
                    namedOrigin to namedTranslated
                else -> {
                    val counts = fields.map { it.type }.groupingBy { it }.eachCount()
                    val richType = listOf(4, 2).firstNotNullOfOrNull { expectedCount ->
                        counts.filter { (candidate, count) ->
                            count == expectedCount && !candidate.isPrimitive && candidate != language.type &&
                                stringField(candidate) != null
                        }.keys.singleOrNull()
                    } ?: return@runCatching null
                    val matchedFields = fields.filter { it.type == richType }
                    matchedFields[0] to matchedFields[1]
                }
            }
            val raw = stringField(origin.type) ?: return@runCatching null

            val namedId = KavaMemberLookup.fieldOrNull(type, "a")?.takeIf { it.type == classOf<Long>() }
            val namedComment = KavaMemberLookup.fieldOrNull(type, "b")?.takeIf { it.type == classOf<Long>() }
            val namedRoot = KavaMemberLookup.fieldOrNull(type, "c")?.takeIf { it.type == classOf<Long>() }
            val (id, comment, root) = if (namedId != null && namedComment != null && namedRoot != null) {
                Triple(namedId, namedComment, namedRoot)
            } else {
                val longs = fields.filter { it.type == classOf<Long>() }
                if (longs.size != 3) return@runCatching null
                Triple(longs[0], longs[1], longs[2])
            }
            ComposeCommentText(id, language, origin, translated, raw, comment, root)
        }.getOrNull()

        private fun stringField(type: Class<*>): Field? =
            KavaMemberLookup.fieldOrNull(type, "a")?.takeIf { it.type == classOf<String>() }
                ?: KavaMemberLookup.declaredFields(type, makeAccessible = true)
                    .singleOrNull { !it.isStatic && it.type == classOf<String>() }
    }
}

/** Dispatcher 身份隔离窗口／页面，同一 cardId 在另一页出现时不能串正文；不强持宿主对象。 */
internal class ComposeCommentBindings<K : Any, M : Any, A : Any> {
    private data class Binding<M : Any, A : Any>(val model: WeakReference<M>, val anchor: WeakReference<A>)
    private class StoreSlot<M : Any, A : Any> {
        var latest: Binding<M, A>? = null
        val byId = object : LinkedHashMap<Long, Binding<M, A>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Binding<M, A>>?): Boolean = size > 64
        }
    }
    private val bindings = WeakHashMap<K, StoreSlot<M, A>>()

    @Synchronized fun bind(owner: K, model: M, anchor: A) {
        val slot = slotFor(owner)
        val old = slot.latest
        if (old?.model?.get() === model && old.anchor.get() === anchor) return
        slot.latest = Binding(WeakReference(model), WeakReference(anchor))
    }

    @Synchronized fun bind(owner: K, itemId: Long, model: M, anchor: A) {
        val slot = slotFor(owner)
        val old = slot.byId[itemId]
        val entry = if (old?.model?.get() === model && old.anchor.get() === anchor) {
            old
        } else {
            Binding(WeakReference(model), WeakReference(anchor)).also { slot.byId[itemId] = it }
        }
        slot.latest = entry
    }

    @Synchronized fun get(owner: K): Pair<M, A>? {
        val slot = bindings[owner] ?: return null
        val bound = slot.latest ?: return null
        val resolved = resolveLive(bound)
        if (resolved == null) slot.latest = null
        return resolved
    }

    @Synchronized fun get(owner: K, itemId: Long): Pair<M, A>? {
        val slot = bindings[owner] ?: return null
        val bound = slot.byId[itemId] ?: return null
        val resolved = resolveLive(bound)
        if (resolved == null) slot.byId.remove(itemId)
        return resolved
    }

    private fun slotFor(owner: K): StoreSlot<M, A> {
        bindings[owner]?.let { return it }
        if (bindings.size >= 64) {
            bindings.entries.removeAll { (_, slot) ->
                slot.byId.entries.removeAll { it.value.model.get() == null || it.value.anchor.get() == null }
                val latestLive = slot.latest?.let { it.model.get() != null && it.anchor.get() != null } == true
                if (!latestLive) slot.latest = null
                !latestLive && slot.byId.isEmpty()
            }
            if (bindings.size >= 64) bindings.remove(bindings.keys.first())
        }
        return StoreSlot<M, A>().also { bindings[owner] = it }
    }

    private fun resolveLive(bound: Binding<M, A>): Pair<M, A>? {
        val model = bound.model.get() ?: return null
        val anchor = bound.anchor.get() ?: return null
        return model to anchor
    }
}

/** 不 Hook 混淆的手势 lambda：只在评论 Store 收到 LONG_PRESS 且弹窗成功时消费该动作。 */
internal class CommentComposeCopyBridge(
    private val enabled: () -> Boolean,
    private val open: (View, String) -> Boolean,
    private val observe: () -> Boolean = enabled,
    private val onMore: (View, Long, () -> Boolean) -> Unit = { _, _, _ -> },
    private val resetMore: () -> Unit = {}
) {
    private val bindings = ComposeCommentBindings<Any, Any, View>()

    fun install(environment: HookEnvironment): FeatureInstallResult {
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val modelClass = KavaMemberLookup.classOrNull(loader, MODEL)
            ?: return FeatureInstallResult.Skipped("not-applicable-host")
        fun missing(reason: String): FeatureInstallResult {
            environment.logError("free_copy_compose_$reason", "[BIL] Compose 评论自由复制未完整接入: $reason")
            return FeatureInstallResult.Skipped(reason)
        }
        val text = ComposeCommentText.resolve(modelClass) ?: return missing("missing-text-shape")
        val renderer = KavaMemberLookup.classOrNull(loader, RENDERER) ?: return missing("missing-renderer")
        val actionClass = KavaMemberLookup.classOrNull(loader, ACTION) ?: return missing("missing-action")
        val sourceClass = KavaMemberLookup.classOrNull(loader, SOURCE) ?: return missing("missing-source")
        val action = actionClass.declaredClasses.singleOrNull { type ->
            val fields = KavaMemberLookup.declaredFields(type)
            fields.count { it.type == sourceClass } == 1 && fields.count { it.type == classOf<Long>() } == 1
        } ?: return missing("ambiguous-more-action")
        val actionSource = KavaMemberLookup.declaredFields(action, makeAccessible = true).single { it.type == sourceClass }
        val actionId = KavaMemberLookup.declaredFields(action, makeAccessible = true).single { it.type == classOf<Long>() }
        val viewAccess = CommentComposeViewAccess.resolve(loader) ?: return missing("missing-local-view")
        val dispatchers = linkedSetOf<Method>()
        var renderInstalled = 0
        var renderExpected = 0
        KavaMemberLookup.declaredMethods(renderer).forEach { method ->
            val modelIndex = method.parameterTypes.indexOf(modelClass)
            val composerIndex = method.parameterTypes.indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
            if (method.isStatic || modelIndex < 0 || composerIndex < 0) return@forEach
            renderExpected++
            val contexts = method.parameterTypes.withIndex().mapNotNull { (index, type) ->
                if (type.isPrimitive || type.isInterface || type.isArray) return@mapNotNull null
                val fields = KavaMemberLookup.declaredFields(type, makeAccessible = true).filter { field ->
                    KavaMemberLookup.declaredMethods(field.type).any {
                        !it.isStatic && it.returnType == Void.TYPE && it.parameterCount == 1 &&
                            it.parameterTypes[0] != classOf<Any>() && actionClass isSubclassOf it.parameterTypes[0]
                    }
                }
                fields.singleOrNull()?.let { index to it }
            }
            val (contextIndex, storeField) = contexts.singleOrNull() ?: return@forEach
            val dispatch = KavaMemberLookup.declaredMethods(storeField.type).singleOrNull {
                !it.isStatic && it.returnType == Void.TYPE && it.parameterCount == 1 &&
                    it.parameterTypes[0] != classOf<Any>() && actionClass isSubclassOf it.parameterTypes[0]
            } ?: return@forEach
            runCatching {
                environment.registrar.exact("free.copy.compose.render.${method.name}", renderer, method.name, *method.parameterTypes) {
                    after {
                        if (!observe() || hasThrowable) return@after
                        runCatching {
                            val model = argOrNull(modelIndex) ?: return@runCatching
                            val context = argOrNull(contextIndex) ?: return@runCatching
                            val store = storeField.get(context) ?: return@runCatching
                            val composer = argOrNull(composerIndex) ?: return@runCatching
                            val anchor = viewAccess.read(composer) ?: return@runCatching
                            val cardId = text.cardId(model)
                            if (cardId != null) bindings.bind(store, cardId, model, anchor) else bindings.bind(store, model, anchor)
                        }.onFailure { environment.logError("free_copy_compose_binding", "[BIL] Compose 评论窗口绑定失败，保留原操作") }
                    }
                }
                dispatchers += dispatch
                renderInstalled++
            }.onFailure { environment.logError("free_copy_compose_register_render", "[BIL] Compose 评论渲染桥注册失败") }
        }
        var actionInstalled = 0
        dispatchers.forEach { method ->
            runCatching {
                environment.registrar.exact("free.copy.compose.dispatch.${method.declaringClass.name}", method.declaringClass, method.name, *method.parameterTypes) {
                    before {
                        if (!observe()) return@before
                        val request = argOrNull(0)?.takeIf(action::isInstance) ?: return@before
                        // 先使上一条菜单失效；没有绑定／正文身份不符也不能沿用旧脉络。
                        resetMore()
                        val source = (actionSource.get(request) as? Enum<*>)?.name
                        val store = instance ?: return@before
                        val requestedId = actionId.get(request) as? Long
                        val (model, anchor) = (requestedId?.let { bindings.get(store, it) } ?: bindings.get(store))
                            ?: return@before
                        if (!anchor.isAttachedToWindow || !anchor.isShown || anchor.windowToken == null) return@before
                        if (requestedId != text.cardId(model)) return@before
                        if (source == "LONG_PRESS" || source == "BUTTON") {
                            text.rootCommentId(model)?.let { rootId ->
                                val ownerRef = WeakReference(store)
                                val anchorRef = WeakReference(anchor)
                                val cardId = text.cardId(model)
                                onMore(anchor, rootId) {
                                    val owner = ownerRef.get()
                                    val current = owner?.let { target ->
                                        cardId?.let { id -> bindings.get(target, id) } ?: bindings.get(target)
                                    }
                                    current != null && current.second === anchorRef.get() &&
                                        text.cardId(current.first) == cardId &&
                                        text.rootCommentId(current.first) == rootId
                                }
                            }
                        }
                        if (!enabled() || source != "LONG_PRESS" || !anchor.hasWindowFocus()) return@before
                        val raw = text.read(model) ?: run {
                            environment.logError("free_copy_compose_empty", "[BIL] Compose 评论完整正文不可用，保留原菜单")
                            return@before
                        }
                        environment.reportRuntimeEvidence("free_copy_comment_enabled", FeatureRuntimeStage.OBSERVED)
                        if (open(anchor, raw)) {
                            resetMore()
                            result = null
                            environment.reportRuntimeEvidence("free_copy_comment_enabled", FeatureRuntimeStage.APPLIED)
                        }
                    }
                }
                actionInstalled++
            }.onFailure { environment.logError("free_copy_compose_register_action", "[BIL] Compose 评论动作桥注册失败") }
        }
        if (renderInstalled == 0 || actionInstalled == 0) return missing("registration-failed")
        return FeatureInstallResult.Installed(renderInstalled + actionInstalled,
            complete = renderInstalled == renderExpected && actionInstalled == dispatchers.size)
    }

    private companion object {
        const val MODEL = "kntr.common.comment.card.model.comment.CommentRichTextRendererModel"
        const val RENDERER = "kntr.common.comment.card.renderer.comment.CommentRichTextRenderer"
        const val ACTION = "kntr.common.comment.card.action.CardAction"
        const val SOURCE = "kntr.common.comment.card.action.CardAction\$MoreButtonSource"
    }
}
