package app.forge

import app.forge.data.*
import app.forge.data.StreakNotice.Kind
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Every streak rule from section 02, on a calendar starting Monday 5 Jan 2026 (ISO week 2). */
class StreaksTest {
    private val goal = 4
    private val w0: LocalDate = LocalDate.of(2026, 1, 5) // a Monday

    private fun monday(i: Int) = w0.plusWeeks(i.toLong())
    private fun key(i: Int) = Weeks.key(monday(i))

    /** [perWeek][i] sessions in week i (Mon, Tue, … at noon). */
    private fun history(vararg perWeek: Int): List<Workout> = perWeek.flatMapIndexed { i, n ->
        (0 until n).map { d ->
            val at = monday(i).plusDays(d.toLong()).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            Workout(newId(), "S", null, at, at + 3_600_000, emptyList())
        }
    }

    /** Evaluates everything up to (not including) week [i], i.e. opening the app on that Monday. */
    /** An app already in use before week 0 (so not a first-load backfill). */
    private val running = StreakState(evaluatedThrough = Weeks.key(w0.minusWeeks(1)))

    private fun openOnWeek(i: Int, h: List<Workout>, s: StreakState = running) = Streaks.evaluate(s, h, goal, monday(i))

    // ---- Rule 1 -------------------------------------------------------------------------------

    @Test fun rule1_hittingTheGoalAddsAWeek() {
        val s = openOnWeek(3, history(4, 5, 4))
        assertEquals(3, s.weeks)
        assertEquals(3, s.bestStreakWeeks)
        assertNull(s.notice)
        assertEquals(key(2), s.evaluatedThrough)
    }

    @Test fun rule1_fourWeeksInARowEarnAFreezeAndTheBankCapsAtTwo() {
        assertEquals(0, openOnWeek(3, history(4, 4, 4)).freezes)
        assertEquals(1, openOnWeek(4, history(4, 4, 4, 4)).freezes)
        assertEquals(2, openOnWeek(8, history(*IntArray(8) { 4 })).freezes)
        assertEquals(2, openOnWeek(12, history(*IntArray(12) { 4 })).freezes) // not 3
    }

    @Test fun rule1_evaluatesEachWeekOnlyOnce() {
        val h = history(4, 4)
        val once = openOnWeek(2, h)
        assertEquals(once, Streaks.evaluate(once, h, goal, monday(2).plusDays(3)))
    }

    // ---- Rule 2 -------------------------------------------------------------------------------

    @Test fun rule2_aMissUsesABankedFreezeAndKeepsTheStreak() {
        val s = openOnWeek(6, history(4, 4, 4, 4, 4, 2))
        assertEquals(5, s.weeks) // kept, not incremented
        assertEquals(0, s.freezes)
        assertEquals(0, s.towardFreeze) // the run toward the next freeze starts again
        assertEquals(StreakNotice(Kind.FREEZE_USED, key(5), 2, goal), s.notice)
        assertNull(s.repair)
    }

    // ---- Rule 3 -------------------------------------------------------------------------------

    @Test fun rule3_aMissWithNoFreezeStartsARepairQuestForThisWeek() {
        val s = openOnWeek(3, history(4, 4, 1))
        assertEquals(2, s.weeks)
        assertEquals(RepairQuest(key(3), goal + 1, 2, 1), s.repair)
        assertEquals(Kind.REPAIR_OFFERED, s.notice?.kind)
        assertEquals(goal + 1, Streaks.target(s, goal, monday(3).plusDays(2)))
    }

    @Test fun rule3_completingTheQuestCountsTheMissedWeekToo() {
        val s = openOnWeek(4, history(4, 4, 1, 5))
        assertEquals(4, s.weeks) // 2 + missed week + quest week
        assertNull(s.repair)
        assertNull(s.notice)
    }

    @Test fun rule3_displayIncludesBothWeeksOnceTheQuestIsDoneMidWeek() {
        val h = history(4, 4, 1, 5)
        val s = openOnWeek(3, h)
        assertEquals(4, Streaks.display(s, h, goal, monday(3).plusDays(6)))
        assertEquals(2, Streaks.display(s, history(4, 4, 1, 3), goal, monday(3).plusDays(6)))
    }

    @Test fun rule3_acceptingOnlyClearsTheSheet() {
        val s = Streaks.acceptRepair(openOnWeek(3, history(4, 4, 1)))
        assertTrue(s.repair!!.accepted)
        assertNull(s.notice)
    }

