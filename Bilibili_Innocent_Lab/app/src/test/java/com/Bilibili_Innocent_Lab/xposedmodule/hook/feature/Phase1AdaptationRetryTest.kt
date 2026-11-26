package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.HomeRecommendEarlyLoadAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedMenuPoints
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerSpeedSessionLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.WeChatShareCompatAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.earlyload.RecyclerFixtures
import com.bilibili.playerbizcommonv2.widget.speed.RenamedSpeedAdapter
import kotlin.coroutines.Continuation
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import w32
import zag1
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.IdentityHashMap

class Phase1AdaptationRetryTest {

    class FakeUpdateSupplier {
        fun checkUpdate() = Unit
    }

    // ---- PlayerSpeed 重命名测试模板 ----

    interface TemplateStateFlow {
        fun getValue(): Any?
    }

    interface TemplateMutableFlow : TemplateStateFlow {
        fun setValue(value: Any?)
    }

    class TemplateFlowImpl(private var current: Any?) : TemplateMutableFlow {
        override fun getValue(): Any? = current
        override fun setValue(value: Any?) {
            current = value
        }
    }

    class TemplateSpeedManager {
        @JvmField val base: TemplateMutableFlow = TemplateFlowImpl(1f)
        @JvmField val temporary: TemplateMutableFlow = TemplateFlowImpl(null)
        fun speed(): Float = base.getValue() as Float
    }

    class TemplateParams {
        fun getAvid(): Long = 101L
        fun getCid(): Long = 202L
    }

    class TemplatePlayIndex {
        @JvmField var mFrom: String = "vod"

        companion object {
            const val FROM__VOD_COMMON: String = "vod"
        }
    }

    class TemplateMediaResource {
        fun getPlayIndex(): TemplatePlayIndex = TemplatePlayIndex()
    }

    class TemplateMediaItem {
        fun getId(): String = "item-1"
    }

    interface TemplateIMediaPlayer

    interface TemplateOnPreparedListener {
        fun onPrepared(player: TemplateIMediaPlayer)
    }

    interface TemplateCoreApi {
        fun getMediaResource(): TemplateMediaResource
        fun getCurrentMediaItem(): TemplateMediaItem
        fun getPlaySpeed(actual: Boolean): Float
    }

    class TemplateCoreImpl : TemplateCoreApi {
        @JvmField var currentSpeed: Float = 1f
        override fun getMediaResource(): TemplateMediaResource = TemplateMediaResource()
        override fun getCurrentMediaItem(): TemplateMediaItem = TemplateMediaItem()
        override fun getPlaySpeed(actual: Boolean): Float = currentSpeed
        fun setPlaySpeed(value: Float) {
            currentSpeed = value
        }
    }

    class TemplatePreparedListener(@JvmField val core: TemplateCoreApi) : TemplateOnPreparedListener {
        override fun onPrepared(player: TemplateIMediaPlayer) = Unit
    }

    class TemplateMediaPlayContext {
        @Suppress("UNUSED_PARAMETER")
        fun setOnPreparedListener(listener: TemplateOnPreparedListener) = Unit
    }

    interface TemplatePlayable {
        fun params(): TemplateParams
    }

    class TemplateSessionOwner {
        @JvmField val manager: TemplateSpeedManager = TemplateSpeedManager()
        fun active(): TemplatePlayable? = null

        @Suppress("UNUSED_PARAMETER")
        fun run(playable: TemplatePlayable?, continuation: Continuation<*>): Any = Unit
    }

    class TemplateSessionWrapper(
        @Suppress("UNUSED_PARAMETER") core: TemplateCoreApi,
        @Suppress("UNUSED_PARAMETER") owner: TemplateSessionOwner,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope
    )

    // ---- HomeRecommendEarlyLoad ViewGroup 与 Adapter 测试模板 ----

    open class FakeEarlyLoadRecyclerView : ViewGroup(null) {
        open class OnScrollListener
        interface OnChildAttachStateChangeListener {
            fun onChildViewAttachedToWindow(view: View)
            fun onChildViewDetachedFromWindow(view: View)
        }
        abstract class Adapter {
            abstract fun getItemCount(): Int
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun addOnScrollListener(listener: OnScrollListener) = Unit

        fun addOnChildAttachStateChangeListener(listener: OnChildAttachStateChangeListener) {
            recordAttach(this, listener)
        }

        fun getAdapter(): Adapter? = null

        @Suppress("UNUSED_PARAMETER")
        fun getChildAdapterPosition(child: View): Int = -1
        fun isComputingLayout(): Boolean = false
        fun getLayoutManager(): Any? = null

        companion object {
            val attachCounts = IdentityHashMap<Any, Int>()
            fun recordAttach(receiver: Any, @Suppress("UNUSED_PARAMETER") listener: Any) {
                synchronized(attachCounts) {
                    attachCounts[receiver] = (attachCounts[receiver] ?: 0) + 1
                }
            }
        }
    }

    class RelocatedEarlyLoadListener(
        @JvmField val callback: RecyclerFixtures.Function0 = RecyclerFixtures.MergedCallback(),
        @JvmField var enabled: Boolean = true,
        @JvmField var lead: Int = 2
    ) : FakeEarlyLoadRecyclerView.OnScrollListener(), FakeEarlyLoadRecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = Unit
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    class ValidRootListener(
        @JvmField val callback: () -> Unit = {},
        @JvmField var enabled: Boolean = true,
        @JvmField var lead: Int = 2
    ) : androidx.recyclerview.widget.RecyclerView.OnScrollListener(),
        androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = Unit
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    class ValidRootListener2(
        @JvmField val callback: () -> Unit = {},
        @JvmField var enabled: Boolean = true,
        @JvmField var lead: Int = 2
    ) : androidx.recyclerview.widget.RecyclerView.OnScrollListener(),
        androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = Unit
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    class MissingFunction0Listener :
        androidx.recyclerview.widget.RecyclerView.OnScrollListener(),
        androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
        @JvmField var enabled: Boolean = true
        @JvmField var lead: Int = 2
        override fun onChildViewAttachedToWindow(view: View) = Unit
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }

