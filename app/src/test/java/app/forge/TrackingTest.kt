package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test

/** v1.1 tracking types: volume, records and progression for each, plus cardio's place in the week. */
class TrackingTest {
    private var id = 0L
    private val day = 86_400_000L

    private fun set(kg: Double? = null, reps: Int? = null, sec: Int? = null, m: Double? = null, done: Boolean = true) =
        SetEntry(++id, weightKg = kg, reps = reps, done = done, durationSec = sec, distanceM = m)

    private fun entry(ex: String, t: Tracking, vararg sets: SetEntry, bw: Double? = null) = ExerciseEntry(++id, ex, sets.toList(), tracking = t, bodyweightKg = bw)

    private fun workout(at: Long, vararg e: ExerciseEntry, countsForGoal: Boolean = true) =
        Workout(++id, "W", null, at, at + 3_600_000, e.toList(), countsForGoal = countsForGoal)

    // ---- Volume -----------------------------------------------------------------------------------

    @Test fun volumePerType() {
        assertEquals(600.0, Stats.volume(entry("bench", Tracking.WEIGHT_REPS, set(60.0, 10))), 0.001)
        // Bodyweight: the logged bodyweight plus anything added; without a bodyweight just the added weight.
        assertEquals(800.0, Stats.volume(entry("pullup", Tracking.BODYWEIGHT_REPS, set(0.0, 10), bw = 80.0)), 0.001)
        assertEquals(900.0, Stats.volume(entry("pullup", Tracking.BODYWEIGHT_REPS, set(10.0, 10), bw = 80.0)), 0.001)
        assertEquals(0.0, Stats.volume(entry("pullup", Tracking.BODYWEIGHT_REPS, set(0.0, 10))), 0.001)
        // Never volume: assisted, timed, carries, cardio.
        assertEquals(0.0, Stats.volume(entry("assisted_pullup", Tracking.ASSISTED, set(20.0, 10), bw = 80.0)), 0.001)
        assertEquals(0.0, Stats.volume(entry("plank", Tracking.DURATION, set(10.0, sec = 60))), 0.001)
        assertEquals(0.0, Stats.volume(entry("farmers", Tracking.DISTANCE_WEIGHT, set(40.0, m = 30.0))), 0.001)
        assertEquals(0.0, Stats.volume(entry("rower", Tracking.CARDIO, set(sec = 1200, m = 5000.0))), 0.001)
    }

    @Test fun oldEntriesReadAsWeightAndReps() {
        // An entry from before v1.1 has no tracking: it's weight × reps, exactly as before.
        assertEquals(Tracking.WEIGHT_REPS, ExerciseEntry(1, "plank", listOf(set(0.0, 60))).tracking)
        assertEquals(Stats.e1rm(100.0, 5), Measure.score(ExerciseEntry(1, "squat", emptyList()), set(100.0, 5)), 0.001)
    }

    // ---- Records ----------------------------------------------------------------------------------

    private fun prOf(t: Tracking, before: List<SetEntry>, now: SetEntry, bw: Double? = null): String? {
        val past = workout(0, entry("x", t, *before.toTypedArray(), bw = bw))
        return Stats.setPr(entry("x", t, now, bw = bw), 0, listOf(past), WeightUnit.KG)?.detail
    }

    @Test fun assistedRecordsAreLessHelp() {
        assertEquals("−17.5 kg × 8 — 2.5 kg less assistance than ever", prOf(Tracking.ASSISTED, listOf(set(20.0, 8)), set(17.5, 8)))
        assertNull(prOf(Tracking.ASSISTED, listOf(set(20.0, 8)), set(22.5, 8))) // more help is no record
        assertEquals("−20 kg × 10 — 2 more reps than ever with this help", prOf(Tracking.ASSISTED, listOf(set(20.0, 8)), set(20.0, 10)))
    }

    @Test fun assistedSessionRecordAndVsLastInvert() {
        val past = workout(0, entry("ap", Tracking.ASSISTED, set(20.0, 8)))
        val now = workout(day, entry("ap", Tracking.ASSISTED, set(15.0, 8)))
        assertEquals("LEAST HELP", Stats.sessionPrs(now, listOf(past)).single().kind)
        val vs = Stats.vsLast(now.exercises[0], listOf(past), now.startedAt, WeightUnit.KG)!!
        assertTrue(vs.better!!) // 5 kg less help is better
        assertEquals("▲ −5 kg", vs.text)
        // Score: less assistance, or more reps with the same help, is higher.
        val e = entry("ap", Tracking.ASSISTED)
        assertTrue(Measure.score(e, set(15.0, 8)) > Measure.score(e, set(20.0, 8)))
        assertTrue(Measure.score(e, set(20.0, 10)) > Measure.score(e, set(20.0, 8)))
    }

