package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class StoryPressScopeTest {
    @Test fun nestedPressesRestoreTheirPreviousValueAndDoNotCrossThreads() {
        val scope = StoryPressScope<String>()
        assertNull(scope.current())
        val first = scope.enter("first")
        val second = scope.enter("second")
        assertEquals("second", scope.current())
        val otherThreadValue = AtomicReference<String?>("unread")
        Thread { otherThreadValue.set(scope.current()) }.apply { start(); join() }
        assertNull(otherThreadValue.get())
        scope.exit(second)
        assertEquals("first", scope.current())
        scope.exit(first)
        assertNull(scope.current())
    }
}
