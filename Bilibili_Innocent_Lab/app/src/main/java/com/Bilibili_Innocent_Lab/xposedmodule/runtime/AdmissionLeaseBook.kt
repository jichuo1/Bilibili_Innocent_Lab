package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity

/** 有界、一次性、进程绑定的准备/确认状态；已经授予的进程不冒充可被原地卸载。 */
internal class AdmissionLeaseBook(private val clock: () -> Long) {
    data class Lease(val uid: Int, val pid: Int, val nonce: String, val challenge: String,
        val source: String, val identity: PublicationIdentity, val deadline: Long)
    private val pending = linkedMapOf<String, Lease>()
    @Synchronized fun prepare(lease: Lease): Boolean {
        pending.entries.removeAll { it.value.deadline < clock() }
        if (lease.uid < 0 || lease.pid <= 0 || lease.deadline < clock() || pending.size >= 64 || lease.challenge in pending) return false
        pending[lease.challenge] = lease
        return true
    }
    @Synchronized fun consume(uid: Int, pid: Int, nonce: String, challenge: String, identity: PublicationIdentity): Lease? {
        val lease = pending[challenge] ?: return null
        if (lease.uid != uid || lease.pid != pid || lease.nonce != nonce) return null
        pending.remove(challenge)
        return lease.takeIf { it.deadline >= clock() && it.identity == identity }
    }
}
