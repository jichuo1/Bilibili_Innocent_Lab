package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.commentfishfixture

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.CommentFilterFeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishScorePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FishVideoMetadata
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.HookExceptionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernMemberHookCreator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 评论列表 getter 真的把"装着这批回复的消息"（楼中楼里就是主评论）交给了 AI 账号判据。 */
class CommentFishRootEchoHookTest {

    class EchoMember(private val who: String, private val lv: Int) {
        fun getName() = who
        fun getLevel() = lv
    }

    class EchoContent(private val text: String) {
        fun getMessage() = text
    }

    /** 与宿主 ReplyInfo 同形：正文、作者、子回复列表都可配置。 */
    class EchoReply(
        private val text: String,
        private val who: String,
        private val lv: Int,
        private val replies: List<EchoReply> = emptyList(),
        private val oid: Long = 0L,
        private val type: Long = 1L,
        private val id: Long = 0L,
        private val mid: Long = 0L,
        private val root: Long = 0L
    ) {
        fun getContent() = EchoContent(text)
        fun getMember() = EchoMember(who, lv)
        fun getRepliesList(): List<EchoReply> = replies
        fun getOid() = oid
        fun getType() = type
        fun getId() = id
        fun getMid() = mid
        fun getRoot() = root
        fun getParent() = root
    }

    /** 主楼响应：它的列表挂在响应上，不挂在某条评论上，没有主评论可对照。 */
    class EchoMainList(private val replies: List<EchoReply>) {
        fun getRepliesList(): List<EchoReply> = replies
    }

    private fun point(owner: Class<*>, name: String) = VersionAdapter.HookPoint(owner.name, name, emptyList())

    private val points = VersionAdapter.CommentFilterPoints(
        replyListGetters = listOf(point(EchoReply::class.java, "getRepliesList"), point(EchoMainList::class.java, "getRepliesList")),
        contentGetter = point(EchoReply::class.java, "getContent"),
        messageGetter = point(EchoContent::class.java, "getMessage"),
        memberGetter = point(EchoReply::class.java, "getMember"),
        levelGetter = point(EchoMember::class.java, "getLevel"),
        memberNameGetter = point(EchoMember::class.java, "getName")
    )

    private val recorded = PlayerPortTestRegistrar()
    private var status = ""
    private val registrar = object : HookRegistrar by recorded {
        override fun adapted(
            id: String,
            point: VersionAdapter.HookPoint,
            exceptionPolicy: HookExceptionPolicy,
            block: ModernMemberHookCreator.() -> Unit
        ) {
            recorded.exact(id, Class.forName(point.className), point.methodName,
                *point.paramClassNames.orEmpty().map { Class.forName(it) }.toTypedArray(), block = block)
        }
    }
    private val env = HookEnvironment("tv.danmaku.bili", javaClass.classLoader, HookPointRegistry(javaClass.classLoader),
        registrar, { _, _ -> }, { _, _ -> }, { _, s -> status = s })

    private val rootText = "一米宽的画布能画出四季变化，镜头里的细节太绝了"
    private val echoText = "一米宽的画布能画出四季变化真的太有意思了"
    private val ownText = "我昨天也去看了展览，人太多了根本挤不进去"

    private fun install(sensitivity: String = "standard", metadata: FishVideoMetadata = FishVideoMetadata()) {
        val fish = FishScorePolicy.Config(enabled = true, sensitivityId = sensitivity)
        val installer = CommentFilterFeatureInstaller(
            false, "", false, 3, points = points, fishFilter = fish, fishVideoMetadata = metadata
        )
        assertTrue(installer.install(env) is FeatureInstallResult.Installed)
    }

    private fun judge(owner: Any?, replies: List<EchoReply>, id: String = "comment.filter.list.0"): List<*> =
        recorded.invoke(id, receiver = owner) { replies } as List<*>

