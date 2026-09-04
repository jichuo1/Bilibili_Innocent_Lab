package com.bapis.bilibili.app.distribution.setting.play

import com.bapis.bilibili.app.distribution.BoolValue

/** Unit-test host stub for the two stable protobuf getters. */
open class PlayConfig {
    open fun getLandscapeAutoStory(): BoolValue = BoolValue.getDefaultInstance()
    open fun getShouldAutoStory(): BoolValue = BoolValue.getDefaultInstance()
}
