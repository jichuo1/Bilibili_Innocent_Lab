package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class FeedbackContentSignatureTest {
    @Test fun composeFlagsAreFoundForEachVerifiedSignature() {
        val tail = listOf("kotlin.jvm.functions.Function1", "androidx.compose.runtime.Composer", "int", "int")
        val first = listOf("java.util.List")
        val head = first + "kntr.app.pegasus.feedbackdialog.model.FeedbackHead"
        assertEquals(3, FeedbackContentSignature.changedIndex(first + tail))
        assertEquals(4, FeedbackContentSignature.changedIndex(first + "boolean" + tail))
        assertEquals(5, FeedbackContentSignature.changedIndex(head + "boolean" + tail))
        assertNull(FeedbackContentSignature.changedIndex(head + tail))
        assertNull(FeedbackContentSignature.changedIndex(listOf("java.util.Map") + tail))
        assertNull(FeedbackContentSignature.changedIndex(head + tail + "int"))
    }
}
