package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import androidx.compose.ui.Modifier
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter.AdaptState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapterTest
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.AdaptPlan
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PointFamily
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.bapis.bilibili.main.community.reply.v1.ReplyInfo
import com.bapis.bilibili.main.community.reply.v1.SubjectControl
import com.bilibili.app.comment3.data.model.CommentItem
import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.core.HookEnvironment as MeguvizHookEnvironment
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Constructor

class ReplyTopology920AdaptationTest {

    // -------------------------------------------------------------------------
    // Fixtures for Compose menu entry validation & installation
    // -------------------------------------------------------------------------

    class FixtureCompositionLocal
    object FixtureAndroidLocals {
        private val localView = FixtureCompositionLocal()

        @JvmStatic
        fun getLocalView(): FixtureCompositionLocal = localView
    }

    interface FixtureComposer {
        fun consume(key: FixtureCompositionLocal): Any?
    }

    abstract class FixturePainter

    @Suppress("unused")
    data class FixtureWmo1WithDefaults(
        val a: String,
        val b: FixturePainter,
        val c: String = "",
        val d: FixturePainter,
        val e: () -> Unit
    ) {
        companion object {
            @JvmField
            val f: Int = 8
        }
    }

    @Suppress("unused")
    class FixtureLegacyEntry(
        val a: String,
        val b: () -> Unit
    )

    @Suppress("unused")
    class FixtureBadFirstFieldEntry(
        val a: Int,
        val b: () -> Unit
    )

    @Suppress("unused")
    class FixtureBadLastFieldEntry(
        val a: String,
        val b: Int
    )

    @Suppress("unused")
    class FixtureMismatchedCtorEntry {
        val a: String = ""
        val b: () -> Unit = {}
    }

    enum class FixtureEnumEntry { ONLY }
    interface FixtureInterfaceEntry

    object FixtureMoreMenuBubble {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun d(
            items: List<*>,
            onDismiss: (Any?) -> Unit,
            composer: FixtureComposer,
            changed: Int
        ) = Unit

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun b(
            entry: FixtureWmo1WithDefaults,
            onDismiss: () -> Unit,
            modifier: Modifier,
            composer: FixtureComposer,
            changed: Int
        ) = Unit
    }

    object FixtureAmbiguousMoreMenuBubble {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun d(
            items: List<*>,
            onDismiss: (Any?) -> Unit,
            composer: FixtureComposer,
            changed: Int
        ) = Unit

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun b(
            entry: FixtureWmo1WithDefaults,
            onDismiss: () -> Unit,
            modifier: Modifier,
            composer: FixtureComposer,
            changed: Int
        ) = Unit

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun b(
            entry: FixtureLegacyEntry,
            onDismiss: () -> Unit,
            modifier: Modifier,
            composer: FixtureComposer,
            changed: Int
        ) = Unit
    }

    // -------------------------------------------------------------------------
    // Fixtures for 9.19 / 9.20 root ReplyInfo -> CommentItem mappers
    // -------------------------------------------------------------------------

    class FixtureFifthParam

    object FixtureRootMapper {
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun B(
            reply: ReplyInfo,
            control: SubjectControl,
            folded: Boolean,
            scene: String,
            extra: FixtureFifthParam
        ): CommentItem = CommentItem()
    }

    // -------------------------------------------------------------------------
    // Constant-pool bytecode renaming ClassLoader
    // -------------------------------------------------------------------------

