package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.os.Looper
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isFinal
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isNotSubclassOf
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isStatic
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ref.isSubclassOf
import java.lang.reflect.Field
import java.lang.reflect.Method

internal fun commentReplyBindingShape(types: List<String>): String? {
    val counts = types.groupingBy { it }.eachCount()
    val image = "com.bilibili.magicasakura.widgets.TintImageView"
    val text = "com.bilibili.magicasakura.widgets.TintTextView"
    val constraint = "androidx.constraintlayout.widget.ConstraintLayout"
    val linear = "android.widget.LinearLayout"
    val frame = "android.widget.FrameLayout"
    return when {
        counts == mapOf(constraint to 1, image to 5, text to 3) -> "legacy"
        counts == mapOf(linear to 2, image to 4, text to 4, frame to 1) -> "exp3"
        counts == mapOf(constraint to 1, image to 4, text to 3, frame to 3, linear to 1) ||
            counts == mapOf(constraint to 1, image to 4, text to 3, frame to 3, linear to 2) ||
            counts == mapOf(constraint to 1, image to 2, text to 3, frame to 3, linear to 1) -> "next"
        else -> null
    }
}

/** 只解析三套原生操作栏及 Compose 的独立回复分支；不依赖正文复制或脉络开关。 */
internal class CommentReplyButtonFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id = ID
    override val capabilityIds: List<String> get() = if (enabled) listOf(CAPABILITY) else emptyList()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-classloader")
        val coverage = CommentFamilyCoverage()
        val visibility = CommentReplyButtonVisibility(environment)
        var installed = 0
        installComment2(environment, visibility)?.let { result ->
            coverage.record("comment2", result)
            installed += (result as? FeatureInstallResult.Installed)?.hookCount ?: 0
        }
        for ((family, packageName, relocatedOwner) in NATIVE_FAMILIES) {
            val plans = nativePlans(loader, family, packageName, relocatedOwner)
            if (plans.isEmpty()) {
                coverage.record(family, false)
                continue
            }
            val readers = plans.map { it.binding }.distinct().associateWith { binding ->
                NativeReader(viewFields(binding))
            }
            var hooks = 0
            for (plan in plans) {
                val reader = readers.getValue(plan.binding)
                runCatching {
                    check(commentReplyBindingShape(reader.fieldTypes) == family) { "inaccessible-binding-fields" }
                    environment.registrar.exact(
                        "comment.reply_button.$family.${plan.method.name}",
                        plan.method.declaringClass,
                        plan.method.name,
                        *plan.method.parameterTypes
                    ) {
                        after {
                            if (hasThrowable) return@after
                            if (Looper.myLooper() != Looper.getMainLooper()) return@after
                            val binding = argOrNull(plan.index) ?: return@after
                            runCatching {
                                val target = reader.read(binding)
                                if (target != null) visibility.hide(target)
                            }.onFailure { throwable ->
                                environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.ERROR)
                                environment.logError("comment_reply_button_runtime_$family", "[BIL] 评论回复按钮读取失败($family): $throwable")
                            }
                        }
                    }
                    hooks++
                }.onFailure { throwable ->
                    environment.logError("comment_reply_button_$family", "[BIL] 评论回复按钮原生 Hook 注册失败($family): $throwable")
                }
            }
            val result = if (hooks == 0) FeatureInstallResult.Skipped("registration-failed")
                else FeatureInstallResult.Installed(hooks, hooks == plans.size)
            coverage.record(family, result)
            installed += hooks
        }
        CommentReplyButtonCompose.install(environment)?.let { result ->
            coverage.record("compose", result)
            installed += (result as? FeatureInstallResult.Installed)?.hookCount ?: 0
        }
        val result = if (installed == 0) FeatureInstallResult.Skipped("missing-reply-button-structure")
            else FeatureInstallResult.Installed(installed, coverage.isComplete())
        environment.reportStatus("comment_reply_button_layers", coverage.describe())
        environment.reportCapability(CAPABILITY, result)
        if (installed > 0) environment.reportRuntimeEvidence(CAPABILITY, FeatureRuntimeStage.ADAPTED)
        if (!coverage.isComplete()) {
            environment.logError("comment_reply_button_missing", "[BIL] 评论回复按钮未完整适配: ${coverage.describe()}")
        }
        return result
    }

    private data class NativePlan(val method: Method, val index: Int, val binding: Class<*>)
    private data class NativeFamily(val name: String, val packageName: String, val relocatedOwner: String)

    private fun installComment2(
        environment: HookEnvironment,
        visibility: CommentReplyButtonVisibility
    ): FeatureInstallResult? {
        val owner = KavaMemberLookup.classOrNull(environment.classLoader, COMMENT2_REPLY) ?: return null
        if (owner isNotSubclassOf classOf<View>()) return FeatureInstallResult.Skipped("missing-comment2-reply-view")
        val constructors = KavaMemberLookup.declaredConstructors(owner).filter {
            it.parameterTypes.firstOrNull()?.name == "android.content.Context" && it.parameterCount in 1..5
        }
        var installed = 0
        constructors.forEachIndexed { index, constructor ->
            runCatching {
                environment.registrar.constructor("comment.reply_button.comment2.$index", constructor) {
                    after {
                        if (!hasThrowable) (instance as? View)?.let(visibility::hide)
                    }
                }
                installed++
            }.onFailure { throwable ->
                environment.logError("comment_reply_button_comment2", "[BIL] 评论回复按钮 Comment2 Hook 注册失败: $throwable")
            }
        }
        return if (installed == 0) FeatureInstallResult.Skipped("missing-comment2-reply-constructor")
            else FeatureInstallResult.Installed(installed, installed == constructors.size)
    }

    private fun nativePlans(loader: ClassLoader, family: String, packageName: String, relocatedOwner: String): List<NativePlan> {
        val bindingShapes = hashMapOf<Class<*>, String?>()
        val ownerNames = ('a'..'z').map { "$packageName.$it" } + relocatedOwner
        val candidates = ownerNames.mapNotNull { KavaMemberLookup.classOrNull(loader, it) }.flatMap { owner ->
            KavaMemberLookup.declaredMethods(owner).mapNotNull { method ->
                if (method.isBridge || method.isSynthetic || method.returnType != Void.TYPE ||
                    method.parameterCount !in 2..5 || method.parameterTypes[0].name != COMMENT_ITEM
                ) return@mapNotNull null
                val indexes = method.parameterTypes.indices.filter { index ->
                    val type = method.parameterTypes[index]
                    val shape = if (bindingShapes.containsKey(type)) bindingShapes[type] else {
                        bindingShape(type).also { bindingShapes[type] = it }
                    }
                    shape == family
                }
                if (indexes.size != 1) null else NativePlan(method, indexes.single(), method.parameterTypes[indexes.single()])
            }
        }
        // 不能把同包另一组件或拆分后的未知实现悄悄当成同一个操作栏。
        return candidates.takeIf { plans ->
            plans.map { it.binding }.distinct().size == 1 &&
                plans.map { it.method.declaringClass }.distinct().size == 1
        }.orEmpty()
    }

    private fun bindingShape(type: Class<*>): String? {
        if (type.isPrimitive || type.isArray || type.isInterface) return null
        if (type.interfaces.none { it.name == "androidx.viewbinding.ViewBinding" }) return null
        if (KavaMemberLookup.declaredMethods(type).none {
                it.name == "getRoot" && it.parameterCount == 0 && it.returnType isSubclassOf classOf<View>()
            }) return null
        val fields = KavaMemberLookup.declaredFields(type).filterNot { it.isStatic }
        if (fields.any { !it.isFinal }) return null
        return commentReplyBindingShape(fields.map { it.type.name })
    }

    private fun viewFields(type: Class<*>): List<Field> =
        KavaMemberLookup.declaredFields(type, makeAccessible = true).filter {
            !it.isStatic && it.type isSubclassOf classOf<View>()
        }

    private class NativeReader(private val fields: List<Field>) {
        val fieldTypes = fields.map { it.type.name }
        private var replyId = 0
        private val access = CommentReplyFieldAccess(fields) { value ->
            value is View && value.id == replyId
        }

        @SuppressLint("DiscouragedApi")
        fun read(binding: Any): View? {
            if (replyId == 0) {
                val sample = fields.firstNotNullOfOrNull { it.get(binding) as? View } ?: return null
                // 资源 ID 每个进程只校准一次；字段缓存不保留任何 Binding/View 实例。
                replyId = sample.resources.getIdentifier("reply_button", "id", TARGET_PACKAGE).takeIf { it != 0 } ?: -1
                check(replyId != -1) { "missing-reply-button-resource" }
            }
            if (replyId == -1) return null
            return access.read(binding) as? View
        }
    }

    companion object {
        const val ID = "comment_reply_button"
        const val CAPABILITY = "comments_reply_button_removed"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val COMMENT_ITEM = "com.bilibili.app.comment3.data.model.CommentItem"
        private const val COMMENT2_REPLY = "com.bilibili.app.comm.comment2.phoenix.view.CommentReplyWidget"
        // 9.19.0 (9190200/9190400): the binding layouts still use reply_button; their renderers move to the root package.
        private val NATIVE_FAMILIES = listOf(
            NativeFamily("legacy", "com.bilibili.app.comment3.ui.holder.handle", "dut"),
            NativeFamily("next", "com.bilibili.app.comment3.ui.nextholder.handle", "bxu"),
            NativeFamily("exp3", "com.bilibili.app.comment3.ui.nextholderexp3.handle", "hzu")
        )
    }
}
