package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/** Process-lifetime state only; no Context/View, persisted cooldown or wall clock. */
internal class ColdStartUpdateState {
    data class Notice(
        val channel: GitHubReleaseChecker.UpdateChannel,
        val release: GitHubReleaseChecker.ReleaseInfo
    )

    private var foregroundOwner: Long? = null
    private var foregroundSince: Long = 0
    private var automaticClaimed = false
    private var requestSequence = 0L
    private var notice: Notice? = null

    @Synchronized fun resume(owner: Long, elapsedMs: Long) {
        if (foregroundOwner == owner) return
        foregroundOwner = owner
        foregroundSince = elapsedMs
    }

    @Synchronized fun pause(owner: Long) {
        if (foregroundOwner == owner) foregroundOwner = null
    }

    @Synchronized fun remainingMs(owner: Long, elapsedMs: Long): Long? {
        if (automaticClaimed || foregroundOwner != owner) return null
        return (DWELL_MS - (elapsedMs - foregroundSince).coerceAtLeast(0)).coerceAtLeast(0)
    }

    @Synchronized fun claimAutomatic(owner: Long, elapsedMs: Long): Boolean {
        if (remainingMs(owner, elapsedMs) != 0L) return false
        automaticClaimed = true
        return true
    }

    @Synchronized fun beginRequest(): Long = ++requestSequence
    @Synchronized fun isCurrentRequest(sequence: Long): Boolean = sequence == requestSequence

    @Synchronized fun accept(
        sequence: Long,
        channel: GitHubReleaseChecker.UpdateChannel,
        selectedChannel: GitHubReleaseChecker.UpdateChannel,
        release: GitHubReleaseChecker.ReleaseInfo,
        installedVersion: String
    ): Boolean {
        if (!isCurrentRequest(sequence) || channel != selectedChannel) return false
        notice = if (GitHubReleaseChecker.compareVersions(release.tagName, installedVersion) ==
            GitHubReleaseChecker.VersionRelation.REMOTE_NEWER) Notice(channel, release) else null
        return true
    }

    @Synchronized fun noticeFor(channel: GitHubReleaseChecker.UpdateChannel): Notice? =
        notice?.takeIf { it.channel == channel }

    @Synchronized fun channelChanged() {
        notice = null
    }

    companion object { const val DWELL_MS = 10_000L }
}

/** Weak foreground observer lets a recreated Activity receive an in-flight result. */
internal object ColdStartUpdateSession {
    val state = ColdStartUpdateState()
    private val owners = AtomicLong()
    private var observerOwner: Long? = null
    private var observer: WeakReference<() -> Unit>? = null

    fun newOwner(): Long = owners.incrementAndGet()
    fun observe(owner: Long, callback: () -> Unit) {
        observerOwner = owner
        observer = WeakReference(callback)
    }
    fun stopObserving(owner: Long) {
        if (observerOwner == owner) {
            observer = null
            observerOwner = null
        }
    }
    /** Called on main only, after completing a network request. */
    fun notifyChanged() { observer?.get()?.invoke() }
}