    @Test fun `replies under a root are compared with that root through the getter receiver`() {
        install()
        val echoing = EchoReply(echoText, "深海大鱼君", 2)
        val unrelated = EchoReply(ownText, "深海大鱼君", 2)
        val plain = EchoReply(echoText, "路人甲", 2)
        val replies = listOf(echoing, unrelated, plain)
        val root = EchoReply(rootText, "画家本人", 4, replies)

        assertEquals(listOf(unrelated, plain), judge(root, replies))
        assertEquals(3, replies.size)
    }

    @Test fun `without a root nothing is compared and the same replies stay`() {
        install()
        val echoing = EchoReply(echoText, "深海大鱼君", 2)
        val replies = listOf(echoing)
        // 主楼列表挂在响应上，没有主评论；接收者读不到时同样不比。
        assertSame(replies, judge(EchoMainList(replies), replies, "comment.filter.list.1"))
        assertSame(replies, judge(null, replies))
    }

    @Test fun `the thread owner answering in their own thread is kept`() {
        install()
        val own = EchoReply(echoText, "深海大鱼君", 2)
        val replies = listOf(own)
        assertSame(replies, judge(EchoReply(rootText, "深海大鱼君", 4, replies), replies))
    }

    @Test fun `the verdict is reused for the same list instance`() {
        install()
        val replies = listOf(EchoReply(echoText, "深海大鱼君", 2), EchoReply(ownText, "深海大鱼君", 2))
        val root = EchoReply(rootText, "画家本人", 4, replies)
        val first = judge(root, replies)
        val second = judge(root, replies)
        assertEquals(1, first.size)
        assertEquals(first, second)
    }

    @Test fun `the same list is reconsidered after matching video metadata arrives`() {
        val metadata = FishVideoMetadata()
        install(metadata = metadata)
        val video = EchoReply(echoText, "深海大鱼君", 2, oid = 42L)
        val otherVideo = EchoReply(echoText, "深海大鱼君", 2, oid = 43L)
        val nonVideo = EchoReply(echoText, "深海大鱼君", 2, oid = 42L, type = 11L)
        val replies = listOf(video, otherVideo, nonVideo)
        val main = EchoMainList(replies)

        assertSame(replies, judge(main, replies, "comment.filter.list.1"))
        metadata.remember(42L, null, rootText)
        assertEquals(listOf(otherVideo, nonVideo), judge(main, replies, "comment.filter.list.1"))
    }

    @Test fun `a peer snapshot changes only the matching video reply verdict`() {
        install(sensitivity = "strict")
        val message = "三道题都没提帧率，三次全给24帧"
        val video = EchoReply(message, "大鱼君", 2, oid = 42L, id = 1L, mid = 10L)
        val other = EchoReply(message, "大鱼君", 2, oid = 43L, id = 1L, mid = 10L)
        val nonVideo = EchoReply(message, "大鱼君", 2, oid = 42L, type = 11L, id = 1L, mid = 10L)
        val replies = listOf(video, other, nonVideo)
        val main = EchoMainList(replies)
        assertSame(replies, judge(main, replies, "comment.filter.list.1"))
        val raw = EchoMainList(listOf(
            video,
            EchoReply("三次不说帧率就给24帧，确实是这套", "甲", 4, oid = 42L, id = 2L, mid = 20L),
            EchoReply("没指定帧率，连续三次都默认24帧", "乙", 4, oid = 42L, id = 3L, mid = 30L),
            EchoReply("三次都没设置帧率，每次默认24帧", "丙", 4, oid = 42L, id = 4L, mid = 40L)
        ))
        assertSame(raw, recorded.invoke("comment.semantic.sync.mainList", ReplyMoss(), arrayOf(raw)) { raw })
        assertEquals(listOf(other, nonVideo), judge(main, replies, "comment.filter.list.1"))
    }
}

/** 与测试评论同包，让安装器走真实的 Java Moss 响应观察路径。 */
class ReplyMoss {
    fun executeMainList(response: CommentFishRootEchoHookTest.EchoMainList) = response
}
