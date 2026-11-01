package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** The demo data is used for portfolio screenshots, so it has to look like a real person's log. */
class DemoTest {
    private val today = LocalDate.of(2026, 10, 15) // a Thursday
    private val exercises = Library.exercises.associateBy { it.id }
    private val demo = Demo.build(Library.defaultTemplates, { exercises.getValue(it) }, today)
    private val h = demo.history

    @Test fun aboutOneSessionInFourSetsARecord() {
        val prs = Stats.prsBySession(h)
        val rate = prs.values.count { it > 0 }.toDouble() / h.size
        assertTrue("record rate was $rate", rate in 0.18..0.32)
    }

    @Test fun weightsOnlyGoUpInSensibleSteps() {
        val bench = Stats.e1rmSeries(h, "bench").map { it.second }
        assertTrue(bench.last() > bench.first())
        val loads = h.sortedBy { it.startedAt }.mapNotNull { w -> w.exercises.firstOrNull { it.exerciseId == "bench" }?.sets?.first()?.weightKg }
        loads.zipWithNext().forEach { (a, b) -> assertTrue("bench went $a → $b", b - a in 0.0..2.5) }
    }

    @Test fun aFewWeeksBelowGoalAndOnePausedWeek() {
        val monday = Weeks.monday(today)
        val perWeek = (12 downTo 1).map { Weeks.sessionsIn(h, monday.minusWeeks(it.toLong())).size }
        assertEquals(Demo.weekPattern, perWeek)
        assertEquals(2, perWeek.count { it in 1..3 })
        assertEquals(Weeks.key(monday.minusWeeks(3)), demo.pausedWeek)
    }

    @Test fun streakRebuildsToNineWeeksWithTwoFreezesUsed() {
        val s = Streaks.evaluate(StreakState(pausedWeeks = listOf(demo.pausedWeek)), h, 4, today)
        assertEquals(9, s.weeks)
        assertEquals(9, s.bestStreakWeeks)
        assertEquals(0, s.freezes)
        assertNull(s.repair)
        assertNull(s.notice)
    }

    @Test fun thisWeekIsPartlyDone() {
        assertEquals(2, Weeks.sessionsIn(h, Weeks.monday(today)).size) // Monday and Tuesday before a Thursday
    }
}
