package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test

/** Step 3 of docs/rest-design.md: the id-based logging path in Session, and its rest transitions. */
class SessionLogTest {
    private val now = 1_000_000L
    private fun set(id: Long, kg: Double? = 60.0, reps: Int? = 8, done: Boolean = false, skipped: Boolean = false) =
        SetEntry(id, weightKg = kg, reps = reps, done = done, skipped = skipped)

    private fun data(
        ends: Long? = null, autoRest: Boolean = true, total: Int = 0,
        entries: List<ExerciseEntry> = listOf(ExerciseEntry(1, "bench", listOf(set(11), set(12), set(13)))),
    ) = AppData(
        active = Workout(5, "Push", null, 0, null, entries, restEndsAt = ends, restTotalSec = total),
        settings = Settings(autoRest = autoRest, restSeconds = 90),
    )

    private fun AppData.sets(ex: Int = 0) = active!!.exercises[ex].sets
    private val AppData.ends get() = active!!.restEndsAt

    // ---- idempotence and stale targets ----
    @Test fun repeatingALogChangesNothingTheSecondTime() {
        val once = Session.log(data(), 5, 11, now = now)
        val twice = Session.log(once, 5, 11, now = now + 5_000)
        assertSame(once, twice)
        assertEquals(now + 90_000, twice.ends) // the repeat didn't restart the rest
    }

    @Test fun aStaleWorkoutIdOrARemovedSetChangesNothing() {
        val d = data()
        assertSame(d, Session.log(d, 99, 11, now = now))
        assertSame(d, Session.log(d, 5, 999, now = now))
        assertSame(d, Session.untick(d, 99, 11))
        assertSame(d, Session.setValues(d, 5, 999, SetEntry(0, weightKg = 1.0, reps = 1)))
    }

    @Test fun aSkippedSetOrOneWithNothingToLogIsRefused() {
        val skipped = data(entries = listOf(ExerciseEntry(1, "bench", listOf(set(11, skipped = true)))))
        assertSame(skipped, Session.log(skipped, 5, 11, now = now))
        val empty = data(entries = listOf(ExerciseEntry(1, "bench", listOf(set(11, kg = null, reps = null)))))
        assertSame(empty, Session.log(empty, 5, 11, now = now))
        val withValues = Session.log(empty, 5, 11, SetEntry(0, weightKg = 50.0, reps = 5), now = now)
        assertTrue(withValues.sets()[0].done)
    }

    // ---- values ----
    @Test fun loggingWithValuesUsesThemAndKeepsTheSetIdAndType() {
        val d = data(entries = listOf(ExerciseEntry(1, "bench", listOf(set(11).copy(type = SetType.WARMUP)))))
        val s = Session.log(d, 5, 11, SetEntry(0, weightKg = 72.5, reps = 6), now = now).sets()[0]
        assertEquals(11L, s.id); assertEquals(SetType.WARMUP, s.type)
        assertEquals(72.5, s.weightKg!!, 0.0); assertEquals(6, s.reps); assertTrue(s.done)
    }

    @Test fun aBodyweightSetWithNoWeightLogsAsZero() {
        val d = data(entries = listOf(ExerciseEntry(1, "pullup", listOf(set(11, kg = null)), tracking = Tracking.BODYWEIGHT_REPS)))
        assertEquals(0.0, Session.log(d, 5, 11, SetEntry(0, reps = 7), now = now).sets()[0].weightKg!!, 0.0)
        assertEquals(60.0, Session.log(data(), 5, 11, SetEntry(0, weightKg = 60.0, reps = 7), now = now).sets()[0].weightKg!!, 0.0)
    }