    @Test fun timedRecordsAreLongest() {
        assertEquals("1:05 — 5 s longer than ever", prOf(Tracking.DURATION, listOf(set(sec = 60)), set(sec = 65)))
        assertNull(prOf(Tracking.DURATION, listOf(set(sec = 60)), set(sec = 55)))
        val past = workout(0, entry("plank", Tracking.DURATION, set(sec = 60)))
        val now = workout(day, entry("plank", Tracking.DURATION, set(sec = 90)))
        assertEquals("LONGEST", Stats.sessionPrs(now, listOf(past)).single().kind)
        assertEquals("▲ +30 s", Stats.vsLast(now.exercises[0], listOf(past), now.startedAt, WeightUnit.KG)!!.text)
    }

    @Test fun bodyweightRecordsUseRepsAndAddedWeight() {
        assertEquals("BW × 12 — 2 more reps than ever at this weight", prOf(Tracking.BODYWEIGHT_REPS, listOf(set(0.0, 10)), set(0.0, 12)))
        assertEquals("BW + 5 kg × 6 — 5 kg over your best", prOf(Tracking.BODYWEIGHT_REPS, listOf(set(0.0, 10)), set(5.0, 6)))
    }

    @Test fun carryRecords() {
        assertEquals("50 kg × 20 m — heaviest carry yet", prOf(Tracking.DISTANCE_WEIGHT, listOf(set(40.0, m = 30.0)), set(50.0, m = 20.0)))
        assertEquals("40 kg × 40 m — farthest at this weight", prOf(Tracking.DISTANCE_WEIGHT, listOf(set(40.0, m = 30.0)), set(40.0, m = 40.0)))
    }

    @Test fun cardioNeverSetsRecords() {
        val past = workout(0, entry("rower", Tracking.CARDIO, set(sec = 600)))
        val now = workout(day, entry("rower", Tracking.CARDIO, set(sec = 1800, m = 8000.0)))
        assertTrue(Stats.sessionPrs(now, listOf(past)).isEmpty())
        assertNull(Stats.setPr(now.exercises[0], 0, listOf(past), WeightUnit.KG))
        assertEquals(0, Stats.prsBySession(listOf(now, past)).values.sum())
    }

    // ---- Progression ------------------------------------------------------------------------------

    private val rule = TemplateExercise("x", 3, 8, repMin = 8, repMax = 12)

    @Test fun assistedProgressesByTakingHelpOff() {
        val t = Progression.target(rule, listOf(set(20.0, 12), set(20.0, 12), set(20.0, 12)), WeightUnit.KG, Tracking.ASSISTED)!!
        assertEquals(Progression.Kind.LEVEL_UP, t.kind)
        assertEquals(17.5, t.weightKg!!, 0.001)
        assertEquals(8, t.reps)
        // Never below no help at all.
        assertEquals(0.0, Progression.target(rule, listOf(set(2.0, 12), set(2.0, 12)), WeightUnit.KG, Tracking.ASSISTED)!!.weightKg!!, 0.001)
        // Nothing left to take off: hold.
        assertEquals(Progression.Kind.HOLD, Progression.target(rule, listOf(set(0.0, 12)), WeightUnit.KG, Tracking.ASSISTED)!!.kind)
    }

    @Test fun assistedLighterWeekIsMoreHelp() {
        assertEquals(22.5, Progression.lighterKg(rule, listOf(set(20.0, 8)), WeightUnit.KG, Tracking.ASSISTED)!!, 0.001)
    }

    @Test fun bodyweightProgressesFromNothingAdded() {
        val t = Progression.target(rule, listOf(set(null, 12), set(null, 12)), WeightUnit.KG, Tracking.BODYWEIGHT_REPS)!!
        assertEquals(Progression.Kind.LEVEL_UP, t.kind)
        assertEquals(2.5, t.weightKg!!, 0.001)
    }

    @Test fun timedSetsAimFiveSecondsLonger() {
        val t = Progression.target(rule, listOf(set(sec = 60), set(sec = 50)), WeightUnit.KG, Tracking.DURATION)!!
        assertEquals(55, t.durationSec)
        assertEquals(Progression.Kind.BASELINE, Progression.target(rule, emptyList(), WeightUnit.KG, Tracking.DURATION)!!.kind)
    }

