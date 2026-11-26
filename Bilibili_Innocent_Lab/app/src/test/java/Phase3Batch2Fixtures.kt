import kntr.srcs.app.splash.brand.model.BrandSplashMode
import kntr.srcs.app.splash.brand.model.BrandSplashSource

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
            onExit: kotlin.jvm.functions.Function0<Unit>,
            modifier: Any,
            onLogoClick: kotlin.jvm.functions.Function0<Unit>,
            composer: Any,
            flags: Int
        ) = Unit
    }
}
