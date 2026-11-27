package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentReplyButtonAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.DetailSpecialTagAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.NormalQualityLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.invoke
import com.google.android.material.tabs.TabLayout
import io.github.libxposed.api.XposedInterface
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable

class Wave3HardcodingAndCleanupTest {

    private class RecordingHookRegistrar : HookRegistrar {
        data class Entry(val member: Executable?, val callback: ModernMemberHookCreator)
        val exactHooks = linkedMapOf<String, Entry>()
        val adaptedHooks = linkedMapOf<String, ModernMemberHookCreator>()
        val constructorHooks = linkedMapOf<String, Entry>()
        val attempts = mutableListOf<String>()

        override fun first(
            id: String,
            className: String,
            methodName: String,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            attempts += id
        }

        override fun all(
            id: String,
            className: String,
            methodName: String,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            attempts += id
        }

        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            attempts += id
            val m = owner.getDeclaredMethod(methodName, *parameterTypes)
            exactHooks[id] = Entry(m, ModernMemberHookCreator(m).apply(block))
        }

        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            attempts += id
            adaptedHooks[id] = ModernMemberHookCreator(DummyExecutable::class.java.declaredMethods.first()).apply(block)
        }

        override fun constructor(
            id: String,
            constructor: Constructor<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            attempts += id
            constructorHooks[id] = Entry(constructor, ModernMemberHookCreator(constructor).apply(block))
        }

        fun invokeExact(id: String, receiver: Any? = null, args: Array<Any?> = emptyArray(), original: (Array<Any?>) -> Any? = { null }): Any? {
            val entry = exactHooks.getValue(id)
            return invokeCreator(entry.callback, entry.member, receiver, args, original)
        }

        fun invokeConstructor(id: String, instance: Any, args: Array<Any?>): Any? {
            val entry = constructorHooks.getValue(id)
            return invokeCreator(entry.callback, entry.member, instance, args) { null }
        }

        private fun invokeCreator(
            creator: ModernMemberHookCreator,
            member: Executable?,
            receiver: Any?,
            originalArgs: Array<Any?>,
            original: (Array<Any?>) -> Any?
        ): Any? = creator.invoke(object : XposedInterface.Chain {
            override fun getExecutable() = member ?: error("No executable")
            override fun getThisObject() = receiver
            override fun getArgs() = originalArgs.toList()
            override fun getArg(index: Int) = originalArgs[index]
            override fun proceed() = original(originalArgs)
            override fun proceed(args: Array<Any?>): Any? {
                if (args.size == originalArgs.size) args.copyInto(originalArgs)
                return original(args)
            }
            override fun proceedWith(thisObject: Any) = original(originalArgs)
            override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? {
                if (args.size == originalArgs.size) args.copyInto(originalArgs)
                return original(args)
            }
        })

        object DummyExecutable { fun run() = Unit }
    }

    private class StructuralClassLoader(
        parent: ClassLoader,
        private val templates: Map<String, Class<*>>,
        private val replacements: Map<String, String> = emptyMap(),
        private val hiddenPrefixes: Set<String> = emptySet()
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (hiddenPrefixes.any(name::startsWith)) {
                throw ClassNotFoundException(name)
            }
            val template = templates[name] ?: return@synchronized super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: run {
                val raw = requireNotNull(template.getResourceAsStream("/${template.name.replace('.', '/')}.class"))
                    .use { it.readBytes() }
                val map = replacements + (template.name.replace('.', '/') to name.replace('.', '/'))
                val bytes = rewriteConstantPool(raw, map)
                defineClass(name, bytes, 0, bytes.size)
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
            var index = 1
            while (index < count) {
                val tag = input.readUnsignedByte()
                output.writeByte(tag)
                if (tag == 1) {
                    var value = input.readUTF()
                    map.entries.sortedByDescending { it.key.length }.forEach { (k, v) ->
                        value = value.replace(k, v)
                    }
                    output.writeUTF(value)
                } else {
                    val length = when (tag) {
                        3, 4, 9, 10, 11, 12, 17, 18 -> 4
                        5, 6 -> 8
                        7, 8, 16, 19, 20 -> 2
                        15 -> 3
                        else -> error("Unsupported CP tag: $tag")
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

    private fun env(loader: ClassLoader, registrar: HookRegistrar, statuses: MutableMap<String, String> = linkedMapOf(), stages: MutableList<FeatureRuntimeStage> = mutableListOf()) =
        HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> statuses[k] = v },
            runtimeEvidence = { _, stage, _ -> stages += stage }
        )

    // -------------------------------------------------------------------------
    // 1. NormalQuality modern autoSwitch
    // -------------------------------------------------------------------------
    object AutoSwitchTemplateA {
        @JvmStatic fun c(): Boolean = true
        @JvmStatic fun helper(x: Int): Boolean = x > 0
    }
    object AutoSwitchTemplateB {
        @JvmStatic fun b(): Boolean = true
    }
    object QualityDefaultTemplate {
        @JvmStatic fun a(): Int = 80
    }
    class EmptyQualityMarkerTemplate

    fun `normal quality modern autoSwitch resolves installs and flips true to false without touching legacy tests`() {
        val methodA = AutoSwitchTemplateA::class.java.getDeclaredMethod("c")
        val methodHelper = AutoSwitchTemplateA::class.java.getDeclaredMethod("helper", Int::class.javaPrimitiveType)
        val methodB = AutoSwitchTemplateB::class.java.getDeclaredMethod("b")
        assertNull(NormalQualityLocator.selectAutoSwitch(listOf(methodHelper)))
        assertNull(NormalQualityLocator.selectAutoSwitch(listOf(methodA, methodB)))
        assertNull(NormalQualityLocator.selectAutoSwitch(emptyList()))

        val theseusName = "com.bilibili.ship.theseus.united.page.videoquality.TheseusPlayerQualityService"
        val modernLoader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                theseusName to EmptyQualityMarkerTemplate::class.java,
                "rt02" to AutoSwitchTemplateA::class.java,
                "wj12" to QualityDefaultTemplate::class.java
            ),
            hiddenPrefixes = setOf("com.bilibili.video.story.player.quality.StoryQualityService")
        )
        val rt02 = modernLoader.loadClass("rt02")
        val selected = NormalQualityLocator.selectAutoSwitch(rt02.declaredMethods.toList())
        assertEquals("c", selected?.name)
        val point = VersionAdapter.HookPoint("rt02", "c", emptyList())
        assertNotNull(NormalQualityLocator.resolveAutoSwitch(modernLoader, point))
        assertNull(NormalQualityLocator.resolveAutoSwitch(modernLoader, point.copy(paramClassNames = listOf("int"))))
        assertNull(NormalQualityLocator.resolveAutoSwitch(modernLoader, point.copy(className = "com.bapis.rt02")))

        assertTrue(NormalQualityLocator.applicableModern(modernLoader, VersionAdapter.HookPoint("wj12", "a", emptyList())))
        assertFalse(NormalQualityLocator.applicableModern(modernLoader, VersionAdapter.HookPoint("gh6.h", "c", emptyList())))

        val registrar = RecordingHookRegistrar()
        val statuses = linkedMapOf<String, String>()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val points = VersionAdapter.PlayerQualityPoints(
            defaultQualityMethod = VersionAdapter.HookPoint("wj12", "a", emptyList()),
            normalAutoSwitch = point
        )
        val installer = PlayerQualityFeatureInstaller(80, points)
        val installResult = installer.install(env(modernLoader, registrar, statuses, stages))
        assertEquals(FeatureInstallResult.Installed(2, complete = true), installResult)
        assertEquals("success", statuses["player_quality_status"])
        assertFalse(installer.requiresAdaptationRetry)

        val flipped = registrar.invokeExact("player.quality.normal.auto_switch") { true }
        assertEquals(false, flipped)
        assertTrue(FeatureRuntimeStage.OBSERVED in stages)
        assertTrue(FeatureRuntimeStage.APPLIED in stages)

        // Non-gh6.h mock without TheseusPlayerQualityService and without normalAutoSwitch stays expected = 1
        val noTheseusLoader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf("wj12" to QualityDefaultTemplate::class.java),
            hiddenPrefixes = setOf(
                theseusName,
                "com.bilibili.video.story.player.quality.StoryQualityService"
            )
        )
        val noTheseusInstaller = PlayerQualityFeatureInstaller(
            80, VersionAdapter.PlayerQualityPoints(VersionAdapter.HookPoint("wj12", "a", emptyList()))
        )
        val legacyResult = noTheseusInstaller.install(env(noTheseusLoader, RecordingHookRegistrar()))
        assertEquals(FeatureInstallResult.Installed(1, complete = true), legacyResult)
    }

    // -------------------------------------------------------------------------
    // 2. DetailSpecialTagAdapter flattened root mapper + retry
    // -------------------------------------------------------------------------
    class TemplateSpecialTag
    class TemplateSpecialTagCell(@JvmField val uri: String)
    class TemplateSpecialTagModel(@JvmField val a: List<TemplateSpecialTagCell>, @JvmField val b: Boolean)
    object TemplateSpecialTagMapper {
        @JvmStatic fun b(tag: TemplateSpecialTag): TemplateSpecialTagModel = TemplateSpecialTagModel(emptyList(), false)
    }
    object TemplateBapisSpecialTagMapper {
        @JvmStatic fun newBuilder(tag: TemplateSpecialTag): TemplateSpecialTagModel = TemplateSpecialTagModel(emptyList(), false)
    }

    fun `detail special tag adapter discovers flattened kp12 mapper rejects bapis and retries cleanly`() {
        val specialTagName = DetailSpecialTagAdapter.SPECIAL_TAG_CLASS
        val replacements = mapOf(
            TemplateSpecialTag::class.java.name.replace('.', '/') to specialTagName.replace('.', '/'),
            TemplateSpecialTagModel::class.java.name.replace('.', '/') to "v2p2"
        )
        val loader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                specialTagName to TemplateSpecialTag::class.java,
                "v2p2" to TemplateSpecialTagModel::class.java,
                "kp12" to TemplateSpecialTagMapper::class.java,
                "com.bapis.bilibili.FakeTagMapper" to TemplateBapisSpecialTagMapper::class.java
            ),
            replacements = replacements,
            hiddenPrefixes = setOf(DetailUnitedPresentationPurifyPolicy.HEADLINE_SERVICE_CLASS)
        )
        val kp12 = loader.loadClass("kp12")
        val fakeBapis = loader.loadClass("com.bapis.bilibili.FakeTagMapper")
        assertNotNull(DetailSpecialTagAdapter.inspectMapper(loader, kp12))
        assertNull(DetailSpecialTagAdapter.inspectMapper(loader, fakeBapis))

        val cached = VersionAdapter.HookPoint("kp12", "b", listOf(specialTagName))
        assertNotNull(DetailSpecialTagAdapter.resolve(loader, cached))
        assertNull(DetailSpecialTagAdapter.resolve(loader, cached.copy(className = "com.bapis.bilibili.FakeTagMapper")))

        var cachedRef: VersionAdapter.HookPoint? = null
        val registrar = RecordingHookRegistrar()
        val installer = DetailUnitedPresentationPurifyFeatureInstaller(
            hideHotSearchBadge = false,
            hideSpecialTopicTags = true,
            cachedSpecialTagMapperProvider = { cachedRef }
        )
        val first = installer.install(env(loader, registrar))
        assertEquals(FeatureInstallResult.Skipped("not-applicable-host"), first)
        assertTrue(installer.requiresAdaptationRetry)

        cachedRef = cached
        val retried = installer.install(env(loader, registrar))
        assertEquals(FeatureInstallResult.Installed(1, complete = true), retried)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(listOf("detail_united_presentation.special_topic"), registrar.exactHooks.keys.toList())
    }

    // -------------------------------------------------------------------------
    // 3. CommentReplyButtonAdapter flattened 3-family discovery & retry
    // -------------------------------------------------------------------------
    interface TemplateViewBinding
    open class TemplateViewStub(ctx: Context) : View(ctx)
    open class TemplateConstraintStub(ctx: Context) : View(ctx)
    open class TemplateImageStub(ctx: Context) : View(ctx)
    open class TemplateTextStub(ctx: Context) : View(ctx)
    open class TemplateLinearStub(ctx: Context) : LinearLayout(ctx)
    open class TemplateFrameStub(ctx: Context) : FrameLayout(ctx)
    class TemplateCommentItem
    class TemplateReplyButtonView
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

    fun `comment reply button adapter discovers flattened 3 families without dut bxu hzu and retries cleanly`() {
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
        val flattenedLoader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
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
            ),
            replacements = replacements,
            hiddenPrefixes = setOf(
                "com.bilibili.app.comm.comment2.phoenix.view.CommentReplyWidget",
                "kntr.common.comment.card.renderer.comment."
            )
        )
        val loader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
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
                "q3k" to TemplateExp3Handler::class.java,
                "com.bilibili.app.comment3.ui.holder.handle.a" to TemplateLegacyHandler::class.java
            ),
            replacements = replacements,
            hiddenPrefixes = setOf(
                "com.bilibili.app.comm.comment2.phoenix.view.CommentReplyWidget",
                "kntr.common.comment.card.renderer.comment."
            )
        )
        val cachedMap = mapOf("legacy" to "wou", "next" to "w2n", "exp3" to "q3k")
        assertTrue(CommentReplyButtonAdapter.validCache(cachedMap))
        val json = CommentReplyButtonAdapter.toJson(cachedMap)
        assertEquals(cachedMap, CommentReplyButtonAdapter.fromJson(json))
        assertNull(CommentReplyButtonAdapter.fromJson(org.json.JSONObject().put("unknown", "wou")))

        val resolvedOwners = CommentReplyButtonAdapter.resolveOwners(flattenedLoader, cachedMap)
        assertEquals(mapOf("legacy" to "wou", "next" to "w2n", "exp3" to "q3k"), resolvedOwners)

        var ownerCache = emptyMap<String, String>()
        val registrar = RecordingHookRegistrar()
        val installer = CommentReplyButtonFeatureInstaller(enabled = true) { ownerCache }
        // First run finds only direct legacy package handler; requires retry
        val first = installer.install(env(loader, registrar))
        assertEquals(FeatureInstallResult.Installed(1, complete = false), first)
        assertTrue(installer.requiresAdaptationRetry)
        val attemptsAfterFirst = registrar.attempts.size

        // Retry with flattened 3 owners installs next & exp3 without duplicating legacy
        ownerCache = cachedMap
        val retried = installer.install(env(loader, registrar))
        assertEquals(FeatureInstallResult.Installed(3, complete = true), retried)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(attemptsAfterFirst + 2, registrar.attempts.size)
    }

    // -------------------------------------------------------------------------
    // 4. RelatedFeedbackTags flattened 9.20 models (vd72/uh72)
    // -------------------------------------------------------------------------
    class TemplateRelateCard(@JvmField val t: com.bilibili.ship.theseus.united.page.intro.module.relate.RelateCardType, @JvmField val m: TemplateRelateMenu)
    class TemplateRelateMenu(@JvmField val x: TemplateRelateBox, @JvmField val y: TemplateRelateBox)
    class TemplateRelateBox(@JvmField val items: List<TemplateRelateReason>)
    class TemplateRelateReason(
        @JvmField val a: Long, @JvmField val b: Long, @JvmField val tag: Long,
        @JvmField val d: Int, @JvmField val text: String
    )

    fun `related feedback tags resolves 920 flattened vd72 and uh72 graphs`() {
        for (cardName in listOf("vd72", "uh72")) {
            val menuName = "${cardName}m"
            val boxName = "${cardName}b"
            val reasonName = "${cardName}r"
            val loader = StructuralClassLoader(
                javaClass.classLoader!!,
                templates = mapOf(
                    cardName to TemplateRelateCard::class.java,
                    menuName to TemplateRelateMenu::class.java,
                    boxName to TemplateRelateBox::class.java,
                    reasonName to TemplateRelateReason::class.java
                ),
                replacements = mapOf(
                    TemplateRelateMenu::class.java.name.replace('.', '/') to menuName,
                    TemplateRelateBox::class.java.name.replace('.', '/') to boxName,
                    TemplateRelateReason::class.java.name.replace('.', '/') to reasonName
                )
            )
            val cardClass = loader.loadClass(cardName)
            val tags = requireNotNull(RelatedFeedbackTags.resolve(cardClass))
            val reasonClass = loader.loadClass(reasonName)
            val r1 = reasonClass.declaredConstructors.single().newInstance(1L, 2L, 777L, 4, "spam")
            val boxClass = loader.loadClass(boxName)
            val b1 = boxClass.declaredConstructors.single().newInstance(listOf(r1))
            val menuClass = loader.loadClass(menuName)
            val m = menuClass.declaredConstructors.single().newInstance(b1, b1)
            val card = cardClass.declaredConstructors.single().newInstance(
                com.bilibili.ship.theseus.united.page.intro.module.relate.RelateCardType.AV, m
            )
            assertEquals(listOf(FeedbackTag(777L, "spam")), tags.read(card))
            assertTrue(tags.matches(card, setOf(777L), emptySet()))
            assertFalse(tags.matches(card, setOf(1L, 2L, 4L), emptySet()))
        }
    }

    // -------------------------------------------------------------------------
    // 5. locateMineEntry 5x(AccountMine) + unique(AccountMine, boolean)
    // -------------------------------------------------------------------------
    class TemplateAccountMine(@JvmField val sectionListV2: List<Any> = emptyList())
    class TemplateMenuItem
    open class TemplateRecyclerAdapter
    class TemplateQ3Adapter : TemplateRecyclerAdapter()
    class TemplateMineFragment(
        @JvmField val z9List: List<Any> = emptyList(),
        @JvmField val q3Adapter: TemplateQ3Adapter = TemplateQ3Adapter()
    ) {
        fun m1(a: TemplateAccountMine) = Unit
        fun m2(a: TemplateAccountMine) = Unit
        fun m3(a: TemplateAccountMine) = Unit
        fun m4(a: TemplateAccountMine) = Unit
        fun m5(a: TemplateAccountMine) = Unit
        fun wg(a: TemplateAccountMine, refresh: Boolean) = Unit
        class InnerClick {
            fun customClick(item: TemplateMenuItem) = Unit
        }
    }

    fun `locateMineEntry and MineComponentFilter handle 5 one-arg plus unique 2-arg build and renamed fields`() {
        val fragName = "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment"
        val accountName = "tv.danmaku.bili.ui.main2.api.AccountMine"
        val itemName = "com.bilibili.lib.homepage.mine.MenuGroup\$Item"
        val clickName = "$fragName\$InnerClick"
        val adapterName = "q3AdapterCls"
        val loader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                fragName to TemplateMineFragment::class.java,
                clickName to TemplateMineFragment.InnerClick::class.java,
                accountName to TemplateAccountMine::class.java,
                itemName to TemplateMenuItem::class.java,
                adapterName to TemplateQ3Adapter::class.java
            ),
            replacements = mapOf(
                TemplateMineFragment.InnerClick::class.java.name.replace('.', '/') to clickName.replace('.', '/'),
                TemplateMineFragment::class.java.name.replace('.', '/') to fragName.replace('.', '/'),
                TemplateAccountMine::class.java.name.replace('.', '/') to accountName.replace('.', '/'),
                TemplateMenuItem::class.java.name.replace('.', '/') to itemName.replace('.', '/'),
                TemplateQ3Adapter::class.java.name.replace('.', '/') to adapterName,
                TemplateRecyclerAdapter::class.java.name.replace('.', '/') to "androidx/recyclerview/widget/RecyclerView\$Adapter"
            )
        )
        val entry = requireNotNull(VersionAdapter.locateMineEntry(loader, allowModuleInstance = true))
        val build = entry.buildMethods.single()
        assertEquals("wg", build.methodName)
        assertEquals(listOf(accountName, "boolean"), build.paramClassNames)
        assertEquals("z9List", entry.groupListField)
        assertEquals("q3Adapter", entry.adapterField)
        assertEquals("customClick", entry.clickMethod.methodName)
        assertEquals(clickName, entry.clickMethod.className)
    }

    // -------------------------------------------------------------------------
    // 6. locateDynamicTabs non-a/b String fields
    // -------------------------------------------------------------------------
    open class TemplateBaseTabLayout
    class TemplateMediatorTabLayout : TemplateBaseTabLayout()
    class TemplateDynamicTabItemRenamed(@JvmField val id: Int, @JvmField val titleCol: String, @JvmField val nameCol: String)
    class TemplateDynamicTabItemAmbiguous(@JvmField val a1: String, @JvmField val b1: String, @JvmField val c1: String)
    class TemplateMediatorFragment {
        fun fetchTabs(): List<TemplateDynamicTabItemRenamed> = emptyList()
    }
    class TemplateMediatorFragmentAmbiguous {
        fun fetchTabs(): List<TemplateDynamicTabItemAmbiguous> = emptyList()
    }

    fun `locateDynamicTabs resolves unique non-ab 2 String fields and rejects 3 String fields`() {
        val fragName = "com.bilibili.bplus.followinglist.home.mediator.MediatorFragment"
        val tabName = "com.bilibili.bplus.followinglist.home.mediator.MediatorTabLayout"
        val tabLayoutSuper = TabLayout::class.java.name.replace('.', '/')
        val okLoader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                fragName to TemplateMediatorFragment::class.java,
                tabName to TemplateMediatorTabLayout::class.java
            ),
            replacements = mapOf(
                TemplateBaseTabLayout::class.java.name.replace('.', '/') to tabLayoutSuper
            )
        )
        val ok = requireNotNull(VersionAdapter.locateDynamicTabs(okLoader))
        assertEquals("fetchTabs", ok.listGetter.methodName)
        assertEquals("titleCol", ok.itemTitleField)
        assertEquals("nameCol", ok.itemNameField)

        val badLoader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                fragName to TemplateMediatorFragmentAmbiguous::class.java,
                tabName to TemplateMediatorTabLayout::class.java
            ),
            replacements = mapOf(
                TemplateBaseTabLayout::class.java.name.replace('.', '/') to tabLayoutSuper
            )
        )
        assertNull(VersionAdapter.locateDynamicTabs(badLoader))
    }

    // -------------------------------------------------------------------------
    // 7. isHomeDefaultWordEntry arbitrary obfuscated method & interface
    // -------------------------------------------------------------------------
    interface TemplateHintModel {
        fun getShow(): String
        fun getAnimationTimeMilli(): Long
        fun enableAnimation(): Boolean
        fun isShowFront(): Boolean
    }
    class SwitchTextView(context: Context) : TextView(context)
    class TemplateBaseMainFrameFragment {
        @Suppress("unused")
        private var searchBox: SwitchTextView? = null
        @Suppress("UNUSED_PARAMETER")
        fun onViewCreated(view: View, savedInstanceState: Bundle?) = Unit
    }
    class TemplateMainFragment {
        @Suppress("UNUSED_PARAMETER")
        fun obfuscatedBind(hint: TemplateHintModel?) = Unit
    }

    fun `locateHomeTopBar matches default word entry with arbitrary method and interface names`() {
        val mainName = "tv.danmaku.bili.ui.main2.MainFragment"
        val baseName = "tv.danmaku.bili.ui.main2.basic.BaseMainFrameFragment"
        val hintName = "x.y.Z"
        val loader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                baseName to TemplateBaseMainFrameFragment::class.java,
                mainName to TemplateMainFragment::class.java,
                hintName to TemplateHintModel::class.java
            ),
            replacements = mapOf(
                TemplateHintModel::class.java.name.replace('.', '/') to hintName.replace('.', '/')
            ),
            hiddenPrefixes = setOf(
                "com.bilibili.lib.homepage.startdust.menu.a",
                "tv.danmaku.bili.home.components.topbar.",
                KotlinDefaultWordsLocator.MOSS
            )
        )
        val points = requireNotNull(VersionAdapter.locateHomeTopBar(loader))
        assertEquals(baseName, points.baseOnViewCreated?.className)
        assertEquals("searchBox", points.baseOnViewCreated?.viewField)
        val entry = points.defaultWordMethods.single()
        assertEquals(mainName, entry.className)
        assertEquals("obfuscatedBind", entry.methodName)
    }

    // -------------------------------------------------------------------------
    // 8. KotlinDefaultWords 4-param & 5-param JSON validity
    // -------------------------------------------------------------------------
    interface Callback4 { fun onNext(v: Any?) }
    class LegacyKSearchMoss {
        @Suppress("UNUSED_PARAMETER")
        fun defaultWords(req: Any, s: SerializationStrategy<*>, d: DeserializationStrategy<*>, h: Callback4) = Unit
    }

    fun `kotlin default words supports 4-param legacy and 5-param modern across JSON roundtrip`() {
        val mossName = KotlinDefaultWordsLocator.MOSS
        val loader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(mossName to LegacyKSearchMoss::class.java)
        )
        val m = requireNotNull(KotlinDefaultWordsLocator.direct(loader))
        val point = KotlinDefaultWordsLocator.point(m)
        assertEquals(4, point.paramClassNames?.size)
        assertNotNull(KotlinDefaultWordsLocator.resolve(loader, point))

        val diag = VersionAdapter.AdaptDiagnostic("stub", VersionAdapter.AdaptState.FOUND, "ok")
        val result = VersionAdapter.AdaptResult(
            biliVersionCode = 9000000,
            ts = 100L,
            commentLow = null, commentHigh = null, mineEntry = null,
            pause = VersionAdapter.PausePoints(emptyList(), null, null, null),
            banner = null,
            homeTopBar = VersionAdapter.HomeTopBarPoints(null, null, null, emptyList(), point),
            mineVip = null, blockUpdate = null, dynamicTabs = null, fullNumbers = null,
            playerPortrait = null, playerInteractiveOverlays = null, pgcAutoActivityPopup = null,
            playerStatusBar = null, homeRecommendFeed = null, videoRelate = null, homeTabs = null,
            homeComponents = null, mineComponents = null, mineAccountMine = null, storyFeed = null,
            bottomBar = null, playerQuality = null, teenagersMode = null, commentPurify = null,
            commentFilter = null, commentTopology = null, commentSection = null, splashAds = null,
            hostFingerprint = "fp",
            protocolFingerprint = "protocol-v1:1:000000000000000000000000",
            diagnostics = listOf(diag)
        )
        assertNotNull(VersionAdapter.AdaptResult.fromJson(result.toJson()))
    }

    // -------------------------------------------------------------------------
    // 9. CommentComposeCopyBridge & CommentComposePurifyBridge renamed fields / store
    // -------------------------------------------------------------------------
    enum class LangEnum { ORIGIN, TRANSLATING, TRANSLATION }
    class RichTextBox(@JvmField val renamedRaw: String)
    class RenamedComposeModel(
        @JvmField val z1: Long,
        @JvmField val z2: Long,
        @JvmField val z3: Long,
        @JvmField val state: LangEnum,
        @JvmField val orig: RichTextBox,
        @JvmField val trans: RichTextBox,
        @JvmField val a1: RichTextBox,
        @JvmField val a2: RichTextBox
    )

    fun `compose copy and purify bridges resolve renamed fields and renderer-discovered CardStore`() {
        val modelAccess = requireNotNull(ComposeCommentText.resolve(RenamedComposeModel::class.java))
        val sample = RenamedComposeModel(
            1001L, 1002L, 1003L, LangEnum.TRANSLATION,
            RichTextBox("orig"), RichTextBox("translated"), RichTextBox("o"), RichTextBox("p")
        )
        assertEquals(1001L, modelAccess.cardId(sample))
        assertEquals(1003L, modelAccess.rootCommentId(sample))
        assertEquals("translated", modelAccess.read(sample))

        val modelPkg = "kntr.common.comment.card.model.comment"
        val renderPkg = "kntr.common.comment.card.renderer.comment"
        val userName = "$modelPkg.CommentUserInfoRendererModel"
        val decorName = "$userName\$Deco"
        val followName = "$userName\$B"
        val actionName = "kntr.common.comment.card.action.CardAction"
        val quickTypeName = "$actionName\$RenamedClickCard"
        val rendererName = "$renderPkg.CommentRichTextRenderer"
        val loader = StructuralClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                "$modelPkg.CommentModel" to Wave3CommentModel::class.java,
                userName to Wave3FakeUserInfo::class.java,
                decorName to Wave3Decorated::class.java,
                followName to Wave3RenamedFollow::class.java,
                "$userName\$A" to Wave3SlotA::class.java,
                "$userName\$C" to Wave3SlotC::class.java,
                "$userName\$D" to Wave3SlotD::class.java,
                actionName to Wave3FakeCardAction::class.java,
                quickTypeName to Wave3FakeCardAction.RenamedClickCard::class.java,
                rendererName to Wave3FakeRenderer::class.java,
                "cstore" to Wave3FlattenedStore::class.java,
                "cctx" to Wave3FlattenedCtx::class.java,
                "androidx.compose.runtime.Composer" to Wave3FakeComposer::class.java
            ),
            replacements = mapOf(
                Wave3CommentModel::class.java.name.replace('.', '/') to "$modelPkg/CommentModel",
                Wave3FakeUserInfo::class.java.name.replace('.', '/') to userName.replace('.', '/'),
                Wave3Decorated::class.java.name.replace('.', '/') to decorName.replace('.', '/'),
                Wave3RenamedFollow::class.java.name.replace('.', '/') to followName.replace('.', '/'),
                Wave3SlotA::class.java.name.replace('.', '/') to "$userName\$A".replace('.', '/'),
                Wave3SlotC::class.java.name.replace('.', '/') to "$userName\$C".replace('.', '/'),
                Wave3SlotD::class.java.name.replace('.', '/') to "$userName\$D".replace('.', '/'),
                Wave3FakeCardAction::class.java.name.replace('.', '/') to actionName.replace('.', '/'),
                Wave3FakeCardAction.RenamedClickCard::class.java.name.replace('.', '/') to quickTypeName.replace('.', '/'),
                Wave3FlattenedStore::class.java.name.replace('.', '/') to "cstore",
                Wave3FlattenedCtx::class.java.name.replace('.', '/') to "cctx",
                Wave3FakeComposer::class.java.name.replace('.', '/') to "androidx/compose/runtime/Composer"
            )
        )
        val registrar = RecordingHookRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val results = CommentComposePurifyBridge(vote = false, follow = true, quickReply = true)
            .install(env(loader, registrar, stages = stages))
        assertTrue((results["comments_follow_buttons_removed"] as? FeatureInstallResult.Installed)?.complete == true)
        assertTrue((results["comments_quick_reply_blocked"] as? FeatureInstallResult.Installed)?.complete == true)
        assertTrue("comment.compose.quick_reply.cstore" in registrar.exactHooks)

        val loadedDecor = loader.loadClass(decorName)
        val loadedFollow = loader.loadClass(followName).getDeclaredConstructor(Long::class.javaPrimitiveType).newInstance(42L)
        val followArgs = arrayOf<Any?>(null, loadedFollow, null, null)
        val decorInstance = loadedDecor.declaredConstructors.single().newInstance(*arrayOfNulls<Any>(4))
        registrar.invokeConstructor("comment.compose.follow.$decorName", decorInstance, followArgs)
        assertNull(followArgs[1])

        val clickCard = loader.loadClass(quickTypeName).declaredConstructors.single()
            .newInstance(1L, 2L, 3L, 4L, 5L, "s1", "s2", true)
        registrar.invokeExact("comment.compose.quick_reply.cstore", args = arrayOf(clickCard))
        assertTrue(FeatureRuntimeStage.OBSERVED in stages)
        assertTrue(FeatureRuntimeStage.APPLIED in stages)
    }

    // -------------------------------------------------------------------------
    // 10. Static verification: zero jadx pXXXX & dead hardcoded literals in main
    // -------------------------------------------------------------------------
    fun `production sources retain zero jadx fake packages and zero cleaned hardcodings`() {
        val mainRoot = listOf(File("src/main/java"), File("app/src/main/java")).first(File::isDirectory)
        val forbidden = Regex(""""[^"]*(p\d{4}[a-z]|xm3\.d|BottomGameBenefitWidgetKt|yx3\.a|hv2\.a|cg1|dut|bxu|hzu|bjx1|iox1|hkx1|wmx1|rop0)""")
        val hits = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { idx, line ->
                    if (forbidden.containsMatchIn(line)) "${file.name}:${idx + 1}: ${line.trim()}" else null
                }
            }.toList()
        assertTrue("Unexpected forbidden literals in main sources: $hits", hits.isEmpty())
        assertEquals(100, VersionAdapterContract.RULE_VERSION)
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (Wave3HardcodingAndCleanupTest) -> Unit) {
            val s = Wave3HardcodingAndCleanupTest()
            block(s)
        }
        runIsolated { s -> s.`normal quality modern autoSwitch resolves installs and flips true to false without touching legacy tests`() }
        runIsolated { s -> s.`detail special tag adapter discovers flattened kp12 mapper rejects bapis and retries cleanly`() }
        runIsolated { s -> s.`comment reply button adapter discovers flattened 3 families without dut bxu hzu and retries cleanly`() }
        runIsolated { s -> s.`related feedback tags resolves 920 flattened vd72 and uh72 graphs`() }
        runIsolated { s -> s.`locateMineEntry and MineComponentFilter handle 5 one-arg plus unique 2-arg build and renamed fields`() }
        runIsolated { s -> s.`locateDynamicTabs resolves unique non-ab 2 String fields and rejects 3 String fields`() }
        runIsolated { s -> s.`locateHomeTopBar matches default word entry with arbitrary method and interface names`() }
        runIsolated { s -> s.`kotlin default words supports 4-param legacy and 5-param modern across JSON roundtrip`() }
        runIsolated { s -> s.`compose copy and purify bridges resolve renamed fields and renderer-discovered CardStore`() }
        runIsolated { s -> s.`production sources retain zero jadx fake packages and zero cleaned hardcodings`() }
    }
}

