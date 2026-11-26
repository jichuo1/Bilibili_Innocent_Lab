package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntrySource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentSurfaceWiringContractTest {
    fun additionalCommentRpcResponsesUseTheSharedBridgeWithScopedSingleRemoval() {
        for (file in listOf("CommentFilterFeatureInstaller.kt", "CommentPurifyFeatureInstaller.kt")) {
            val source = SourceContract.read("hook/feature/$file")
            for (mapping in listOf(
                "\"foldList\" to \"FoldListResp\"",
                "\"hotspotPage\" to \"HotspotPageResp\"",
                "\"replyInfo\" to \"ReplyInfoReply\""
            )) assertTrue(mapping, source.contains(mapping))
            assertTrue(source.contains("shareUnchangedReply = true"))
        }
        val filter = SourceContract.read("hook/feature/CommentFilterFeatureInstaller.kt")
            .after("private fun installKotlinChannel").before("private fun prewarmSemantic")
        assertTrue(filter.contains("if (rpc == \"replyInfo\") singleRewriter else rewriter"))
        assertTrue(filter.contains("val result = rpcRewriter.rewrite(javaReply)"))
        assertTrue(filter.contains("confirmedTransform ="))
        val purify = SourceContract.read("hook/feature/CommentPurifyFeatureInstaller.kt")
            .after("private fun installKotlinChannel").before("private fun installAbsentPayload")
        assertTrue(purify.contains("rpc == \"mainList\" && requestTransform != null"))
    }

    fun fullTreeLoadingSwitchesBudgetBeforeInspectingAnInFlightRequest() {
        val open = HookEntrySource.function("onFullTreeRequested", HookEntrySource.commentTopologyFiles())
        assertTrue(open.before("if (!state.loadingRequest)").contains("if (!state.loadAll) state.guard.beginBatch"))
        val host = SourceContract.read("hook/feature/ReplyTopologyHostAccess.kt")
        assertTrue(host.contains("MAX_MAPPER_SEED_NODES, requireReplies = true"))
        assertTrue(host.contains("if (requireReplies &&"))
        val page = host.after("private fun parsePage").before("private fun snapshotTree")
        assertTrue(page.contains("error(\"DetailListReply pagination is unavailable\")"))
        assertTrue(page.contains("error(\"DetailListReply next offset is unavailable\")"))
        assertFalse(page.contains("pagination?.let"))
        assertTrue(host.after("private fun snapshotTree").before("private fun snapshotNode")
            .contains("?: error(\"ReplyInfo replies is unavailable\")"))
    }

    fun storyPanelUsesTheClickedCommentWindowAndRetainsOnlyWeakOwnership() {
        val binder = HookEntrySource.file("hook/feature/comment/ReplyTopologyAnchorBinder.kt")
        val pager = HookEntrySource.file("hook/feature/comment/ReplyTopologyPager.kt")
        assertTrue(binder.contains("coordinator?.open(activity, currentSeed, this@ReplyTopologyAnchorView)"))
        assertTrue(pager.contains("state.sourceAnchor?.get()"))
        val geometry = binder.after("private class AnchorGeometry(").before("fun toTarget(")
        assertTrue(geometry.contains("val include: WeakReference<ViewGroup>"))
        assertTrue(geometry.contains("val row: WeakReference<ViewGroup>"))
        assertTrue(geometry.contains("val referenceView: WeakReference<View>"))
        assertTrue(geometry.contains("val primaryMessage: WeakReference<View>?"))
        assertTrue(geometry.contains("val secondaryMessage: WeakReference<View>?"))
        val controller = SourceContract.read("ui/overlay/ReplyTopologyPanelController.kt")
        assertTrue(controller.contains("sourceAnchor.rootView"))
        assertTrue(controller.contains("parentRef = WeakReference(parent)"))
        assertTrue(controller.contains("parent.windowToken == panel.windowToken"))
        assertFalse(controller.contains("SYSTEM_ALERT_WINDOW"))
    }

    fun imageAndPushDoNotTurnEveryContentIdIntoAComment() {
        val ids = HookEntrySource.function("isCommentBodyTextView", HookEntrySource.freeCopyScopeFiles())
        assertFalse(ids.contains("getIdentifier(\"content\""))
        val bridge = SourceContract.read("hook/feature/CommentNativeSupplementBridge.kt")
        assertTrue(bridge.contains("entry.plan.body.get(binding) !== view"))
        assertTrue(bridge.contains("sample.resources.getIdentifier(\"sub_content\""))
    }

    fun composeBindingDoesNotReadPreferencesOrLookupClassesOnEveryRender() {
        val bridge = SourceContract.read("hook/feature/CommentComposeCopyBridge.kt")
            .after("after {").before("dispatchers += dispatch")
        assertFalse(bridge.contains("classOrNull"))
        assertFalse(bridge.contains("getBoolean"))
        assertFalse(bridge.contains("DexKit"))
        val menu = SourceContract.read("hook/feature/ReplyTopologyComposeMenu.kt")
        assertTrue(menu.contains("HostThreadGuard.run(\"reply_topology.compose_menu_click\")"))
        assertTrue(menu.contains("and 0b1110.inv()"))
        assertTrue(menu.contains("reportRuntimeEvidence(CommentTopologyFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED)"))
        assertFalse(menu.contains("reportRuntimeEvidence(\"reply_topology_enabled\""))
    }

    fun composeMoreActionInvalidatesPreviousContextBeforeAnyBindingFallback() {
        val hook = HookEntrySource.hookEntry()
        assertTrue(hook.contains("onComposeResetMore = commentTopologyInstaller::clearComposeMenu"))
        assertTrue(
            HookEntrySource.file("hook/feature/freecopy/FreeCopyFeatureInstaller.kt")
                .contains("resetMore = onComposeResetMore")
        )
        val dispatch = SourceContract.read("hook/feature/CommentComposeCopyBridge.kt")
            .after("val request = argOrNull(0)?.takeIf(action::isInstance)")
            .before("actionInstalled++")
        assertTrue(dispatch.before("bindings.get(store)").contains("resetMore()"))
        assertTrue(dispatch.after("if (open(anchor, raw))").contains("resetMore()"))
        val remember = SourceContract.read("hook/feature/CommentTopologyFeatureInstaller.kt")
            .after("fun rememberComposeMenu").before("override val id")
        assertFalse(remember.contains("?: return"))
        assertTrue(remember.contains("composeMenu?.remember(anchor, seed, sourceCurrent)"))
        val menu = SourceContract.read("hook/feature/ReplyTopologyComposeMenu.kt")
        assertTrue(menu.contains("context.acceptsSource"))
        assertTrue(menu.contains("current.menuRoot.get() !== root"))
        assertTrue(menu.contains("if (!acceptsClick(current, menu, menuCanBeDismissed = true)) return@run"))
        assertTrue(menu.contains("takeIf { acceptsClick(current, it) }"))
    }

    fun performancePathsKeepCopyDisabledHooksOutAndShareOnlyCommentReplies() {
        val bridge = SourceContract.read("hook/feature/CommentNativeSupplementBridge.kt")
        assertTrue(bridge.after("fun install(environment").before("val loader").contains("if (!enabled()) return emptyMap()"))
        assertTrue(bridge.contains("setter.declaringClass"))
        assertFalse(bridge.contains("free.copy.image.spannable"))
        val installComment = HookEntrySource.function("installCommentFreeCopyHooks", HookEntrySource.freeCopyScopeFiles())
            .before("val isInitialAttempt =")
        assertTrue(installComment.contains("if (!FreeCopyRuntimeConfig.runtimeCommentFreeCopyEnabled)"))
        assertTrue(installComment.contains("composeCommentCopy.install(commentEnv)"))
        assertTrue(installComment.contains("return"))
        val seedBridge = HookEntrySource.file("hook/feature/comment/ReplyTopologySeedBridge.kt")
        assertTrue(seedBridge.contains("ProtobufReplyTreeVisitor(host.replyInfoClass)"))
        assertTrue(seedBridge.contains("reader.visit(reply); reply"))
        assertTrue(seedBridge.contains("shareUnchangedReply = true"))
        for (file in listOf("CommentFilterFeatureInstaller.kt", "CommentPurifyFeatureInstaller.kt")) {
            assertTrue(SourceContract.read("hook/feature/$file").contains("shareUnchangedReply = true"))
        }
        assertFalse(SourceContract.read("hook/feature/SearchPurifyFeatureInstaller.kt").contains("shareUnchangedReply = true"))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentSurfaceWiringContractTest) -> Unit) {
            val s = CommentSurfaceWiringContractTest()
            block(s)
        }
        runIsolated { s -> s.additionalCommentRpcResponsesUseTheSharedBridgeWithScopedSingleRemoval() }
        runIsolated { s -> s.fullTreeLoadingSwitchesBudgetBeforeInspectingAnInFlightRequest() }
        runIsolated { s -> s.storyPanelUsesTheClickedCommentWindowAndRetainsOnlyWeakOwnership() }
        runIsolated { s -> s.imageAndPushDoNotTurnEveryContentIdIntoAComment() }
        runIsolated { s -> s.composeBindingDoesNotReadPreferencesOrLookupClassesOnEveryRender() }
        runIsolated { s -> s.composeMoreActionInvalidatesPreviousContextBeforeAnyBindingFallback() }
        runIsolated { s -> s.performancePathsKeepCopyDisabledHooksOutAndShareOnlyCommentReplies() }
    }
}
