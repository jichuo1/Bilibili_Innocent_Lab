package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyTopologySeedStoreTest {

    @Test
    fun identityMissFallsBackToMatchingCommentItemRpid() {
        val store = ReplyTopologySeedStore(maxRememberedRoots = 2)
        val mappedCommentItem = Any()
        val reboundCommentItem = Any()
        val seed = seed(rootRpid = 101L)
        store.put(mappedCommentItem, seed)

        assertEquals(seed, store.get(reboundCommentItem, commentItemId = 101L))
        assertNull(store.get(reboundCommentItem, commentItemId = 102L))
        assertNull(store.get(reboundCommentItem, commentItemId = null))
    }

    @Test
    fun rpidFallbackUsesAccessOrderAndEvictsOnlyTheEldestFallback() {
        val store = ReplyTopologySeedStore(maxRememberedRoots = 2)
        val firstCommentItem = Any()
        val secondCommentItem = Any()
        val first = seed(rootRpid = 201L)
        val second = seed(rootRpid = 202L)
        val third = seed(rootRpid = 203L)
        store.put(firstCommentItem, first)
        store.put(secondCommentItem, second)

        assertEquals(first, store.get(Any(), commentItemId = 201L))
        store.put(Any(), third)

        assertEquals(first, store.get(Any(), commentItemId = 201L))
        assertNull(store.get(Any(), commentItemId = 202L))
        assertEquals(third, store.get(Any(), commentItemId = 203L))

        // rpid 回退淘汰不应破坏仍存活 CommentItem 的弱身份命中。
        assertEquals(second, store.get(secondCommentItem, commentItemId = null))
    }

    @Test
    fun identityCacheIsBoundedAndUsesAccessOrder() {
        // rpid 容量为 1 时，弱身份容量固定为 2。
        val store = ReplyTopologySeedStore(maxRememberedRoots = 1)
        val firstCommentItem = Any()
        val secondCommentItem = Any()
        val thirdCommentItem = Any()
        val first = seed(rootRpid = 301L)
        val second = seed(rootRpid = 302L)
        val third = seed(rootRpid = 303L)
        store.put(firstCommentItem, first)
        store.put(secondCommentItem, second)

        // 读取 first 将它提升为最近使用项，随后新增 third 应淘汰 second。
        assertEquals(first, store.get(firstCommentItem, commentItemId = null))
        store.put(thirdCommentItem, third)

        assertEquals(first, store.get(firstCommentItem, commentItemId = null))
        assertNull(store.get(secondCommentItem, commentItemId = null))
        assertEquals(third, store.get(thirdCommentItem, commentItemId = null))
    }

    private fun seed(rootRpid: Long): ReplyTopologySeed {
        val key = ReplyTopologyThreadKey(
            oid = 10_000L + rootRpid,
            type = 1L,
            rootRpid = rootRpid
        )
        return ReplyTopologySeed(
            key = key,
            expectedReplyCount = 1,
            nodes = listOf(
                ReplyTopologyNodeSnapshot.fromRaw(
                    rpid = rootRpid,
                    rootRpid = 0L,
                    parentRpid = 0L,
                    authorName = "author-$rootRpid",
                    message = "message-$rootRpid"
                )
            )
        )
    }
}
