import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.earlyload.RecyclerFixtures
import com.bilibili.adcommon.data.model.Dm
import com.bilibili.lib.media.resource.MediaResource
import com.bilibili.player.tangram.basic.StoryPreferenceFixture
import com.bilibili.ship.theseus.united.page.intro.module.relate.RelateCardType
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.tencent.mm.opensdk.openapi.WeChatFixtureRuntime
import kntr.common.share.common.handler.wx.WeChatShareType
import kntr.srcs.app.splash.brand.model.BrandSplashMode
import kntr.srcs.app.splash.brand.model.BrandSplashSource

// From RelocatedHomeEarlyLoadFixture.kt
internal class d571(private val a: w32, private val b: Int = 1, private val c: Boolean = true) :
    RecyclerFixtures.RecyclerView.OnScrollListener(), RecyclerFixtures.RecyclerView.OnChildAttachStateChangeListener {
    override fun onChildViewAttachedToWindow(view: View) = Unit
    override fun onChildViewDetachedFromWindow(view: View) = Unit
}

internal class w32 : RecyclerFixtures.Function0 {
    override fun invoke(): Any? = null
}

internal class zag1(private val a: op2, private val b: Int = 1, private val c: Boolean = true) :
    RecyclerFixtures.RecyclerView.OnScrollListener(), RecyclerFixtures.RecyclerView.OnChildAttachStateChangeListener {
    override fun onChildViewAttachedToWindow(view: View) = Unit
    override fun onChildViewDetachedFromWindow(view: View) = Unit
}

internal class op2 : RecyclerFixtures.Function0 {
    override fun invoke(): Any? = null
}

// From RelocatedRelatedFeedbackFixture.kt
internal class bjx1(@JvmField val a: RelateCardType, @JvmField val d: iox1)
internal class iox1(@JvmField val a: hkx1, @JvmField val b: hkx1)
internal class hkx1(@JvmField val f: List<wmx1>)
internal class wmx1(
    @JvmField val a: Long,
    @JvmField val b: Long,
    @JvmField val d: Long,
    @JvmField val c: Int,
    @JvmField val e: String
)
internal class UnrelatedRootFeedbackCard(@JvmField val a: RelateCardType, @JvmField val d: iox1)

// From FlatPlayerSpeedFixtures.kt
class FlatPlayerSpeedOption(@JvmField val speed: Float, @JvmField val available: Boolean)

object FlatPlayerSpeedFactory {
    @JvmField val defaults = listOf(0.5f, 1.0f, 1.5f, 2.0f)

    @JvmStatic fun options(resource: MediaResource, quality: Int?, type: Int): List<FlatPlayerSpeedOption> =
        defaults.map { FlatPlayerSpeedOption(it, true) }
}

class FlatSpeedMenuAdapter(@Suppress("UNUSED_PARAMETER") theme: Int) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>(), View.OnClickListener {
    class Item { @JvmField var speed = 1f; @JvmField var selected = false }
    @JvmField val items: List<Item> = arrayListOf()
    @JvmField val speeds = floatArrayOf(2f, 1f, 0.5f)
    fun a(@Suppress("UNUSED_PARAMETER") current: Float) = Unit
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = error("unused")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    override fun onClick(view: View?) = Unit
}

// From FlatStoryQualityFacade.kt
/** The 9.19 host moves the strategy owner to the root DEX package. */
object FlatStoryQualityFacade {
    @JvmStatic fun policy(): StoryPreferenceFixture = StoryPreferenceFixture.Auto
    @JvmStatic fun resource(value: MediaResource?) = Unit
}

object FlatStoryQualityWithInteger {
    @JvmStatic fun integerDefault(): Int = 80
    @JvmStatic fun policy(): StoryPreferenceFixture = StoryPreferenceFixture.Auto
    @JvmStatic fun resource(value: MediaResource?) = Unit
}

// From Phase3Batch2Fixtures.kt
/** Phase 3B Tier-A 测试用根包扁平化夹具（模拟 R8 repackageclasses 到默认包）。 */
class FlatPhase3QuickReplyCollector(
    @JvmField val owner: com.bilibili.app.comment3.ui.CommentContainerImpl? = null
) {
    fun emit(intent: com.bilibili.app.comment3.data.state.PublishDialogIntent, cont: Any?): Any = Unit
}

class FlatPhase3QuickReplyCollectorB(
    @JvmField val owner: com.bilibili.app.comment3.ui.CommentContainerImpl? = null
) {
    fun emit(intent: com.bilibili.app.comment3.data.state.PublishDialogIntent, cont: Any?): Any = Unit
}

class FlatPhase3UnrelatedCollector1 {
    fun emit(intent: com.bilibili.app.comment3.data.state.PublishDialogIntent, cont: Any?): Any = Unit
}

class FlatPhase3UnrelatedCollector2 {
    fun emit(intent: com.bilibili.app.comment3.data.state.PublishDialogIntent, cont: Any?): Any = Unit
}

class FlatPhase3BadQuickReplyCollector {
    fun emit(intent: BadPhase3QuickReplyIntent, cont: Any?): Any = Unit
}

class BadPhase3QuickReplyIntent(
    @JvmField val onlyOneBool: Boolean = true,
    @JvmField val pos: BadPos = BadPos.MAIN
) {
    enum class BadPos { MAIN }
}

class FlatPhase3BadPosMissingIntent(
    @JvmField val flag1: Boolean = false,
    @JvmField val flag2: Boolean = true
)

class FlatPhase3BadIntentCollector {
    fun emit(intent: FlatPhase3BadPosMissingIntent, cont: Any?): Any = Unit
}

class FlatPhase3HomeTabItem {
    @JvmField var id: String = "id"
    @JvmField var title: String = "title"
    @JvmField var uri: String = "bilibili://home"
    @JvmField var reporterId: String = "r"
}