    class NoiseScrollOnlyListener(
        @JvmField val callback: RecyclerFixtures.Function0 = RecyclerFixtures.MergedCallback(),
        @JvmField var enabled: Boolean = true,
        @JvmField var lead: Int = 2
    ) : FakeEarlyLoadRecyclerView.OnScrollListener()

    class DummyPegasusFragment

    class TemplateInitFeedsForValidRoot(
        @Suppress("UNUSED_PARAMETER") fragment: DummyPegasusFragment,
        @Suppress("UNUSED_PARAMETER") listener: ValidRootListener,
        @Suppress("UNUSED_PARAMETER") cont: Continuation<*>
    )

    class FakeNonZeroArgModernWx(
        @Suppress("UNUSED_PARAMETER") context: android.content.Context,
        @Suppress("UNUSED_PARAMETER") type: kntr.common.share.common.handler.wx.WeChatShareType
    ) {
        @JvmField val api: com.tencent.mm.opensdk.openapi.IWXAPI? = null
        @Suppress("UNUSED_PARAMETER")
        fun initialize(mode: Int): Boolean = true
    }

    class FakeListOnlyOptionModel(@JvmField val speed: Float, @JvmField val available: Boolean)

    class FakeListOnlySpeedAdapter(@Suppress("UNUSED_PARAMETER") theme: Int) :
        androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>(),
        View.OnClickListener {
        class Item {
            @JvmField var speed: Float = 1f
            @JvmField var selected: Boolean = false
        }

        @JvmField val items: List<Item> = arrayListOf()

        @Suppress("UNUSED_PARAMETER")
        fun populate(current: Float, options: List<FakeListOnlyOptionModel>) = Unit

        override fun getItemCount(): Int = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder =
            error("unused")
        override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) = Unit
        override fun onClick(v: View?) = Unit
    }

    class FakeTheseus2Port {
        fun start(): Float? = 3f
    }

    companion object {
        @JvmStatic
        fun fakeTheseus2Calculate(@Suppress("UNUSED_PARAMETER") playable: Any?, @Suppress("UNUSED_PARAMETER") pace: Float?): Float = 3f
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
                val targetInternal = name.replace('.', '/')
                val selfInternal = template.name.replace('.', '/')
                val fullMap = replacements + (selfInternal to targetInternal)
                val rewritten = rewrite(raw, fullMap)
                defineClass(name, rewritten, 0, rewritten.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        private fun rewrite(raw: ByteArray, map: Map<String, String>): ByteArray {
            val input = DataInputStream(ByteArrayInputStream(raw))
            val out = ByteArrayOutputStream()
            val output = DataOutputStream(out)
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
            return out.toByteArray()
        }
    }

    private fun allocateView(cls: Class<*>): ViewGroup {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, cls) as ViewGroup
    }

    // -------------------------------------------------------------------------
    // 1. BlockUpdate retry + idempotency
    // -------------------------------------------------------------------------
    fun `block update installer retries supplier layer idempotently without duplicating caller hooks`() {
        val registrar = PlayerPortTestRegistrar()
        val adaptedAttempts = mutableListOf<String>()
        val trackingRegistrar = object : HookRegistrar by registrar {
            override fun adapted(
                id: String,
                point: VersionAdapter.HookPoint,
                exceptionPolicy: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy,
                block: com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator.() -> Unit
            ) {
                adaptedAttempts += id
            }
        }
        val stages = mutableListOf<FeatureRuntimeStage>()
        val statuses = linkedMapOf<String, String>()
        val loader = requireNotNull(javaClass.classLoader)
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = loader,
            hookPoints = HookPointRegistry(loader),
            registrar = trackingRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> statuses[k] = v },
            runtimeEvidence = { _, stage, _ -> stages += stage }
        )
        var supplierPoint: VersionAdapter.HookPoint? = null
        val installer = BlockUpdateFeatureInstaller(
            enabled = true,
            point = null,
            pointProvider = { supplierPoint }
        )

        val run1 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(3, run1.hookCount)
        assertFalse(run1.complete)
        assertTrue(installer.requiresAdaptationRetry)
        assertEquals("caller-only", statuses["block_update_status"])
        assertEquals(3, registrar.registrationAttempts.size)
        assertTrue(adaptedAttempts.isEmpty())

        supplierPoint = VersionAdapter.HookPoint(FakeUpdateSupplier::class.java.name, "checkUpdate", emptyList())
        val run2 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(4, run2.hookCount)
        assertTrue(run2.complete)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals("success", statuses["block_update_status"])
        assertEquals(3, registrar.registrationAttempts.size)
        assertEquals(listOf("update.block.check"), adaptedAttempts)
        assertTrue(FeatureRuntimeStage.ADAPTED in stages)

        val run3 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(4, run3.hookCount)
        assertTrue(run3.complete)
        assertEquals(3, registrar.registrationAttempts.size)
        assertEquals(listOf("update.block.check"), adaptedAttempts)
    }

    // -------------------------------------------------------------------------
    // 2. WeChatShare drifted profile fallback + retry idempotency
    // -------------------------------------------------------------------------
    fun `wechat share compat falls back to cached classes on drifted profile and retries idempotently`() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        val driftedLoader = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "gz0.a") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val cachedClasses = WeChatShareClasses(
            legacyOwner = "kz3.a",
            legacyInitialize = "t",
            modernOwner = "kntr.common.share.common.handler.wx.k",
            modernInitialize = "j",
            usesLegacyConstructors = true
        )
        assertNull(WeChatShareCompatAccess.locate(driftedLoader, 9130500, cached = null))
        assertNotNull(WeChatShareCompatAccess.locate(driftedLoader, 9130500, cached = cachedClasses))

        val registrar = PlayerPortTestRegistrar()
        val statuses = linkedMapOf<String, String>()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = driftedLoader,
            hookPoints = HookPointRegistry(driftedLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { k, v -> statuses[k] = v },
            runtimeEvidence = { _, stage, _ -> stages += stage }
        )

        val installer = WeChatShareCompatFeatureInstaller(
            enabled = true,
            cachedClasses = null,
            hostVersionCode = { 9130500 }
        )
        val run1 = installer.install(env)
        assertEquals(FeatureInstallResult.Skipped("missing-verified-share-structure"), run1)
        assertTrue(installer.requiresAdaptationRetry)
        assertTrue(registrar.registrationAttempts.isEmpty())

        installer.updateAdaptedClasses(cachedClasses)
        val run2 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(3, run2.hookCount)
        assertTrue(run2.complete)
        assertFalse(installer.requiresAdaptationRetry)
        assertEquals("success", statuses["wechat_share_compat_status"])
        assertEquals(3, registrar.registrationAttempts.size)
        assertTrue(FeatureRuntimeStage.ADAPTED in stages)

        val run3 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(3, run3.hookCount)
        assertTrue(run3.complete)
        assertEquals(3, registrar.registrationAttempts.size)
    }

    // -------------------------------------------------------------------------
    // 3. PlayerSpeed partial -> retry via FeatureInstallCoordinator + prepared live counts
    // -------------------------------------------------------------------------
    fun `player speed installer retries through coordinator preserving verified capabilities and updates late prepared counts`() {
        val parent = requireNotNull(javaClass.classLoader)
        fun createSpeedLoader(): ClassLoader {
            val templates = linkedMapOf<String, Class<*>>(
                "kotlinx.coroutines.flow.StateFlow" to TemplateStateFlow::class.java,
                "kotlinx.coroutines.flow.MutableStateFlow" to TemplateMutableFlow::class.java,
                "test.phase1.FlowImpl" to TemplateFlowImpl::class.java,
                PlayerSpeedLocator.SPEED_MANAGER to TemplateSpeedManager::class.java,
                PlayerSpeedSessionLocator.PARAMS to TemplateParams::class.java,
                PlayerSpeedSessionLocator.INDEX to TemplatePlayIndex::class.java,
                "${PlayerSpeedSessionLocator.INDEX}\$Companion" to TemplatePlayIndex.Companion::class.java,
                PlayerSpeedSessionLocator.RESOURCE to TemplateMediaResource::class.java,
                PlayerSpeedSessionLocator.ITEM to TemplateMediaItem::class.java,
                PlayerSpeedSessionLocator.PLAYER to TemplateIMediaPlayer::class.java,
                PlayerSpeedSessionLocator.PREPARED to TemplateOnPreparedListener::class.java,
                PlayerSpeedSessionLocator.CORE to TemplateCoreApi::class.java,
                PlayerSpeedSessionLocator.CONTEXT to TemplateMediaPlayContext::class.java,
                "com.bilibili.ship.theseus.keel.player.Phase1Playable" to TemplatePlayable::class.java,
                "test.phase1.SessionOwner" to TemplateSessionOwner::class.java,
                "test.phase1.SessionWrapper" to TemplateSessionWrapper::class.java,
                "test.phase1.CoreImpl" to TemplateCoreImpl::class.java,
                "test.phase1.PreparedListener" to TemplatePreparedListener::class.java
            )
            val replacements = templates.entries.associate { (targetName, tpl) ->
                tpl.name.replace('.', '/') to targetName.replace('.', '/')
            }
            return RenamingClassLoader(
                parent = parent,
                templates = templates,
                replacements = replacements,
                hidden = setOf(
                    PlayerSpeedSessionLocator.RUN,
                    PlayerSpeedLocator.THESEUS_SPEED_WIDGET,
                    PlayerSpeedLocator.LONG_PRESS_COROUTINE
                )
            )
        }

        // 3a: disableLongPress + defaultPercent (150) -> Run 1 (3 hooks) -> Run 2 (+session/bridge=5) -> prepared (success, 5/5)
        run {
            val loader = createSpeedLoader()
            val registrar = PlayerPortTestRegistrar()
            val statuses = linkedMapOf<String, String>()
            val events = mutableListOf<FeatureInstallRecord>()
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { k, v -> statuses[k] = v },
                installationEvidence = { events += it }
            )
            val coordinator = FeatureInstallCoordinator(env)
            var sessionClasses: PlayerSpeedSessionClasses? = null
            val installer = PlayerSpeedFeatureInstaller(
                disableLongPress = true,
                longPressPercent = 0,
                defaultPercent = 150,
                speedMenuSpec = "",
                hostVersionCode = 9210100,
                cachedSessionClassesProvider = { sessionClasses }
            )

            coordinator.installAll(listOf(installer))
            val run1 = events.last { it.id == PlayerSpeedFeatureInstaller.ID }.result as FeatureInstallResult.Installed
            assertEquals(3, run1.hookCount)
            assertFalse(run1.complete)
            assertTrue(installer.requiresAdaptationRetry)
            assertEquals(3, registrar.registrationAttempts.size)

            events.clear()
            sessionClasses = PlayerSpeedSessionClasses("test.phase1.SessionOwner", "test.phase1.SessionWrapper")
            coordinator.installAll(listOf(installer))

            assertFalse(installer.requiresAdaptationRetry)
            assertEquals(5, registrar.registrationAttempts.size)
            val counts = registrar.registrationAttempts.map { it.first }.groupingBy { it }.eachCount()
            assertEquals(1, counts["player.speed.disable_long_press"])
            assertEquals(1, counts["player.speed.initial_value"])
            assertEquals(1, counts["player.speed.prepared_registration"])
            assertEquals(1, counts["player.speed.media_session"])
            assertEquals(1, counts["player.speed.core_bridge"])

            for (cap in installer.capabilityIds) {
                val capResult = events.lastOrNull { it.id == cap }?.result
                assertTrue("Expected Installed capability $cap, was $capResult", capResult is FeatureInstallResult.Installed)
            }

            events.clear()
            val ctx = loader.loadClass(PlayerSpeedSessionLocator.CONTEXT).getConstructor().newInstance()
            val core = loader.loadClass("test.phase1.CoreImpl").getConstructor().newInstance()
            val listener = loader.loadClass("test.phase1.PreparedListener")
                .getConstructor(loader.loadClass(PlayerSpeedSessionLocator.CORE))
                .newInstance(core)
            registrar.invoke("player.speed.prepared_registration", receiver = ctx, args = arrayOf(listener))

            assertEquals("success", statuses["player_speed_status"])
            assertEquals(
                FeatureInstallResult.Installed(5, complete = true),
                events.last { it.id == PlayerSpeedFeatureInstaller.ID }.result
            )
        }

        // 3b: longPressPercent=275 (coroutine hidden) + defaultPercent=150 -> late prepared reports partial:4/5
        run {
            val loader = createSpeedLoader()
            val registrar = PlayerPortTestRegistrar()
            val statuses = linkedMapOf<String, String>()
            val events = mutableListOf<FeatureInstallRecord>()
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { k, v -> statuses[k] = v },
                installationEvidence = { events += it }
            )
            val coordinator = FeatureInstallCoordinator(env)
            var sessionClasses: PlayerSpeedSessionClasses? = null
            val installer = PlayerSpeedFeatureInstaller(
                disableLongPress = false,
                longPressPercent = 275,
                defaultPercent = 150,
                speedMenuSpec = "",
                hostVersionCode = 9210100,
                cachedSessionClassesProvider = { sessionClasses }
            )

            coordinator.installAll(listOf(installer))
            assertEquals(2, registrar.registrationAttempts.size)
            assertTrue(installer.requiresAdaptationRetry)

            sessionClasses = PlayerSpeedSessionClasses("test.phase1.SessionOwner", "test.phase1.SessionWrapper")
            coordinator.installAll(listOf(installer))
            assertEquals(4, registrar.registrationAttempts.size)
            assertFalse(installer.requiresAdaptationRetry)

            events.clear()
            val ctx = loader.loadClass(PlayerSpeedSessionLocator.CONTEXT).getConstructor().newInstance()
            val core = loader.loadClass("test.phase1.CoreImpl").getConstructor().newInstance()
            val listener = loader.loadClass("test.phase1.PreparedListener")
                .getConstructor(loader.loadClass(PlayerSpeedSessionLocator.CORE))
                .newInstance(core)
            registrar.invoke("player.speed.prepared_registration", receiver = ctx, args = arrayOf(listener))

            assertEquals("partial:4/5", statuses["player_speed_status"])
            assertEquals(
                FeatureInstallResult.Installed(4, complete = false),
                events.last { it.id == PlayerSpeedFeatureInstaller.ID }.result
            )
        }

        // 3c: disableLongPress + speedMenuSpec ("200,150,100") -> Run 1 (1 hook) -> Run 2 (+menu_adapter_populate=2), capabilities preserved
        run {
            val loader = createSpeedLoader()
            val registrar = PlayerPortTestRegistrar()
            val events = mutableListOf<FeatureInstallRecord>()
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { _, _ -> },
                installationEvidence = { events += it }
            )
            val coordinator = FeatureInstallCoordinator(env)
            var menuPoints: PlayerSpeedMenuPoints? = null
            val installer = PlayerSpeedFeatureInstaller(
                disableLongPress = true,
                longPressPercent = 0,
                defaultPercent = 0,
                speedMenuSpec = "200,150,100",
                hostVersionCode = 9210100,
                cachedMenuPointsProvider = { menuPoints }
            )

            coordinator.installAll(listOf(installer))
            assertEquals(1, registrar.registrationAttempts.size)
            assertTrue(installer.requiresAdaptationRetry)

            events.clear()
            menuPoints = PlayerSpeedMenuPoints(
                adapter = VersionAdapter.HookPoint(RenamedSpeedAdapter::class.java.name, "populate", listOf("float"))
            )
            coordinator.installAll(listOf(installer))

            assertFalse(installer.requiresAdaptationRetry)
            assertEquals(2, registrar.registrationAttempts.size)
            val counts = registrar.registrationAttempts.map { it.first }.groupingBy { it }.eachCount()
            assertEquals(1, counts["player.speed.disable_long_press"])
            assertEquals(1, counts["player.speed.menu_adapter_populate"])
            assertTrue(events.last { it.id == "player_long_press_disabled" }.result is FeatureInstallResult.Installed)
            assertTrue(events.last { it.id == PlayerSpeedFeatureInstaller.MENU_CAPABILITY }.result is FeatureInstallResult.Installed)
        }
    }

    // -------------------------------------------------------------------------
    // 4. HomeRecommendEarlyLoadAdapter + AdaptResult + LoadMoreWatcher Replay
    // -------------------------------------------------------------------------
    fun `home recommend early load adapter verifies candidates fails closed on ambiguity and LoadMoreWatcher replays pending ViewGroup listeners`() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        assertTrue(HomeRecommendEarlyLoadAdapter.allowedClassName("zag1"))
        assertTrue(HomeRecommendEarlyLoadAdapter.allowedClassName("com.example.FastLoader"))
        assertFalse(HomeRecommendEarlyLoadAdapter.allowedClassName("androidx.recyclerview.widget.RecyclerView"))
        assertFalse(HomeRecommendEarlyLoadAdapter.allowedClassName("[Lzag1;"))
        assertFalse(HomeRecommendEarlyLoadAdapter.allowedClassName(""))
        assertFalse(HomeRecommendEarlyLoadAdapter.allowedClassName("123bad"))

        assertTrue(HomeRecommendEarlyLoadAdapter.verify(baseLoader, ValidRootListener::class.java))
        assertFalse(HomeRecommendEarlyLoadAdapter.verify(baseLoader, MissingFunction0Listener::class.java))

        // AdaptResult JSON 往返：含 listener 与缺省为 null 时不写键。
        val baseResult = VersionAdapter.AdaptResult(
            biliVersionCode = 9210100,
            ts = 1L,
            commentLow = null, commentHigh = null, mineEntry = null,
            pause = VersionAdapter.PausePoints(emptyList(), null, null, null),
            banner = null, homeTopBar = null, mineVip = null, blockUpdate = null,
            dynamicTabs = null, fullNumbers = null, playerPortrait = null,
            playerInteractiveOverlays = null, pgcAutoActivityPopup = null, playerStatusBar = null,
            homeRecommendFeed = null, videoRelate = null, homeTabs = null, homeComponents = null,
            mineComponents = null, mineAccountMine = null, storyFeed = null, bottomBar = null,
            playerQuality = null, teenagersMode = null, commentPurify = null, commentFilter = null,
            commentTopology = null, commentSection = null, splashAds = null,
            hostFingerprint = "fp",
            protocolFingerprint = "protocol-v1:1:000000000000000000000000",
            diagnostics = emptyList()
        )
        val jsonWithNull = baseResult.toJson()
        assertFalse(jsonWithNull.has("home_recommend_early_load_listener"))
        assertNull(VersionAdapter.AdaptResult.fromJson(jsonWithNull)?.homeRecommendEarlyLoadListener)

        val jsonWithListener = baseResult.copy(homeRecommendEarlyLoadListener = "x9a").toJson()
        assertEquals("x9a", jsonWithListener.getString("home_recommend_early_load_listener"))
        assertEquals("x9a", VersionAdapter.AdaptResult.fromJson(jsonWithListener)?.homeRecommendEarlyLoadListener)

        // locate 歧义 / 超限 fail-closed 与唯一命中（含噪音类共存）。
        val noInitFeedsLoader = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val oversizedSession = DexAssistSession(
            DexAssistEngine { DexAssistResult.Candidates(emptyList(), List(33) { ValidRootListener::class.java }) },
            listOf("test.apk"), noInitFeedsLoader, setOf(HomeRecommendEarlyLoadAdapter.query)
        )
        assertNull(HomeRecommendEarlyLoadAdapter.locate(noInitFeedsLoader, oversizedSession, enabled = true, cached = null, planned = true))

        val ambiguousSession = DexAssistSession(
            DexAssistEngine {
                DexAssistResult.Candidates(emptyList(), listOf(ValidRootListener::class.java, ValidRootListener2::class.java))
            },
            listOf("test.apk"), noInitFeedsLoader, setOf(HomeRecommendEarlyLoadAdapter.query)
        )
        assertNull(HomeRecommendEarlyLoadAdapter.locate(noInitFeedsLoader, ambiguousSession, enabled = true, cached = null, planned = true))

        val uniqueSession = DexAssistSession(
            DexAssistEngine {
                DexAssistResult.Candidates(emptyList(), listOf(ValidRootListener::class.java, MissingFunction0Listener::class.java))
            },
            listOf("test.apk"), noInitFeedsLoader, setOf(HomeRecommendEarlyLoadAdapter.query)
        )
        assertEquals(
            ValidRootListener::class.java.name,
            HomeRecommendEarlyLoadAdapter.locate(noInitFeedsLoader, uniqueSession, enabled = true, cached = null, planned = true)
        )

        // LoadMoreWatcher: 负缓存清理 + ViewGroup WeakHashMap 缓冲上限(8) + 重放挂接。
        val watcherNames = HomeRecommendEarlyLoadFeatureInstaller.HostNames(
            recyclerView = FakeEarlyLoadRecyclerView::class.java.name,
            gridLayoutManager = RecyclerFixtures.GridLayoutManager::class.java.name,
            function0 = RecyclerFixtures.Function0::class.java.name
        )
        val access = requireNotNull(HomeRecommendEarlyLoadFeatureInstaller.RecyclerAccess.resolve(baseLoader, watcherNames))
        val stages = mutableListOf<FeatureRuntimeStage>()
        val watcherEnv = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = baseLoader,
            hookPoints = HookPointRegistry(baseLoader),
            registrar = PlayerPortTestRegistrar(),
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> },
            runtimeEvidence = { _, stage, _ -> stages += stage }
        )
        val watcher = HomeRecommendEarlyLoadFeatureInstaller.LoadMoreWatcher(
            recycler = access,
            environment = watcherEnv,
            initialRelocatedListener = null,
            initialAwaitingRelocated = true
        )
        val views = List(9) { allocateView(FakeEarlyLoadRecyclerView::class.java) }
        val relocated = RelocatedEarlyLoadListener()
        watcher.onScrollListenerAdded(views[0], relocated)
        assertNull(watcher.shapeOf(RelocatedEarlyLoadListener::class.java))
        assertEquals(1, watcher.pendingCandidateCountForTest())

        // 非目标噪音监听器不入待重放缓冲。
        watcher.onScrollListenerAdded(allocateView(FakeEarlyLoadRecyclerView::class.java), NoiseScrollOnlyListener())
        assertEquals(1, watcher.pendingCandidateCountForTest())

        for (i in 1 until views.size) {
            watcher.onScrollListenerAdded(views[i], relocated)
        }
        assertEquals(8, watcher.pendingCandidateCountForTest())

        synchronized(FakeEarlyLoadRecyclerView.attachCounts) { FakeEarlyLoadRecyclerView.attachCounts.clear() }
        watcher.updateRelocatedListener(RelocatedEarlyLoadListener::class.java)
        assertEquals(0, watcher.pendingCandidateCountForTest())
        assertNotNull(watcher.shapeOf(RelocatedEarlyLoadListener::class.java))
        assertEquals(1, FakeEarlyLoadRecyclerView.attachCounts[views[0]])
        assertEquals(1, FakeEarlyLoadRecyclerView.attachCounts[views[7]])
        assertNull(FakeEarlyLoadRecyclerView.attachCounts[views[8]])
        assertTrue(FeatureRuntimeStage.OBSERVED in stages)

        // 真实 HostNames() 两阶段 HomeRecommendEarlyLoadFeatureInstaller retry。
        val pegasusTemplates = mapOf(
            PegasusLoadMoreShape.PEGASUS_FRAGMENT to DummyPegasusFragment::class.java
        )
        val pegasusLoader = RenamingClassLoader(
            parent = baseLoader,
            templates = pegasusTemplates,
            replacements = mapOf(
                DummyPegasusFragment::class.java.name.replace('.', '/') to
                    PegasusLoadMoreShape.PEGASUS_FRAGMENT.replace('.', '/')
            ),
            hidden = setOf(PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION)
        )
        var cachedRelocatedName: String? = null
        val installerRegistrar = PlayerPortTestRegistrar()
        val installerEnv = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = pegasusLoader,
            hookPoints = HookPointRegistry(pegasusLoader),
            registrar = installerRegistrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val earlyInstaller = HomeRecommendEarlyLoadFeatureInstaller(
            enabled = true,
            cachedRelocatedListenerProvider = { cachedRelocatedName }
        )
        val earlyRun1 = earlyInstaller.install(installerEnv)
        assertEquals(FeatureInstallResult.Installed(1), earlyRun1)
        assertTrue(earlyInstaller.requiresAdaptationRetry)
        assertEquals(1, installerRegistrar.registrationAttempts.size)

        cachedRelocatedName = ValidRootListener::class.java.name
        val earlyRun2 = earlyInstaller.install(installerEnv)
        assertEquals(FeatureInstallResult.Installed(1), earlyRun2)
        assertFalse(earlyInstaller.requiresAdaptationRetry)
        assertEquals(1, installerRegistrar.registrationAttempts.size)
    }

    // -------------------------------------------------------------------------
    // 5. Phase 1 Review Remediation Regression Tests (P0 + P1 + P2)
    // -------------------------------------------------------------------------
    fun `home recommend early load adaptationAttemptedInCache finishAdaptationWait and re-entry preserve work without poisoning negative cache`() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        val pegasusLoader = RenamingClassLoader(
            parent = baseLoader,
            templates = mapOf(PegasusLoadMoreShape.PEGASUS_FRAGMENT to DummyPegasusFragment::class.java),
            replacements = mapOf(
                DummyPegasusFragment::class.java.name.replace('.', '/') to
                    PegasusLoadMoreShape.PEGASUS_FRAGMENT.replace('.', '/')
            ),
            hidden = setOf(PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION)
        )
        val reg1 = PlayerPortTestRegistrar()
        val env1 = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = pegasusLoader,
            hookPoints = HookPointRegistry(pegasusLoader),
            registrar = reg1,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )

        // Cached MISSING diagnosis suppresses awaiting retry on cold start (P0).
        val cachedAttemptedInstaller = HomeRecommendEarlyLoadFeatureInstaller(
            enabled = true,
            cachedRelocatedListenerProvider = { null },
            adaptationAttemptedInCache = true
        )
        assertEquals(FeatureInstallResult.Installed(1), cachedAttemptedInstaller.install(env1))
        assertFalse(cachedAttemptedInstaller.requiresAdaptationRetry)

        // finishAdaptationWait() closes retry state & drains pending candidates (P0).
        val awaitingInstaller = HomeRecommendEarlyLoadFeatureInstaller(
            enabled = true,
            cachedRelocatedListenerProvider = { null },
            adaptationAttemptedInCache = false
        )
        assertEquals(FeatureInstallResult.Installed(1), awaitingInstaller.install(env1))
        assertTrue(awaitingInstaller.requiresAdaptationRetry)
        awaitingInstaller.finishAdaptationWait()
        assertFalse(awaitingInstaller.requiresAdaptationRetry)

        // Direct PegasusFragment$initFeeds$5 listener is preserved even when provider returns null on re-entry (P1 #1.3).
        val directTemplates = mapOf(
            PegasusLoadMoreShape.PEGASUS_FRAGMENT to DummyPegasusFragment::class.java,
            PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION to TemplateInitFeedsForValidRoot::class.java
        )
        val directLoader = RenamingClassLoader(
            parent = baseLoader,
            templates = directTemplates,
            replacements = mapOf(
                DummyPegasusFragment::class.java.name.replace('.', '/') to
                    PegasusLoadMoreShape.PEGASUS_FRAGMENT.replace('.', '/'),
                TemplateInitFeedsForValidRoot::class.java.name.replace('.', '/') to
                    PegasusLoadMoreShape.INIT_FEEDS_CONTINUATION.replace('.', '/')
            )
        )
        val watcherNames = HomeRecommendEarlyLoadFeatureInstaller.HostNames(
            recyclerView = FakeEarlyLoadRecyclerView::class.java.name,
            gridLayoutManager = RecyclerFixtures.GridLayoutManager::class.java.name,
            function0 = RecyclerFixtures.Function0::class.java.name
        )
        val directAccess = requireNotNull(HomeRecommendEarlyLoadFeatureInstaller.RecyclerAccess.resolve(directLoader, watcherNames))
        val watcher = HomeRecommendEarlyLoadFeatureInstaller.LoadMoreWatcher(
            recycler = directAccess,
            environment = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = directLoader,
                hookPoints = HookPointRegistry(directLoader),
                registrar = PlayerPortTestRegistrar(),
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { _, _ -> }
            ),
            initialRelocatedListener = RelocatedEarlyLoadListener::class.java,
            initialAwaitingRelocated = false
        )
        assertNotNull(watcher.shapeOf(RelocatedEarlyLoadListener::class.java))
        // null completion must never wipe an already-resolved relocatedListenerRef (P1 #1.3).
        watcher.updateRelocatedListener(null)
        assertNotNull(watcher.shapeOf(RelocatedEarlyLoadListener::class.java))

        // While awaitingRelocatedListener=true, dual-interface listener must not be negatively cached (P1 #1.1).
        val awaitingWatcher = HomeRecommendEarlyLoadFeatureInstaller.LoadMoreWatcher(
            recycler = directAccess,
            environment = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = directLoader,
                hookPoints = HookPointRegistry(directLoader),
                registrar = PlayerPortTestRegistrar(),
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { _, _ -> }
            ),
            initialRelocatedListener = null,
            initialAwaitingRelocated = true
        )
        val v0 = allocateView(FakeEarlyLoadRecyclerView::class.java)
        val rel = RelocatedEarlyLoadListener()
        awaitingWatcher.onScrollListenerAdded(v0, rel)
        assertNull(awaitingWatcher.shapeOf(RelocatedEarlyLoadListener::class.java))
        assertEquals(1, awaitingWatcher.pendingCandidateCountForTest())
        // Releasing wait with null stops future buffering and clears pendingCandidates (P0).
        awaitingWatcher.updateRelocatedListener(null)
        assertEquals(0, awaitingWatcher.pendingCandidateCountForTest())
        awaitingWatcher.onScrollListenerAdded(allocateView(FakeEarlyLoadRecyclerView::class.java), rel)
        assertEquals(0, awaitingWatcher.pendingCandidateCountForTest())
    }

    fun `adaptResult rejects explicit null early load listener and mergeRuntimeWithCached preserves cached listener and missing diagnostic`() {
        val baseResult = VersionAdapter.AdaptResult(
            biliVersionCode = 9210100,
            ts = 1L,
            commentLow = null, commentHigh = null, mineEntry = null,
            pause = VersionAdapter.PausePoints(emptyList(), null, null, null),
            banner = null, homeTopBar = null, mineVip = null, blockUpdate = null,
            dynamicTabs = null, fullNumbers = null, playerPortrait = null,
            playerInteractiveOverlays = null, pgcAutoActivityPopup = null, playerStatusBar = null,
            homeRecommendFeed = null, videoRelate = null, homeTabs = null, homeComponents = null,
            mineComponents = null, mineAccountMine = null, storyFeed = null, bottomBar = null,
            playerQuality = null, teenagersMode = null, commentPurify = null, commentFilter = null,
            commentTopology = null, commentSection = null, splashAds = null,
            hostFingerprint = "fp",
            protocolFingerprint = "protocol-v1:1:000000000000000000000000",
            diagnostics = emptyList()
        )
        val corruptedJson = baseResult.toJson().put("home_recommend_early_load_listener", org.json.JSONObject.NULL)
        assertNull(VersionAdapter.AdaptResult.fromJson(corruptedJson))

        val cachedFound = baseResult.copy(
            homeRecommendEarlyLoadListener = "ValidListener",
            domainRuleVersions = com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract.DOMAIN_RULE_VERSIONS,
            diagnostics = listOf(
                VersionAdapter.AdaptDiagnostic(
                    HomeRecommendEarlyLoadAdapter.DIAGNOSTIC,
                    VersionAdapter.AdaptState.FOUND,
                    "ok"
                )
            )
        )
        val mergedFound = requireNotNull(VersionAdapter.mergeRuntimeWithCached(baseResult, cachedFound))
        assertEquals("ValidListener", mergedFound.homeRecommendEarlyLoadListener)
        assertTrue(mergedFound.diagnostics.any {
            it.id == HomeRecommendEarlyLoadAdapter.DIAGNOSTIC && it.state == VersionAdapter.AdaptState.FOUND
        })

        val missingDiag = VersionAdapter.AdaptDiagnostic(
            HomeRecommendEarlyLoadAdapter.DIAGNOSTIC,
            VersionAdapter.AdaptState.MISSING,
            "no-match"
        )
        val mergedMissing = requireNotNull(VersionAdapter.mergeRuntimeWithCached(
            baseResult,
            baseResult.copy(
                domainRuleVersions = com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterContract.DOMAIN_RULE_VERSIONS,
                diagnostics = listOf(missingDiag)
            )
        ))
        assertNull(mergedMissing.homeRecommendEarlyLoadListener)
        assertTrue(com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptAssistGate.isAttemptedInCache(missingDiag))
        assertTrue(mergedMissing.diagnostics.any {
            it.id == HomeRecommendEarlyLoadAdapter.DIAGNOSTIC && it.state == VersionAdapter.AdaptState.MISSING
        })
    }

    fun `wechat share partial profile drift falls back to complete cache retries idempotently and discover rejects parameterized initialize`() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        // Version 8890400 expects kz3.a (legacy) + kntr.common.share.common.handler.wx.k (modern).
        val partialLoader = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "kntr.common.share.common.handler.wx.k") throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val directPartial = requireNotNull(WeChatShareCompatAccess.locate(partialLoader, 8890400, cached = null))
        assertEquals(1, directPartial.wrappers.size)
        assertEquals(2, directPartial.expectedWrappers)

        val completeCache = WeChatShareClasses(
            legacyOwner = "kz3.a",
            legacyInitialize = "t",
            modernOwner = "FlatWave1WeChatWrapper",
            modernInitialize = "m",
            usesLegacyConstructors = true
        )
        val preferredCache = requireNotNull(WeChatShareCompatAccess.locate(partialLoader, 8890400, cached = completeCache))
        assertEquals(2, preferredCache.wrappers.size)

        val registrar = PlayerPortTestRegistrar()
        val env = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = partialLoader,
            hookPoints = HookPointRegistry(partialLoader),
            registrar = registrar,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val installer = WeChatShareCompatFeatureInstaller(
            enabled = true,
            cachedClasses = null,
            hostVersionCode = { 8890400 }
        )
        val run1 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(2, run1.hookCount)
        assertFalse(run1.complete)
        assertTrue(installer.requiresAdaptationRetry)
        assertEquals(2, registrar.registrationAttempts.size)

        installer.updateAdaptedClasses(completeCache)
        val run2 = installer.install(env) as FeatureInstallResult.Installed
        assertEquals(3, run2.hookCount)
        assertTrue(run2.complete)
        assertFalse(installer.requiresAdaptationRetry)
        // factory + legacy already installed on Run 1; Run 2 adds only kntr wrapper.
        assertEquals(3, registrar.registrationAttempts.size)
        assertEquals(listOf("wechat.share.factory", "wechat.share.legacy", "wechat.share.kntr"),
            registrar.registrationAttempts.map { it.first })

        // discover must reject non-zero-arg initialize methods inside wx package (P1 #6).
        val wxBadLoader = RenamingClassLoader(
            parent = baseLoader,
            templates = mapOf("kntr.common.share.common.handler.wx.BadModern" to FakeNonZeroArgModernWx::class.java),
            replacements = mapOf(
                FakeNonZeroArgModernWx::class.java.name.replace('.', '/') to
                    "kntr/common/share/common/handler/wx/BadModern"
            )
        )
        val legacyInit = wxBadLoader.loadClass("kz3.a").getMethod("t")
        val badModernInit = wxBadLoader.loadClass("kntr.common.share.common.handler.wx.BadModern")
            .getMethod("initialize", Int::class.javaPrimitiveType)
        assertNull(
            WeChatShareCompatAccess.discover(
                wxBadLoader,
                DexAssistResult.Candidates(listOf(legacyInit, badModernInit))
            )
        )
    }

    fun `player speed preserves menuAdapterInstalled on null retry and shares theseus2PressScope across Hook2 failure`() {
        val baseLoader = requireNotNull(javaClass.classLoader)
        // Build loader with list-only speed adapter at widget pkg "f" and no factory -> complete=false triggers retry.
        val menuTemplates = linkedMapOf<String, Class<*>>(
            "${PlayerSpeedLocator.SPEED_WIDGET_PKG}.f" to FakeListOnlySpeedAdapter::class.java,
            "${PlayerSpeedLocator.SPEED_WIDGET_PKG}.f\$Item" to FakeListOnlySpeedAdapter.Item::class.java,
            "test.phase1.ListOptionModel" to FakeListOnlyOptionModel::class.java
        )
        val menuReplacements = mapOf(
            FakeListOnlySpeedAdapter::class.java.name.replace('.', '/') to
                "${PlayerSpeedLocator.SPEED_WIDGET_PKG.replace('.', '/')}/f",
            FakeListOnlySpeedAdapter.Item::class.java.name.replace('.', '/') to
                "${PlayerSpeedLocator.SPEED_WIDGET_PKG.replace('.', '/')}/f\$Item",
            FakeListOnlyOptionModel::class.java.name.replace('.', '/') to
                "test/phase1/ListOptionModel"
        )
        val menuLoader = RenamingClassLoader(
            parent = baseLoader,
            templates = menuTemplates,
            replacements = menuReplacements
        )
        val reg1 = PlayerPortTestRegistrar()
        val menuInstaller = PlayerSpeedFeatureInstaller(
            disableLongPress = false,
            longPressPercent = 0,
            defaultPercent = 0,
            speedMenuSpec = "200,150,100"
        )
        val envMenu1 = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = menuLoader,
            hookPoints = HookPointRegistry(menuLoader),
            registrar = reg1,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val menuRun1 = menuInstaller.install(envMenu1) as FeatureInstallResult.Installed
        assertEquals(1, menuRun1.hookCount)
        assertFalse("player.speed.menu_options_factory" in reg1.hooks.keys)
        assertTrue(menuInstaller.requiresAdaptationRetry)

        // Retry where menuPoint cannot be resolved (menuPoint == null): must preserve menuAdapterInstalled = true (P1 #3).
        val hiddenMenuLoader = object : ClassLoader(baseLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("${PlayerSpeedLocator.SPEED_WIDGET_PKG}.")) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val envMenu2 = envMenu1.copy(
            classLoader = hiddenMenuLoader,
            hookPoints = HookPointRegistry(hiddenMenuLoader)
        )
        val menuRun2 = menuInstaller.install(envMenu2) as FeatureInstallResult.Installed
        assertEquals(1, menuRun2.hookCount)
        assertFalse(menuInstaller.requiresAdaptationRetry)
        assertEquals(1, reg1.registrationAttempts.size)

        // Shared theseus2PressScope survives Hook2 failure and partial retry without duplicating Hook1 (P1 #4).
        val scopeReg = PlayerPortTestRegistrar(failId = "player.speed.theseus2_long_press_scope")
        val scopeInstaller = PlayerSpeedFeatureInstaller(disableLongPress = false, longPressPercent = 175, defaultPercent = 0)
        val scopeEnv = HookEnvironment(
            processName = "tv.danmaku.bili",
            classLoader = baseLoader,
            hookPoints = HookPointRegistry(baseLoader),
            registrar = scopeReg,
            logInfo = { _, _ -> },
            logError = { _, _ -> },
            reportStatus = { _, _ -> }
        )
        val start = FakeTheseus2Port::class.java.getMethod("start")
        val calc = Phase1AdaptationRetryTest::class.java.getMethod("fakeTheseus2Calculate", Any::class.java, Float::class.javaObjectType)

        val failedAttempt = runCatching { scopeInstaller.installTheseus2LongPressSpeed(scopeEnv, start, calc, 1.75f) }
        assertTrue(failedAttempt.isFailure)
        assertEquals(1, scopeReg.hooks.size)
        assertTrue("player.speed.theseus2_calculate_long_press" in scopeReg.hooks.keys)

        scopeReg.failId = null
        scopeInstaller.installTheseus2LongPressSpeed(scopeEnv, start, calc, 1.75f)
        scopeInstaller.installTheseus2LongPressSpeed(scopeEnv, start, calc, 1.75f)
        assertEquals(1, scopeReg.registrationAttempts.count { it.first == "player.speed.theseus2_calculate_long_press" })
        // 尝试记录含第一次失败与第二次成功共 2 次；第三次重入不得再新增注册尝试。
        assertEquals(2, scopeReg.registrationAttempts.count { it.first == "player.speed.theseus2_long_press_scope" })
        assertEquals(2, scopeReg.hooks.size)

        var playbackResult: Any? = null
        val indicatorResult = scopeReg.invoke("player.speed.theseus2_long_press_scope", FakeTheseus2Port()) {
            scopeReg.invoke("player.speed.theseus2_calculate_long_press", args = arrayOf(Any(), null)) { 3f }
                .also { playbackResult = it }
        }
        assertEquals(1.75f, playbackResult)
        assertEquals(1.75f, indicatorResult)
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (Phase1AdaptationRetryTest) -> Unit) {
            val s = Phase1AdaptationRetryTest()
            block(s)
        }
        runIsolated { s -> s.`block update installer retries supplier layer idempotently without duplicating caller hooks`() }
        runIsolated { s -> s.`wechat share compat falls back to cached classes on drifted profile and retries idempotently`() }
        runIsolated { s -> s.`player speed installer retries through coordinator preserving verified capabilities and updates late prepared counts`() }
        runIsolated { s -> s.`home recommend early load adapter verifies candidates fails closed on ambiguity and LoadMoreWatcher replays pending ViewGroup listeners`() }
        runIsolated { s -> s.`home recommend early load adaptationAttemptedInCache finishAdaptationWait and re-entry preserve work without poisoning negative cache`() }
        runIsolated { s -> s.`adaptResult rejects explicit null early load listener and mergeRuntimeWithCached preserves cached listener and missing diagnostic`() }
        runIsolated { s -> s.`wechat share partial profile drift falls back to complete cache retries idempotently and discover rejects parameterized initialize`() }
        runIsolated { s -> s.`player speed preserves menuAdapterInstalled on null retry and shares theseus2PressScope across Hook2 failure`() }
    }
}