    @Test fun carriesAndCardioHaveNoTarget() {
        assertNull(Progression.target(rule, listOf(set(40.0, m = 30.0)), WeightUnit.KG, Tracking.DISTANCE_WEIGHT))
        assertNull(Progression.target(rule, listOf(set(sec = 600)), WeightUnit.KG, Tracking.CARDIO))
    }

    @Test fun onlyRepWorkStalls() {
        val h = (1..5).map { workout(it * day, entry("plank", Tracking.DURATION, set(sec = 60))) }.reversed()
        assertNull(Progression.stall(h, "plank", null))
    }

    // ---- Cardio and the week ----------------------------------------------------------------------

    private fun cardio(min: Int) = Workout(++id, "Cardio", null, 0, min * 60_000L, listOf(entry("rower", Tracking.CARDIO, set(sec = min * 60))))

    @Test fun standaloneCardioCountsAtTwentyMinutes() {
        val on = Settings(cardioCountsForGoal = true)
        assertTrue(Session.countsForGoal(cardio(25), on))
        assertFalse(Session.countsForGoal(cardio(15), on))
        assertFalse(Session.countsForGoal(cardio(25), on.copy(cardioCountsForGoal = false)))
        // Cardio inside a lifting session never changes whether the session counts.
        val mixed = workout(0, entry("bench", Tracking.WEIGHT_REPS, set(60.0, 10)), entry("rower", Tracking.CARDIO, set(sec = 300)))
        assertFalse(mixed.cardioOnly)
        assertTrue(Session.countsForGoal(mixed, on.copy(cardioCountsForGoal = false)))
    }

    @Test fun shortCardioIsShownButNotCounted() {
        val monday = java.time.LocalDate.of(2026, 9, 7)
        val at = monday.atTime(9, 0).atZone(Weeks.zone).toInstant().toEpochMilli()
        val short = cardio(15).copy(startedAt = at, countsForGoal = false)
        val lift = workout(at + day, entry("bench", Tracking.WEIGHT_REPS, set(60.0, 10)))
        val h = listOf(lift, short)
        assertEquals(1, Weeks.sessionsIn(h, monday).size)
        assertEquals(2, Weeks.allIn(h, monday).size)
        // And it doesn't earn the session XP.
        assertEquals(Xp.WORKOUT, Xp.total(listOf(short, lift), weeklyGoal = 4))
    }

    @Test fun balanceCountsSecondaryHalfAndSkipsCardio() {
        val today = java.time.LocalDate.of(2026, 9, 10)
        val at = today.atTime(9, 0).atZone(Weeks.zone).toInstant().toEpochMilli()
        val w = workout(at, entry("bench", Tracking.WEIGHT_REPS, set(60.0, 10), set(60.0, 10)), entry("rower", Tracking.CARDIO, set(sec = 600)))
        val all = Library.exercises.associateBy { it.id }
        val sets = Stats.setsPerMuscle(listOf(w), { all.getValue(it).muscle }, today = today, secondaryOf = { all.getValue(it).secondary })
        assertEquals(2.0, sets["Chest"]!!, 0.0)
        assertEquals(1.0, sets["Triceps"]!!, 0.0)
        assertNull(sets["Cardio"])
    }

    @Test fun cardioHeadline() {
        assertEquals("25 minutes of cardio.", Stats.headline(cardio(25), emptyList()))
    }

    // ---- How sets read ----------------------------------------------------------------------------

    @Test fun formats() {
        val kg = WeightUnit.KG
        assertEquals("62.5 kg × 10", Measure.format(Tracking.WEIGHT_REPS, set(62.5, 10), kg))
        assertEquals("−20 kg × 8", Measure.format(Tracking.ASSISTED, set(20.0, 8), kg))
        assertEquals("BW × 12", Measure.format(Tracking.BODYWEIGHT_REPS, set(0.0, 12), kg))
        assertEquals("BW + 10 kg × 6", Measure.format(Tracking.BODYWEIGHT_REPS, set(10.0, 6), kg))
        assertEquals("1:05", Measure.format(Tracking.DURATION, set(sec = 65), kg))
        assertEquals("10 kg · 1:05", Measure.format(Tracking.DURATION, set(10.0, sec = 65), kg))
        assertEquals("40 kg × 30 m", Measure.format(Tracking.DISTANCE_WEIGHT, set(40.0, m = 30.0), kg))
        assertEquals("25:00 · 4.2 km · L8 · 142 bpm", Measure.cardio(SetEntry(1, durationSec = 1500, distanceM = 4200.0, level = 8, heartRate = 142), kg))
        assertEquals("25:00 · 2.6 mi", Measure.cardio(SetEntry(1, durationSec = 1500, distanceM = 4200.0), WeightUnit.LB))
    }
}
