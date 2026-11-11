import com.bilibili.lib.media.resource.MediaResource

class FlatPlayerSpeedOption(@JvmField val speed: Float, @JvmField val available: Boolean)

object FlatPlayerSpeedFactory {
    @JvmField val defaults = listOf(0.5f, 1.0f, 1.5f, 2.0f)

    @JvmStatic fun options(resource: MediaResource, quality: Int?, type: Int): List<FlatPlayerSpeedOption> =
        defaults.map { FlatPlayerSpeedOption(it, true) }
}
