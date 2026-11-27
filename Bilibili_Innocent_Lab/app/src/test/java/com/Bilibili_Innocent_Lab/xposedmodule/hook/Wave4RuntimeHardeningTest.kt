package com.Bilibili_Innocent_Lab.xposedmodule.hook

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.GuestPlayerCoreAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistEngine
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentComposePurifyBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentKotlinPurifier
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossReplyBridge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.KotlinMossResponseHandlerProxy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecForceFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerCodecPreference
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerDecodeMode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.writeBackVideoRelateItems
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.invoke
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.bapis.bilibili.app.playurl.v1.CodeType as LegacyCodeType
import com.google.protobuf.Internal
import io.github.libxposed.api.XposedInterface
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Proxy

class Wave4RuntimeHardeningTest {

    private class LocalTestRegistrar : HookRegistrar {
        data class Entry(val member: Executable, val callback: ModernMemberHookCreator)
        val exactHooks = linkedMapOf<String, Entry>()
        val constructorHooks = linkedMapOf<String, Entry>()

        override fun first(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) = Unit
        override fun all(id: String, className: String, methodName: String, block: ModernMemberHookCreator.() -> Unit) = Unit
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) = Unit

        override fun exact(
            id: String,
            owner: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            val m = owner.getDeclaredMethod(methodName, *parameterTypes)
            require(!java.lang.reflect.Modifier.isAbstract(m.modifiers)) { "Cannot hook abstract executable: $m" }
            exactHooks[id] = Entry(m, ModernMemberHookCreator(m).apply(block))
        }

        override fun constructor(id: String, constructor: Constructor<*>, block: ModernMemberHookCreator.() -> Unit) {
            constructorHooks[id] = Entry(constructor, ModernMemberHookCreator(constructor).apply(block))
        }

        fun invokeExact(id: String, receiver: Any? = null, args: Array<Any?> = emptyArray(), original: (Array<Any?>) -> Any? = { null }): Any? {
            val entry = exactHooks.getValue(id)
            return invokeChain(entry.callback, entry.member, receiver, args, original)
        }

        fun invokeConstructor(id: String, instance: Any, args: Array<Any?>): Any? {
            val entry = constructorHooks.getValue(id)
            return invokeChain(entry.callback, entry.member, instance, args) { null }
        }

        private fun invokeChain(
            creator: ModernMemberHookCreator,
            member: Executable,
            receiver: Any?,
            originalArgs: Array<Any?>,
            original: (Array<Any?>) -> Any?
        ): Any? = creator.invoke(object : XposedInterface.Chain {
            override fun getExecutable() = member
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
    }

    private class RenamingClassLoader(
        parent: ClassLoader,
        private val templates: Map<String, Class<*>>,
        private val replacements: Map<String, String> = emptyMap(),
        private val hiddenNames: Set<String> = emptySet(),
        private val hiddenPrefixes: Set<String> = emptySet()
    ) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            if (name in hiddenNames || hiddenPrefixes.any(name::startsWith)) {
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

    private fun env(
        loader: ClassLoader,
        registrar: HookRegistrar = LocalTestRegistrar(),
        stages: MutableList<Pair<String, FeatureRuntimeStage>> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> },
        runtimeEvidence = { id, stage, _ -> stages += id to stage }
    )

    // -------------------------------------------------------------------------
    // 1. CommentComposePurifyBridge private ctor + safe inner class name check
    // -------------------------------------------------------------------------
    class ComposeCommentStub
    class ComposeUserStub(val d: ComposeDecoPrivateStub?)
    class ComposeSlotA
    class ComposeSlotC
    class ComposeSlotD
    class ComposeFollowStub(val mid: Long) {
        override fun toString() = "Follow(mid=$mid)"
    }
    class ComposeDecoPrivateStub private constructor(
        val a: ComposeSlotA?,
        val renamedFollow: ComposeFollowStub?,
        val c: ComposeSlotC?,
        val d: ComposeSlotD?
    )

    fun `compose purify bridge resolves private constructor and Follow inner class without getSimpleName`() {
        val modelPkg = "kntr.common.comment.card.model.comment"
        val userName = "$modelPkg.CommentUserInfoRendererModel"
        val decorName = "$userName\$Deco"
        val followName = "$userName\$Follow"
        val loader = RenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                "$modelPkg.CommentModel" to ComposeCommentStub::class.java,
                userName to ComposeUserStub::class.java,
                decorName to ComposeDecoPrivateStub::class.java,
                followName to ComposeFollowStub::class.java,
                "$userName\$A" to ComposeSlotA::class.java,
                "$userName\$C" to ComposeSlotC::class.java,
                "$userName\$D" to ComposeSlotD::class.java
            ),
            replacements = mapOf(
                ComposeCommentStub::class.java.name.replace('.', '/') to "$modelPkg/CommentModel",
                ComposeUserStub::class.java.name.replace('.', '/') to userName.replace('.', '/'),
                ComposeDecoPrivateStub::class.java.name.replace('.', '/') to decorName.replace('.', '/'),
                ComposeFollowStub::class.java.name.replace('.', '/') to followName.replace('.', '/'),
                ComposeSlotA::class.java.name.replace('.', '/') to "$userName\$A".replace('.', '/'),
                ComposeSlotC::class.java.name.replace('.', '/') to "$userName\$C".replace('.', '/'),
                ComposeSlotD::class.java.name.replace('.', '/') to "$userName\$D".replace('.', '/')
            )
        )
        val registrar = LocalTestRegistrar()
        val stages = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        val results = CommentComposePurifyBridge(vote = false, follow = true, quickReply = false)
            .install(env(loader, registrar, stages))
        val installResult = results["comments_follow_buttons_removed"] as? FeatureInstallResult.Installed
        assertNotNull(installResult)
        assertTrue(installResult!!.complete)

