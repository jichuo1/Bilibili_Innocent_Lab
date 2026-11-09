package kntr.base.neuron

/** Fixture with a subset of the 9.16.1 KNeuron producer surface plus non-producer members. */
@Suppress("UNUSED_PARAMETER")
class KNeuron {
    fun reportClick(force: Boolean, event: String, extras: Map<String, String>) = Unit
    fun trackT(force: Boolean, event: String, extras: Map<String, String>, condition: () -> Boolean) = Unit
    fun simpleTrackT(event: String, extras: Map<String, String>) = Unit
    fun onForeground() = Unit
    fun getInstalledKmpEngine(): Any? = null
}
