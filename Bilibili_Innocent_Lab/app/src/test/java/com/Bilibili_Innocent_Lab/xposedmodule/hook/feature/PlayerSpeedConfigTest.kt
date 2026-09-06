package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedConfigTest {
    @Test fun `range is bounded with follow host sentinel`() {
        assertNull(PlayerSpeedConfig.multiplier(0))
        assertEquals(0.25f, PlayerSpeedConfig.multiplier(25))
        assertEquals(4f, PlayerSpeedConfig.multiplier(400))
        listOf(-1, 1, 24, 401, Int.MAX_VALUE).forEach {
            assertEquals(0, PlayerSpeedConfig.normalize(it))
            assertNull(PlayerSpeedConfig.multiplier(it))
        }
    }
    @Test fun `decimal parsing never rounds invalid input into range`() {
        listOf("NaN", "Infinity", "", " ", "1.001", "0.249", "4.001", "-1", "1,25").forEach {
            assertNull(it, PlayerSpeedConfig.parseMultiplier(it))
        }
        assertEquals(125, PlayerSpeedConfig.parseMultiplier(" 1.25 "))
        assertEquals(25, PlayerSpeedConfig.parseMultiplier("0.25"))
        assertEquals(400, PlayerSpeedConfig.parseMultiplier("4.00"))
    }
    @Test fun `all supported values round trip without floating point drift`() {
        PlayerSpeedConfig.supportedPercents.forEach {
            assertEquals(it, PlayerSpeedConfig.parseMultiplier(PlayerSpeedConfig.formatMultiplier(it)))
        }
    }
    @Test fun `disable wins without changing saved custom speed`() {
        assertEquals(0, PlayerSpeedConfig.effectiveLongPressPercent(true, 275))
        assertEquals(275, PlayerSpeedConfig.effectiveLongPressPercent(false, 275))
    }
}
