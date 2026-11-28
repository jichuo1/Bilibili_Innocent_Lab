package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import FlatPlayerSpeedFactory
import FlatPlayerSpeedOption
import FlatSpeedMenuAdapter
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.HookPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistRequest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerSpeedFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.testsupport.TestRenamingClassLoader
import com.bilibili.lib.media.resource.MediaResource
import com.bilibili.playerbizcommonv2.widget.speed.OtherRenamedSpeedAdapter
import com.bilibili.playerbizcommonv2.widget.speed.RenamedSpeedAdapter
import com.bilibili.ship.theseus.keel.player.RenamedSpeedSessionOwner
import com.bilibili.ship.theseus.keel.player.SecondSpeedSessionOwner
import com.bilibili.ship.theseus.united.player.oldway.playercontainer.RenamedSpeedSessionWrapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class PlayerSpeedAdapterAndLocatorTest {
    private val loader = javaClass.classLoader!!

    @Test
    fun speedMenuSessionLongPressAndTheseus2Locators() {
        runSpeedMenuCases()
        runSpeedSessionCases()
        runTheseus2LongPressCases()
        runTheseus2KeelAndLazyRefreshCases()
    }

    private fun runSpeedMenuCases() {
        val method = RenamedSpeedAdapter::class.java.getDeclaredMethod("populate", Float::class.javaPrimitiveType)

        // semantic candidate discovers a renamed adapter and cached member is revalidated
        assertNull(PlayerSpeedLocator.speedMenu(loader))
        val selected = requireNotNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(listOf(method))))
        assertEquals(method.declaringClass.name, selected.adapter?.className)
        val restored = requireNotNull(PlayerSpeedMenuPoints.fromJson(JSONObject(selected.toJson().toString())))
        assertEquals(selected, restored)
        assertEquals(method, PlayerSpeedMenuAdapter.resolve(loader, restored)?.adapter?.populateMethod)
        assertFalse(PlayerSpeedMenuAdapter.needsQuery(loader, restored, enabled = true, attempted = false))
        val stale = restored.copy(adapter = restored.adapter!!.copy(methodName = "obsolete"))
        assertNull(PlayerSpeedMenuAdapter.resolve(loader, stale))
        assertTrue(PlayerSpeedMenuAdapter.needsQuery(loader, stale, enabled = true, attempted = false))

        // disabled complete and negative cached states do not trigger a session query
        var calls = 0
        val session = DexAssistSession(DexAssistEngine { calls++; DexAssistResult.Candidates(listOf(method)) },
            listOf("test.apk"), loader, setOf(PlayerSpeedMenuAdapter.query))
        assertFalse(PlayerSpeedMenuAdapter.needsQuery(loader, null, enabled = false, attempted = false))
        assertFalse(PlayerSpeedMenuAdapter.needsQuery(loader, null, enabled = true, attempted = true))
        assertTrue(PlayerSpeedMenuAdapter.needsQuery(loader, null, enabled = true, attempted = false))
        assertNull(PlayerSpeedMenuAdapter.locate(loader, session, null, enabled = false, planned = true))
        assertNull(PlayerSpeedMenuAdapter.locate(loader, session, null, enabled = true, planned = false))
        assertEquals(0, calls)
        val found = requireNotNull(PlayerSpeedMenuAdapter.locate(loader, session, null, enabled = true, planned = true))
        assertEquals(method, PlayerSpeedMenuAdapter.resolve(loader, found)?.adapter?.populateMethod)
        assertEquals(1, calls)

        // ambiguous oversized and unavailable results fail closed
        val other = OtherRenamedSpeedAdapter::class.java.getDeclaredMethod("populate", Float::class.javaPrimitiveType)
        assertNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(listOf(method, other))))
        assertNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(List(33) { method })))
        val unavailableSession = DexAssistSession(DexAssistEngine { DexAssistResult.Unavailable(DexAssistResult.Reason.NATIVE_UNAVAILABLE) },
            listOf("test.apk"), loader, setOf(PlayerSpeedMenuAdapter.query))
        assertNull(PlayerSpeedMenuAdapter.locate(loader, unavailableSession, null, enabled = true, planned = true))

        // cache rejects foreign owners wildcard signatures and incomplete factory models
        val valid = PlayerSpeedMenuPoints(adapter = HookPoint(method.declaringClass.name, method.name, listOf("float")))
        assertTrue(valid.isStructurallyValid())
        val bad = listOf(
            valid.copy(adapter = valid.adapter!!.copy(className = "java.lang.Runtime")),
            valid.copy(adapter = valid.adapter!!.copy(paramClassNames = null)),
            valid.copy(adapter = valid.adapter!!.copy(viewField = "items")),
            valid.copy(adapter = valid.adapter!!.copy(paramClassNames = emptyList())),
            valid.copy(factory = HookPoint("${PlayerSpeedLocator.SPEED_UTILS_PKG}.Renamed", "build",
                listOf(PlayerSpeedLocator.MEDIA_RESOURCE, "java.lang.Integer", "boolean", "int"))),
            valid.copy(compactOwner = "java.lang.Runtime")
        )
        for (points in bad) {
            assertFalse(points.isStructurallyValid())
            assertNull(PlayerSpeedMenuPoints.fromJson(points.toJson()))
        }

        // root package three parameter factory requires its linked option model
        val factory = FlatPlayerSpeedFactory::class.java.getDeclaredMethod("options", MediaResource::class.java,
            Int::class.javaObjectType, Int::class.javaPrimitiveType)
        assertNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(listOf(factory))))
        val points = requireNotNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(listOf(factory),
            relatedClasses = mapOf(factory to listOf(FlatPlayerSpeedOption::class.java)))))
        assertEquals(points, PlayerSpeedMenuPoints.fromJson(points.toJson()))
        assertEquals(factory, PlayerSpeedMenuAdapter.resolve(loader, points)?.factory?.factoryMethod)
        val fourParamFlattened = points.copy(factory = points.factory!!.copy(paramClassNames =
            listOf(PlayerSpeedLocator.MEDIA_RESOURCE, "java.lang.Integer", "boolean", "int")))
        assertEquals(fourParamFlattened, PlayerSpeedMenuPoints.fromJson(fourParamFlattened.toJson()))
        assertNull(PlayerSpeedMenuPoints.fromJson(points.copy(factory = points.factory!!.copy(paramClassNames =
            listOf(PlayerSpeedLocator.MEDIA_RESOURCE, "java.lang.String", "int"))).toJson()))

        // root package flattened speed adapter is accepted by structure and revalidated from cache
        val flatMethod = FlatSpeedMenuAdapter::class.java.getDeclaredMethod("a", Float::class.javaPrimitiveType)
        assertTrue(PlayerSpeedMenuAdapter.adapterAbi(flatMethod))
        val selectedFlat = requireNotNull(PlayerSpeedMenuAdapter.select(loader, DexAssistResult.Candidates(listOf(flatMethod))))
        val restoredFlat = requireNotNull(PlayerSpeedMenuPoints.fromJson(selectedFlat.toJson()))
        assertEquals(selectedFlat, restoredFlat)
        assertEquals(flatMethod, PlayerSpeedMenuAdapter.resolve(loader, restoredFlat)?.adapter?.populateMethod)
    }

    private fun runSpeedSessionCases() {
        val run = RenamedSpeedSessionOwner::class.java.declaredMethods.single { it.name == "runPlayable" }
        val secondRun = SecondSpeedSessionOwner::class.java.declaredMethods.single { it.name == "runPlayable" }
        val seek = RenamedSpeedSessionWrapper::class.java.getMethod("seekTo", Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType)
        fun runs() = DexAssistResult.Candidates(listOf(run))
        fun wrappers() = DexAssistResult.Candidates(listOf(seek))

        // renamedOwnerAndWrapperAreSelectedTogetherAndCacheIsReverified
        val selected = requireNotNull(PlayerSpeedSessionLocator.select(loader, runs(), wrappers()))
        assertEquals(RenamedSpeedSessionOwner::class.java.name, selected.owner)
        assertEquals(RenamedSpeedSessionWrapper::class.java.name, selected.wrapper)
        val decoded = requireNotNull(PlayerSpeedSessionClasses.fromJson(selected.toJson()))
        assertEquals(selected, decoded)
        assertNotNull(PlayerSpeedSessionLocator.resolve(loader, decoded))
        val missingOwner = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == decoded.owner) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertNull(PlayerSpeedSessionLocator.resolve(missingOwner, decoded))

        // twoVerifiedOwnersOrOverBudgetInputsCannotBeInstalled
        assertNull(PlayerSpeedSessionLocator.select(loader,
            DexAssistResult.Candidates(listOf(run, secondRun)), wrappers()))
        assertNull(PlayerSpeedSessionLocator.select(loader, DexAssistResult.Candidates(List(33) { run }), wrappers()))
        assertNull(PlayerSpeedSessionLocator.select(loader, runs(), DexAssistResult.Candidates(List(33) { seek })))

        // disabledDirectAndAlreadyAttemptedHostsNeverNeedARepeatScan
        assertFalse(PlayerSpeedSessionLocator.needsQuery(false, true, false))
        assertFalse(PlayerSpeedSessionLocator.needsQuery(true, false, false))
        assertFalse(PlayerSpeedSessionLocator.needsQuery(true, true, true))
        assertTrue(PlayerSpeedSessionLocator.needsQuery(true, true, false))
        assertTrue(PlayerSpeedSessionLocator.refreshCache(true, true, false, false, false))
        assertFalse(PlayerSpeedSessionLocator.refreshCache(true, true, false, false, true))
        assertFalse(PlayerSpeedSessionLocator.refreshCache(true, true, false, true, false))

        // sessionQueriesShareOnePlannedBatchWithSponsor
        var batches = 0
        val engine = object : DexAssistEngine {
            override fun resolve(request: DexAssistRequest): DexAssistResult = error("unexpected separate scan")
            override fun resolveAll(queries: Set<DexAssistQuery>, codePaths: List<String>,
                classLoader: ClassLoader): Map<DexAssistQuery, DexAssistResult> {
                batches++; assertEquals(PlayerSpeedSessionLocator.queries, queries)
                return mapOf(DexAssistQuery.SPONSOR_RUN_PLAYABLE to runs(),
                    DexAssistQuery.SPONSOR_PLAYER_WRAPPER to wrappers())
            }
        }
        val session = DexAssistSession(engine, listOf("base.apk"), loader, PlayerSpeedSessionLocator.queries)
        val assistedSelected = requireNotNull(PlayerSpeedSessionLocator.assisted(loader, session))
        assertEquals(RenamedSpeedSessionOwner::class.java.name, assistedSelected.owner)
        assertEquals(1, batches)

        // classesJsonRejectsMalformedNames
        assertNull(PlayerSpeedSessionClasses.fromJson(JSONObject().put("owner", "").put("wrapper", JSONObject.NULL)))
        assertNull(PlayerSpeedSessionClasses.fromJson(JSONObject().put("owner", "bad name!").put("wrapper", JSONObject.NULL)))
        assertNull(PlayerSpeedSessionClasses.fromJson(JSONObject().put("owner", "a.B").put("wrapper", "bad/name")))
    }

    private fun runTheseus2LongPressCases() {
        val serviceName = PlayerSpeedLocator.THESEUS2_SPEED_SERVICE
        val renamedLoader = TestRenamingClassLoader(
            parent = loader,
            templates = mapOf(
                serviceName to TemplateTripleSpeedService::class.java,
                "FlatPhase3LongPressLaunch" to TemplateLongPressLaunch::class.java,
                "FlatPhase3LongPressLaunch2" to TemplateLongPressLaunch::class.java,
                "FlatPhase3LongPressPort" to TemplateLongPressPort::class.java,
                "FlatPhase3LongPressPort2" to TemplateLongPressPort::class.java,
                "FlatPhase3WrongReturnPort" to TemplateWrongReturnPort::class.java,
                "evil.pkg.Port" to TemplateLongPressPort::class.java
            ),
            replacements = mapOf(
                TemplateContinuation::class.java.name.replace('.', '/') to "kotlin/coroutines/Continuation",
                TemplateTripleSpeedService::class.java.name.replace('.', '/') to serviceName.replace('.', '/')
            )
        )

        val service = requireNotNull(PlayerSpeedLocator.theseus2LongPressService(renamedLoader))
        assertNull(PlayerSpeedLocator.theseus2LongPress(renamedLoader, service, cached = null))
        assertTrue(PlayerSpeedLocator.theseus2LongPressNeedsQuery(renamedLoader, cached = null, enabled = true, attempted = false))

        val launchClass = renamedLoader.loadClass("FlatPhase3LongPressLaunch")
        val launch2Class = renamedLoader.loadClass("FlatPhase3LongPressLaunch2")
        val portClass = renamedLoader.loadClass("FlatPhase3LongPressPort")
        val port2Class = renamedLoader.loadClass("FlatPhase3LongPressPort2")
        val wrongReturn = renamedLoader.loadClass("FlatPhase3WrongReturnPort")
        val evilPort = renamedLoader.loadClass("evil.pkg.Port")

        val session = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(wrongReturn, evilPort, launchClass, portClass)) },
            listOf("base.apk"),
            renamedLoader,
            PlayerSpeedLocator.theseus2LongPressQueries
        )
        val located = requireNotNull(
            PlayerSpeedLocator.locateTheseus2LongPress(renamedLoader, session, enabled = true, cached = null, planned = true)
        )
        assertEquals("FlatPhase3LongPressLaunch", located.launchClass)
        assertEquals("FlatPhase3LongPressPort", located.portClass)
        assertEquals("p", located.portMethod)
        assertFalse(PlayerSpeedLocator.theseus2LongPressNeedsQuery(renamedLoader, located, enabled = true, attempted = false))

        val ambigLaunch = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(launchClass, launch2Class, portClass)) },
            listOf("base.apk"),
            renamedLoader,
            PlayerSpeedLocator.theseus2LongPressQueries
        )
        assertNull(PlayerSpeedLocator.locateTheseus2LongPress(renamedLoader, ambigLaunch, enabled = true, cached = null, planned = true))

        val ambigPort = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(methods = emptyList(), classes = listOf(launchClass, portClass, port2Class)) },
            listOf("base.apk"),
            renamedLoader,
            PlayerSpeedLocator.theseus2LongPressQueries
        )
        assertNull(PlayerSpeedLocator.locateTheseus2LongPress(renamedLoader, ambigPort, enabled = true, cached = null, planned = true))

        val baseJson = VersionAdapterTest().result().copy(playerSpeedTheseus2LongPress = located).toJson()
        val restored = requireNotNull(AdaptResult.fromJson(JSONObject(baseJson.toString())))
        assertEquals(located, restored.playerSpeedTheseus2LongPress)
        assertNull(AdaptResult.fromJson(JSONObject(baseJson.toString()).put("player_speed_theseus2_long_press", JSONObject.NULL)))
        assertNull(
            AdaptResult.fromJson(
                JSONObject(baseJson.toString()).put(
                    "player_speed_theseus2_long_press",
                    JSONObject().put("launch", "evil.Launch").put("port", "FlatPhase3LongPressPort").put("m", "p")
                )
            )
        )

        val longPressRef = AtomicReference<Theseus2LongPressPoint?>(null)
        val registrar = PlayerPortTestRegistrar()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = renamedLoader,
            hookPoints = HookPointRegistry(renamedLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val installer = PlayerSpeedFeatureInstaller(
            disableLongPress = true,
            longPressPercent = 0,
            defaultPercent = 0,
            cachedTheseus2LongPressProvider = { longPressRef.get() }
        )
        installer.install(env)
        assertTrue(installer.requiresAdaptationRetry)
        assertEquals(0, registrar.registrationAttempts.count { it.first == "player.speed.disable_long_press_theseus2" })

        longPressRef.set(located)
        installer.install(env)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals(1, registrar.registrationAttempts.count { it.first == "player.speed.disable_long_press_theseus2" })
    }

    private fun runTheseus2KeelAndLazyRefreshCases() {
        fun buildLoader(keelName: String): ClassLoader {
            val templates = linkedMapOf<String, Class<*>>(
                PlayerSpeedLocator.SPEED_MANAGER to KeelManagerTemplate::class.java,
                keelName to KeelPlayerTemplate::class.java,
                PlayerSpeedLocator.THESEUS_SPEED_WIDGET to KeelSpeedWidgetTemplate::class.java,
                PlayerSpeedLocator.MEDIA_RESOURCE to SpeedMediaResourceTemplate::class.java,
                PlayerSpeedLocator.RECYCLER_ADAPTER to SpeedRecyclerAdapterTemplate::class.java,
                "tv.danmaku.biliplayerv2.service.AbsFunctionWidgetService" to FunctionSvcTemplate::class.java,
                "${PlayerSpeedLocator.SPEED_UTILS_PKG}.a" to SpeedFactoryTemplate::class.java,
                "${PlayerSpeedLocator.SPEED_UTILS_PKG}.b" to SpeedOptionTemplate::class.java,
                "${PlayerSpeedLocator.SPEED_WIDGET_PKG}.f" to SpeedAdapterTemplate::class.java,
                "${PlayerSpeedLocator.SPEED_WIDGET_PKG}.f\$Item" to SpeedAdapterTemplate.Item::class.java,
                "${PlayerSpeedLocator.THESEUS_SPEED_PKG}.a" to CompactOwnerTemplate::class.java,
                "fkWindowDelegate" to WindowDelegateTemplate::class.java
            )
            val replacements = linkedMapOf(
                KeelManagerTemplate::class.java.name.replace('.', '/') to PlayerSpeedLocator.SPEED_MANAGER.replace('.', '/'),
                KeelPlayerTemplate::class.java.name.replace('.', '/') to keelName.replace('.', '/'),
                KeelSpeedWidgetTemplate::class.java.name.replace('.', '/') to PlayerSpeedLocator.THESEUS_SPEED_WIDGET.replace('.', '/'),
                SpeedMediaResourceTemplate::class.java.name.replace('.', '/') to PlayerSpeedLocator.MEDIA_RESOURCE.replace('.', '/'),
                SpeedRecyclerAdapterTemplate::class.java.name.replace('.', '/') to PlayerSpeedLocator.RECYCLER_ADAPTER.replace('.', '/'),
                FunctionSvcTemplate::class.java.name.replace('.', '/') to "tv/danmaku/biliplayerv2/service/AbsFunctionWidgetService",
                SpeedFactoryTemplate::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.SPEED_UTILS_PKG.replace('.', '/')}/a",
                SpeedOptionTemplate::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.SPEED_UTILS_PKG.replace('.', '/')}/b",
                SpeedAdapterTemplate::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.SPEED_WIDGET_PKG.replace('.', '/')}/f",
                SpeedAdapterTemplate.Item::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.SPEED_WIDGET_PKG.replace('.', '/')}/f\$Item",
                CompactOwnerTemplate::class.java.name.replace('.', '/') to "${PlayerSpeedLocator.THESEUS_SPEED_PKG.replace('.', '/')}/a",
                WindowDelegateTemplate::class.java.name.replace('.', '/') to "fkWindowDelegate"
            )
            return TestRenamingClassLoader(loader, templates, replacements)
        }
        assertNotNull(PlayerSpeedLocator.speedMenu(buildLoader("com.bilibili.ship.theseus2.keel.player.m"))?.compactRoute)
        assertTrue(PlayerSpeedMenuAdapter.flattenedOwner("fk"))
        assertNotNull(PlayerSpeedLocator.speedMenu(buildLoader("fk"))?.compactRoute)

        var probes = 0
        fun failOnLambda(): Boolean {
            probes++
            return true
        }
        assertFalse(PlayerSpeedSessionLocator.refreshCache(false, ::failOnLambda, ::failOnLambda, ::failOnLambda, ::failOnLambda))
        assertFalse(PlayerSpeedLocator.calculatorRefreshCache(false, ::failOnLambda, ::failOnLambda, ::failOnLambda, ::failOnLambda))
        assertEquals("disabled features must not evaluate refreshCache lambdas", 0, probes)
    }

    class TemplateContinuation
    class TemplateTripleSpeedService
    class TemplateLongPressLaunch(
        @Suppress("UNUSED_PARAMETER") svc: TemplateTripleSpeedService,
        @Suppress("UNUSED_PARAMETER") speed: Float,
        @Suppress("UNUSED_PARAMETER") obj: Any?,
        @Suppress("UNUSED_PARAMETER") cont: TemplateContinuation
    )
    class TemplateLongPressPort(@JvmField val svc: TemplateTripleSpeedService) {
        fun p(): Float? = 2.0f
    }
    class TemplateWrongReturnPort(@JvmField val svc: TemplateTripleSpeedService) {
        fun p(): Int = 1
    }

    class KeelManagerTemplate
    class KeelPlayerTemplate(@JvmField val mgr: KeelManagerTemplate = KeelManagerTemplate())
    class WindowDelegateTemplate
    class FunctionSvcTemplate
    class KeelSpeedWidgetTemplate(@JvmField val keel: KeelPlayerTemplate = KeelPlayerTemplate())
    class SpeedMediaResourceTemplate
    open class SpeedRecyclerAdapterTemplate
    class SpeedOptionTemplate(@JvmField val s: Float, @JvmField val a: Boolean)
    object SpeedFactoryTemplate {
        @JvmField val defaults = listOf(0.5f, 1.0f, 1.5f, 2.0f)
        @JvmStatic fun create(r: SpeedMediaResourceTemplate?, q: Int?, f: Boolean, m: Int): List<SpeedOptionTemplate> = emptyList()
    }
    class SpeedAdapterTemplate(@Suppress("UNUSED_PARAMETER") theme: Int) : SpeedRecyclerAdapterTemplate(), View.OnClickListener {
        class Item { @JvmField var a = 1.0f; @JvmField var b = false; @JvmField var c = true }
        @JvmField val list: List<Item> = emptyList()
        fun pop(f: Float, l: List<SpeedOptionTemplate>) = Unit
        override fun onClick(v: View?) = Unit
    }
    class CompactOwnerTemplate(
        @JvmField val fs: FunctionSvcTemplate,
        @JvmField val kp: KeelPlayerTemplate,
        @JvmField val wd: WindowDelegateTemplate
    ) {
        fun getFunctionWidgetService(): FunctionSvcTemplate = fs
        fun getKeelPlayer(): KeelPlayerTemplate = kp
        fun getWindowSizeDelegate(): WindowDelegateTemplate = wd
        companion object {
            @JvmStatic fun open(o: CompactOwnerTemplate) = Unit
        }
    }
}
