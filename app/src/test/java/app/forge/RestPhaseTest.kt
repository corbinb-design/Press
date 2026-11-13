package app.forge

import app.forge.data.*
import org.junit.Assert.assertEquals
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
}
