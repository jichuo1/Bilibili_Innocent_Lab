@file:Suppress("ClassName", "unused", "UNUSED_PARAMETER")

package playbackfixture.legacy

import playbackfixture.Accounts
import playbackfixture.Playable

/** 8.84-8.96 shape: named service, t(state, long) seal plus an unrelated o(long). */
class TheseusHistoryService(private val g: Playable, private val c: Accounts) {
    class b
    fun t(state: b, time: Long) {}
    fun o(time: Long) {}
}
