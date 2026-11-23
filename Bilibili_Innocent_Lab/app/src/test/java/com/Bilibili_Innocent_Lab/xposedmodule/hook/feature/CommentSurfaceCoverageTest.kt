package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bilibili.app.comm.comment2.model.BiliComment

class CommentSurfaceCoverageTest {
    private enum class Translation { ORIGIN, TRANSLATING, TRANSLATION }
    private class Rich(@JvmField val a: String)
    private class TextModel(
        @JvmField val a: Long,
        @JvmField val i: Translation,
        @JvmField val j: Rich,
        @JvmField val k: Rich?,
        @JvmField val o: Rich? = Rich("展开"),
        @JvmField val p: Rich? = Rich("收起"),
        @JvmField val b: Long = 100,
        @JvmField val c: Long = 0
    )
    private class WrongTextModel(@JvmField val a: Long, @JvmField val i: String, @JvmField val j: Rich)
    private class LegacyListener(@JvmField val first: BiliComment, @JvmField val second: BiliComment? = null)
    private class GetterRich(private val value: String) { @Suppress("unused") fun e() = value }

    fun translatingUsesCompleteOriginAndNeverControlSuffix() {
        val reader = ComposeCommentText.resolve(TextModel::class.java)
        assertNotNull(reader)
        val model = TextModel(21, Translation.TRANSLATING, Rich("完整原文[dog]"), Rich("译文"))
        assertEquals("完整原文[dog]", reader!!.read(model))
        assertEquals(21L, reader.cardId(model))
    }

    fun translatedDisplayUsesTranslationWithOriginFallback() {
        val reader = ComposeCommentText.resolve(TextModel::class.java)!!
        assertEquals("translated emoji [dog]", reader.read(TextModel(1, Translation.TRANSLATION,
            Rich("原文"), Rich("translated emoji [dog]"))))
        assertEquals("原文", reader.read(TextModel(1, Translation.TRANSLATION, Rich("原文"), null)))
    }

    fun blankBodyAndWrongShapeKeepTheHostActionAvailable() {
        val reader = ComposeCommentText.resolve(TextModel::class.java)!!
        assertNull(reader.read(TextModel(1, Translation.ORIGIN, Rich("  \n"), null)))
        assertNull(ComposeCommentText.resolve(WrongTextModel::class.java))
    }

    fun rebindAndSameCardOnAnotherDispatcherCannotCrossWindows() {
        val registry = ComposeCommentBindings<Any, Any, Any>()
        val firstOwner = Any()
        val secondOwner = Any()
        val oldModel = Any()
        val newModel = Any()
        val firstWindow = Any()
        val secondWindow = Any()
        registry.bind(firstOwner, oldModel, firstWindow)
        registry.bind(secondOwner, oldModel, secondWindow)
        registry.bind(firstOwner, newModel, firstWindow)
        assertSame(newModel, registry.get(firstOwner)!!.first)
        assertSame(firstWindow, registry.get(firstOwner)!!.second)
        assertSame(oldModel, registry.get(secondOwner)!!.first)
        assertSame(secondWindow, registry.get(secondOwner)!!.second)
        assertNull(registry.get(Any()))
    }

    fun failedFamilyStaysInDenominatorEvenIfOtherFamiliesAreReady() {
        val coverage = CommentFamilyCoverage()
        assertFalse(coverage.isComplete())
        coverage.record("legacy", true)
        coverage.record("next", true)
        coverage.record("compose", false)
        assertEquals(2, coverage.installedCount())
        assertFalse(coverage.isComplete())
        assertTrue(coverage.describe().contains("compose=missing"))
        coverage.record("compose", true)
        assertTrue(coverage.isComplete())
    }

    fun composeFailureCannotDisappearBehindSuccessfulNativeLayer() {
        val merged = mergeCommentLayer(FeatureInstallResult.Installed(3),
            FeatureInstallResult.Skipped("missing-compose-structure")) as FeatureInstallResult.Installed
        assertEquals(3, merged.hookCount)
        assertFalse(merged.complete)
        val complete = mergeCommentLayer(FeatureInstallResult.Installed(3),
            FeatureInstallResult.Installed(2)) as FeatureInstallResult.Installed
        assertEquals(5, complete.hookCount)
        assertTrue(complete.complete)
    }

    fun legacyListenerMustIdentifyExactlyOneCurrentComment() {
        val reader = CommentLegacyCopyBridge(javaClass.classLoader!!)
        val comment = BiliComment(BiliComment.Content("完整评论[dog]"))
        assertEquals("完整评论[dog]", reader.capture(LegacyListener(comment))!!.first)
        assertSame(comment.mContent, reader.capture(LegacyListener(comment, comment))!!.second)
        assertNull(reader.capture(LegacyListener(comment, BiliComment(BiliComment.Content("另一条")))))
    }

    fun nativeRichTextReaderSupportsOldGetterAndNewFieldWithoutTruncation() {
        val text = "[dog]".repeat(800)
        assertEquals(text, CommentRichTextReader.resolve(GetterRich::class.java)!!.read(GetterRich(text)))
        assertEquals(text, CommentRichTextReader.resolve(Rich::class.java)!!.read(Rich(text)))
    }

    fun rootAndChildModelSelectTheirOwnThread() {
        val reader = ComposeCommentText.resolve(TextModel::class.java)!!
        assertEquals(10L, reader.rootCommentId(TextModel(1, Translation.ORIGIN, Rich("主楼"), null, b = 10)))
        assertEquals(10L, reader.rootCommentId(TextModel(2, Translation.ORIGIN, Rich("子回复"), null, b = 11, c = 10)))
    }

    fun sameStoreTracksMultipleCardIdsWithoutCrossOverwriting() {
        val registry = ComposeCommentBindings<Any, Any, Any>()
        val store = Any()
        val firstModel = Any()
        val secondModel = Any()
        val firstAnchor = Any()
        val secondAnchor = Any()

        registry.bind(store, 101L, firstModel, firstAnchor)
        registry.bind(store, 202L, secondModel, secondAnchor)

        assertSame(firstModel, registry.get(store, 101L)!!.first)
        assertSame(firstAnchor, registry.get(store, 101L)!!.second)
        assertSame(secondModel, registry.get(store, 202L)!!.first)
        assertSame(secondAnchor, registry.get(store, 202L)!!.second)
        assertSame(secondModel, registry.get(store)!!.first)
        assertNull(registry.get(store, 303L))
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentSurfaceCoverageTest) -> Unit) {
            val s = CommentSurfaceCoverageTest()
            block(s)
        }
        runIsolated { s -> s.translatingUsesCompleteOriginAndNeverControlSuffix() }
        runIsolated { s -> s.translatedDisplayUsesTranslationWithOriginFallback() }
        runIsolated { s -> s.blankBodyAndWrongShapeKeepTheHostActionAvailable() }
        runIsolated { s -> s.rebindAndSameCardOnAnotherDispatcherCannotCrossWindows() }
        runIsolated { s -> s.failedFamilyStaysInDenominatorEvenIfOtherFamiliesAreReady() }
        runIsolated { s -> s.composeFailureCannotDisappearBehindSuccessfulNativeLayer() }
        runIsolated { s -> s.legacyListenerMustIdentifyExactlyOneCurrentComment() }
        runIsolated { s -> s.nativeRichTextReaderSupportsOldGetterAndNewFieldWithoutTruncation() }
        runIsolated { s -> s.rootAndChildModelSelectTheirOwnThread() }
        runIsolated { s -> s.sameStoreTracksMultipleCardIdsWithoutCrossOverwriting() }
    }
}
