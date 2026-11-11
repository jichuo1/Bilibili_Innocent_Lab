package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentReplyButtonScopeContractTest {
    @Test
    fun relocatedRenderersStillNeedTheUniqueNativeBindingShape() {
        val installer = SourceContract.read("hook/feature/CommentReplyButtonFeatureInstaller.kt")
        for ((family, owner) in listOf("legacy" to "dut", "next" to "bxu", "exp3" to "hzu")) {
            assertTrue(Regex("NativeFamily\\(\"$family\", \"[^\"]+\", \"$owner\"\\)").containsMatchIn(installer))
        }
        val plan = installer.after("private fun nativePlans(").before("private fun bindingShape(")
        assertTrue(plan.contains("+ relocatedOwner"))
        assertTrue(plan.contains("shape == family"))
        assertTrue(plan.contains("plans.map { it.binding }.distinct().size == 1"))
        assertTrue(plan.contains("plans.map { it.method.declaringClass }.distinct().size == 1"))
        assertTrue(installer.contains("getIdentifier(\"reply_button\", \"id\", TARGET_PACKAGE)"))
    }

    @Test
    fun nativeBindingCallbacksDoNotDiscoverClassesOrTraverseViewTrees() {
        val installer = SourceContract.read("hook/feature/CommentReplyButtonFeatureInstaller.kt")
        val callback = installer.after("after {").before("hooks++")
        for (operation in listOf("classOrNull", "declaredFields", "declaredMethods", "getBoolean", "DexKit")) {
            assertFalse(operation, callback.contains(operation))
        }
        assertFalse(installer.contains("findViewById"))
        assertTrue(callback.contains("hasThrowable"))
        assertTrue(callback.contains("Looper.myLooper() != Looper.getMainLooper()"))
    }

    @Test
    fun visibilityCorrectionHasWeakTargetsGuardedCallbacksAndDetachCleanup() {
        val source = SourceContract.read("hook/feature/CommentReplyButtonVisibility.kt")
        val guard = source.after("private class Guard(").before("private companion object")
        assertTrue(source.contains("WeakHashMap<View, Guard>"))
        assertTrue(guard.contains("target = WeakReference(view)"))
        assertTrue(guard.contains("WeakReference<ViewTreeObserver>"))
        for (callback in listOf("attach", "detach", "layout")) {
            assertTrue(guard.contains("HostThreadGuard.run(\"comment.reply_button.$callback\")"))
        }
        assertTrue(guard.after("override fun onViewDetachedFromWindow").before("override fun onGlobalLayout")
            .contains("stop()"))
        for (operation in listOf("postOnAnimation", "postDelayed", "Choreographer", "ValueAnimator", "findViewById")) {
            assertFalse(operation, source.contains(operation))
        }
    }

    @Test
    fun theDefaultOffInstallerIsWiredOnceIndependentlyOfCopyAndTopology() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertEquals(1, Regex("CommentReplyButtonFeatureInstaller\\(").findAll(hook).count())
        val call = hook.after("CommentReplyButtonFeatureInstaller(").before("CommentPurifyFeatureInstaller(")
        assertTrue(call.contains("prefs.getBoolean(FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, false)"))
        assertFalse(call.contains("REPLY_TOPOLOGY_ENABLED"))
        assertFalse(call.contains("runtimeCommentFreeCopyEnabled"))
    }
}
