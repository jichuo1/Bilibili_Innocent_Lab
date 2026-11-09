@file:Suppress("ClassName", "unused", "UNUSED_PARAMETER")

package playbackfixture.ambiguous

import playbackfixture.Accounts
import playbackfixture.Playable

/** Two void(long) methods: the seal cannot be told apart, so the locator must return null. */
class b(private val accounts: Accounts, private val playable: Playable) {
    fun a(time: Long) {}
    fun d(time: Long) {}
}

class `TheseusHistoryService$run$2`(@JvmField val owner: b)
