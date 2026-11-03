package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

/** Distinguishes unrelated calls from local calls that must never fall through to the network. */
internal class GuestFollowGroupCallGuard<T : Any>(
    private val bindings: GuestFollowGroupBindings<T>,
    private val valid: (T) -> Boolean
) {
    sealed interface Decision<out T : Any> {
        data object Unrelated : Decision<Nothing>
        data object Blocked : Decision<Nothing>
        data class Local<T : Any>(val binding: GuestFollowGroupBindings.Binding<T>) : Decision<T>
    }

    fun decide(call: Any): Decision<T> {
        return try {
            val tagged = bindings.tagged(call) ?: return Decision.Unrelated
            val binding = tagged.binding ?: return Decision.Blocked
            if (valid(binding.token)) Decision.Local(binding) else Decision.Blocked
        } catch (_: Exception) {
            // A failure to classify a possible local call cannot authorize a network request.
            Decision.Blocked
        }
    }
}
