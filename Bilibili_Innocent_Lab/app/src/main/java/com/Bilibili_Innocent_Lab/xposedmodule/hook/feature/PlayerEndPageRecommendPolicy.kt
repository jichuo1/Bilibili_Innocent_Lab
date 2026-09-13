package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/** 副本清理与真实读回。自身读取时临时绕过后备 getter，不能拿被 Hook 的 0 证明清理成功。 */
internal class PlayerEndPageRecommendPolicy(private val access: PlayerEndPageRecommendLocator.Access) {
    data class Cleaned(val reply: Any, val removed: Int)
    private val originalReads = ThreadLocal<Boolean>()
    val transformingResponse: Boolean get() = originalReads.get() == true

    internal fun <T> withOriginalReads(action: () -> T): T {
        val previous = originalReads.get()
        originalReads.set(true)
        return try { action() } finally {
            if (previous == null) originalReads.remove() else originalReads.set(previous)
        }
    }

    fun clean(reply: Any?): Cleaned? = withOriginalReads {
        if (reply == null || !access.reply.isInstance(reply) || !access.canCopy) return@withOriginalReads null
        if (reply === access.defaultInstance!!.invoke(null)) return@withOriginalReads null
        val count = access.count!!.invoke(reply) as Int
        val cards = access.list!!.invoke(reply) as List<*>
        check(count >= 0 && count == cards.size) { "inconsistent-relates" }
        if (count == 0) return@withOriginalReads null
        val copy = access.plan!!.edit(reply) { access.clear!!.invoke(it) }
        check(access.count.invoke(copy) == 0 && (access.list.invoke(copy) as List<*>).isEmpty()) { "relates-readback-failed" }
        Cleaned(copy, count)
    }
}
