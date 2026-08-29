package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyThreadKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyTopologyLocateRouteTest {

    private val key = ReplyTopologyThreadKey(
        oid = 456L,
        type = 1L,
        rootRpid = 789L
    )

    @Test
    fun childRouteUsesPublicDetailOrderAndAnchor() {
        assertEquals(
            "bilibili://comment/detail/1/456/789?anchor=987",
            buildReplyTopologyLocateRoute(key, 987L)
        )
    }

    @Test
    fun rootRouteOmitsAnchor() {
        assertEquals(
            "bilibili://comment/detail/1/456/789",
            buildReplyTopologyLocateRoute(key, 789L)
        )
    }

    @Test
    fun invalidIdentityDoesNotProduceARoute() {
        assertNull(buildReplyTopologyLocateRoute(key, 0L))
        assertNull(
            buildReplyTopologyLocateRoute(
                ReplyTopologyThreadKey(oid = 0L, type = 1L, rootRpid = 789L),
                987L
            )
        )
    }
}