    @Test fun applyToRemainingCopiesToLaterUnloggedSetsOnly() {
        val d = data(entries = listOf(ExerciseEntry(1, "bench", listOf(set(10, done = true), set(11), set(12, done = true), set(13)))))
        val s = Session.log(d, 5, 11, SetEntry(0, weightKg = 80.0, reps = 5), applyToRemaining = true, now = now).sets()
        assertEquals(60.0, s[0].weightKg!!, 0.0) // earlier set untouched
        assertEquals(80.0, s[1].weightKg!!, 0.0)
        assertEquals(60.0, s[2].weightKg!!, 0.0) // later but already done: untouched
        assertEquals(80.0, s[3].weightKg!!, 0.0); assertEquals(5, s[3].reps)
        assertFalse(s[3].done) // copied, not ticked
    }

    // ---- rest transitions ----
    @Test fun t1LoggingFromWorkingStartsTheRest() {
        val d = Session.log(data(), 5, 11, now = now)
        assertEquals(now + 90_000, d.ends); assertEquals(90, d.active!!.restTotalSec)
    }

    @Test fun t2AutoRestOffOrCardioStaysWorking() {
        assertNull(Session.log(data(autoRest = false), 5, 11, now = now).ends)
        val cardio = data(entries = listOf(ExerciseEntry(1, "run", listOf(SetEntry(11, durationSec = 600)), tracking = Tracking.CARDIO)))
        assertNull(Session.log(cardio, 5, 11, now = now).ends)
    }

    @Test fun t8LoggingWhileRestingReplacesTheRest() {
        val d = Session.log(data(ends = now + 30_000, total = 30), 5, 11, now = now)
        assertEquals(now + 90_000, d.ends); assertEquals(90, d.active!!.restTotalSec)
    }

    @Test fun t9LoggingWhileRestingWithAutoRestOffEndsTheRest() {
        assertNull(Session.log(data(ends = now + 30_000, autoRest = false), 5, 11, now = now).ends)
        val cardio = data(ends = now + 30_000, entries = listOf(ExerciseEntry(1, "run", listOf(SetEntry(11, durationSec = 600)), tracking = Tracking.CARDIO)))
        assertNull(Session.log(cardio, 5, 11, now = now).ends)
    }

    @Test fun t11LoggingWhenOverStartsANewRest() {
        val d = Session.log(data(ends = now - 12_000, total = 30), 5, 11, now = now)
        assertEquals(now + 90_000, d.ends)
    }

    @Test fun t12LoggingWhenOverWithAutoRestOffEndsTheRest() {
        assertNull(Session.log(data(ends = now - 12_000, autoRest = false), 5, 11, now = now).ends)
    }

    @Test fun aRefusedLogLeavesTheRestAlone() {
        val d = Session.log(data(), 5, 11, now = now)
        assertSame(d, Session.log(d, 5, 11, now = now + 60_000)) // already done
        assertEquals(now + 90_000, d.ends)
    }

    // ---- untick and setValues ----
    @Test fun untickKeepsTheRest() {
        val logged = Session.log(data(), 5, 11, now = now)
        val un = Session.untick(logged, 5, 11)
        assertFalse(un.sets()[0].done)
        assertEquals(logged.ends, un.ends); assertEquals(logged.active!!.restTotalSec, un.active!!.restTotalSec)
        assertSame(un, Session.untick(un, 5, 11)) // already unticked: no-op
    }

    @Test fun setValuesChangesNumbersWithoutTickingOrTouchingTheRest() {
        val d = data(ends = now + 30_000, total = 30)
        val after = Session.setValues(d, 5, 11, SetEntry(0, weightKg = 100.0, reps = 3))
        assertEquals(100.0, after.sets()[0].weightKg!!, 0.0); assertEquals(3, after.sets()[0].reps)
        assertFalse(after.sets()[0].done)
        assertEquals(d.ends, after.ends)
        val done = Session.setValues(Session.log(d, 5, 11, now = now), 5, 11, SetEntry(0, weightKg = 90.0, reps = 4))
        assertTrue(done.sets()[0].done) // an edit to a logged set keeps its tick
        val rest = Session.setValues(d, 5, 11, SetEntry(0, weightKg = 70.0, reps = 6), applyToRemaining = true).sets()
        assertEquals(70.0, rest[2].weightKg!!, 0.0)
    }
}