    @Test fun noStreakToProtectMeansNoFreezeOrQuest() {
        val s = openOnWeek(2, history(2, 1))
        assertEquals(0, s.weeks)
        assertNull(s.repair)
        assertNull(s.notice)
    }

    // ---- Rule 4 -------------------------------------------------------------------------------

    @Test fun rule4_failedQuestResetsKeepsBestAndMakesNextWeekAComeback() {
        val s = openOnWeek(5, history(4, 4, 4, 1, 2))
        assertEquals(0, s.weeks)
        assertEquals(3, s.bestStreakWeeks)
        assertEquals(key(5), s.comebackWeek)
        assertEquals(Kind.FRESH_START, s.notice?.kind)
        assertEquals(2, Streaks.target(s, goal, monday(5)))
    }

    @Test fun rule4_comebackWeekNeedsTwoThenTheGoalReturns() {
        val after = openOnWeek(6, history(4, 4, 4, 1, 2, 2))
        assertEquals(1, after.weeks)
        assertNull(after.comebackWeek)
        assertEquals(goal, Streaks.target(after, goal, monday(6)))
    }

    @Test fun rule4_missedComebackWeekJustStaysAtZero() {
        val s = openOnWeek(6, history(4, 4, 4, 1, 2, 1))
        assertEquals(0, s.weeks)
        assertNull(s.comebackWeek)
        assertNull(s.repair)
    }

    @Test fun rule4_failedQuestThatStillMetTheGoalStartsAtOne() {
        val s = openOnWeek(5, history(4, 4, 4, 1, 4))
        assertEquals(1, s.weeks)
        assertNull(s.comebackWeek)
        assertEquals(Kind.FRESH_START, s.notice?.kind)
    }

    @Test fun rule4_decliningResetsNowAndMakesThisWeekTheComeback() {
        val offered = openOnWeek(3, history(4, 4, 1))
        val s = Streaks.declineRepair(offered, monday(3).plusDays(1))
        assertEquals(0, s.weeks)
        assertEquals(2, s.bestStreakWeeks)
        assertNull(s.repair)
        assertEquals(key(3), s.comebackWeek)
        assertEquals(Kind.FRESH_START, s.notice?.kind)
        assertEquals(1, Streaks.evaluate(s, history(4, 4, 1, 2), goal, monday(4)).weeks)
    }

    // ---- Rule 5 -------------------------------------------------------------------------------

    @Test fun rule5_pausedWeeksAreSkippedWithoutFreezesOrBreaks() {
        val h = history(4, 4, 0, 4)
        val paused = Streaks.pause(openOnWeek(2, h), Streaks.PauseLength.ONE, "Holiday", monday(2))
        val s = Streaks.evaluate(paused, h, goal, monday(4))
        assertEquals(3, s.weeks)
        assertEquals(0, s.freezes)
        assertNull(s.repair)
        assertNull(s.notice)
        assertEquals(0, Streaks.target(paused, goal, monday(2)))
    }

    @Test fun rule5_pausedWeeksDontBreakTheRunTowardAFreeze() {
        val h = history(4, 4, 0, 4, 4)
        val paused = Streaks.pause(openOnWeek(2, h), Streaks.PauseLength.ONE, null, monday(2))
        assertEquals(1, Streaks.evaluate(paused, h, goal, monday(5)).freezes)
    }

    @Test fun rule5_trainingThroughAPausedWeekStillCounts() {
        // Week 3 is paused but has 4 sessions: it counts, and completes the run of 4 that earns a freeze.
        val h = history(4, 4, 4, 4)
        val paused = Streaks.pause(openOnWeek(3, h), Streaks.PauseLength.ONE, "Holiday", monday(3))
        assertEquals(4, Streaks.display(paused, h, goal, monday(3).plusDays(5)))
        val s = Streaks.evaluate(paused, h, goal, monday(4))
        assertEquals(4, s.weeks)
        assertEquals(1, s.freezes)
        // Fewer than the goal in a paused week costs nothing.
        val light = Streaks.evaluate(paused, history(4, 4, 4, 1), goal, monday(4))
        assertEquals(3, light.weeks)
        assertNull(light.repair)
        assertNull(light.notice)
    }

