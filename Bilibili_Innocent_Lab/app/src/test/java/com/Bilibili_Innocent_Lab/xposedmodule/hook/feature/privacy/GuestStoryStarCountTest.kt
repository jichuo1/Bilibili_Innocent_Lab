package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestStoryStarCountTest {
    @Test fun aWholeNumberCountMovesByOne() {
        assertEquals("9029", GuestStoryStar.nudged("9028", 1))
        assertEquals("9027", GuestStoryStar.nudged("9028", -1))
    }

    @Test fun surroundingWhitespaceIsIgnored() {
        assertEquals("10", GuestStoryStar.nudged("  9 ", 1))
    }

    @Test fun anAbbreviatedCountIsLeftAloneBecauseTheRoundingCannotBeGuessed() {
        assertNull(GuestStoryStar.nudged("1.2万", 1))
        assertNull(GuestStoryStar.nudged("3.9万", -1))
        assertNull(GuestStoryStar.nudged("1,234", 1))
    }

    @Test fun emptyOrMissingTextIsLeftAlone() {
        assertNull(GuestStoryStar.nudged(null, 1))
        assertNull(GuestStoryStar.nudged("", 1))
        assertNull(GuestStoryStar.nudged("   ", -1))
    }

    @Test fun aCountNeverGoesBelowZero() {
        assertEquals("0", GuestStoryStar.nudged("0", -1))
        assertEquals(0, GuestStoryStar.shifted(0, -1))
        assertEquals(0, GuestStoryStar.shifted(1, -5))
    }

    @Test fun theNumericCountMovesByTheDelta() {
        assertEquals(10, GuestStoryStar.shifted(9, 1))
        assertEquals(8, GuestStoryStar.shifted(9, -1))
    }

    @Test fun textThatLooksNumericButIsNotAPlainDigitRunIsNotTouched() {
        assertNull(GuestStoryStar.nudged("-5", 1))
        assertNull(GuestStoryStar.nudged("+5", 1))
        assertNull(GuestStoryStar.nudged("5.0", 1))
    }
}
