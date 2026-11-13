package app.forge

import app.forge.ui.restClockText
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.2: the one rest clock shared by the pill, the set editor and the lock-screen editor. */
class RestClockTest {
    @Test fun countsDownAsMinutesAndSeconds() {
        assertEquals("1:30", restClockText(90))
        assertEquals("0:05", restClockText(5))
        assertEquals("0:00", restClockText(0))
    }

    @Test fun runsPastZeroWithAMinus() {
        assertEquals("−0:01", restClockText(-1))
        assertEquals("−0:12", restClockText(-12))
        assertEquals("−10:00", restClockText(-600))
    }
}