    @Test fun rule5_aQuestOrComebackInAPausedWeekMovesToTheNextWeek() {
        val offered = openOnWeek(3, history(4, 4, 1))
        val paused = Streaks.pause(offered, Streaks.PauseLength.ONE, null, monday(3))
        val s = Streaks.evaluate(paused, history(4, 4, 1, 0), goal, monday(4))
        assertEquals(key(4), s.repair?.week)
        assertEquals(2, s.weeks)
        val comeback = Streaks.evaluate(running.copy(comebackWeek = key(3), pausedWeeks = listOf(key(3)), evaluatedThrough = key(2)), history(0, 0, 0, 1), goal, monday(4))
        assertEquals(key(4), comeback.comebackWeek)
    }

    @Test fun rule5_twoWeekPauseCoversThisWeekAndNext() {
        val s = Streaks.pause(StreakState(), Streaks.PauseLength.TWO, null, monday(2).plusDays(3))
        assertEquals(listOf(key(2), key(3)), s.pausedWeeks)
    }

    @Test fun rule5_untilResumedPausesEachWeekAsItPassesAndResumeEndsIt() {
        val h = history(4, 4, 0, 0, 0, 4)
        var s = Streaks.pause(openOnWeek(2, h), Streaks.PauseLength.UNTIL_RESUMED, "Injury", monday(2))
        s = Streaks.evaluate(s, h, goal, monday(5))
        assertEquals(listOf(key(2), key(3), key(4)), s.pausedWeeks)
        assertEquals(2, s.weeks)
        assertTrue(Streaks.pausedNow(s, monday(5)))
        s = Streaks.resume(s, monday(5))
        assertNull(s.pausedFrom)
        assertEquals(3, Streaks.evaluate(s, h, goal, monday(6)).weeks)
    }

    @Test fun rule5_atMostFourPausedWeeksAYear() {
        val h = history(4, 4, 0, 0, 0, 0, 0)
        var s = Streaks.pause(openOnWeek(2, h), Streaks.PauseLength.UNTIL_RESUMED, null, monday(2))
        s = Streaks.evaluate(s, h, goal, monday(7))
        assertEquals(4, s.pausedWeeks.size)
        assertNull(s.pausedFrom) // the allowance ran out, so week 6 counted as a miss
        assertNotNull(s.repair)
        assertEquals(0, Streaks.pausesLeft(s, monday(7)))
        assertEquals(s, Streaks.pause(s, Streaks.PauseLength.ONE, null, monday(7)))
    }

    @Test fun rule5_fixedPauseIsCappedByTheYearlyAllowance() {
        val s = StreakState(pausedWeeks = listOf(key(0), key(1), key(2)))
        assertEquals(4, Streaks.pause(s, Streaks.PauseLength.TWO, null, monday(5)).pausedWeeks.size)
    }

    // ---- Late opens ---------------------------------------------------------------------------

    @Test fun openingAfterSeveralWeeksAwayShowsOnlyTheLatestSheet() {
        // Freeze used in week 4, then a quest offered for week 6 after missing week 5.
        val s = openOnWeek(6, history(4, 4, 4, 4, 0, 0))
        assertEquals(Kind.REPAIR_OFFERED, s.notice?.kind)
        assertEquals(key(5), s.notice?.week)
    }

    @Test fun firstLoadBackfillsSilently() {
        // An existing file whose history ends with a freeze being used, then a miss with no freeze left.
        val usedFreeze = openOnWeek(6, history(4, 4, 4, 4, 4, 2), StreakState())
        assertEquals(0, usedFreeze.freezes)
        assertNull(usedFreeze.notice) // the freeze is spent, but no 2.1 sheet for a week before the update
        val endsInMiss = openOnWeek(7, history(4, 4, 4, 4, 4, 2, 1), StreakState())
        assertNull(endsInMiss.notice) // no 2.2 sheet either
        assertTrue(endsInMiss.repair!!.accepted) // the quest still runs, shown only as the Home card
        // From the first new week after the update, sheets come back as normal.
        val next = Streaks.evaluate(usedFreeze, history(4, 4, 4, 4, 4, 2, 1), goal, monday(7))
        assertEquals(Kind.REPAIR_OFFERED, next.notice?.kind)
    }

    @Test fun firstLoadRebuildsTheStreakFromHistory() {
        val s = openOnWeek(10, history(4, 4, 4, 4, 4, 4, 4, 4, 4, 4))
        assertEquals(10, s.weeks)
        assertEquals(2, s.freezes)
        assertEquals(2, s.towardFreeze)
    }

    @Test fun weekKeysRoundTrip() {
        (0..60).forEach { assertEquals(monday(it), Streaks.monday(key(it))) }
    }
}
