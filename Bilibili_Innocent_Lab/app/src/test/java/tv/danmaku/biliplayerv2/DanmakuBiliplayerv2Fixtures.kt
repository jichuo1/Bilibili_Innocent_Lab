package tv.danmaku.biliplayerv2

import tv.danmaku.biliplayerv2.service.IPlayerCoreService

// From SharedPlayerFixture.kt
class PlayerSharingBundle

class PlayerParamsV2 {
    var sharingBundle: PlayerSharingBundle? = null
}

// From SponsorContainerFixture.kt
interface IPlayerContainer {
    fun getPlayerCoreService(): IPlayerCoreService

    class Builder {
        @JvmField val parameters = PlayerParamsV2()
        fun build(): IPlayerContainer = object : IPlayerContainer {
            override fun getPlayerCoreService(): IPlayerCoreService = error("unused fixture core")
        }
    }
}

abstract class PlayerContainer : IPlayerContainer
