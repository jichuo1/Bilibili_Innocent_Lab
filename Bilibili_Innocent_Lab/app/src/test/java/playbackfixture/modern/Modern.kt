@file:Suppress("ClassName", "unused", "UNUSED_PARAMETER")

package playbackfixture.modern

import playbackfixture.Accounts
import playbackfixture.Playable

/** 8.97+ shape: renamed owner `b`, single void(long) seal, lambdas keep the old service name. */
class b(private val accounts: Accounts, private val playable: Playable, private val state: a?) {
    class a(val duration: Long, val position: Long)
    fun a(time: Long) {}
    fun c(other: Any): Any = other
}

class `TheseusHistoryService$run$2`(@JvmField val owner: b, @JvmField val label: Int)
