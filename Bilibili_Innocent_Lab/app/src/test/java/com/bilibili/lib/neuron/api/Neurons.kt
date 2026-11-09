package com.bilibili.lib.neuron.api

@Suppress("UNUSED_PARAMETER")
class Neurons {
    fun reportClick(event: String) = Unit
    fun trackT(event: String, condition: () -> Boolean) = Unit
    fun track(event: String) = Unit
    fun reportCountAfterLastFetch(): Pair<Int, Int> = 0 to 0
    fun initialize() = Unit
    fun getUUID(): String = "persistent-account-identity"
    fun setUUID(uuid: String) = Unit
}
