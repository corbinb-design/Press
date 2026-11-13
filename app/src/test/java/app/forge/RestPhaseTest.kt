package app.forge

import app.forge.data.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Step 2 of docs/rest-design.md: the three rest states are derived from the clock, never stored. */
class RestPhaseTest {
    private val now = 1_000_000L
    private fun w(ends: Long?) = Workout(id = 1, name = "Push", startedAt = 0, exercises = emptyList(), restEndsAt = ends)

    @Test fun noRestIsWorking() {
        assertEquals(RestPhase.WORKING, Session.rest(w(null), now))
        assertEquals(0, Session.signedSecondsLeft(w(null), now))
    }

    @Test fun anEndInTheFutureIsResting() {
        assertEquals(RestPhase.RESTING, Session.rest(w(now + 90_000), now))
        assertEquals(90, Session.signedSecondsLeft(w(now + 90_000), now))
    }

    @Test fun anEndExactlyNowIsOver() {
        assertEquals(RestPhase.OVER, Session.rest(w(now), now))
        assertEquals(0, Session.signedSecondsLeft(w(now), now))
    }

    @Test fun anEndInThePastIsOverAndCountsOn() {
        assertEquals(RestPhase.OVER, Session.rest(w(now - 12_000), now))
        assertEquals(-12, Session.signedSecondsLeft(w(now - 12_000), now))
    }

    @Test fun partialSecondsRoundAwayFromZero() {
        assertEquals(1, Session.signedSecondsLeft(w(now + 1), now)) // 0:01 left, not 0:00
        assertEquals(1, Session.signedSecondsLeft(w(now + 1_000), now))
        assertEquals(2, Session.signedSecondsLeft(w(now + 1_001), now))
        assertEquals(-1, Session.signedSecondsLeft(w(now - 1), now)) // -0:01, not -0:00
        assertEquals(-1, Session.signedSecondsLeft(w(now - 1_000), now))
        assertEquals(-2, Session.signedSecondsLeft(w(now - 1_001), now))
    }

    private fun d(ends: Long?, total: Int = 90) = AppData(active = w(ends).copy(restTotalSec = total))
    private fun AppData.ends() = active!!.restEndsAt

    @Test fun t5And6PlusAndMinusMoveTheEndWhileResting() {
        assertEquals(now + 105_000, Session.adjustRest(d(now + 90_000), 15, now).ends())
        assertEquals(105, Session.adjustRest(d(now + 90_000), 15, now).active!!.restTotalSec)
        assertEquals(now + 75_000, Session.adjustRest(d(now + 90_000), -15, now).ends())
    }

    @Test fun t7MinusFifteenPastNowMakesItOverInsteadOfClearing() {
        val after = Session.adjustRest(d(now + 10_000), -15, now)
        assertEquals(now - 5_000, after.ends())
        assertEquals(RestPhase.OVER, Session.rest(after.active!!, now))
        assertEquals(-5, Session.signedSecondsLeft(after.active!!, now))
    }

    @Test fun t13PlusFifteenFromOverStartsFifteenMoreFromNow() {
        val after = Session.adjustRest(d(now - 40_000), 15, now)
        assertEquals(now + 15_000, after.ends())
        assertEquals(15, after.active!!.restTotalSec)
        assertEquals(RestPhase.RESTING, Session.rest(after.active!!, now))
        // exactly at the end counts as Over too
        assertEquals(now + 15_000, Session.adjustRest(d(now), 15, now).ends())
    }

    @Test fun minusFifteenWhileOverAndAdjustingWithNoRestChangeNothing() {
        val over = d(now - 5_000)
        assertSame(over, Session.adjustRest(over, -15, now))
        val none = d(null)
        assertSame(none, Session.adjustRest(none, 15, now))
        assertSame(none, Session.adjustRest(none, -15, now))
    }
}