    private class RenamingClassLoader(
        parent: ClassLoader,
        private val templateByTarget: Map<String, Class<*>>,
        private val descriptorReplacements: Map<String, String>,
        private val hiddenPrefixes: Set<String> = emptySet()
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (hiddenPrefixes.any(name::startsWith)) {
                throw ClassNotFoundException(name)
            }
            val template = templateByTarget[name] ?: return@synchronized super.loadClass(name, resolve)
            val loaded = findLoadedClass(name) ?: run {
                val bytes = rewriteConstantPool(template, descriptorReplacements)
                defineClass(name, bytes, 0, bytes.size)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }

        private fun rewriteConstantPool(
            template: Class<*>,
            replacements: Map<String, String>
        ): ByteArray {
            val raw = requireNotNull(
                template.getResourceAsStream("/${template.name.replace('.', '/')}.class")
            ).use { it.readBytes() }
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
                    val original = input.readUTF()
                    var rewritten = original
                    replacements.entries
                        .sortedByDescending { it.key.length }
                        .forEach { (oldInternal, newInternal) ->
                            rewritten = rewritten.replace(oldInternal, newInternal)
                        }
                    output.writeUTF(rewritten)
                } else {
                    val length = when (tag) {
                        3, 4, 9, 10, 11, 12, 17, 18 -> 4
                        5, 6 -> 8
                        7, 8, 16, 19, 20 -> 2
                        15 -> 3
                        else -> error("Unsupported classfile constant tag: $tag")
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

    private class RecordingRegistrar : HookRegistrar, AutoCloseable {
        val backend = FakeBackend.create()
        private val megEnv = MeguvizHookEnvironment.create(backend)
        val installedIds = linkedSetOf<String>()

        override fun first(
            id: String,
            className: String,
            methodName: String,
            block: ModernMemberHookCreator.() -> Unit
        ) = error("Unexpected first() for $id")

        override fun all(
            id: String,
            className: String,
            methodName: String,
            block: ModernMemberHookCreator.() -> Unit
        ) = error("Unexpected all() for $id")

        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            val method = owner.getDeclaredMethod(methodName, *parameterTypes)
            val spec = ModernMemberHookCreator(method).apply(block)
                .toSpec(HookId.of(id), ErrorPolicy.PROPAGATE)
            val receipt = megEnv.install(spec)
            assertTrue(receipt.message(), receipt.isInstalled)
            installedIds += id
        }

        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) = error("Unexpected adapted() for $id")

        override fun constructor(
            id: String,
            constructor: Constructor<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) = error("Unexpected constructor() for $id")

        override fun close() = megEnv.close()
    }

    private fun composeHostClassLoader(
        entryClassName: String,
        bubbleTemplate: Class<*> = FixtureMoreMenuBubble::class.java
    ): ClassLoader {
        fun internalName(cls: Class<*>): String = cls.name.replace('.', '/')
        val templates = mapOf(
            "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt" to FixtureAndroidLocals::class.java,
            "androidx.compose.runtime.CompositionLocal" to FixtureCompositionLocal::class.java,
            "androidx.compose.runtime.Composer" to FixtureComposer::class.java,
            "androidx.compose.ui.graphics.painter.Painter" to FixturePainter::class.java,
            "kntr.common.comment.common.ui.moremenu.MoreMenuBubbleKt" to bubbleTemplate,
            entryClassName to FixtureWmo1WithDefaults::class.java,
            "$entryClassName\$Companion" to FixtureWmo1WithDefaults.Companion::class.java,
            "zAltEntry" to FixtureLegacyEntry::class.java
        )
        val replacements = linkedMapOf(
            internalName(FixtureAndroidLocals::class.java) to "androidx/compose/ui/platform/AndroidCompositionLocals_androidKt",
            internalName(FixtureCompositionLocal::class.java) to "androidx/compose/runtime/CompositionLocal",
            internalName(FixtureComposer::class.java) to "androidx/compose/runtime/Composer",
            internalName(FixturePainter::class.java) to "androidx/compose/ui/graphics/painter/Painter",
            internalName(bubbleTemplate) to "kntr/common/comment/common/ui/moremenu/MoreMenuBubbleKt",
            internalName(FixtureWmo1WithDefaults::class.java) to entryClassName.replace('.', '/'),
            internalName(FixtureWmo1WithDefaults.Companion::class.java) to "${entryClassName.replace('.', '/')}\$Companion",
            internalName(FixtureLegacyEntry::class.java) to "zAltEntry"
        )
        return RenamingClassLoader(
            parent = requireNotNull(javaClass.classLoader),
            templateByTarget = templates,
            descriptorReplacements = replacements
        )
    }

    private fun mapperHostClassLoader(
        owners: List<Pair<String, String>>
    ): ClassLoader {
        fun internalName(cls: Class<*>): String = cls.name.replace('.', '/')
        val templates = linkedMapOf<String, Class<*>>()
        val replacements = linkedMapOf<String, String>()
        // Support up to two distinct root mapper pairs in one loader for cross-build isolation checks.
        if (owners.size == 1) {
            val (ownerName, fifthName) = owners.single()
            templates[ownerName] = FixtureRootMapper::class.java
            templates[fifthName] = FixtureFifthParam::class.java
            replacements[internalName(FixtureRootMapper::class.java)] = ownerName.replace('.', '/')
            replacements[internalName(FixtureFifthParam::class.java)] = fifthName.replace('.', '/')
        }
        return RenamingClassLoader(
            parent = requireNotNull(javaClass.classLoader),
            templateByTarget = templates,
            descriptorReplacements = replacements,
            hiddenPrefixes = setOf("com.bilibili.app.comment3.data.source.v1.")
        )
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `isValidComposeMenuEntryClass accepts 920 wmo1 and legacy shapes and rejects invalid entries`() {
        val loader920 = composeHostClassLoader("wmo1")
        assertTrue(isValidComposeMenuEntryClass(loader920.loadClass("wmo1")))
        assertTrue(isValidComposeMenuEntryClass(FixtureLegacyEntry::class.java))

        assertFalse(isValidComposeMenuEntryClass(FixtureBadFirstFieldEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(FixtureBadLastFieldEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(FixtureMismatchedCtorEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(FixtureEnumEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(FixtureInterfaceEntry::class.java))
        assertFalse(isValidComposeMenuEntryClass(Int::class.java))
        assertFalse(isValidComposeMenuEntryClass(Array<String>::class.java))
    }

    @Test
    fun `ReplyTopologyComposeMenu installs across 920 and 921 build obfuscations and skips ambiguous entries`() {
        for (entryName in listOf("cko1", "clo1", "wmo1", "gao1", "c9o1")) {
            val loader = composeHostClassLoader(entryName)
            RecordingRegistrar().use { registrar ->
                val env = HookEnvironment(
                    processName = "tv.danmaku.bili",
                    classLoader = loader,
                    hookPoints = HookPointRegistry(loader),
                    registrar = registrar,
                    logInfo = { _, _ -> },
                    logError = { _, _ -> },
                    reportStatus = { _, _ -> }
                )
                val result = ReplyTopologyComposeMenu { _, _ -> }.install(env)
                assertEquals("Entry class $entryName should install", FeatureInstallResult.Installed(1, complete = true), result)
                assertTrue("reply.topology.compose.menu" in registrar.installedIds)
            }
        }

        val ambiguousLoader = composeHostClassLoader("wmo1", FixtureAmbiguousMoreMenuBubble::class.java)
        RecordingRegistrar().use { registrar ->
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = ambiguousLoader,
                hookPoints = HookPointRegistry(ambiguousLoader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { _, _ -> }
            )
            val result = ReplyTopologyComposeMenu { _, _ -> }.install(env)
            assertEquals(FeatureInstallResult.Skipped("missing-compose-menu-entry"), result)
            assertTrue(registrar.installedIds.isEmpty())
        }
    }

    @Test
    fun `VersionAdapter resolves 919 to 921 mappers strictly per build and reports missing cleanly`() {
        val builds = mapOf(
            9190400 to ("rg40" to "tev"),
            9200100 to ("w360" to "caw"),
            9200200 to ("v260" to "m9w"),
            9200300 to ("l260" to "z8w"),
            9210100 to ("ot50" to "y1w"),
            9210200 to ("kt50" to "b4w"),
            9210300 to ("kt50" to "b4w")
        )
        builds.forEach { (versionCode, pair) ->
            val loader = mapperHostClassLoader(listOf(pair))
            val points = VersionAdapter.locateCommentTopology(loader, hostVersionCode = versionCode)
            assertNotNull("Expected mapper match for $versionCode", points)
            assertEquals(listOf(pair.first), points!!.mapperMethods.map { it.className })
            assertEquals(listOf("B"), points.mapperMethods.map { it.methodName })
            assertTrue(points.hasRequiredMethods())

            // Passing another build's version code with distinct owner must NOT cross-match.
            val otherVersion = builds.entries.first { it.value != pair }.key
            assertNull(VersionAdapter.locateCommentTopology(loader, hostVersionCode = otherVersion))
        }

        // Registered build whose mapper drifted/disappeared must report MISSING, not NOT_APPLICABLE.
        val driftedLoader = mapperHostClassLoader(emptyList())
        val plan = AdaptPlan(1L, setOf(PointFamily.COMMENT_TOPOLOGY), emptySet())
        val located = VersionAdapterTest().result().copy(commentTopology = null)
        for (missingVersion in listOf(9200300, 9210200, 9210300)) {
            val diag = VersionAdapter.buildDiagnostics(
                driftedLoader,
                plan,
                null,
                located,
                "unresolved:reply-mapper-methods",
                hostVersionCode = missingVersion
            ).associateBy { it.id }
            assertEquals(AdaptState.MISSING, diag.getValue("comment.topology").state)
        }
    }

    @Test
    fun `CommentTopologyFeatureInstaller retry flag activates once on missing points and stops after update`() {
        RecordingRegistrar().use { registrar ->
            val loader = requireNotNull(javaClass.classLoader)
            val env = HookEnvironment(
                processName = "tv.danmaku.bili",
                classLoader = loader,
                hookPoints = HookPointRegistry(loader),
                registrar = registrar,
                logInfo = { _, _ -> },
                logError = { _, _ -> },
                reportStatus = { _, _ -> }
            )
            val disabled = CommentTopologyFeatureInstaller(enabled = false, points = null, lowBindPoint = null, highBindPoint = null)
            assertEquals(FeatureInstallResult.Skipped("disabled"), disabled.install(env))
            assertFalse(disabled.requiresAdaptationRetry)

            val installer = CommentTopologyFeatureInstaller(enabled = true, points = null, lowBindPoint = null, highBindPoint = null)
            assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), installer.install(env))
            assertTrue(installer.requiresAdaptationRetry)

            // Once background adapt finishes and supplies updated points (even if still null), retry stops looping.
            installer.updateAdaptedPoints(points = null, lowBindPoint = null, highBindPoint = null)
            assertEquals(FeatureInstallResult.Skipped("missing-adapter-point"), installer.install(env))
            assertFalse(installer.requiresAdaptationRetry)
        }
    }

    @Test
    fun `planConstrainedAnchorInsertion supports zero or one neighbor and rejects conflicts or ambiguity`() {
        assertEquals(
            ConstrainedAnchorPlan(updateNeighbor = false, updateReference = false),
            planConstrainedAnchorInsertion(
                neighborCount = 0,
                referenceInverseConstraint = View.NO_ID,
                neighborViewId = null
            )
        )
        assertEquals(
            ConstrainedAnchorPlan(updateNeighbor = true, updateReference = false),
            planConstrainedAnchorInsertion(
                neighborCount = 1,
                referenceInverseConstraint = View.NO_ID,
                neighborViewId = 77
            )
        )
        assertEquals(
            ConstrainedAnchorPlan(updateNeighbor = true, updateReference = true),
            planConstrainedAnchorInsertion(
                neighborCount = 1,
                referenceInverseConstraint = 77,
                neighborViewId = 77
            )
        )

        val zeroConflict = runCatching {
            planConstrainedAnchorInsertion(neighborCount = 0, referenceInverseConstraint = 42, neighborViewId = null)
        }.exceptionOrNull()
        assertTrue(zeroConflict?.message?.contains("constraint-reference-conflict=42") == true)

        val oneConflict = runCatching {
            planConstrainedAnchorInsertion(neighborCount = 1, referenceInverseConstraint = 88, neighborViewId = 77)
        }.exceptionOrNull()
        assertTrue(oneConflict?.message?.contains("constraint-reference-conflict=88") == true)

        val ambiguous = runCatching {
            planConstrainedAnchorInsertion(neighborCount = 2, referenceInverseConstraint = View.NO_ID, neighborViewId = 77)
        }.exceptionOrNull()
        assertTrue(ambiguous?.message?.contains("constraint-neighbor-count=2") == true)
    }
}
