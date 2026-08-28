package com.Bilibili_Innocent_Lab.xposedmodule.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentTopologyPointsTest {

    @Test
    fun jsonRoundTripPreservesMapperMossAndMethodPoints() {
        val methods = VersionAdapter.CommentTopologyPoints.REQUIRED_METHOD_KEYS
            .mapIndexed { index, key ->
                key to VersionAdapter.HookPoint(
                    className = "topology.Owner$index",
                    methodName = "method$index",
                    paramClassNames = if (index % 2 == 0) listOf("long", "java.lang.String") else null,
                    viewField = if (index == 0) "verifiedField" else null
                )
            }
            .toMap(linkedMapOf())
        val original = VersionAdapter.CommentTopologyPoints(
            mapperMethods = listOf(
                VersionAdapter.HookPoint(
                    className = "topology.Mapper",
                    methodName = "map",
                    paramClassNames = listOf("topology.ReplyInfo"),
                    viewField = "commentItem"
                ),
                VersionAdapter.HookPoint(
                    className = "topology.LegacyMapper",
                    methodName = "mapLegacy",
                    paramClassNames = listOf("topology.ReplyInfo", "boolean")
                )
            ),
            replyMossClassName = "topology.ReplyMoss",
            methods = methods
        )

        val restored = VersionAdapter.CommentTopologyPoints.fromJson(original.toJson())

        assertEquals(original, restored)
        assertEquals(methods.keys, restored.methods.keys)
        assertTrue(restored.hasRequiredMethods())
    }

    @Test
    fun requiredMethodValidationRejectsAnyMissingKeyAndAllowsExtraKeys() {
        val requiredMethods = VersionAdapter.CommentTopologyPoints.REQUIRED_METHOD_KEYS
            .associateWithTo(linkedMapOf()) { key ->
                VersionAdapter.HookPoint("topology.$key", "read")
            }
        val points = VersionAdapter.CommentTopologyPoints(
            mapperMethods = listOf(VersionAdapter.HookPoint("topology.Mapper", "map")),
            replyMossClassName = "topology.ReplyMoss",
            methods = requiredMethods
        )

        assertTrue(points.hasRequiredMethods())

        assertFalse(points.copy(mapperMethods = emptyList()).hasRequiredMethods())
        assertFalse(
            points.copy(
                mapperMethods = List(
                    VersionAdapter.CommentTopologyPoints.MAX_MAPPER_METHODS + 1
                ) { index -> VersionAdapter.HookPoint("topology.Mapper$index", "map") }
            ).hasRequiredMethods()
        )

        VersionAdapter.CommentTopologyPoints.REQUIRED_METHOD_KEYS.forEach { missingKey ->
            assertFalse(
                "missing required method key must invalidate points: $missingKey",
                points.copy(methods = requiredMethods - missingKey).hasRequiredMethods()
            )
        }

        assertTrue(
            points.copy(
                methods = requiredMethods +
                    ("future.optional" to VersionAdapter.HookPoint("topology.Optional", "read"))
            ).hasRequiredMethods()
        )
    }
}