        val loadedDecor = loader.loadClass(decorName)
        val loadedFollow = loader.loadClass(followName).getDeclaredConstructor(Long::class.javaPrimitiveType).newInstance(99L)
        val ctor = loadedDecor.declaredConstructors.single().apply { isAccessible = true }
        val instance = ctor.newInstance(null, null, null, null)
        val args = arrayOf<Any?>(null, loadedFollow, null, null)
        registrar.invokeConstructor("comment.compose.follow.$decorName", instance, args)
        assertNull(args[1])
        assertTrue(stages.any { it.second == FeatureRuntimeStage.APPLIED })
    }

    // -------------------------------------------------------------------------
    // 2. VideoRelateResponseWriteback refuses protobuf getDefaultInstance()
    // -------------------------------------------------------------------------
    private class ProtoListFake(initial: List<String>, private var writable: Boolean = false) :
        AbstractMutableList<String>(), Internal.ProtobufList<String> {
        private val data = initial.toMutableList()
        override val size: Int get() = data.size
        override fun get(index: Int): String = data[index]
        override fun add(index: Int, element: String) { check(writable); data.add(index, element) }
        override fun removeAt(index: Int): String { check(writable); return data.removeAt(index) }
        override fun set(index: Int, element: String): String { check(writable); return data.set(index, element) }
        override fun isModifiable(): Boolean = writable
        override fun makeImmutable() { writable = false }
        override fun mutableCopyWithCapacity(capacity: Int): Internal.ProtobufList<String> =
            ProtoListFake(data, writable = true)
    }

    private open class FakeProtoRelateReply(initial: List<String> = listOf("ad", "keep")) {
        @JvmField var memoizedHashCode = 0
        private var memoizedSize = -1
        @JvmField var items: Internal.ProtobufList<String> = ProtoListFake(initial)
        fun getMemoizedSerializedSize(): Int = memoizedSize
        fun setMemoizedSerializedSize(value: Int) { memoizedSize = value }
        companion object {
            val DEFAULT = FakeProtoRelateReply(listOf("default"))
            @JvmStatic
            fun getDefaultInstance(): FakeProtoRelateReply = DEFAULT
        }
    }

    fun `video relate writeback refuses mutating protobuf getDefaultInstance while modifying normal instances`() {
        val field = requireNotNull(KavaMemberLookup.fieldOrNull(FakeProtoRelateReply::class.java, "items"))
        val defaultInstance = FakeProtoRelateReply.getDefaultInstance()
        val defaultItemsBefore = defaultInstance.items
        assertFalse(writeBackVideoRelateItems(defaultInstance, field, emptyList<String>()))
        assertSame(defaultItemsBefore, defaultInstance.items)
        assertEquals(listOf("default"), defaultInstance.items)

        val liveInstance = FakeProtoRelateReply()
        assertTrue(writeBackVideoRelateItems(liveInstance, field, listOf("keep")))
        assertEquals(listOf("keep"), liveInstance.items)
        assertNotSame(defaultItemsBefore, liveInstance.items)
    }

    // -------------------------------------------------------------------------
    // 3. CommentKotlinPurifier keeps defaultInstance immutable
    // -------------------------------------------------------------------------
    private class PurifyControl(val empty: Boolean) {
        fun hasEmptyPage() = empty
        class Builder(src: PurifyControl) {
            var empty = src.empty
            fun clearEmptyPage(): Builder { empty = false; edits++; return this }
            fun build() = PurifyControl(empty)
        }
        companion object {
            var edits = 0
            @JvmStatic val DEFAULT = PurifyControl(true)
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(src: PurifyControl) = Builder(src)
        }
    }

    private class PurifyContent(val urls: Map<String, String>) {
        fun getUrlsMap(): Map<String, String> = urls
        class Builder(src: PurifyContent) {
            var urls = src.urls.toMutableMap()
            fun clearUrls(): Builder { urls.clear(); edits++; return this }
            fun putAllUrls(map: Map<*, *>): Builder {
                @Suppress("UNCHECKED_CAST")
                urls.putAll(map as Map<String, String>)
                return this
            }
            fun build() = PurifyContent(urls)
        }
        companion object {
            var edits = 0
            @JvmStatic val DEFAULT = PurifyContent(mapOf("search" to "bilibili://search?keyword=test"))
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(src: PurifyContent) = Builder(src)
        }
    }

    private class PurifyReplyItem(private val rawContent: PurifyContent?) {
        fun getContent() = rawContent
        fun getRepliesList(): List<Any> = emptyList()
        fun hasUpSelection() = false
        class Builder(src: PurifyReplyItem) {
            private var rawContent = src.rawContent
            fun setContent(value: PurifyContent): Builder { rawContent = value; edits++; return this }
            fun build() = PurifyReplyItem(rawContent)
        }
        companion object {
            var edits = 0
            @JvmStatic val DEFAULT = PurifyReplyItem(PurifyContent(mapOf("bad" to "s")))
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(src: PurifyReplyItem) = Builder(src)
        }
    }

    private class PurifyTopReply(
        val replies: List<PurifyReplyItem> = emptyList(),
        val qoe: Boolean = true,
        val control: PurifyControl? = PurifyControl(true)
    ) {
        fun getRepliesList(): List<PurifyReplyItem> = replies
        fun hasQoe() = qoe
        fun hasSubjectControl() = control != null
        fun getSubjectControl() = control
        class Builder(src: PurifyTopReply) {
            var items = src.replies.toMutableList()
            var qoe = src.qoe
            var control = src.control
            fun clearReplies(): Builder { items.clear(); edits++; return this }
            fun addAllReplies(values: Iterable<PurifyReplyItem>): Builder { items.addAll(values); return this }
            fun clearQoe(): Builder { qoe = false; edits++; return this }
            fun setSubjectControl(v: PurifyControl): Builder { control = v; edits++; return this }
            fun build() = PurifyTopReply(items, qoe, control)
        }
        companion object {
            var edits = 0
            @JvmStatic val DEFAULT = PurifyTopReply(listOf(PurifyReplyItem.DEFAULT), qoe = true, control = PurifyControl.DEFAULT)
            @JvmStatic fun getDefaultInstance() = DEFAULT
            @JvmStatic fun newBuilder(src: PurifyTopReply) = Builder(src)
        }
    }

    fun `comment kotlin purifier leaves protobuf getDefaultInstance untouched across top control and reply`() {
        PurifyControl.edits = 0
        PurifyContent.edits = 0
        PurifyReplyItem.edits = 0
        PurifyTopReply.edits = 0
        val purifier = CommentKotlinPurifier(
            replyInfoClass = PurifyReplyItem::class.java,
            isSearchUrl = { true },
            clearEmptyPage = true,
            payloads = mapOf("Qoe" to "comments_qoe_removed")
        ) { _, _, _ -> }

        val topDefault = PurifyTopReply.getDefaultInstance()
        assertSame(topDefault, purifier.purify(topDefault))
        assertEquals(0, PurifyTopReply.edits)
        assertEquals(0, PurifyControl.edits)
        assertEquals(0, PurifyReplyItem.edits)
        assertEquals(0, PurifyContent.edits)

        val itemDefaultContainer = PurifyTopReply(replies = listOf(PurifyReplyItem.DEFAULT), qoe = false, control = null)
        val purifiedItemDefaultContainer = purifier.purify(itemDefaultContainer) as PurifyTopReply
        assertSame(itemDefaultContainer, purifiedItemDefaultContainer)
        assertSame(PurifyReplyItem.DEFAULT, purifiedItemDefaultContainer.getRepliesList().single())
        assertEquals(0, PurifyReplyItem.edits)
        assertEquals(0, PurifyContent.edits)

        val defaultContentContainer = PurifyTopReply(replies = listOf(PurifyReplyItem(PurifyContent.DEFAULT)), qoe = false, control = null)
        assertSame(defaultContentContainer, purifier.purify(defaultContentContainer))
        assertEquals(0, PurifyContent.edits)
        assertEquals(0, PurifyReplyItem.edits)

        val withDefaultControl = PurifyTopReply(replies = emptyList(), qoe = true, control = PurifyControl.DEFAULT)
        val purifiedTop = purifier.purify(withDefaultControl) as PurifyTopReply
        assertNotSame(withDefaultControl, purifiedTop)
        assertFalse(purifiedTop.hasQoe())
        assertEquals(1, PurifyTopReply.edits)
        assertSame(PurifyControl.DEFAULT, purifiedTop.getSubjectControl())
        assertEquals(0, PurifyControl.edits)

        val liveItem = PurifyReplyItem(PurifyContent(mapOf("bad" to "s")))
        val liveContainer = PurifyTopReply(replies = listOf(liveItem), qoe = false, control = null)
        val purifiedLive = purifier.purify(liveContainer) as PurifyTopReply
        assertNotSame(liveContainer, purifiedLive)
        assertTrue(purifiedLive.getRepliesList().single().getContent()?.getUrlsMap()?.isEmpty() == true)
        assertEquals(1, PurifyContent.edits)
        assertEquals(1, PurifyReplyItem.edits)
    }

    // -------------------------------------------------------------------------
    // 4. PlayerCodecForceFeatureInstaller readback guard rejects stale builder
    // -------------------------------------------------------------------------
    fun `player codec rewrite returns null when builder readback mismatches`() {
        val mossName = "com.bapis.bilibili.app.playurl.v1.PlayURLMoss"
        val reqName = "com.bapis.bilibili.app.playurl.v1.PlayViewReq"
        val loader = RenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                mossName to Wave4BadPlayURLMoss::class.java,
                reqName to Wave4BadPlayViewReq::class.java,
                "$reqName\$Builder" to Wave4BadPlayViewReq.Builder::class.java,
                "$reqName\$Companion" to Wave4BadPlayViewReq.Companion::class.java
            ),
            replacements = mapOf(
                Wave4BadPlayURLMoss::class.java.name.replace('.', '/') to mossName.replace('.', '/'),
                Wave4BadPlayViewReq::class.java.name.replace('.', '/') to reqName.replace('.', '/')
            ),
            hiddenNames = setOf(
                "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
                "com.bapis.bilibili.app.playerunite.v1.KPlayerMoss",
                "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq",
                "com.bapis.bilibili.app.playerunite.v1.KPlayViewUniteReq",
                "com.bapis.bilibili.app.playurl.v1.KPlayURLMoss",
                "com.bapis.bilibili.app.playurl.v1.KPlayViewReq",
                "tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient"
            )
        )
        val registrar = LocalTestRegistrar()
        val stages = mutableListOf<Pair<String, FeatureRuntimeStage>>()
        val installResult = PlayerCodecForceFeatureInstaller(PlayerCodecPreference.H265.value, PlayerDecodeMode.FOLLOW_HOST.value)
            .install(env(loader, registrar, stages))
        assertTrue("Expected Installed, got $installResult", installResult is FeatureInstallResult.Installed)
        assertEquals(1, (installResult as FeatureInstallResult.Installed).hookCount)

        val badReq = loader.loadClass(reqName).getDeclaredConstructor().newInstance()
        val args = arrayOf<Any?>(badReq)
        registrar.invokeExact("player_codec_force.request.PlayViewReq", loader.loadClass(mossName).getDeclaredConstructor().newInstance(), args)
        assertSame(badReq, args[0])
        assertFalse(stages.any { it.second == FeatureRuntimeStage.APPLIED })
    }

    // -------------------------------------------------------------------------
    // 5. GuestPlayerCoreAdapter discovery, exclusions, cache & AdaptResult roundtrip
    // -------------------------------------------------------------------------
    class FakeMediaParamsStub {
        fun getAvid(): Long = 1001L
        fun getId(): String = "2002"
    }

    class FlattenedPlayerCoreFixture : tv.danmaku.biliplayerv2.service.IPlayerCoreService {
        override fun getCurrentPosition(): Int = 1234
        override fun getDuration(): Int = 5678
        @Suppress("UNUSED_PARAMETER")
        fun setMediaResource(
            resource: com.bilibili.lib.media.resource.MediaResource,
            flag: Boolean,
            params: FakeMediaParamsStub
        ) = Unit
    }

    class BlockedMallCoreFixture : tv.danmaku.biliplayerv2.service.IPlayerCoreService {
        override fun getCurrentPosition(): Int = 1234
        override fun getDuration(): Int = 5678
        @Suppress("UNUSED_PARAMETER")
        fun setMediaResource(
            resource: com.bilibili.lib.media.resource.MediaResource,
            flag: Boolean,
            params: FakeMediaParamsStub
        ) = Unit
    }

    class BlockedAnonCoreFixture : tv.danmaku.biliplayerv2.service.IPlayerCoreService {
        override fun getCurrentPosition(): Int = 1234
        override fun getDuration(): Int = 5678
        @Suppress("UNUSED_PARAMETER")
        fun setMediaResource(
            item: tv.danmaku.videoplayer.coreV2.MediaItem,
            resource: com.bilibili.lib.media.resource.MediaResource,
            flag: Boolean,
            params: FakeMediaParamsStub
        ) = Unit
    }

    fun `guest player core adapter discovers flattened implementation rejects mall and roundtrips json`() {
        assertTrue(GuestPlayerCoreAdapter.allowedOwnerName("it02"))
        assertTrue(GuestPlayerCoreAdapter.allowedOwnerName("bi1.K"))
        assertTrue(GuestPlayerCoreAdapter.allowedOwnerName("lf6.p0"))
        assertFalse(GuestPlayerCoreAdapter.allowedOwnerName(GuestPlayerCoreAdapter.I_PLAYER_CORE_SERVICE))
        assertFalse(GuestPlayerCoreAdapter.allowedOwnerName("com.mall.ui.page.course.CourseCore"))
        assertFalse(GuestPlayerCoreAdapter.allowedOwnerName("com.bilibili.ship.theseus.united.Wrapper"))
        assertFalse(GuestPlayerCoreAdapter.allowedOwnerName("pkg.Owner\$1"))

        val mallName = "com.mall.ui.page.course.CourseCore"
        val anonName = "pkg.PlayerHost\$1"
        val paramsName = GuestPlayerCoreAdapter.MEDIA_ITEM_PARAMS
        val loader = RenamingClassLoader(
            javaClass.classLoader!!,
            templates = mapOf(
                paramsName to FakeMediaParamsStub::class.java,
                "it02" to FlattenedPlayerCoreFixture::class.java,
                mallName to BlockedMallCoreFixture::class.java,
                anonName to BlockedAnonCoreFixture::class.java
            ),
            replacements = mapOf(
                FakeMediaParamsStub::class.java.name.replace('.', '/') to paramsName.replace('.', '/'),
                FlattenedPlayerCoreFixture::class.java.name.replace('.', '/') to "it02",
                BlockedMallCoreFixture::class.java.name.replace('.', '/') to mallName.replace('.', '/'),
                BlockedAnonCoreFixture::class.java.name.replace('.', '/') to anonName.replace('.', '/')
            ),
            hiddenNames = setOf("lf6.p0")
        )
        val flattenedClass = loader.loadClass("it02")
        val mallClass = loader.loadClass(mallName)
        val anonClass = loader.loadClass(anonName)
        val mFlat = flattenedClass.declaredMethods.first { it.name == "setMediaResource" }
        val mMall = mallClass.declaredMethods.first { it.name == "setMediaResource" }
        val mAnon = anonClass.declaredMethods.first { it.name == "setMediaResource" }

        val session = DexAssistSession(
            engine = DexAssistEngine { DexAssistResult.Candidates(listOf(mFlat, mMall, mAnon)) },
            codePaths = emptyList(),
            classLoader = loader,
            planned = setOf(DexAssistQuery.GUEST_PLAYER_CORE_SERVICE)
        )
        assertTrue(GuestPlayerCoreAdapter.applicable(loader))
        assertNull(GuestPlayerCoreAdapter.direct(loader))
        assertEquals(
            "it02",
            GuestPlayerCoreAdapter.locate(loader, session, enabled = true, cached = null, planned = true)
        )
        assertSame(flattenedClass, GuestPlayerCoreAdapter.resolve(loader, "it02"))
        assertNull(GuestPlayerCoreAdapter.resolve(loader, mallName))

        val adaptResult = VersionAdapter.AdaptResult(
            biliVersionCode = 9200300,
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
            diagnostics = emptyList(),
            guestPlayerCore = "it02"
        )
        val roundtripped = VersionAdapter.AdaptResult.fromJson(adaptResult.toJson())
        assertEquals("it02", roundtripped?.guestPlayerCore)
        assertEquals(100, VersionAdapterContract.RULE_VERSION)
    }

    // -------------------------------------------------------------------------
    // 6. HostTopBarFxController dynamic proxy safety
    // -------------------------------------------------------------------------
    private interface TestPageListener {
        fun onPageSelected(position: Int)
        fun onPrimitiveCheck(): Boolean
        fun onIntCheck(): Int
    }

    fun `top bar page change proxy handles Object methods primitive returns and exceptions`() {
        var called = 0
        val handler = HostTopBarFxController.createPageChangeProxyHandler {
            called++
            error("simulated callback exception")
        }
        val proxy = Proxy.newProxyInstance(
            TestPageListener::class.java.classLoader,
            arrayOf(TestPageListener::class.java),
            handler
        ) as TestPageListener
        val other = Proxy.newProxyInstance(
            TestPageListener::class.java.classLoader,
            arrayOf(TestPageListener::class.java),
            HostTopBarFxController.createPageChangeProxyHandler {}
        )

        assertTrue(proxy == proxy)
        assertFalse(proxy == other)
        assertEquals(System.identityHashCode(proxy), proxy.hashCode())
        assertEquals("HostTopBarPageChangeListener", proxy.toString())
        assertFalse(proxy.onPrimitiveCheck())
        assertEquals(0, proxy.onIntCheck())

        proxy.onPageSelected(1)
        assertEquals(1, called)
    }

    // -------------------------------------------------------------------------
    // 7. KotlinMossResponseHandlerProxy guards internal side callbacks only
    // -------------------------------------------------------------------------
    private class EndpointStub {
        @Suppress("UNUSED_PARAMETER")
        fun rpc(req: Any, ser: SerializationStrategy<Any>, dec: DeserializationStrategy<Any>, h: Wave4MossCallback, p: ProtoBuf) = Unit
    }

    fun `kotlin moss proxy contains failed and receipt errors while rethrowing host delegate failures`() {
        val bridge = KotlinMossReplyBridge(
            encode = { error("encode failure") },
            parseJava = { it },
            toBytes = { ByteArray(0) },
            decode = { it }
        )
        val method = EndpointStub::class.java.declaredMethods.single()
        val key = KotlinMossResponseHandlerProxy.Key(method, Any::class.java, bridge, method)

        var delivered: Any? = null
        val throwingDelegate = object : Wave4MossCallback {
            override fun onNext(reply: Any?) {
                delivered = reply
                throw IllegalStateException("host-delegate-boom")
            }
        }
        val throwingFailedProxy = KotlinMossResponseHandlerProxy.wrap(
            Wave4MossCallback::class.java,
            throwingDelegate,
            key,
            bridge,
            failed = { throw RuntimeException("failed-listener-boom") },
            transform = { it }
        ) as Wave4MossCallback

        val err = assertThrows(IllegalStateException::class.java) {
            throwingFailedProxy.onNext("raw-reply")
        }
        assertEquals("host-delegate-boom", err.message)
        assertEquals("raw-reply", delivered)

        val goodBridge = KotlinMossReplyBridge(
            encode = { if (it == "rewritten-msg") byteArrayOf(2) else byteArrayOf(1) },
            parseJava = { if (it.contentEquals(byteArrayOf(2))) "transformed-java" else "java-msg" },
            toBytes = { byteArrayOf(2) },
            decode = { "rewritten-msg" }
        )
        var okDelivered: Any? = null
        val okDelegate = object : Wave4MossCallback {
            override fun onNext(reply: Any?) {
                okDelivered = reply
            }
        }
        val throwingReceiptProxy = KotlinMossResponseHandlerProxy.wrap(
            Wave4MossCallback::class.java,
            okDelegate,
            key,
            goodBridge,
            failed = { throw AssertionError(it) },
            transform = { it },
            confirmedTransform = { KotlinMossResponseHandlerProxy.ConfirmedReply("transformed-java") { throw RuntimeException("receipt-boom") } }
        ) as Wave4MossCallback

        throwingReceiptProxy.onNext("orig-k")
        assertEquals("rewritten-msg", okDelivered)
    }

    @Test
    fun verifyAllCases() {
        fun runIsolated(block: (Wave4RuntimeHardeningTest) -> Unit) {
            val s = Wave4RuntimeHardeningTest()
            block(s)
        }
        runIsolated { s -> s.`compose purify bridge resolves private constructor and Follow inner class without getSimpleName`() }
        runIsolated { s -> s.`video relate writeback refuses mutating protobuf getDefaultInstance while modifying normal instances`() }
        runIsolated { s -> s.`comment kotlin purifier leaves protobuf getDefaultInstance untouched across top control and reply`() }
        runIsolated { s -> s.`player codec rewrite returns null when builder readback mismatches`() }
        runIsolated { s -> s.`guest player core adapter discovers flattened implementation rejects mall and roundtrips json`() }
        runIsolated { s -> s.`top bar page change proxy handles Object methods primitive returns and exceptions`() }
        runIsolated { s -> s.`kotlin moss proxy contains failed and receipt errors while rethrowing host delegate failures`() }
    }
}

internal class Wave4BadPlayViewReq(
    private val prefer: LegacyCodeType = LegacyCodeType.CODE_UNKNOWN,
    private val fnval: Int = 2064
) {
    fun getPreferCodecType() = prefer
    fun getFnval() = fnval
    class Builder(private val src: Wave4BadPlayViewReq) {
        fun setPreferCodecType(@Suppress("UNUSED_PARAMETER") value: LegacyCodeType): Builder = this
        fun setFnval(@Suppress("UNUSED_PARAMETER") value: Int): Builder = this
        // Intentionally returns a distinct instance with UNCHANGED fields (simulates silent builder drop)
        fun build() = Wave4BadPlayViewReq(src.prefer, src.fnval)
    }
    companion object {
        @JvmStatic fun newBuilder(src: Wave4BadPlayViewReq) = Builder(src)
    }
}

internal class Wave4BadPlayURLMoss {
    @Suppress("UNUSED_PARAMETER")
    fun executePlayView(req: Wave4BadPlayViewReq): Any = req
}

internal interface Wave4MossCallback {
    fun onNext(reply: Any?)
}
