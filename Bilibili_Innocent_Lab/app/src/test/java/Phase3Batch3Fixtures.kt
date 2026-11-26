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
