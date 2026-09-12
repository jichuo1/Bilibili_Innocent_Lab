package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 宿主 APK 中出现的三个 Compose 签名；不按参数数目猜中间字段。 */
internal object FeedbackContentSignature {
    fun changedIndex(parameters: List<String>): Int? {
        val tail = listOf("kotlin.jvm.functions.Function1", "androidx.compose.runtime.Composer", "int", "int")
        val head = "kntr.app.pegasus.feedbackdialog.model.FeedbackHead"
        val supported = listOf(listOf("java.util.List"), listOf("java.util.List", "boolean"),
            listOf("java.util.List", head, "boolean"))
        return if (supported.any { it + tail == parameters }) parameters.size - 2 else null
    }
}
