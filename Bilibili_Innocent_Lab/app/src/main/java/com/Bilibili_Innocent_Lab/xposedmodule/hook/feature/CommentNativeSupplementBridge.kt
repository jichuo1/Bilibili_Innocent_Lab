package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.view.View
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal class CommentRichTextReader private constructor(private val field: Field?, private val method: Method?) {
    fun read(value: Any): String? = (field?.get(value) ?: method?.invoke(value)) as? String
    companion object {
        fun resolve(type: Class<*>): CommentRichTextReader? {
            if (type.isPrimitive || type.name.startsWith("java.")) return null
            val field = listOf("a", "raw").mapNotNull { KavaMemberLookup.fieldOrNull(type, it) }
                .firstOrNull { !it.isStatic && it.type == classOf<String>() }
                ?: KavaMemberLookup.declaredFields(type, makeAccessible = true) {
                    !it.isStatic && it.type == classOf<String>()
                }.singleOrNull()
            if (field != null) return CommentRichTextReader(field, null)
            val getter = listOf("getRaw", "e").mapNotNull { KavaMemberLookup.inheritedMethodOrNull(type, it) }
                .firstOrNull { !it.isStatic && it.parameterCount == 0 && it.returnType == classOf<String>() }
                ?: KavaMemberLookup.declaredMethods(type, makeAccessible = true) {
                    !it.isStatic && it.parameterCount == 0 && it.name != "toString" && it.returnType == classOf<String>()
                }.singleOrNull()
            return getter?.let { CommentRichTextReader(null, it) }
        }
    }
}

