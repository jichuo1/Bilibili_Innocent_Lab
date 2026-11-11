package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class CommentClassicDexWiringContractTest {
    @Test fun `dex is feature gated in the shared background session and cached per family`() {
        val adapter = SourceContract.read("hook/VersionAdapter.kt")
        val quick = adapter.after("fun quickLocate(").before("private fun adapt(")
        assertFalse(quick.contains("DexAssistSession("))
        assertFalse(quick.contains("CommentClassicStyleLocator.locate"))
        val background = adapter.after("private fun adapt(").before("private data class ProtocolFingerprint")
        assertTrue(background.contains("if (commentClassicStyleEnabled) CommentClassicStyleLocator.missing(loader)"))
        assertTrue(background.contains("classicMissing.forEach { add(it.query) }"))
        assertTrue(background.contains("DexAssistAttemptGuard("))
        assertTrue(adapter.contains("CommentClassicStyleLocator.merge(runtime.commentClassicStyle, cached.commentClassicStyle)"))
        val installer = SourceContract.read("hook/feature/CommentClassicStyleFeatureInstaller.kt")
        assertFalse(installer.contains("DexRefHookBridge"))
        assertFalse(installer.contains("DexAssistSession("))
        assertTrue(installer.contains("method in registered"))
        assertTrue(installer.contains("!nativeFallbackReady"))
        val hook = SourceContract.read("hook/HookEntry.kt")
        assertTrue(hook.contains("commentClassicStyleEnabled = prefs.getBoolean(FeaturePreferences.COMMENT_CLASSIC_STYLE, false)"))
        assertTrue(hook.contains("featureInstallCoordinator.installAll(listOf(classicInstaller))"))
    }

    @Test fun `literal callers are never installed as replacements`() {
        val engine = SourceContract.read("hook/adapter/dex/DexMeguvizAssistEngine.kt")
        val collection = engine.after("private fun collectClassic(").before("private fun collectStoryDefault(")
        assertTrue(collection.contains("invokes(data)"))
        assertTrue(collection.contains("CommentClassicStyleLocator.verified"))
        assertFalse(collection.contains("bound"))
        assertTrue(collection.contains("resolveInvoke(callee)"))
        assertTrue(collection.contains("invokes(wrapper)"))
        assertTrue(collection.contains("CommentClassicStyleLocator.isDefaultDelegate"))
    }
}