class FlatPhase3HomeTabItem2 {
    @JvmField var a: String = "1"
    @JvmField var b: String = "2"
    @JvmField var c: String = "3"
}

class FlatPhase3WeakHomeTabItem {
    @JvmField var id: String = "1"
    @JvmField var title: String = "2"
}

class FlatPhase3ShuffledHomeTabItem {
    @JvmField var uri: String = "bilibili://home"
    @JvmField var reporterId: String = "r"
    @JvmField var id: String = "id"
    @JvmField var title: String = "title"
}

class FlatPhase3NoisyHomeDto {
    @JvmField var x: String = "1"
    @JvmField var y: String = "2"
    @JvmField var z: String = "3"
}

class FlatPhase3BrandModel(
    val source: BrandSplashSource,
    val id: Long,
    val url: String,
    val thumb: String,
    val thumbHash: String,
    val bg: String,
    val fg: String,
    val showLogo: Boolean,
    val mode: BrandSplashMode
)

class FlatPhase3BrandPageHolder {
    companion object {
        @Suppress("UNUSED_PARAMETER")
        @JvmStatic
        fun a(
            model: FlatPhase3BrandModel,
            onExit: () -> Unit,
            modifier: Any,
            onLogoClick: () -> Unit,
            composer: Any,
            flags: Int
        ) = Unit
    }
}

// From Phase3Batch3Fixtures.kt
/** Phase 3C Batch 3 Tier-B 适配器测试用根包扁平化夹具（默认包名；配合 Phase3Batch3AdaptersTest 类加载器使用）。 */
class FlatPhase3PlaylistDirector {
    fun switchToNext(@Suppress("UNUSED_PARAMETER") fromAutoPlay: Boolean) = Unit
    fun hasNext(@Suppress("UNUSED_PARAMETER") fromAutoPlay: Boolean): Boolean = true
}

class FlatPhase3PlaylistDirector2 {
    fun switchToNext(@Suppress("UNUSED_PARAMETER") fromAutoPlay: Boolean) = Unit
    fun hasNext(@Suppress("UNUSED_PARAMETER") fromAutoPlay: Boolean): Boolean = false
}

class FlatPhase3BadPlaylistDirector {
    fun switchToNext(@Suppress("UNUSED_PARAMETER") fromAutoPlay: Boolean) = Unit
}

class FlatPhase3FollowUpBodyTheseus2

class FlatPhase3FollowUpBodyTheseus2Dup

class FlatPhase3FollowUpBodyTheseus

class FlatPhase3BadFollowUpBody

class FlatPhase3RelateGameComponent

class FlatPhase3RelateGameComponent2

class FlatPhase3BadRelateGameComponent

class FlatPhase3StoryPlayer

class FlatPhase3StoryPlayer2

class FlatPhase3LongPressLaunch

class FlatPhase3LongPressLaunch2

class FlatPhase3LongPressPort

class FlatPhase3LongPressPort2

class FlatPhase3WrongReturnPort

// From Wave1FlatFixtures.kt
class FlatWave1Divider(@JvmField val context: Context) : RecyclerView.ItemDecoration() {
    @JvmField val first: Paint? = null
    @JvmField val second: Paint? = null
    @JvmField val rect: RectF? = null
    @JvmField val disabled: Boolean = false
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) = Unit
}

class FlatWave1EndPageMerger {
    fun b(first: List<String>, second: List<String>): List<String> = first + second
}

class FlatWave1EndPageService {
    @JvmField val customMerger: FlatWave1EndPageMerger = FlatWave1EndPageMerger()
}

class FlatWave1AdvertFloat {
    fun a(): List<Dm> = emptyList()
}

class FlatWave1WeChatWrapper(context: Context?, type: WeChatShareType) {
    @JvmField var api: IWXAPI? = null
    fun m(): Boolean = WeChatFixtureRuntime.call("kntr", this) {
        if (api == null) {
            api = WXAPIFactory.createWXAPI(null, "share", true)
            if (!api!!.isWXAppInstalled()) return@call false
            api!!.registerApp("share")
        }
        true
    } as Boolean
}

object FlatWave1BrandStorage {
    @JvmField var items: List<Any> = emptyList()
    @JvmField var customMode: Boolean = false
    @JvmStatic fun renamedRead(filter: Boolean): List<Any> = items
    @JvmStatic fun renamedWrite(next: List<Any>) { items = next }
    @JvmStatic fun renamedMode(enabled: Boolean) { customMode = enabled }
    @JvmStatic fun renamedPrefs(context: Context): SharedPreferences = context.getSharedPreferences("brand", Context.MODE_PRIVATE)
    @JvmStatic fun renamedLast(context: Context): String = "cursor"
}

open class Wave1TypedContractBase
interface Wave1FeatureFlagInterface

class FlatWave1CloudKeyContract : Wave1TypedContractBase(), Wave1FeatureFlagInterface {
    fun getWithDefault(key: String, default: Boolean): Boolean = default
}

class WrongWave1CloudKeyContract : Wave1TypedContractBase() {
    fun getWithDefault(key: String, default: Boolean): Boolean = default
}

class FlatWave1BrandModel(
    val source: BrandSplashSource,
    val id: Long,
    val url: String,
    val thumb: String,
    val thumbHash: String,
    val bg: String,
    val fg: String,
    val showLogo: Boolean,
    val mode: BrandSplashMode
)

class FlatWave1BrandDraw(@JvmField val model: FlatWave1BrandModel) {
    fun invoke(scope: Any?, composer: Any?, flags: Int): Any = Unit
}

class WrongWave1BrandDraw {
    fun invoke(scope: Any?, composer: Any?, flags: Int): Any = Unit
}
