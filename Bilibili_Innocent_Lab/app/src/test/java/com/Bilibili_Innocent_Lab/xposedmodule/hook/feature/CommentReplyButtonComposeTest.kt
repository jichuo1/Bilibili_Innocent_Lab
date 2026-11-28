package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import java.lang.reflect.Constructor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNUSED_PARAMETER")
class CommentReplyButtonComposeTest {
    fun selectsTheUniqueRendererWithoutDependingOnItsName() {
        val method = renderer(UniqueRenderer::class.java)

        assertEquals("z", method?.name)
        assertEquals(UniqueRenderer::class.java, method?.declaringClass)
        assertEquals(
            listOf(Button::class.java, String::class.java, Composer::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            method?.parameterTypes?.toList()
        )
    }

    fun ignoresAnOtherwiseMatchingStaticMethod() {
        val method = renderer(RendererWithStaticDecoy::class.java)

        assertEquals("render", method?.name)
        assertEquals(RendererWithStaticDecoy::class.java, method?.declaringClass)
    }

    fun rejectsTwoMatchingMethodsInsteadOfChoosingByDeclarationOrder() {
        assertNull(renderer(AmbiguousRenderer::class.java))
    }

    fun rejectsNonVoidMissingRepeatedAndMisplacedComposerShapes() {
        assertNull(renderer(WrongShapes::class.java))
    }

    fun requiresTheExactButtonAndComposerClasses() {
        assertNull(renderer(UniqueRenderer::class.java, OtherButton::class.java))
        assertNull(renderer(UniqueRenderer::class.java, composer = OtherComposer::class.java))
    }

    fun ignoresACompilerBridgeEvenWhenItsErasedSignatureMatches() {
        val owner = ConcreteComposerRenderer::class.java
        assertTrue(owner.declaredMethods.any { method ->
            method.isBridge && method.isSynthetic && method.parameterTypes.contentEquals(
                arrayOf(Button::class.java, Composer::class.java)
            )
        })

        assertNull(renderer(owner))
    }

    fun doesNotTreatAnInheritedRendererAsADeclaredCandidate() {
        assertNull(renderer(InheritedRenderer::class.java))
    }

    private fun renderer(
        owner: Class<*>,
        button: Class<*> = Button::class.java,
        composer: Class<*> = Composer::class.java
    ) = commentReplyButtonRenderer(owner, button, composer)

    private enum class Button { TIME_AND_IP, REPLY, LIKE, DISLIKE, MORE }
    private enum class OtherButton { REPLY }
    private interface Composer
    private interface OtherComposer
    private class ConcreteComposer : Composer

    private class UniqueRenderer {
        fun z(button: Button, model: String, composer: Composer, changed: Int, defaults: Int) = Unit
        fun unused(button: Any, composer: Composer) = Unit
    }

    private class RendererWithStaticDecoy {
        fun render(button: Button, composer: Composer) = Unit

        companion object {
            @JvmStatic
            fun decoy(button: Button, composer: Composer) = Unit
        }
    }

    private class AmbiguousRenderer {
        fun a(button: Button, composer: Composer) = Unit
        fun b(button: Button, composer: Composer, changed: Int) = Unit
    }

    private class WrongShapes {
        fun nonVoid(button: Button, composer: Composer): Int = 1
        fun boxedUnit(button: Button, composer: Composer): Unit? = Unit
        fun missingComposer(button: Button) = Unit
        fun repeatedComposer(button: Button, first: Composer, second: Composer) = Unit
        fun misplacedButton(composer: Composer, button: Button) = Unit
        fun differentButton(button: OtherButton, composer: Composer) = Unit
        fun erasedButton(button: Any, composer: Composer) = Unit
        fun noArguments() = Unit
    }

    private interface GenericRenderer<T : Composer> {
        fun render(button: Button, composer: T)
    }

    private class ConcreteComposerRenderer : GenericRenderer<ConcreteComposer> {
        override fun render(button: Button, composer: ConcreteComposer) = Unit
    }

    private open class BaseRenderer {
        fun render(button: Button, composer: Composer) = Unit
    }

    private class InheritedRenderer : BaseRenderer()

    interface TemplateViewBinding
    open class TemplateViewStub(ctx: Context) : View(ctx)
    open class TemplateConstraintStub(ctx: Context) : View(ctx)
    open class TemplateImageStub(ctx: Context) : View(ctx)
    open class TemplateTextStub(ctx: Context) : View(ctx)
    open class TemplateLinearStub(ctx: Context) : LinearLayout(ctx)
    open class TemplateFrameStub(ctx: Context) : FrameLayout(ctx)
    class TemplateCommentItem
    class TemplateLegacyBinding(
        @JvmField val a: TemplateConstraintStub,
        @JvmField val i1: TemplateImageStub, @JvmField val i2: TemplateImageStub, @JvmField val i3: TemplateImageStub,
        @JvmField val i4: TemplateImageStub, @JvmField val i5: TemplateImageStub,
        @JvmField val t1: TemplateTextStub, @JvmField val t2: TemplateTextStub, @JvmField val t3: TemplateTextStub
    ) : TemplateViewBinding {
        fun getRoot(): TemplateViewStub = error("stub")
    }
    class TemplateNextBinding(
        @JvmField val a: TemplateConstraintStub,
        @JvmField val i1: TemplateImageStub, @JvmField val i2: TemplateImageStub, @JvmField val i3: TemplateImageStub, @JvmField val i4: TemplateImageStub,
        @JvmField val t1: TemplateTextStub, @JvmField val t2: TemplateTextStub, @JvmField val t3: TemplateTextStub,
        @JvmField val f1: TemplateFrameStub, @JvmField val f2: TemplateFrameStub, @JvmField val f3: TemplateFrameStub,
        @JvmField val l1: TemplateLinearStub
    ) : TemplateViewBinding {
        fun getRoot(): TemplateViewStub = error("stub")
    }
    class TemplateExp3Binding(
        @JvmField val l1: TemplateLinearStub, @JvmField val l2: TemplateLinearStub,
        @JvmField val i1: TemplateImageStub, @JvmField val i2: TemplateImageStub, @JvmField val i3: TemplateImageStub, @JvmField val i4: TemplateImageStub,
        @JvmField val t1: TemplateTextStub, @JvmField val t2: TemplateTextStub, @JvmField val t3: TemplateTextStub, @JvmField val t4: TemplateTextStub,
        @JvmField val f1: TemplateFrameStub
    ) : TemplateViewBinding {
        fun getRoot(): TemplateViewStub = error("stub")
    }
    class TemplateLegacyHandler { fun a(item: TemplateCommentItem, b: TemplateLegacyBinding) = Unit }
    class TemplateNextHandler { fun b(item: TemplateCommentItem, b: TemplateNextBinding) = Unit }
    class TemplateExp3Handler { fun c(item: TemplateCommentItem, b: TemplateExp3Binding) = Unit }

    private class AttemptRegistrar : HookRegistrar {
        val attempts = mutableListOf<String>()
        override fun first(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) { attempts += id }
        override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) { attempts += id }
        override fun exact(id: String, owner: Class<*>, methodName: String, vararg parameterTypes: Class<*>, block: ModernMemberHookCreator.() -> Unit) { attempts += id }
        override fun adapted(id: String, point: VersionAdapter.HookPoint, exceptionPolicy: HookExceptionPolicy, block: ModernMemberHookCreator.() -> Unit) { attempts += id }
        override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) { attempts += id }
    }

    fun adapterDiscoversFlattenedThreeFamiliesAndRetriesCleanly() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        val commentItem = CommentReplyButtonAdapter.COMMENT_ITEM
        val replacements = mapOf(
            TemplateViewBinding::class.java.name.replace('.', '/') to "androidx/viewbinding/ViewBinding",
            TemplateConstraintStub::class.java.name.replace('.', '/') to "androidx/constraintlayout/widget/ConstraintLayout",
            TemplateImageStub::class.java.name.replace('.', '/') to "com/bilibili/magicasakura/widgets/TintImageView",
            TemplateTextStub::class.java.name.replace('.', '/') to "com/bilibili/magicasakura/widgets/TintTextView",
            TemplateLinearStub::class.java.name.replace('.', '/') to "android/widget/LinearLayout",
            TemplateFrameStub::class.java.name.replace('.', '/') to "android/widget/FrameLayout",
            TemplateViewStub::class.java.name.replace('.', '/') to "android/view/View",
            TemplateCommentItem::class.java.name.replace('.', '/') to commentItem.replace('.', '/'),
            TemplateLegacyBinding::class.java.name.replace('.', '/') to "t2t",
            TemplateNextBinding::class.java.name.replace('.', '/') to "e9s",
            TemplateExp3Binding::class.java.name.replace('.', '/') to "a9s"
        )
        val hidden = setOf(
            "com.bilibili.app.comm.comment2.phoenix.view.CommentReplyWidget",
            "kntr.common.comment.card.renderer.comment."
        )
        val baseTemplates = mapOf(
            "androidx.viewbinding.ViewBinding" to TemplateViewBinding::class.java,
            "androidx.constraintlayout.widget.ConstraintLayout" to TemplateConstraintStub::class.java,
            "com.bilibili.magicasakura.widgets.TintImageView" to TemplateImageStub::class.java,
            "com.bilibili.magicasakura.widgets.TintTextView" to TemplateTextStub::class.java,
            commentItem to TemplateCommentItem::class.java,
            "t2t" to TemplateLegacyBinding::class.java,
            "e9s" to TemplateNextBinding::class.java,
            "a9s" to TemplateExp3Binding::class.java,
            "wou" to TemplateLegacyHandler::class.java,
            "w2n" to TemplateNextHandler::class.java,
            "q3k" to TemplateExp3Handler::class.java
        )
        val flattenedLoader = TestRenamingClassLoader(
            parent = baseLoader, templates = baseTemplates, replacements = replacements, hiddenPrefixes = hidden
        )
        val loader = TestRenamingClassLoader(
            parent = baseLoader,
            templates = baseTemplates + ("com.bilibili.app.comment3.ui.holder.handle.a" to TemplateLegacyHandler::class.java),
            replacements = replacements,
            hiddenPrefixes = hidden
        )
        val cachedMap = mapOf("legacy" to "wou", "next" to "w2n", "exp3" to "q3k")
        assertTrue(CommentReplyButtonAdapter.validCache(cachedMap))
        val json = CommentReplyButtonAdapter.toJson(cachedMap)
        assertEquals(cachedMap, CommentReplyButtonAdapter.fromJson(json))
        assertNull(CommentReplyButtonAdapter.fromJson(org.json.JSONObject().put("unknown", "wou")))

        assertEquals(cachedMap, CommentReplyButtonAdapter.resolveOwners(flattenedLoader, cachedMap))

        var ownerCache = emptyMap<String, String>()
        val registrar = AttemptRegistrar()
        val installer = CommentReplyButtonFeatureInstaller(enabled = true) { ownerCache }
        val env = HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar, { _, _ -> }, { _, _ -> }, { _, _ -> })
        assertEquals(FeatureInstallResult.Installed(1, complete = false), installer.install(env))
        assertTrue(installer.requiresAdaptationRetry)
        val attemptsAfterFirst = registrar.attempts.size

        ownerCache = cachedMap
        assertEquals(FeatureInstallResult.Installed(3, complete = true), installer.install(env))
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(attemptsAfterFirst + 2, registrar.attempts.size)
    }

    @org.junit.Test
    fun verifyAllCases() {
        fun runIsolated(block: (CommentReplyButtonComposeTest) -> Unit) {
            val s = CommentReplyButtonComposeTest()
            block(s)
        }
        runIsolated { s -> s.selectsTheUniqueRendererWithoutDependingOnItsName() }
        runIsolated { s -> s.ignoresAnOtherwiseMatchingStaticMethod() }
        runIsolated { s -> s.rejectsTwoMatchingMethodsInsteadOfChoosingByDeclarationOrder() }
        runIsolated { s -> s.rejectsNonVoidMissingRepeatedAndMisplacedComposerShapes() }
        runIsolated { s -> s.requiresTheExactButtonAndComposerClasses() }
        runIsolated { s -> s.ignoresACompilerBridgeEvenWhenItsErasedSignatureMatches() }
        runIsolated { s -> s.doesNotTreatAnInheritedRendererAsADeclaredCandidate() }
        runIsolated { s -> s.adapterDiscoversFlattenedThreeFamiliesAndRetriesCleanly() }
    }
}
