package com.Bilibili_Innocent_Lab.xposedmodule.ui.release

/** Baseline is not a claim that the user read or consented to anything. */
internal data class ReleaseHighlightsState(val baseline: Int, val presented: Int) {
    val handled: Int get() = maxOf(baseline, presented)
}

internal object ReleaseHighlightsPolicy {
    fun entriesAfter(batches: List<ReleaseHighlightsBatch>, revision: Int, automatic: Boolean = false): List<ReleaseHighlight> =
        batches.filter { it.revision > revision && (!automatic || it.automatic) }
            .sortedByDescending { it.revision }.flatMap { it.entries }.distinctBy { it.id }
    fun bootstrap(current: Int, upgraded: Boolean): ReleaseHighlightsState {
        require(current > 0)
        return ReleaseHighlightsState(if (upgraded) current - 1 else current, 0)
    }
    fun pendingFrom(state: ReleaseHighlightsState, current: Int): Int? =
        state.handled.takeIf { it < current }
    fun presented(state: ReleaseHighlightsState, current: Int): ReleaseHighlightsState =
        state.copy(presented = maxOf(state.presented, current))
    fun skipAutomatic(state: ReleaseHighlightsState, current: Int): ReleaseHighlightsState =
        state.copy(baseline = maxOf(state.baseline, current))
    fun upgraded(firstInstall: Long?, lastUpdate: Long?): Boolean =
        firstInstall != null && firstInstall > 0 && lastUpdate != null && lastUpdate > firstInstall
    fun canShow(resumed: Boolean, authorized: Boolean, ready: Boolean, focused: Boolean,
        modalShowing: Boolean, requiredPromptPending: Boolean, updateRequestPending: Boolean = false): Boolean =
        resumed && authorized && ready && focused && !modalShowing && !requiredPromptPending && !updateRequestPending
}
