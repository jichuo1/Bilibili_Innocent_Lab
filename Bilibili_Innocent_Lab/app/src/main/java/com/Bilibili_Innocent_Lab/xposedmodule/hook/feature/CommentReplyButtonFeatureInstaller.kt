package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.os.Looper
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
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
internal class CommentReplyButtonFeatureInstaller(
    private val enabled: Boolean,
    private val cachedNativeOwnersProvider: () -> Map<String, String> = { emptyMap() }
) : FeatureInstaller {
    override val id = ID
    override val capabilityIds: List<String> get() = if (enabled) listOf(CAPABILITY) else emptyList()
    private val installedFamilies = hashSetOf<String>()
    private val familyResults = linkedMapOf<String, FeatureInstallResult>()
    private var comment2Attempted = false
    private var comment2Result: FeatureInstallResult? = null
    private var composeAttempted = false
    private var composeResult: FeatureInstallResult? = null
    private var initialRun = false
    private var retryConsumed = false
    @Volatile
    var requiresAdaptationRetry: Boolean = false
        private set

    @Synchronized
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            requiresAdaptationRetry = false
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            requiresAdaptationRetry = false
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: run {
            requiresAdaptationRetry = false
            return FeatureInstallResult.Skipped("missing-classloader")
        }
        val coverage = CommentFamilyCoverage()
        val visibility = CommentReplyButtonVisibility(environment)
        if (!comment2Attempted) {
            comment2Attempted = true
            comment2Result = installComment2(environment, visibility)
        }
        comment2Result?.let { coverage.record("comment2", it) }

        val plansByFamily = CommentReplyButtonAdapter.resolvePlans(loader, cachedNativeOwnersProvider())
        for (family in CommentReplyButtonAdapter.FAMILIES) {
            if (family in installedFamilies) {
                familyResults[family]?.let { coverage.record(family, it) }
                continue
            }
            val plans = plansByFamily[family].orEmpty()
            if (plans.isEmpty() ||
                plans.map { it.binding }.distinct().size != 1 ||
                plans.map { it.method.declaringClass }.distinct().size != 1
            ) {
                familyResults[family] = FeatureInstallResult.Skipped("missing-$family-structure")
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
            val familyResult = if (hooks == 0) FeatureInstallResult.Skipped("registration-failed")
                else FeatureInstallResult.Installed(hooks, hooks == plans.size)
            familyResults[family] = familyResult
            if (hooks == plans.size && hooks > 0) installedFamilies += family
            coverage.record(family, familyResult)
        }
        if (!composeAttempted) {
            composeAttempted = true
            composeResult = CommentReplyButtonCompose.install(environment)
        }
        composeResult?.let { coverage.record("compose", it) }

        if (initialRun) {
            retryConsumed = true
            requiresAdaptationRetry = false
        } else {
            initialRun = true
            requiresAdaptationRetry = !retryConsumed &&
                installedFamilies.size < CommentReplyButtonAdapter.FAMILIES.size &&
                CommentReplyButtonAdapter.applicable(loader)
        }

        val installed = (listOfNotNull(comment2Result, composeResult) + familyResults.values)
            .sumOf { (it as? FeatureInstallResult.Installed)?.hookCount ?: 0 }
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
        private const val COMMENT2_REPLY = "com.bilibili.app.comm.comment2.phoenix.view.CommentReplyWidget"
    }
}