internal class Wave3CommentModel
internal class Wave3RenamedFollow(@JvmField val mid: Long) {
    override fun toString() = "Follow(mid=$mid)"
}
internal class Wave3SlotA
internal class Wave3SlotC
internal class Wave3SlotD
internal class Wave3Decorated(
    @JvmField val w: Wave3SlotA?,
    @JvmField var x: Wave3RenamedFollow?,
    @JvmField val y: Wave3SlotC?,
    @JvmField val z: Wave3SlotD?
)
internal class Wave3FakeUserInfo(@JvmField val decor: Wave3Decorated?)
internal open class Wave3FakeCardAction {
    class RenamedClickCard(
        @JvmField val l1: Long,
        @JvmField val l2: Long,
        @JvmField val l3: Long,
        @JvmField val l4: Long,
        @JvmField val l5: Long,
        @JvmField val s1: String,
        @JvmField val s2: String,
        @JvmField val flag: Boolean
    ) : Wave3FakeCardAction() {
        override fun toString() = "ClickCard(id=$l1)"
    }
}
internal class Wave3FlattenedStore { fun handle(a: Wave3FakeCardAction) = Unit }
internal class Wave3FlattenedCtx(@JvmField val store: Wave3FlattenedStore)
internal interface Wave3FakeComposer
internal class Wave3FakeRenderer {
    fun render(ctx: Wave3FlattenedCtx, composer: Wave3FakeComposer) = Unit
}
