import com.bilibili.lib.media.resource.MediaResource
import com.bilibili.player.tangram.basic.StoryPreferenceFixture

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
