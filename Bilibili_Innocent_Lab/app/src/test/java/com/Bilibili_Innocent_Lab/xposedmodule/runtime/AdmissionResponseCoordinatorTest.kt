package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionResponseCoordinatorTest {
    @Test
    fun `unhandled delivery only ends stage with validated fallback`() {
        val coordinator = AdmissionResponseCoordinator()
        val prepare = coordinator.begin("prepare")
        assertFalse(coordinator.completeUnhandled("prepare", false))
        assertFalse(prepare.response.isDone)
        assertTrue(coordinator.completeUnhandled("prepare", true))
        assertTrue(prepare.response.isDone)
        coordinator.clear(prepare)
        val confirm = coordinator.begin("confirm")
        assertFalse(coordinator.completeUnhandled("prepare", true))
        assertFalse(confirm.response.isDone)
    }

    @Test
    fun `confirm response cannot consume a completed prepare future`() {
        val coordinator = AdmissionResponseCoordinator()
        val prepare = coordinator.begin("prepare")
        val prepareResponse = Bundle()

        assertTrue(coordinator.complete("prepare", prepareResponse))
        assertSame(prepareResponse, prepare.response.get(1, TimeUnit.SECONDS))

        coordinator.clear(prepare)
        val confirm = coordinator.begin("confirm")
        assertFalse(coordinator.complete("prepare", Bundle()))
        val confirmResponse = Bundle()
        assertTrue(coordinator.complete("confirm", confirmResponse))
        assertSame(confirmResponse, confirm.response.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun `empty transport reply does not finish active stage`() {
        val coordinator = AdmissionResponseCoordinator()
        val awaiter = coordinator.begin("prepare")

        assertFalse(coordinator.complete("prepare", null))
        assertFalse(awaiter.response.isDone)
        val response = Bundle()
        assertTrue(coordinator.complete("prepare", response))
        assertSame(response, awaiter.response.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun `stale action cannot complete a newer stage`() {
        val coordinator = AdmissionResponseCoordinator()
        val prepare = coordinator.begin("prepare")
        coordinator.clear(prepare)
        val confirm = coordinator.begin("confirm")

        assertFalse(coordinator.complete("prepare", Bundle()))
        assertFalse(confirm.response.isDone)
    }

    @Test
    fun `malformed callback payload is isolated from the waiting stage`() {
        val coordinator = AdmissionResponseCoordinator()
        val awaiter = coordinator.begin("prepare")

        assertFalse(coordinator.completeSafely("prepare") { error("malformed bundle") })
        assertFalse(awaiter.response.isDone)
    }
}
