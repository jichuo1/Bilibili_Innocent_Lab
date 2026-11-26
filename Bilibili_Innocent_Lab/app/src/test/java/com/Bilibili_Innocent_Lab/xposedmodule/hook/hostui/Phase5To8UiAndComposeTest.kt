package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.BrandSplashKntrAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostBottomBarFxFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostTopBarFxFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.brandSplashDrawConstructorAbi
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPageHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostOgvCardHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardGridAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardSpacing
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostChromeRootFill
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostComposeChromeApi
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostComposeChromeHooks
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.HostComposeTopTabCentering
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome.matchesComposeName
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.ComposeChangedMask
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.isSupportedHeaderTitle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.isSupportedTabLabels
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class Phase5To8UiAndComposeTest {
    private val baseLoader = requireNotNull(javaClass.classLoader)

    // -------------------------------------------------------------------------
    // 5A & 8A: Compose ABI / setModifier / RootFill / BiliImage / RowKt / OGV
    // -------------------------------------------------------------------------

    @Test
    fun `setModifier resolves concrete LayoutNode method directly and via Companion constructor fallback`() {
        val loaderDirect = composeHostLoader(hideLayoutNode = false, use2ParamFillMaxSize = false, omitRowKt = false)
        val modDirect = loaderDirect.loadClass("androidx.compose.ui.Modifier")
        val resolvedDirect = HostComposeChromeApi.resolveSetModifier(loaderDirect, modDirect)
        assertFalse(Modifier.isAbstract(resolvedDirect.modifiers))
        assertFalse(Modifier.isStatic(resolvedDirect.modifiers))
        assertEquals("androidx.compose.ui.node.LayoutNode", resolvedDirect.declaringClass.name)

        // R8 renamed LayoutNode: LayoutNode class name hidden, discovered through ComposeUiNode.Companion.getConstructor()
        val loaderRenamed = composeHostLoader(hideLayoutNode = true, use2ParamFillMaxSize = true, omitRowKt = true)
        val modRenamed = loaderRenamed.loadClass("androidx.compose.ui.Modifier")
        val resolvedFallback = HostComposeChromeApi.resolveSetModifier(loaderRenamed, modRenamed)
        assertFalse(Modifier.isAbstract(resolvedFallback.modifiers))
        assertEquals("renamed.compose.LayoutNodeImpl", resolvedFallback.declaringClass.name)

        // Broken Companion returning interface / abstract fails fast instead of returning ComposeUiNode.setModifier
        val brokenLoader = composeHostLoader(hideLayoutNode = true, brokenConstructor = true)
        val modBroken = brokenLoader.loadClass("androidx.compose.ui.Modifier")
        assertThrows(IllegalArgumentException::class.java) {
            HostComposeChromeApi.resolveSetModifier(brokenLoader, modBroken)
        }
        assertNull(HostComposeChromeApi.resolve(brokenLoader))
    }

    @Test
    fun `HostComposeChromeApi and TopTabCentering survive 2-param fillMaxSize relaxed BiliImage nullable RowKt and probed 6-field RootFill`() {
        val errors = mutableListOf<Throwable>()
        val loader = composeHostLoader(hideLayoutNode = true, use2ParamFillMaxSize = true, omitRowKt = true)
        val api = requireNotNull(HostComposeChromeApi.resolve(loader) { errors += it })
        assertTrue("unexpected errors: $errors", errors.isEmpty())
        assertNull(api.rowPolicy)
        assertTrue(api.backdropImagesReady)
        assertFalse(Modifier.isAbstract(api.applyModifier.modifiers))
        assertEquals("renamed.compose.LayoutNodeImpl", api.applyModifier.declaringClass.name)

        // 2-param fillMaxSize(Modifier, 1f) produces the backdrop marker
        val marker = loader.loadClass("androidx.compose.foundation.layout.SizeKt")
            .getDeclaredMethod("fillMaxSize", loader.loadClass("androidx.compose.ui.Modifier"), Float::class.javaPrimitiveType)
            .invoke(null, loader.loadClass("androidx.compose.ui.Modifier").getField("Companion").get(null), 1f)!!
        assertTrue(api.fillsBackdrop(marker))

        // HostComposeTopTabCentering also resolves via concrete LayoutNode fallback and matchesComposeName
        val tabSetModifier = HostComposeTopTabCentering.resolvedSetModifier(loader)
        assertNotNull(tabSetModifier)
        assertFalse(Modifier.isAbstract(tabSetModifier!!.modifiers))
        assertEquals("renamed.compose.LayoutNodeImpl", tabSetModifier.declaringClass.name)

        // 6-field background element passes via probeShape even though legacy 4/5-field check would reject it
        val bgClass = loader.loadClass("androidx.compose.foundation.BackgroundKt")
        val modClass = loader.loadClass("androidx.compose.ui.Modifier")
        val empty = modClass.getField("Companion").get(null)!!
        val shapeClass = loader.loadClass("androidx.compose.ui.graphics.Shape")
        val rectShape = loader.loadClass("androidx.compose.ui.graphics.RectangleShapeKt")
            .getDeclaredMethod("getRectangleShape").invoke(null)!!
        val sixFieldFill = bgClass.declaredMethods.single { matchesComposeName(it.name, "background") }
            .invoke(null, empty, 0xFF112233L shl 32, rectShape)!!
        val rootFill = HostChromeRootFill(
            loader.loadClass("androidx.compose.ui.Modifier\$Element"),
            loader.loadClass("androidx.compose.ui.graphics.Brush"),
            shapeClass,
            loader.loadClass("kotlin.jvm.functions.Function1"),
            empty,
            bgClass.declaredMethods.single { matchesComposeName(it.name, "background") },
            rectShape
        )
        assertTrue(rootFill.matches(sixFieldFill))
        assertFalse(rootFill.matches(empty))
        val cleared = rootFill.transparent(sixFieldFill)!!
        assertEquals(sixFieldFill.javaClass, cleared.javaClass)
    }

    @Test
    fun `matchesComposeName and ComposeChangedMask prevent overload and bitshift corruption`() {
        assertTrue(matchesComposeName("background", "background"))
        assertTrue(matchesComposeName("background-bw27NRU", "background"))
        assertTrue(matchesComposeName("getBg1-0d7_KjU", "getBg1"))
        assertTrue(matchesComposeName("drawRect-n-J9OG0", "drawRect"))
        assertFalse(matchesComposeName("background\$default", "background"))
        assertFalse(matchesComposeName("background-bw27NRU\$default", "background"))
        assertFalse(matchesComposeName("backgroundColor", "background"))

        // Slot 0 & 2 bit operations preserve unrelated bits & force bit 0
        assertEquals(0b0010_0000, ComposeChangedMask.clearSlots(0b0010_1110, 0))
        assertEquals(0b0010_0101, ComposeChangedMask.markUncertain(0b0010_1111, 0))
        assertEquals(0x7FF and (0b1110 shl 6).inv(), ComposeChangedMask.clearSlots(0x7FF, 2))
        // Overflow index (>= 10) must NOT wrap shift modulo 32 and corrupt low slots!
        val before = 0b0010_0100_1001
        assertEquals(before, ComposeChangedMask.clearSlots(before, 10, 11, -1))
        assertEquals(before, ComposeChangedMask.markUncertain(before, 10, 15))
    }

    @Test
    fun `HostOgvCardHooks Api resolves exact parameter types despite overloads and HostTopBar skips missing rowPolicy check`() {
        val loader = composeHostLoader(hideLayoutNode = false, use2ParamFillMaxSize = true, omitRowKt = true)
        val ogvResult = HostOgvCardHooks.install(
            HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), PlayerPortTestRegistrar(), { _, _ -> }, { _, _ -> }, { _, _ -> }),
            HostVideoCardHostAccess(loader),
            16,
            HostCardMaterial.ORIGINAL,
            emptyMap(),
            {}
        )
        // Api.resolve ran cleanly without throwing on RoundedCornerShape(int) overload
        assertNotNull(ogvResult)

        // Verify HomeChromeAdapter-backed composeExpected check in Top/Bottom installers stays skipped/partial cleanly
        val registrar = PlayerPortTestRegistrar()
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar, { _, _ -> }, { _, _ -> }, { _, _ -> })
        val topRes = HostTopBarFxFeatureInstaller(true, true, null).install(env)
        assertTrue(topRes is FeatureInstallResult.Installed)
        val bottomRes = HostBottomBarFxFeatureInstaller(true, true, null).install(env)
        assertTrue(bottomRes is FeatureInstallResult.Skipped)

        // When RowKt is absent (omitRowKt = true) bottom compose hooks return false while top compose hooks still don't depend on rowPolicy
        assertFalse(
            HostComposeChromeHooks.install(
                env, "bottom", emptyMap(), true, true, HostChromeAppearance(), null, {}, {}, {}
            )
        )
    }

    // -------------------------------------------------------------------------
    // 7A: Follow feed i18n
    // -------------------------------------------------------------------------

    @Test
    fun `follow feed tab labels and header titles support Simplified Traditional and English locales`() {
        assertTrue(isSupportedTabLabels(listOf("全部", "视频")))
        assertTrue(isSupportedTabLabels(listOf(" 全部 ", " 影片 ")))
        assertTrue(isSupportedTabLabels(listOf("全部", "視頻")))
        assertTrue(isSupportedTabLabels(listOf("All", "Video")))
        assertTrue(isSupportedTabLabels(listOf(" all ", " VIDEOS ")))
        assertFalse(isSupportedTabLabels(listOf("全部", "视频", "同城")))
        assertFalse(isSupportedTabLabels(listOf("全部")))
        assertFalse(isSupportedTabLabels(listOf("全部", "关注")))

        assertTrue(isSupportedHeaderTitle("关注"))
        assertTrue(isSupportedHeaderTitle(" 關注 "))
        assertTrue(isSupportedHeaderTitle("追蹤"))
        assertTrue(isSupportedHeaderTitle("Following"))
        assertTrue(isSupportedHeaderTitle(" FOLLOW "))
        assertFalse(isSupportedHeaderTitle("动态"))
        assertFalse(isSupportedHeaderTitle(null))
        assertFalse(isSupportedHeaderTitle(""))
    }

    // -------------------------------------------------------------------------
    // 8A: BrandSplash trailing state captures & obfuscated exit coroutine fallback
    // -------------------------------------------------------------------------

    @Test
    fun `brand splash constructor and obfuscated exit coroutine resolve safely while keeping broken exit rejected`() {
        val state = "androidx.compose.runtime.MutableState"
        val intState = "androidx.compose.runtime.MutableIntState"
        val prefix = listOf("Img", "Model", "androidx.compose.ui.unit.Density", "Res", state)
        assertTrue(brandSplashDrawConstructorAbi(prefix + listOf(state, state), "Img", "Model", "Res"))
        assertTrue(brandSplashDrawConstructorAbi(prefix + listOf(intState, state, state), "Img", "Model", "Res"))
        assertTrue(brandSplashDrawConstructorAbi(prefix + listOf(intState, state, state, state), "Img", "Model", "Res"))
        assertFalse(brandSplashDrawConstructorAbi(prefix + listOf(state, intState, state), "Img", "Model", "Res"))
        assertFalse(brandSplashDrawConstructorAbi(prefix + listOf(state), "Img", "Model", "Res"))
        assertFalse(brandSplashDrawConstructorAbi(prefix + listOf(state, "java.lang.String"), "Img", "Model", "Res"))

        val loader = RenamingClassLoader(
            baseLoader,
            templates = mapOf(
                BrandSplashKntrAccess.CURRENT_EXIT to TemplateRenamedExit::class.java,
                BrandSplashKntrAccess.BITMAP_EXIT to TemplateBrokenSingleMutableExit::class.java,
                "kotlin.coroutines.intrinsics.IntrinsicsKt" to TemplateIntrinsics::class.java,
                "kotlin.Result\$Failure" to TemplateResultFailure::class.java,
                "androidx.compose.runtime.MutableState" to TemplateMutableState::class.java,
                "androidx.compose.runtime.State" to TemplateState::class.java
            ),
            replacements = mapOf(
                TemplateMutableState::class.java.name.replace('.', '/') to "androidx/compose/runtime/MutableState",
                TemplateState::class.java.name.replace('.', '/') to "androidx/compose/runtime/State"
            )
        )
        // Single-valid structural CURRENT_EXIT resolves; if both were valid singleOrNull would fail, so broken BITMAP_EXIT is ignored!
        val exit = BrandSplashKntrAccess.exit(loader)
        assertNotNull(exit)
        assertEquals(BrandSplashKntrAccess.CURRENT_EXIT, exit!!.owner.name)
        assertTrue(exit.waitsForImages)
    }

    // -------------------------------------------------------------------------
    // 8C: Tablet 3-4 column grid, FollowFeedHostAccess fallback, KMP bilitheme & comment3.r
    // -------------------------------------------------------------------------

    @Test
    fun `tablet 3 and 4 column grids map columns 0 1 2 3 cleanly and keep layout margin symmetry`() {
        val loader = RenamingClassLoader(
            baseLoader,
            templates = mapOf(
                "androidx.recyclerview.widget.RecyclerView\$LayoutManager" to FakeLayoutManager::class.java,
                "androidx.recyclerview.widget.LinearLayoutManager" to FakeLinearLayoutManager::class.java,
                "androidx.recyclerview.widget.GridLayoutManager" to FakeGridLayoutManager::class.java,
                "androidx.recyclerview.widget.GridLayoutManager\$LayoutParams" to FakeGridParams::class.java
            ),
            replacements = mapOf(
                FakeLayoutManager::class.java.name.replace('.', '/') to "androidx/recyclerview/widget/RecyclerView\$LayoutManager",
                FakeLinearLayoutManager::class.java.name.replace('.', '/') to "androidx/recyclerview/widget/LinearLayoutManager",
                FakeGridLayoutManager::class.java.name.replace('.', '/') to "androidx/recyclerview/widget/GridLayoutManager",
                FakeGridParams::class.java.name.replace('.', '/') to "androidx/recyclerview/widget/GridLayoutManager\$LayoutParams"
            )
        )
        val access = requireNotNull(HostVideoCardGridAccess.resolve(loader))
        val gridCls = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager")
        val linearCls = loader.loadClass("androidx.recyclerview.widget.LinearLayoutManager")
        val paramsCls = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager\$LayoutParams")
        val grid3 = gridCls.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(3, 1)
        val grid4 = gridCls.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(4, 1)
        val p = paramsCls.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

        assertEquals(0, access.spacingSpan(grid3, p.newInstance(0, 1)))
        assertEquals(3, access.spacingSpan(grid3, p.newInstance(1, 1)))
        assertEquals(1, access.spacingSpan(grid3, p.newInstance(2, 1)))
        assertEquals(2, access.spacingSpan(grid3, p.newInstance(0, 3)))

        assertEquals(0, access.spacingSpan(grid4, p.newInstance(0, 1)))
        assertEquals(3, access.spacingSpan(grid4, p.newInstance(1, 1)))
        assertEquals(3, access.spacingSpan(grid4, p.newInstance(2, 1)))
        assertEquals(1, access.spacingSpan(grid4, p.newInstance(3, 1)))
        assertEquals(2, access.spacingSpan(linearCls.getConstructor(Int::class.javaPrimitiveType).newInstance(1), Any()))
        assertEquals(-1, access.spacingSpan(grid3, Any()))

        val spec = HostHomeCardSpacingSpec(12, 16)
        assertNotNull(spec.horizontalMargin(3, left = true, padding = 0, decoration = 0, density = 1f))
        val colSpacing = HostVideoCardSpacing(0, 0, 0, 0, 1f)
        assertEquals(7, colSpacing.left(3))
        assertEquals(7, colSpacing.right(3))
        assertEquals(5, colSpacing.top(3))
        assertEquals(12, colSpacing.bottom(3))
    }

    @Test
    fun `FollowFeedHostAccess current falls back for renamed identity localModules parent while enforcing a or b localOwner`() {
        val access = requireNotNull(FollowFeedHostAccess.current(CurrentHostModuleOk::class.java, CurrentHostItemRenamed::class.java))
        lateinit var child: CurrentHostItemRenamed
        lateinit var root: CurrentHostItemRenamed
        val mod = CurrentHostModuleOk(
            CurrentHostItemRenamed(99L, emptyList(), null, false),
            CurrentHostItemRenamed(10L, emptyList(), null, true)
        )
        val modules = listOf<Any>(mod)
        root = CurrentHostItemRenamed(99L, modules, null, false)
        child = CurrentHostItemRenamed(10L, modules, root, true)
        mod.top = root
        mod.b = child
        val row = requireNotNull(access.row(CurrentHostHolder(mod), 7))
        assertEquals(99L, row.identity)
        assertEquals(7, row.position)
        assertTrue(row.first)
        assertTrue(row.last)
        assertEquals(10L, row.reference?.identity)
        assertEquals(true, access.isQuotedModule(mod))

        assertNull(FollowFeedHostAccess.current(CurrentHostModuleBadOwner::class.java, CurrentHostItemRenamed::class.java))
    }

    @Test
    fun `HostBackgroundPageHooks supports kntr bilitheme and obfuscated comment3 section header`() {
        val loader = composeHostLoader(hideLayoutNode = false, use2ParamFillMaxSize = true, omitRowKt = true, includeKntrTheme = true, includeObfuscatedComment3 = true)
        val registrar = PlayerPortTestRegistrar()
        val errors = mutableListOf<String>()
        val env = HookEnvironment(
            "tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { id, msg -> errors += "$id:$msg" }
        )
        val controller = HostBackgroundController(
            HostBackgroundConfig(preset = HostBackgroundPreset.STARRY),
            onError = { errors += "bg:$it" }
        )
        val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(loader)) { errors += "pages:$it" }
        val count = HostBackgroundPageHooks.install(env, pages)
        assertTrue("expected hooks installed, errors=$errors, hooks=${registrar.hooks.keys}", count > 0)
        assertTrue("host_background.compose_night.kntr" in registrar.hooks)
        assertTrue("host_background.compose_color.kntr" in registrar.hooks)
        assertTrue(registrar.hooks.keys.any { it.startsWith("host_background.comment_section.") })
    }

    // -------------------------------------------------------------------------
    // Helpers & pure-JVM ABI templates
    // -------------------------------------------------------------------------

    private fun composeHostLoader(
        hideLayoutNode: Boolean,
        brokenConstructor: Boolean = false,
        use2ParamFillMaxSize: Boolean = true,
        omitRowKt: Boolean = false,
        includeKntrTheme: Boolean = false,
        includeObfuscatedComment3: Boolean = false
    ): ClassLoader {
        val templates = linkedMapOf<String, Class<*>>(
            "androidx.compose.ui.Modifier" to TplModifier::class.java,
            "androidx.compose.ui.Modifier\$Companion" to TplModifier.Companion::class.java,
            "androidx.compose.ui.Modifier\$Element" to TplModifierElement::class.java,
            "androidx.compose.ui.graphics.Brush" to TplBrush::class.java,
            "androidx.compose.ui.graphics.Shape" to TplShape::class.java,
            "androidx.compose.ui.graphics.RectangleShapeKt" to TplRectangleShapeKt::class.java,
            "androidx.compose.foundation.BackgroundKt" to TplBackgroundKt::class.java,
            "androidx.compose.foundation.layout.PaddingKt" to TplPaddingKt::class.java,
            "androidx.compose.foundation.layout.BoxKt" to TplBoxKt::class.java,
            "androidx.compose.foundation.layout.SpacerKt" to TplSpacerKt::class.java,
            "androidx.compose.ui.draw.DrawModifierKt" to TplDrawModifierKt::class.java,
            "androidx.compose.ui.graphics.drawscope.DrawScope" to TplDrawScope::class.java,
            "androidx.compose.ui.graphics.drawscope.ContentDrawScope" to TplContentDrawScope::class.java,
            "androidx.compose.ui.graphics.drawscope.DrawContext" to TplDrawContext::class.java,
            "androidx.compose.ui.graphics.Canvas" to TplCanvas::class.java,
            "androidx.compose.ui.graphics.AndroidCanvas_androidKt" to TplAndroidCanvasKt::class.java,
            "androidx.compose.runtime.Composer" to TplComposer::class.java,
            "androidx.compose.runtime.CompositionLocal" to TplCompositionLocal::class.java,
            "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt" to TplAndroidLocalsKt::class.java,
            "androidx.compose.ui.platform.AndroidComposeView" to TplAndroidComposeView::class.java,
            "androidx.compose.ui.node.LayoutNodeDrawScope" to TplLayoutNodeDrawScope::class.java,
            "androidx.compose.runtime.SnapshotIntStateKt" to TplSnapshotIntStateKt::class.java,
            "androidx.compose.runtime.IntState" to TplIntState::class.java,
            "androidx.compose.runtime.MutableIntState" to TplMutableIntState::class.java,
            "androidx.compose.ui.layout.OnGloballyPositionedModifierKt" to TplOnGloballyPositionedKt::class.java,
            "androidx.compose.ui.layout.LayoutCoordinates" to TplLayoutCoordinates::class.java,
            "androidx.compose.ui.layout.LayoutCoordinatesKt" to TplLayoutCoordinatesKt::class.java,
            "androidx.compose.ui.Alignment" to TplAlignment::class.java,
            "androidx.compose.ui.unit.LayoutDirection" to TplLayoutDirection::class.java,
            "androidx.compose.ui.layout.SubcomposeMeasureScope" to TplSubcomposeMeasureScope::class.java,
            "androidx.compose.ui.layout.SubcomposeLayoutKt" to TplSubcomposeLayoutKt::class.java,
            "androidx.compose.foundation.shape.RoundedCornerShape" to TplRoundedCornerShape::class.java,
            "androidx.compose.foundation.shape.RoundedCornerShapeKt" to TplRoundedCornerShapeKt::class.java,
            "androidx.compose.ui.draw.ShadowKt" to TplShadowKt::class.java,
            "androidx.compose.ui.draw.ClipKt" to TplClipKt::class.java,
            "kntr.base.imageloader.ImmutableImageRequest" to TplImmutableImageRequest::class.java,
            "kntr.base.imageloader.BiliImageKt" to TplBiliImageKt::class.java,
            "androidx.compose.ui.node.ComposeUiNode" to (if (brokenConstructor) TplBrokenComposeUiNode::class.java else TplComposeUiNode::class.java),
            "androidx.compose.ui.node.ComposeUiNode\$Companion" to (if (brokenConstructor) TplBrokenComposeUiNode.Companion::class.java else TplComposeUiNode.Companion::class.java),
            "renamed.compose.LayoutNodeCtorFn" to (if (brokenConstructor) TplBrokenConstructorFn::class.java else TplLayoutNodeConstructorFn::class.java),
            "renamed.compose.RectShape" to TplRectShape::class.java,
            "renamed.compose.FullModifier" to TplFullModifier::class.java,
            "renamed.compose.MutableIntStateImpl" to TplMutableIntStateImpl::class.java,
            "renamed.compose.BgElement6" to TplBgElement6::class.java
        )
        if (hideLayoutNode) {
            templates["renamed.compose.LayoutNodeImpl"] = TplLayoutNode::class.java
        } else {
            templates["androidx.compose.ui.node.LayoutNode"] = TplLayoutNode::class.java
        }
        templates["androidx.compose.foundation.layout.SizeKt"] =
            if (use2ParamFillMaxSize) TplSizeKtNoDefault::class.java else TplSizeKtDefault::class.java
        if (!omitRowKt) {
            templates["androidx.compose.foundation.layout.RowKt"] = TplRowKt::class.java
            templates["androidx.compose.ui.layout.MeasurePolicy"] = TplMeasurePolicy::class.java
            templates["androidx.compose.foundation.layout.Arrangement\$Horizontal"] = TplArrHorizontal::class.java
            templates["androidx.compose.ui.Alignment\$Vertical"] = TplAlignVertical::class.java
        }
        if (includeKntrTheme) {
            templates["kntr.common.bilitheme.BiliThemeKt"] = TplKntrBiliThemeKt::class.java
            templates["kntr.common.bilitheme.ThemeStrategy"] = TplThemeStrategy::class.java
            templates["kntr.common.bilitheme.BiliColors"] = TplBiliColors::class.java
            templates["androidx.fragment.app.Fragment"] = TplFragment::class.java
        }
        if (includeObfuscatedComment3) {
            templates["androidx.recyclerview.widget.RecyclerView"] = TplDummy::class.java
            templates["androidx.recyclerview.widget.RecyclerView\$ItemDecoration"] = TplItemDecoration::class.java
            templates["com.bilibili.app.comment3.ui.view.CommentDetailLayer"] = TplCommentDetailLayer::class.java
            templates["com.bilibili.app.comment3.data.model.CommentBase"] = TplCommentBase::class.java
            templates["com.bilibili.app.comment3.data.model.CommentItem"] = TplCommentItem::class.java
            templates["com.bilibili.app.comment3.data.model.obfuscatedR"] = TplObfuscatedSectionHeader::class.java
            templates["o.SectionDecor"] = TplCommentDecoration::class.java
        }

        val replacements = templates.entries.associate { (targetName, srcCls) ->
            srcCls.name.replace('.', '/') to targetName.replace('.', '/')
        }

        return RenamingClassLoader(
            baseLoader,
            templates,
            replacements,
            hidden = buildSet {
                if (hideLayoutNode) add("androidx.compose.ui.node.LayoutNode")
                if (omitRowKt) add("androidx.compose.foundation.layout.RowKt")
                if (includeObfuscatedComment3) add("com.bilibili.app.comment3.data.model.r")
                if (includeKntrTheme) {
                    add("com.bilibili.compose.theme.BiliThemeKt")
                    add("com.bilibili.compose.theme.BiliColors")
                }
            }
        )
    }

    private class RenamingClassLoader(
        parent: ClassLoader,
        private val templates: Map<String, Class<*>>,
        private val replacements: Map<String, String>,
        private val hidden: Set<String> = emptySet()
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (name in hidden) throw ClassNotFoundException(name)
            val template = templates[name] ?: return@synchronized super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: run {
                val raw = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class"))
                    .use { it.readBytes() }
                val fullMap = replacements + (template.name.replace('.', '/') to name.replace('.', '/'))
                val rewritten = rewriteConstantPool(raw, fullMap)
                defineClass(name, rewritten, 0, rewritten.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        private fun rewriteConstantPool(raw: ByteArray, map: Map<String, String>): ByteArray {
            val input = DataInputStream(ByteArrayInputStream(raw))
            val outBuffer = ByteArrayOutputStream()
            val output = DataOutputStream(outBuffer)
            output.writeInt(input.readInt())
            output.writeShort(input.readUnsignedShort())
            output.writeShort(input.readUnsignedShort())
            val count = input.readUnsignedShort()
            output.writeShort(count)
            val ordered = map.entries.sortedByDescending { it.key.length }
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte()
                output.writeByte(tag)
                if (tag == 1) {
                    var value = input.readUTF()
                    for ((k, v) in ordered) value = value.replace(k, v)
                    output.writeUTF(value)
                } else {
                    val length = when (tag) {
                        3, 4, 9, 10, 11, 12, 17, 18 -> 4
                        5, 6 -> 8
                        7, 8, 16, 19, 20 -> 2
                        15 -> 3
                        else -> error("Unsupported constant pool tag: $tag")
                    }
                    repeat(length) { output.writeByte(input.readUnsignedByte()) }
                    if (tag == 5 || tag == 6) index++
                }
                index++
            }
            output.write(input.readBytes())
            output.flush()
            return outBuffer.toByteArray()
        }
    }

    // --- Compose Templates ---
    interface TplModifier {
        fun foldIn(initial: Any?, operation: kotlin.jvm.functions.Function2<Any?, Any?, Any?>): Any?
        fun then(other: TplModifier): TplModifier
        interface Element : TplModifier
        companion object : TplModifier {
            override fun foldIn(initial: Any?, operation: kotlin.jvm.functions.Function2<Any?, Any?, Any?>): Any? = initial
            override fun then(other: TplModifier): TplModifier = other
        }
    }
    interface TplModifierElement : TplModifier
    class TplFullModifier : TplModifier {
        override fun foldIn(initial: Any?, operation: kotlin.jvm.functions.Function2<Any?, Any?, Any?>): Any? = initial
        override fun then(other: TplModifier): TplModifier = other
    }
    interface TplShape
    class TplRectShape : TplShape
    class TplBrush
    object TplRectangleShapeKt {
        private val rect = TplRectShape()
        @JvmStatic fun getRectangleShape(): TplShape = rect
    }
    class TplBgElement6(
        @JvmField val color: Long,
        @JvmField val brush: TplBrush?,
        @JvmField val alpha: Float,
        @JvmField val shape: TplShape,
        @JvmField val extra1: Int,
        @JvmField val extra2: String
    ) : TplModifierElement {
        override fun foldIn(initial: Any?, operation: kotlin.jvm.functions.Function2<Any?, Any?, Any?>): Any? = operation.invoke(initial, this)
        override fun then(other: TplModifier): TplModifier = other
    }
    object TplBackgroundKt {
        @JvmStatic fun `background-bw27NRU`(mod: TplModifier, color: Long, shape: TplShape): TplModifier =
            TplBgElement6(color, null, 1f, shape, 0, "bg")
    }
    object TplPaddingKt {
        @JvmStatic fun `padding-qDBjuR0`(mod: TplModifier, a: Float, b: Float, c: Float, d: Float): TplModifier = mod
    }
    object TplBoxKt {
        @JvmStatic fun Box(mod: TplModifier, c: TplComposer, changed: Int) = Unit
    }
    object TplSpacerKt {
        @JvmStatic fun Spacer(mod: TplModifier, c: TplComposer, changed: Int) = Unit
    }
    object TplSizeKtDefault {
        private val full = TplFullModifier()
        @JvmStatic fun `fillMaxSize$default`(m: TplModifier, fraction: Float, mask: Int, obj: Any?): TplModifier = full
        @JvmStatic fun `height-3ABfNKs`(m: TplModifier, h: Float): TplModifier = m
        @JvmStatic fun wrapContentSize(m: TplModifier, a: TplAlignment, u: Boolean): TplModifier = m
        @JvmStatic fun fillMaxWidth(m: TplModifier, f: Float): TplModifier = m
    }
    object TplSizeKtNoDefault {
        private val full = TplFullModifier()
        @JvmStatic fun fillMaxSize(m: TplModifier, fraction: Float): TplModifier = full
        @JvmStatic fun `height-3ABfNKs`(m: TplModifier, h: Float): TplModifier = m
        @JvmStatic fun wrapContentSize(m: TplModifier, a: TplAlignment, u: Boolean): TplModifier = m
        @JvmStatic fun fillMaxWidth(m: TplModifier, f: Float): TplModifier = m
    }
    class TplLayoutNodeConstructorFn : kotlin.jvm.functions.Function0<Any> {
        override fun invoke(): Any = TplLayoutNode()
    }
    class TplBrokenConstructorFn : kotlin.jvm.functions.Function0<Any> {
        override fun invoke(): Any = Any()
    }
    interface TplComposeUiNode {
        fun setModifier(mod: TplModifier)
        fun getModifier(): TplModifier
        companion object {
            fun getConstructor(): kotlin.jvm.functions.Function0<Any> = TplLayoutNodeConstructorFn()
        }
    }
    interface TplBrokenComposeUiNode {
        fun setModifier(mod: TplModifier)
        fun getModifier(): TplModifier
        companion object {
            fun getConstructor(): kotlin.jvm.functions.Function0<Any> = TplBrokenConstructorFn()
        }
    }
    class TplLayoutNode : TplComposeUiNode {
        private var m: TplModifier = TplModifier.Companion
        override fun setModifier(mod: TplModifier) { m = mod }
        override fun getModifier(): TplModifier = m
    }
    interface TplDrawScope {
        fun `getSize-NH-jbRc`(): Long
        fun getDrawContext(): TplDrawContext
    }
    interface TplContentDrawScope : TplDrawScope {
        fun drawContent()
    }
    interface TplDrawContext { fun getCanvas(): TplCanvas }
    class TplCanvas
    object TplAndroidCanvasKt {
        @JvmStatic fun getNativeCanvas(c: TplCanvas): Canvas = Canvas()
    }
    object TplDrawModifierKt {
        @JvmStatic fun drawBehind(m: TplModifier, fn: kotlin.jvm.functions.Function1<Any?, Any?>): TplModifier = m
        @JvmStatic fun drawWithContent(m: TplModifier, fn: kotlin.jvm.functions.Function1<Any?, Any?>): TplModifier = m
    }
    interface TplComposer { fun consume(local: TplCompositionLocal): Any? }
    open class TplCompositionLocal
    object TplAndroidLocalsKt {
        private val local = TplCompositionLocal()
        @JvmStatic fun getLocalView(): TplCompositionLocal = local
    }
    class TplAndroidComposeView { fun dispatchDraw(c: Canvas) = Unit }
    interface TplIntState { fun getIntValue(): Int }
    interface TplMutableIntState : TplIntState { fun setIntValue(v: Int) }
    class TplMutableIntStateImpl(private var v: Int) : TplMutableIntState {
        override fun getIntValue(): Int = v
        override fun setIntValue(value: Int) { v = value }
    }
    object TplSnapshotIntStateKt {
        @JvmStatic fun mutableIntStateOf(init: Int): TplMutableIntState = TplMutableIntStateImpl(init)
    }
    object TplOnGloballyPositionedKt {
        @JvmStatic fun onGloballyPositioned(m: TplModifier, fn: kotlin.jvm.functions.Function1<Any?, Any?>): TplModifier = m
    }
    interface TplLayoutCoordinates {
        fun isAttached(): Boolean
        fun getParentLayoutCoordinates(): TplLayoutCoordinates?
        fun `getSize-YbymL2g`(): Long
        fun `localPositionOf-R5De75A`(sourceCoordinates: TplLayoutCoordinates, relativeToSource: Long): Long
    }
    object TplLayoutCoordinatesKt {
        @JvmStatic fun positionInWindow(c: TplLayoutCoordinates): Long = 0L
        @JvmStatic fun positionInRoot(c: TplLayoutCoordinates): Long = 0L
    }
    interface TplAlignment {
        fun `align-KFBX0sM`(size: Long, space: Long, dir: TplLayoutDirection): Long
    }
    enum class TplLayoutDirection { Ltr }
    interface TplSubcomposeMeasureScope {
        fun subcompose(slotId: Any?, content: kotlin.jvm.functions.Function2<Any?, Any?, Any?>): List<Any>
    }
    object TplSubcomposeLayoutKt {
        @JvmStatic fun SubcomposeLayout(m: TplModifier, policy: kotlin.jvm.functions.Function2<Any?, Any?, Any?>, c: TplComposer, changed: Int, def: Int) = Unit
    }
    class TplRoundedCornerShape : TplShape
    object TplRoundedCornerShapeKt {
        @JvmStatic fun RoundedCornerShape(percent: Int): TplRoundedCornerShape = TplRoundedCornerShape()
        @JvmStatic fun `RoundedCornerShape-0680j_4`(size: Float): TplRoundedCornerShape = TplRoundedCornerShape()
    }
    object TplShadowKt {
        @JvmStatic fun `shadow-s4CzXII`(m: TplModifier, elevation: Float, shape: TplShape, clip: Boolean, ambient: Long, spot: Long): TplModifier = m
    }
    object TplClipKt {
        @JvmStatic fun clip(m: TplModifier, shape: TplShape): TplModifier = m
    }
    class TplImmutableImageRequest
    object TplBiliImageKt {
        // 5-param slimmed KMP signature instead of 13 params
        @JvmStatic fun BiliImage(req: TplImmutableImageRequest, mod: TplModifier, desc: String?, c: TplComposer, changed: Int) = Unit
    }
    object TplRowKt {
        @JvmStatic fun rowMeasurePolicy(h: TplArrHorizontal, v: TplAlignVertical, c: TplComposer, changed: Int): TplMeasurePolicy = TplMeasurePolicy()
    }
    class TplMeasurePolicy
    class TplArrHorizontal
    class TplAlignVertical

    // KMP bilitheme + obfuscated comment3.r templates
    enum class TplThemeStrategy { Auto, FollowApp, ForceNight }
    object TplKntrBiliThemeKt {
        @JvmStatic fun BiliTheme(strategy: TplThemeStrategy, a: Any?, b: Any?, c: TplComposer, changed: Int, defaults: Int) = Unit
    }
    class TplBiliColors { fun `getBg1-0d7_KjU`(): Long = 0L }
    class TplLayoutNodeDrawScope {
        fun `drawRect-n-J9OG0`(color: Long, topLeft: Long, size: Long, alpha: Float, style: Any?, filter: Any?, blendMode: Int) = Unit
    }
    class TplFragment {
        fun performViewCreated() = Unit
        fun getView(): View? = null
    }
    class TplDummy
    open class TplItemDecoration
    class TplCommentDetailLayer(@JvmField val decor: TplCommentDecoration? = null)
    open class TplCommentBase
    class TplCommentItem : TplCommentBase()
    class TplObfuscatedSectionHeader : TplCommentBase()
    class TplCommentDecoration : TplItemDecoration() {
        private fun drawSection(c: Canvas, rv: TplDummy, child: View, a: TplCommentBase, b: TplCommentBase) = Unit
    }

    // BrandSplash templates
    class TemplateMutableState
    class TemplateState
    object TemplateIntrinsics { @JvmStatic fun getCOROUTINE_SUSPENDED(): Any = Any() }
    class TemplateResultFailure
    class TemplateRenamedExit(@JvmField val `$a`: () -> Unit) {
        @JvmField var label = 0
        @JvmField var `$b`: TemplateMutableState? = null
        @JvmField var `$c`: TemplateMutableState? = null
        @JvmField var `$d`: TemplateMutableState? = null
        @JvmField var `$e`: TemplateState? = null
        fun invokeSuspend(value: Any?): Any? = value
    }
    class TemplateBrokenSingleMutableExit(@JvmField val `$onExit`: () -> Unit) {
        @JvmField var label = 0
        @JvmField var `$customLogoReady$delegate`: TemplateMutableState? = null
        @JvmField var `$currentOnShow`: TemplateState? = null
        fun invokeSuspend(value: Any?): Any? = value
    }

    // Tablet grid templates
    open class FakeLayoutManager {
        fun measureChildWithMargins(child: View, w: Int, h: Int) = Unit
        fun calculateItemDecorationsForChild(child: View, out: android.graphics.Rect) = Unit
    }
    open class FakeLinearLayoutManager(private val orient: Int = 1) : FakeLayoutManager() {
        fun getOrientation(): Int = orient
    }
    class FakeGridLayoutManager(private val cols: Int, orient: Int) : FakeLinearLayoutManager(orient) {
        fun measureChild(child: View, spec: Int, boolFlag: Boolean) = Unit
        fun getSpanCount(): Int = cols
    }
    class FakeGridParams(private val index: Int, private val size: Int) {
        fun getSpanIndex(): Int = index
        fun getSpanSize(): Int = size
    }

    // FollowFeedHostAccess renamed templates
    class CurrentHostItemRenamed(
        private val uid: Long,
        @JvmField val mods: List<Any>,
        @JvmField val refParent: CurrentHostItemRenamed?,
        private val hasQuote: Boolean
    ) {
        fun renamedId(): Long = uid
        fun b(): List<Any> = mods
        fun h(): Boolean = hasQuote
    }
    class CurrentHostModuleOk(
        @JvmField var top: Any,
        @JvmField var b: CurrentHostItemRenamed
    ) {
        fun M(): CurrentHostItemRenamed = top as CurrentHostItemRenamed
        fun O(): Boolean = false
    }
    class CurrentHostHolder(@JvmField val module: CurrentHostModuleOk)
    class CurrentHostModuleBadOwner(
        private val top: CurrentHostItemRenamed,
        @JvmField val c: CurrentHostItemRenamed
    ) {
        fun M(): CurrentHostItemRenamed = top
        fun O(): Boolean = false
    }
}
