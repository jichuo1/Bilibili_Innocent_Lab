package com.bilibili.player.tangram.basic

// From PlaySpeedManagerImpl.kt
/** Host-shaped fixture for the speed manager type used by PlayerSpeedSessionLocator tests. */
class PlaySpeedManagerImpl

// From StoryPreferenceFixture.kt
abstract class StoryPreferenceFixture {
    object Auto : StoryPreferenceFixture()
    class Fixed(val qn: Int) : StoryPreferenceFixture()
}