/** 专用 Binding 身份限定 image/push，不扩大全局 id/content 的含义。 */
internal class CommentNativeSupplementBridge(
    private val enabled: () -> Boolean,
    private val bind: (View, String?, Any?) -> Unit,
    private val rawComment: (Any) -> String?
) {
    private class ImagePlan(val binding: Field, val body: Field, val item: Field)
    private class ImageOwner(val owner: WeakReference<Any>, val plan: ImagePlan)
    private val imageOwners = ArrayList<ImageOwner>()
    private var imageBodyClass: Class<*>? = null
    private val pushBodyIds = ConcurrentHashMap<Class<*>, Pair<Int, Int>>()

    /** 长按时再从当前 Binding 验真，避免图片翻页后使用上一张图片的模型。 */
    fun imageContent(view: View): Pair<String, Any>? {
        if (imageBodyClass?.isInstance(view) != true) return null
        synchronized(imageOwners) {
            imageOwners.removeAll { it.owner.get() == null }
            var matchedRaw: String? = null
            var matchedItem: Any? = null
            for (entry in imageOwners) {
                val owner = entry.owner.get() ?: continue
                val binding = entry.plan.binding.get(owner) ?: continue
                if (entry.plan.body.get(binding) !== view) continue
                val item = entry.plan.item.get(owner) ?: continue
                val raw = rawComment(item) ?: continue
                if (matchedItem != null) return null
                matchedRaw = raw
                matchedItem = item
            }
            return matchedItem?.let { requireNotNull(matchedRaw) to it }
        }
    }

    fun isImageBody(view: View): Boolean = imageBodyClass?.isInstance(view) == true

    @SuppressLint("DiscouragedApi") // 宿主资源不属于模块 R；只在专用 Binding 首次使用时解析并缓存。
    fun install(environment: HookEnvironment): Map<String, FeatureInstallResult> {
        if (!enabled()) return emptyMap()
        val loader = environment.classLoader ?: return emptyMap()
        val results = linkedMapOf<String, FeatureInstallResult>()
        imageBodyClass = KavaMemberLookup.classOrNull(loader, IMAGE_BODY)
        imageBodyClass?.let { bodyClass ->
            var expected = 0
            var installed = 0
            owners(IMAGE_HANDLERS).forEach { name ->
                val owner = KavaMemberLookup.classOrNull(loader, name) ?: return@forEach
                val fields = KavaMemberLookup.declaredFields(owner, makeAccessible = true)
                val item = fields.singleOrNull { it.type.name == COMMENT_ITEM } ?: return@forEach
                val binding = fields.mapNotNull { field ->
                    val body = KavaMemberLookup.declaredFields(field.type, makeAccessible = true)
                        .singleOrNull { it.type == bodyClass } ?: return@mapNotNull null
                    field to body
                }.singleOrNull() ?: return@forEach
                expected++
                val plan = ImagePlan(binding.first, binding.second, item)
                var hooked = false
                KavaMemberLookup.declaredConstructors(owner).forEach { constructor ->
                    runCatching {
                        environment.registrar.constructor("free.copy.image.owner.${owner.name}.${constructor.parameterCount}", constructor) {
                            after {
                                if (!enabled()) return@after
                                val value = instance ?: return@after
                                synchronized(imageOwners) {
                                    imageOwners.removeAll { it.owner.get() == null || it.owner.get() === value }
                                    if (imageOwners.size >= 32) imageOwners.removeAt(0)
                                    imageOwners.add(ImageOwner(WeakReference(value), plan))
                                }
                            }
                        }
                        hooked = true
                    }
                }
                if (hooked) installed++
            }
            if (installed > 0) runCatching {
                // 最近的宿主声明层同时覆盖直接 setText 和 setSpannableText 的虚调用。
                // 只有宿主没有覆盖成员时才用平台兜底，避免整 App 文本更新都经过本回调。
                val setter = KavaMemberLookup.inheritedMethodOrNull(bodyClass, "setText",
                    classOf<CharSequence>(), classOf<TextView.BufferType>())
                    ?: error("missing-image-text-setter")
                environment.registrar.exact("free.copy.image.text", setter.declaringClass, setter.name,
                    *setter.parameterTypes) {
                    after {
                        if (!enabled() || hasThrowable) return@after
                        val view = instance as? View ?: return@after
                        if (!bodyClass.isInstance(view)) return@after
                        imageContent(view)?.let { (raw, item) -> bind(view, raw, item) }
                    }
                }
            }.onFailure { installed = 0 }
            results["image"] = if (installed > 0) FeatureInstallResult.Installed(installed, installed == expected)
            else FeatureInstallResult.Skipped("missing-image-binding")
        }
        var pushExpected = 0
        var pushInstalled = 0
        owners(PUSH_HOLDERS).forEach { name ->
            val owner = KavaMemberLookup.classOrNull(loader, name) ?: return@forEach
            val fields = KavaMemberLookup.declaredFields(owner, makeAccessible = true)
            val binding = fields.singleOrNull { field ->
                val body = KavaMemberLookup.declaredFields(field.type)
                body.count { it.type.name.endsWith(".RichTextView") } >= 3 &&
                    KavaMemberLookup.methodOrNull(field.type, "getRoot")?.returnType?.let { it isSubclassOf classOf<View>() } == true
            } ?: return@forEach
            pushExpected++
            // 生成 Binding 字段名会漂移；最终以该专用 Binding 内的正文资源身份绑定。
            val texts = KavaMemberLookup.declaredFields(binding.type, makeAccessible = true)
                .filter { it.type.name.endsWith(".RichTextView") }.sortedBy { it.name }
            if (texts.size != 3) return@forEach
            val dataClass = (owner.genericSuperclass as? ParameterizedType)?.actualTypeArguments?.singleOrNull() as? Class<*>
                ?: return@forEach
            val (sourceFields, richFields, readers) = resolvePushModelAccess(dataClass) ?: return@forEach
            val methods = KavaMemberLookup.declaredMethods(owner).filter {
                it.returnType == Void.TYPE && it.parameterCount == 4 &&
                    it.parameterTypes[2] == classOf<List<*>>() && it.parameterTypes[3] == classOf<Int>()
            }
            var count = 0
            methods.forEach { method ->
                runCatching {
                    environment.registrar.exact("free.copy.push.${owner.name}.${method.name}", owner, method.name, *method.parameterTypes) {
                        after {
                            if (!enabled() || hasThrowable) return@after
                            val model = argOrNull(0)?.takeIf(dataClass::isInstance) ?: return@after
                            val value = instance?.let { binding.get(it) } ?: return@after
                            val sample = texts[0].get(value) as? TextView ?: return@after
                            val ids = pushBodyIds[binding.type] ?: (
                                sample.resources.getIdentifier("content", "id", "tv.danmaku.bili") to
                                    sample.resources.getIdentifier("sub_content", "id", "tv.danmaku.bili")
                                ).also { pushBodyIds[binding.type] = it }
                            if (ids.first <= 0 || ids.second <= 0) {
                                environment.logError("free_copy_push_body_ids", "[BIL] 推送评论正文资源身份不可用，保留原操作")
                                return@after
                            }
                            var primary: TextView? = null
                            var secondary: TextView? = null
                            texts.forEach { field ->
                                val view = field.get(value) as? TextView ?: return@forEach
                                if (view.id == ids.first) primary = view
                                if (view.id == ids.second) secondary = view
                            }
                            for (i in 0..1) {
                                val view = (if (i == 0) primary else secondary) ?: continue
                                val source = sourceFields[i].get(model) ?: continue
                                val rich = richFields[i].get(source) ?: continue
                                val raw = readers[i].read(rich) ?: continue
                                bind(view, raw, rich)
                            }
                        }
                    }
                    count++
                }
            }
            if (count == methods.size && count > 0) pushInstalled++
        }
        if (pushExpected > 0 || KavaMemberLookup.hasClass(loader, "com.bilibili.app.comment3.push.CommentPushContainerFragment")) results["push"] = if (pushInstalled > 0) {
            FeatureInstallResult.Installed(pushInstalled, pushInstalled == pushExpected)
        } else FeatureInstallResult.Skipped("missing-push-binding")
        results.forEach { (family, result) ->
            if (result !is FeatureInstallResult.Installed || !result.complete) {
                environment.logError("free_copy_native_$family", "[BIL] 评论复制 $family 专用绑定未完整接入")
            }
        }
        return results
    }

    private fun resolvePushModelAccess(
        dataClass: Class<*>
    ): Triple<List<Field>, List<Field>, List<CommentRichTextReader>>? {
        val namedSources = listOf("b", "c").map { KavaMemberLookup.fieldOrNull(dataClass, it) }
        if (namedSources.none { it == null }) {
            val sources = namedSources.filterNotNull()
            val namedRich = sources.map { KavaMemberLookup.fieldOrNull(it.type, "h") }
            if (namedRich.none { it == null }) {
                val rich = namedRich.filterNotNull()
                val readers = rich.map { CommentRichTextReader.resolve(it.type) }
                if (readers.none { it == null }) {
                    return Triple(sources, rich, readers.filterNotNull())
                }
            }
        }
        val structural = KavaMemberLookup.declaredFields(dataClass, makeAccessible = true).mapNotNull { sourceField ->
            if (sourceField.isStatic || sourceField.type.isPrimitive || sourceField.type.name.startsWith("java.")) {
                return@mapNotNull null
            }
            val richField = KavaMemberLookup.fieldOrNull(sourceField.type, "h")
                ?.takeIf { !it.isStatic && CommentRichTextReader.resolve(it.type) != null }
                ?: KavaMemberLookup.declaredFields(sourceField.type, makeAccessible = true).singleOrNull { f ->
                    !f.isStatic && !f.type.isPrimitive && !f.type.name.startsWith("java.") &&
                        CommentRichTextReader.resolve(f.type) != null
                } ?: return@mapNotNull null
            val reader = CommentRichTextReader.resolve(richField.type) ?: return@mapNotNull null
            Triple(sourceField, richField, reader)
        }
        if (structural.size != 2) return null
        return Triple(
            structural.map { it.first },
            structural.map { it.second },
            structural.map { it.third }
        )
    }

    private fun owners(prefix: String): List<String> =
        (('a'..'z') + ('A'..'Z')).map { prefix + it } +
            if (prefix == IMAGE_HANDLERS) listOf(prefix + "CardRichTextHandler") else emptyList()

    private companion object {
        const val COMMENT_ITEM = "com.bilibili.app.comment3.data.model.CommentItem"
        const val IMAGE_BODY = "com.bilibili.app.comment3.ui.widget.imagecardviewer.ui.widget.CardExpandableTextView"
        const val IMAGE_HANDLERS = "com.bilibili.app.comment3.ui.widget.imagecardviewer.ui.handler."
        const val PUSH_HOLDERS = "com.bilibili.app.comment3.push.ui.holder."
    }
}
