import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView
import com.bilibili.adcommon.data.model.Dm
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.tencent.mm.opensdk.openapi.WeChatFixtureRuntime
import kntr.common.share.common.handler.wx.WeChatShareType
import kntr.srcs.app.splash.brand.model.BrandSplashMode
import kntr.srcs.app.splash.brand.model.BrandSplashSource

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

