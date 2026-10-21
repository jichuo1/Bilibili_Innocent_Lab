package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

internal enum class HostChromeLayer { SURFACE, BOTTOM_OUTER, BOTTOM_ROW }

/** A bounded caller check only inside one verified bottom-content invocation, never global Rows. */
internal class HostChromeRowSelection(private val owner: String, private val method: String) {
    var selected = false
        private set

    fun select(policyName: String, frames: () -> Array<StackTraceElement>): Boolean {
        if (selected) return false
        selected = directCaller(frames(), policyName, owner, method)
        return selected
    }

    companion object {
        internal fun directCaller(frames: Array<StackTraceElement>, policyName: String, owner: String, method: String): Boolean {
            val bound = minOf(frames.size, 48)
            var index = 0
            // LSPosed may generate a random owner while retaining the hooked method name.
            // Ignore everything before that precise method anchor; do not guess framework packages.
            while (index < bound && frames[index].methodName != policyName) index++
            if (index == bound) return false
            do { index++ } while (index < bound && frames[index].methodName == policyName)
            return index < bound && frames[index].className == owner && frames[index].methodName == method
        }
    }
}
