package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentTopologyBindingPolicyTest {

    private class BindingWithRoot {
        @Suppress("unused")
        fun getRoot(): View = throw UnsupportedOperationException()
    }

    private class NotABinding

    private class OtherBindingWithRoot {
        @Suppress("unused")
        fun getRoot(): View = throw UnsupportedOperationException()
    }

    private class MultiBranchHandler {
        @Suppress("UNUSED_PARAMETER")
        fun b(binding: BindingWithRoot, expanded: Boolean) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun c(binding: BindingWithRoot) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun ordinary(value: NotABinding) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun other(binding: OtherBindingWithRoot) = Unit

        @Suppress("UNUSED_PARAMETER")
        fun helper(binding: BindingWithRoot): String = "helper"

        companion object {
            @JvmStatic
            @Suppress("UNUSED_PARAMETER")
            fun h(binding: BindingWithRoot) = Unit
        }
    }

    @Test
    fun secondaryBindingsNeverMutateTheRootAnchor() {
        assertEquals(
            ReplyTopologyBindingAction.IGNORE,
            replyTopologyBindingAction(ReplyTopologyBindingScope.SECONDARY, hasSeed = false)
        )
        assertEquals(
            ReplyTopologyBindingAction.IGNORE,
            replyTopologyBindingAction(ReplyTopologyBindingScope.SECONDARY, hasSeed = true)
        )
    }

    @Test
    fun onlyTrustedRootBindingsMayClearWhileUnknownBindingsOnlyRetry() {
        assertEquals(
            ReplyTopologyBindingAction.CLEAR_AND_RETRY,
            replyTopologyBindingAction(ReplyTopologyBindingScope.OWNER, hasSeed = false)
        )
        assertEquals(
            ReplyTopologyBindingAction.CLEAR_AND_RETRY,
            replyTopologyBindingAction(ReplyTopologyBindingScope.PRIMARY, hasSeed = false)
        )
        assertEquals(
            ReplyTopologyBindingAction.RETRY_ONLY,
            replyTopologyBindingAction(ReplyTopologyBindingScope.UNKNOWN, hasSeed = false)
        )
    }

    @Test
    fun visibleMessageSelectsPrimaryOrSecondaryWhenBothViewsExist() {
        assertEquals(
            ReplyTopologyBindingScope.PRIMARY,
            replyTopologyMessageScope(
                primaryPresent = true,
                primaryVisible = true,
                secondaryPresent = true,
                secondaryVisible = false
            )
        )
        assertEquals(
            ReplyTopologyBindingScope.SECONDARY,
            replyTopologyMessageScope(
                primaryPresent = true,
                primaryVisible = false,
                secondaryPresent = true,
                secondaryVisible = true
            )
        )
        assertEquals(
            ReplyTopologyBindingScope.UNKNOWN,
            replyTopologyMessageScope(
                primaryPresent = true,
                primaryVisible = false,
                secondaryPresent = true,
                secondaryVisible = false
            )
        )
    }

    @Test
    fun aResolvedRootSeedCanBindFromEveryNonSecondaryScope() {
        listOf(
            ReplyTopologyBindingScope.OWNER,
            ReplyTopologyBindingScope.PRIMARY
        ).forEach { scope ->
            assertEquals(
                ReplyTopologyBindingAction.SHOW,
                replyTopologyBindingAction(scope, hasSeed = true)
            )
        }
        assertEquals(
            ReplyTopologyBindingAction.RETRY_ONLY,
            replyTopologyBindingAction(
                ReplyTopologyBindingScope.UNKNOWN,
                hasSeed = true
            )
        )
    }

    @Test
    fun highHandlerExpansionKeepsEveryInstanceBindingBranchOnlyOnce() {
        val cached = VersionAdapter.HookPoint(
            className = MultiBranchHandler::class.java.name,
            methodName = "b",
            paramClassNames = listOf(
                BindingWithRoot::class.java.name,
                java.lang.Boolean.TYPE.name
            )
        )

        val points = collectReplyTopologyHighBindPoints(
            MultiBranchHandler::class.java,
            cached
        )

        assertEquals(
            setOf(
                "b(${BindingWithRoot::class.java.name},boolean)",
                "c(${BindingWithRoot::class.java.name})"
            ),
            points.map { point ->
                "${point.methodName}(${point.paramClassNames.orEmpty().joinToString(",")})"
            }.toSet()
        )
        assertEquals(2, points.size)
    }
}
