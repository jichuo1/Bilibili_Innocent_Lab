package com.Bilibili_Innocent_Lab.xposedmodule.hook.modern

import io.github.jichuo1.meguviz.hook.api.ErrorPolicy
import io.github.jichuo1.meguviz.hook.api.HookId
import io.github.jichuo1.meguviz.hook.core.HookEnvironment
import io.github.jichuo1.meguviz.hook.testing.ContractOracle
import io.github.jichuo1.meguviz.hook.testing.FakeBackend
import org.junit.Assert.*
import org.junit.Test

class ModernBorrowedArgumentTest {
    private class Player {
        @Suppress("UNUSED_PARAMETER") fun setOutputSurface(surface: Any) = Unit
    }

    @Test fun rewrittenSurfaceBorrowIsReturnedOnceEvenWhenTheOriginalSetterThrows() {
        val target = Player::class.java.getDeclaredMethod("setOutputSurface", Any::class.java)
        val player = Player()
        val display = Any()
        val input = Any()
        val failure = IllegalArgumentException("invalid surface")
        for (throws in listOf(false, true)) {
            val backend = FakeBackend.create()
            backend.onTarget(target) { owner, arguments ->
                assertSame(player, owner)
                assertSame(input, arguments.single())
                if (throws) throw failure
                null
            }
            var releases = 0
            val setter = ModernMemberHookCreator(target).apply {
                before {
                    assertSame(display, argOrNull(0))
                    setObjectExtra("borrow", input)
                    args[0] = input
                }
                after {
                    assertSame(input, getObjectExtra("borrow"))
                    assertEquals(throws, hasThrowable)
                    releases++
                }
            }
            HookEnvironment.create(backend).use { environment ->
                val receipt = environment.install(setter.toSpec(HookId.of("bil.surface.repeat"), ErrorPolicy.PROPAGATE))
                assertTrue(receipt.message(), receipt.isInstalled)
                val observed = runCatching { backend.call(target, player, display) }.exceptionOrNull()
                val expected = if (throws) ContractOracle.originalThrows() else ContractOracle.originalReturns()
                assertSame(if (throws) failure else null, observed)
                assertEquals(expected.originalCalls(), backend.originalCallCount(target))
                assertEquals(if (expected.afterCallbacksRan()) 1 else 0, releases)
            }
        }
    }
}
