package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesSourcePolicyTest {

    @Test
    fun `normalizes line endings and removes unsafe controls without flattening markdown`() {
        val result = ReleaseNotesSourcePolicy.prepare(
            "## Changes\r\n\r\n- **Fixed** item\u0000\u0007\r\n- `code`"
        )

        assertEquals("## Changes\n\n- **Fixed** item\n- `code`", result.markdown)
        assertFalse(result.truncated)
    }

    @Test
    fun `truncates at a complete line and closes an open code fence`() {
        val result = ReleaseNotesSourcePolicy.prepare(
            buildString {
                append("## Changes\n\n```kotlin\n")
                repeat(30) { append("println(\"line-$it\")\n") }
                append("```\n")
            },
            maxLength = 160
        )

        assertTrue(result.truncated)
        assertTrue(result.markdown.startsWith("## Changes\n\n```kotlin\n"))
        assertTrue(result.markdown.endsWith("```"))
    }

    @Test
    fun `does not split a unicode surrogate pair at the hard boundary`() {
        val prefix = "x".repeat(63)
        val result = ReleaseNotesSourcePolicy.prepare(
            prefix + "😀" + "tail".repeat(20),
            maxLength = 64
        )

        assertTrue(result.truncated)
        assertEquals(prefix, result.markdown)
    }
}
