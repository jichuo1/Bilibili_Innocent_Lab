package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import BadPhase3QuickReplyIntent
import FlatPhase3QuickReplyCollector
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator.Family
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentPurifyFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentReplyButtonFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.app.comment3.data.state.PublishDialogIntent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class CommentClassicStyleLocatorTest {
    interface Decision
    class Context
    class Legacy
    class Native {
        fun read(key: String, fallback: Boolean): Boolean = fallback
        fun unrelated(key: String): Boolean = true
        fun wrong(key: Int, fallback: Boolean): Boolean = fallback
    }
    class OtherNative { fun read(key: String, fallback: Boolean): Boolean = fallback }
    class KotlinReaders {
        companion object {
            @JvmStatic fun getBool(decision: Decision?, key: String, fallback: Boolean, context: Context?): Boolean = fallback
            @JvmStatic fun `getBool$default`(decision: Decision?, key: String, fallback: Boolean, context: Context?, mask: Int, marker: Any?): Boolean = fallback
            @JvmStatic fun bad(decision: Any?, key: String, fallback: Boolean, context: Context?): Boolean = fallback
        }
    }

    companion object {
        fun loader(kotlin: Boolean = false, hideNative: Boolean = false, missingKotlin: Boolean = false): ClassLoader =
            object : ClassLoader(CommentClassicStyleLocatorTest::class.java.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> = when (name) {
                    CommentClassicStyleLocator.NATIVE_ENTRY -> Legacy::class.java
                    CommentClassicStyleLocator.NATIVE_OWNER -> if (hideNative) throw ClassNotFoundException(name) else super.loadClass(name, resolve)
                    CommentClassicStyleLocator.COMPOSE_ENTRY -> if (kotlin) Legacy::class.java else throw ClassNotFoundException(name)
                    CommentClassicStyleLocator.KOTLIN_OWNER -> if (kotlin && !missingKotlin) KotlinReaders::class.java else throw ClassNotFoundException(name)
                    else -> super.loadClass(name, resolve)
                }
            }

        fun point(owner: Class<*>, name: String) = owner.declaredMethods.single { it.name == name && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .let { HookPoint(it.declaringClass.name, it.name, it.parameterTypes.map(Class<*>::getName)) }
    }

    private fun classicStyleLocatorCases() {
        val native = Native::class.java.declaredMethods.toList()
        assertEquals(listOf("read"), CommentClassicStyleLocator.select(native, Family.NATIVE).map { it.name })
        assertTrue(CommentClassicStyleLocator.select(native + OtherNative::class.java.declaredMethods, Family.NATIVE).isEmpty())
        assertEquals(
            setOf("getBool", "getBool\$default"),
            CommentClassicStyleLocator.select(KotlinReaders::class.java.declaredMethods.toList(), Family.KOTLIN).map { it.name }.toSet()
        )

        assertEquals(1, Family.KOTLIN.keyIndex)
        assertEquals(2, CommentClassicStyleLocator.direct(loader(kotlin = true), Family.KOTLIN).size)
        assertFalse(CommentClassicStyleLocator.matchesKey("comment.kntr.enabled_extra"))
        assertFalse(CommentClassicStyleLocator.matchesKey("comment.kntr.future.enabled"))
        assertTrue(CommentClassicStyleLocator.matchesKey("comment.kntr.story.enabled"))
        val methods = KotlinReaders::class.java.declaredMethods.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val wrapper = methods.single { it.name == "getBool\$default" }
        assertTrue(CommentClassicStyleLocator.isDefaultDelegate(wrapper, methods.single { it.name == "getBool" }))
        assertFalse(CommentClassicStyleLocator.isDefaultDelegate(wrapper, methods.single { it.name == "bad" }))

        val k = listOf(point(KotlinReaders::class.java, "getBool"))
        val cache = CommentClassicStylePoints(emptyList(), k)
        assertEquals(1, CommentClassicStyleLocator.readers(loader(kotlin = true, missingKotlin = true), cache, Family.KOTLIN).size)
        assertEquals(2, CommentClassicStyleLocator.readers(loader(kotlin = true), cache, Family.KOTLIN).size)
        assertTrue(CommentClassicStyleLocator.resolve(loader(), k.map { it.copy(paramClassNames = listOf("bad")) }, Family.KOTLIN).isEmpty())
        val live = CommentClassicStylePoints(emptyList(), k)
        val nativePoint = HookPoint("native", "read", listOf("java.lang.String", "boolean"))
        assertEquals(CommentClassicStylePoints(listOf(nativePoint), k), CommentClassicStyleLocator.merge(live, CommentClassicStylePoints(listOf(nativePoint), emptyList())))

        val points = CommentClassicStylePoints(emptyList(), listOf(point(KotlinReaders::class.java, "getBool")))
        assertEquals(points, CommentClassicStylePoints.fromJson(points.toJson()))
        assertNull(CommentClassicStylePoints.fromJson(JSONObject().put("native", JSONArray()).put("kotlin", "bad")))
        assertNull(CommentClassicStylePoints.fromJson(points.toJson().put("kotlin", JSONArray((0..16).map { points.kotlinReaders[0].toJson() }))))

        var batches = 0
        val engine = DexAssistEngine { request ->
            batches++
            DexAssistResult.Candidates(
                if (request.query == Family.NATIVE.query) Native::class.java.declaredMethods.toList()
                else KotlinReaders::class.java.declaredMethods.toList()
            )
        }
        val direct = loader(kotlin = true)
        val session = DexAssistSession(engine, listOf("base.apk"), direct, Family.entries.map { it.query }.toSet())
        CommentClassicStyleLocator.locate(direct, session, true)
        assertEquals(0, batches)
        val missing = loader(kotlin = true, hideNative = true, missingKotlin = true)
        val fallback = DexAssistSession(engine, listOf("base.apk"), missing, Family.entries.map { it.query }.toSet())
        CommentClassicStyleLocator.locate(missing, fallback, false)
        assertEquals(0, batches)
        val located = CommentClassicStyleLocator.locate(missing, fallback, true)
        assertEquals(2, batches)
        assertEquals(1, located.nativeReaders.size)
        assertEquals(2, located.kotlinReaders.size)
    }

    private fun quickReplyAdapterReplyButtonAndComposeMenuCases() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        assertTrue(CommentQuickReplyAdapter.applicable(baseLoader))
        val validEmit = FlatPhase3QuickReplyCollector::class.java.getDeclaredMethod("emit", PublishDialogIntent::class.java, Any::class.java)
        val loaderWithContinuation = TestRenamingClassLoader(
            parent = baseLoader,
            templates = mapOf(
                "FlatPhase3QuickReplyCollector" to TemplateQuickReplyWithCommentOwner::class.java,
                "FlatPhase3QuickReplyCollectorB" to TemplateQuickReplyWithCommentOwner::class.java,
                "FlatPhase3UnrelatedCollector1" to TemplateQuickReplyUnrelated::class.java,
                "FlatPhase3UnrelatedCollector2" to TemplateQuickReplyUnrelated::class.java,
                "FlatPhase3BadQuickReplyCollector" to TemplateQuickReplyBadIntent::class.java,
                "FlatPhase3BadIntentCollector" to TemplateQuickReplyMissingPosIntent::class.java,
                "com.example.Bad.PublishDialogIntent" to BadPublishIntentSuffix::class.java
            ),
            replacements = mapOf(
                TemplateBaseContinuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation",
                BadPublishIntentSuffix::class.java.name.replace('.', '/') to "com/example/Bad/PublishDialogIntent"
            )
        )
        val flattenedClass = loaderWithContinuation.loadClass("FlatPhase3QuickReplyCollector")
        val flattenedMethod = flattenedClass.declaredMethods.single { it.name == "emit" }
        val flattenedClassB = loaderWithContinuation.loadClass("FlatPhase3QuickReplyCollectorB")
        val flattenedMethodB = flattenedClassB.declaredMethods.single { it.name == "emit" }
        val badIntentMethod = loaderWithContinuation.loadClass("FlatPhase3BadQuickReplyCollector").declaredMethods.single { it.name == "emit" }
        val badPosMethod = loaderWithContinuation.loadClass("FlatPhase3BadIntentCollector").declaredMethods.single { it.name == "emit" }

        assertTrue(CommentQuickReplyAdapter.verifiedMethod(flattenedMethod))
        assertFalse(CommentQuickReplyAdapter.verifiedMethod(validEmit))
        assertFalse(CommentQuickReplyAdapter.verifiedMethod(badIntentMethod))
        assertFalse(CommentQuickReplyAdapter.verifiedMethod(badPosMethod))
        assertFalse(CommentQuickReplyAdapter.allowedOwnerName("evil.pkg.Collector"))

        val session = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(badIntentMethod, badPosMethod, flattenedMethod, flattenedMethodB)) },
            listOf("base.apk"), loaderWithContinuation, setOf(CommentQuickReplyAdapter.query)
        )
        val selected = CommentQuickReplyAdapter.locate(
            loaderWithContinuation, session, enabled = true, directMethods = emptyList(), cachedMethods = emptyList(), planned = true
        )
        assertEquals(2, selected.size)
        assertEquals(listOf("FlatPhase3QuickReplyCollector", "FlatPhase3QuickReplyCollectorB"), selected.map { it.className })
        assertEquals(selected, CommentQuickReplyAdapter.resolve(loaderWithContinuation, selected))

        val unrelated1 = loaderWithContinuation.loadClass("FlatPhase3UnrelatedCollector1").declaredMethods.single { it.name == "emit" }
        val unrelated2 = loaderWithContinuation.loadClass("FlatPhase3UnrelatedCollector2").declaredMethods.single { it.name == "emit" }
        val ambiguousSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(unrelated1, unrelated2)) },
            listOf("base.apk"), loaderWithContinuation, setOf(CommentQuickReplyAdapter.query)
        )
        assertTrue(CommentQuickReplyAdapter.locate(loaderWithContinuation, ambiguousSession, enabled = true, directMethods = emptyList(), cachedMethods = emptyList(), planned = true).isEmpty())

        val uniqueSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(listOf(unrelated1)) },
            listOf("base.apk"), loaderWithContinuation, setOf(CommentQuickReplyAdapter.query)
        )
        assertEquals(
            listOf("FlatPhase3UnrelatedCollector1"),
            CommentQuickReplyAdapter.locate(loaderWithContinuation, uniqueSession, enabled = true, directMethods = emptyList(), cachedMethods = emptyList(), planned = true).map { it.className }
        )

        val base = VersionAdapterTest().result()
        val cachedQuickReply = HookPoint("FlatPhase3QuickReplyCollector", "emit", listOf(PublishDialogIntent::class.java.name, "kotlin.coroutines.Continuation"))
        val runtimeWithoutQuickReply = base.copy(commentPurify = base.commentPurify!!.copy(quickReplyDialogMethods = emptyList()))
        val cachedWithQuickReply = base.copy(
            domainRuleVersions = VersionAdapterContract.DOMAIN_RULE_VERSIONS,
            commentPurify = base.commentPurify.copy(quickReplyDialogMethods = listOf(cachedQuickReply))
        )
        val merged = requireNotNull(VersionAdapter.mergeRuntimeWithCached(runtimeWithoutQuickReply, cachedWithQuickReply))
        assertEquals(listOf(cachedQuickReply), merged.commentPurify?.quickReplyDialogMethods)

        // CommentReplyButtonFeatureInstaller single-shot bounded hot retry
        val replyInstaller = CommentReplyButtonFeatureInstaller(enabled = true) { emptyMap() }
        val replyLoader = TestRenamingClassLoader(
            parent = baseLoader,
            hiddenNames = setOf("com.bilibili.app.comm.comment2.phoenix.view.CommentReplyWidget")
        )
        val replyRegistrar = PlayerPortTestRegistrar()
        val replyEnv = HookEnvironment("tv.danmaku.bili", replyLoader, HookPointRegistry(replyLoader), replyRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> })
        replyInstaller.install(replyEnv)
        assertTrue(replyInstaller.requiresAdaptationRetry)
        replyInstaller.install(replyEnv)
        assertFalse(replyInstaller.requiresAdaptationRetry)

        // CommentPurifyFeatureInstaller quickReplyDialogMethods retry
        val qrPoint = HookPoint("FlatPhase3QuickReplyCollector", "emit", listOf(PublishDialogIntent::class.java.name, "kotlin.coroutines.Continuation"))
        val purifyRef = AtomicReference<VersionAdapter.CommentPurifyPoints?>(null)
        val purifyAdaptedIds = mutableListOf<String>()
        val purifyDelegate = PlayerPortTestRegistrar()
        val purifyRegistrar = object : HookRegistrar by purifyDelegate {
            override fun adapted(id: String, point: HookPoint, exceptionPolicy: HookExceptionPolicy, block: ModernMemberHookCreator.() -> Unit) {
                purifyAdaptedIds += id
                purifyDelegate.adapted(id, point, exceptionPolicy, block)
            }
        }
        val purifyEnv = HookEnvironment("tv.danmaku.bili", loaderWithContinuation, HookPointRegistry(loaderWithContinuation), purifyRegistrar, { _, _ -> }, { _, _ -> }, { _, _ -> })
        val purifyInstaller = CommentPurifyFeatureInstaller(
            removeSearchLinks = false, removeEmptyGuide = false, removeVoteWidgets = false,
            removeFollowButtons = false, removeQoe = false, removeOperations = false,
            blockQuickReply = true, points = null, pointsProvider = { purifyRef.get() }
        )
        purifyInstaller.install(purifyEnv)
        assertTrue(purifyInstaller.requiresAdaptationRetry)
        purifyRef.set(VersionAdapter.CommentPurifyPoints(emptyList(), emptyList(), emptyList(), null, null, emptyList(), quickReplyDialogMethods = listOf(qrPoint)))
        assertTrue(purifyInstaller.install(purifyEnv) is FeatureInstallResult.Installed)
        assertFalse(purifyInstaller.requiresAdaptationRetry)
        assertEquals(1, purifyAdaptedIds.count { it.startsWith("comment.purify.quick_reply") })
        purifyInstaller.install(purifyEnv)
        assertEquals(1, purifyAdaptedIds.count { it.startsWith("comment.purify.quick_reply") })

        // CommentReplyButtonAdapter & CommentComposeMenuAdapter direct probe checks
        val loaderLookups = mutableListOf<String>()
        val probeLoader = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                loaderLookups += name
                return super.loadClass(name, resolve)
            }
        }
        assertTrue(CommentReplyButtonAdapter.resolvePlans(probeLoader, emptyMap()).isEmpty())
        assertTrue(loaderLookups.any { it.startsWith("com.bilibili.app.comment3.") })
        assertFalse(CommentComposeMenuAdapter.resolve(probeLoader, null).isComplete())
    }

    @Test
    fun verifyAllCases() {
        classicStyleLocatorCases()
        quickReplyAdapterReplyButtonAndComposeMenuCases()
    }

    class TemplateBaseContinuation
    class BadPublishIntentSuffix
    class TemplateQuickReplyWithCommentOwner(
        @JvmField val owner: com.bilibili.app.comment3.ui.CommentContainerImpl? = null
    ) {
        @Suppress("UNUSED_PARAMETER")
        fun emit(intent: PublishDialogIntent, cont: TemplateBaseContinuation): Any = Unit
    }
    class TemplateQuickReplyUnrelated {
        @Suppress("UNUSED_PARAMETER")
        fun emit(intent: PublishDialogIntent, cont: TemplateBaseContinuation): Any = Unit
    }
    class TemplateQuickReplyBadIntent {
        @Suppress("UNUSED_PARAMETER")
        fun emit(intent: BadPhase3QuickReplyIntent, cont: TemplateBaseContinuation): Any = Unit
    }
    class TemplateQuickReplyMissingPosIntent {
        @Suppress("UNUSED_PARAMETER")
        fun emit(intent: BadPublishIntentSuffix, cont: TemplateBaseContinuation): Any = Unit
    }
}
