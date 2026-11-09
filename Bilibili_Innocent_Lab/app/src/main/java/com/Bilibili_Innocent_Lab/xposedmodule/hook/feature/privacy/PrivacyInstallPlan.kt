package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

/**
 * All-or-nothing install plan for a privacy sub-feature.
 *
 * A sub-feature must resolve every REQUIRED host anchor BEFORE it registers any hook, so a host
 * build missing one structure skips the whole sub-feature cleanly instead of leaving a partial,
 * possibly-leaking boundary half-installed. Callers resolve anchors, enqueue the registrations as
 * deferred steps with [step], and run them with [apply] only once the full preflight succeeded.
 *
 * This is the reusable form of the resolve-then-apply pattern used by the incognito installer.
 */
internal class PrivacyInstallPlan {
    private val steps = mutableListOf<() -> Unit>()

    /** Number of enqueued registration steps (each is one hook registration). */
    val size: Int get() = steps.size

    /** Enqueue a registration to run during [apply]; resolution must already have succeeded. */
    fun step(block: () -> Unit) {
        steps += block
    }

    /** Run every enqueued registration in order and return how many were applied. */
    fun apply(): Int {
        steps.forEach { it() }
        return steps.size
    }
}
