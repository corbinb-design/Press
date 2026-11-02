package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Phase 2: the rules engine. The Scotland fixture uses the brief's real 23–24 Oct numbers (split hike with walks,
 * the 3h57 "Other" with 9.5 km and 540 m); 22 and 25 Oct are made-up hikes to fill the four days. Before the trip:
 * three weeks at the weekly goal of 4, squats hitting the top of the range at 90 kg twice in a row.
 */
class RulesTest {
    private val mon = LocalDate.of(2026, 10, 19)          // the Scotland week, 2026-W43
    private val thu = mon.plusDays(3); private val fri = mon.plusDays(4); private val sat = mon.plusDays(5); private val sun = mon.plusDays(6)
    private val back = mon.plusWeeks(1)                    // Mon 26 Oct: first session after the trip
    private var n = 0L

    private fun at(d: LocalDate, hm: String) = d.atTime(LocalTime.parse(hm)).atZone(Weeks.zone).toInstant().toEpochMilli()
    private fun hc(d: LocalDate, type: OutsideType, from: String, to: String, km: Double? = null, climb: Double? = null) =
        OutsideSession("r${++n}", type, at(d, from), at(d, to), distanceM = km?.times(1000), elevationGainM = climb)

    private val legs = Template(3, "Legs", listOf(
        TemplateExercise("squat", 4, 8, repMin = 6, repMax = 8),
        TemplateExercise("rdl", 3, 8, repMin = 8, repMax = 12),
        TemplateExercise("leg_press", 3, 10, repMin = 10, repMax = 12),
        TemplateExercise("leg_curl", 3, 12, repMin = 10, repMax = 12),
    ))
    private val push = Template(1, "Push", listOf(TemplateExercise("bench", 3, 6)))

    private fun sets(kg: Double, vararg reps: Int) = reps.map { SetEntry(++n, weightKg = kg, reps = it, done = true) }
    private fun legDay(d: LocalDate, squatReps: Int) = Workout(++n, "Legs", 3, at(d, "18:00"), at(d, "19:00"), listOf(
        ExerciseEntry(++n, "squat", sets(90.0, squatReps, squatReps, squatReps, squatReps)),
        ExerciseEntry(++n, "rdl", sets(62.5, 10, 10, 9)),
        ExerciseEntry(++n, "leg_press", sets(140.0, 12, 11, 10)),
        ExerciseEntry(++n, "leg_curl", sets(45.0, 11, 10, 10)),
    ))
    private fun pushDay(d: LocalDate) = Workout(++n, "Push", 1, at(d, "18:00"), at(d, "19:00"), listOf(ExerciseEntry(++n, "bench", sets(80.0, 6, 6, 6))))

    /** W40–W42: four sessions a week; the last two leg days hit 8 on every squat set. Last gym session Sat 17 Oct. */
    private val history = (3 downTo 1).flatMap { w ->
        val m = mon.minusWeeks(w.toLong())
        listOf(pushDay(m), legDay(m.plusDays(1), if (w == 3) 7 else 8), pushDay(m.plusDays(3)), legDay(m.plusDays(5), 8))
    }.sortedByDescending { it.startedAt }

    /** 28 days of steps, ~8,000 a day, with the hiking days far above. */
    private fun steps(d: LocalDate): Long = when (d) { thu -> 21_500; fri -> 24_310; sat -> 19_800; sun -> 22_040; else -> 8_000 }

    private val scotland = HealthSnapshot(at(back, "08:00"), (27L downTo 0).map { back.minusDays(it) }.map { d ->
        ContextDay(d.toString(), steps(d), steps(d) * 0.75, outsideSessions = when (d) {
            thu -> listOf(hc(thu, OutsideType.HIKING, "09:00", "14:00", 14.0, 610.0))
            fri -> listOf(
                hc(fri, OutsideType.WALKING, "09:00", "09:15", 1.1), hc(fri, OutsideType.HIKING, "09:22", "10:29", 3.0, 210.0),
                hc(fri, OutsideType.HIKING, "10:51", "13:35", 6.5, 330.0), hc(fri, OutsideType.WALKING, "13:50", "14:07", 1.0),
            )
            sat -> listOf(hc(sat, OutsideType.OTHER, "09:03", "13:00", 9.5, 540.0))
            sun -> listOf(hc(sun, OutsideType.HIKING, "10:00", "15:00", 8.0, 420.0))
            else -> emptyList()
        })
    })

    private val note = ContextNote(1, "Hiking in Scotland, 4 days, heavy pack", listOf(
        NoteActivity("Hiking", 4, Intensity.HIGH, Outside.LEGS, "heavy pack", listOf(thu, fri, sat, sun).map(LocalDate::toString)),
    ), confirmedAt = at(back, "08:30"), parsedByAi = true)

    /** The app as it stood on Monday morning: streak evaluated the usual way, before any outside context. */
    private fun appBefore(notes: List<ContextNote> = emptyList()) = AppData(
        templates = listOf(push, legs), history = history, settings = Settings(name = "Corbin", weeklyGoal = 4, onboarded = true),
        streak = Streaks.evaluate(StreakState(), history, 4, back), contextNotes = notes,
    )
    private fun appAfter() = Outside.sync(appBefore(listOf(note)), scotland, back, force = true)

    // ---- Outside load and the streak ----------------------------------------------------------------

    @Test fun `the Scotland week meets the outside rule, and the streak keeps going`() {
        val before = appBefore()
        // Without context: W43 is a miss with no freeze banked, so a repair quest and the 3-week streak at stake.
        assertEquals(3, before.streak.weeks)
        assertNotNull(before.streak.repair)

        val after = appAfter()
        assertTrue(Outside.activeWeek(after.outsideDays, mon))
        assertEquals(listOf("2026-W43"), after.streak.outsideWeeks)
        assertEquals(4, after.streak.weeks)
        assertNull("no repair quest for a week in the hills", after.streak.repair)
        assertEquals("the 4th week in a row banks a freeze", 1, after.streak.freezes)
        assertNull(after.streak.notice)
    }

    @Test fun `the streak needs two outings and three hours - one long one or two short ones aren't enough`() {
        fun week(vararg o: OutingSummary) = listOf(OutsideDay(thu.toString(), outings = o.toList()))
        fun hike(min: Int, d: LocalDate = thu) = OutingSummary(at(d, "09:00"), at(d, "09:00") + min * 60_000L, min, OutsideType.HIKING)
        assertFalse(Outside.activeWeek(week(hike(400)), mon))
        assertFalse(Outside.activeWeek(week(hike(70), hike(70)), mon))
        assertTrue(Outside.activeWeek(week(hike(90), hike(90)), mon))
        assertFalse("under an hour doesn't count as a session", Outside.activeWeek(week(hike(150), hike(59)), mon))
    }

    @Test fun `rebuild keeps pauses and never lowers the best streak`() {
        val s = StreakState(weeks = 9, bestStreakWeeks = 12, pausedWeeks = listOf("2026-W38"), outsideWeeks = listOf("2026-W43"))
        val r = Streaks.rebuild(s, history, 4, back)
        assertEquals(listOf("2026-W38"), r.pausedWeeks)
        assertEquals(12, r.bestStreakWeeks)
        assertEquals(listOf("2026-W43"), r.outsideWeeks)
    }

    @Test fun `legs are trained outside the gym that week, and the Sunday issue stops calling them short`() {
        val after = appAfter()
        val trained = Outside.musclesBetween(after.outsideDays, mon, sun)
        assertEquals(Outside.LEGS.toSet(), trained.keys)
        assertEquals(listOf("hiking"), trained.getValue("Quads"))

        // No gym sets anywhere: without context a leg muscle is "short"; with it, only upper body can be.
        val legsOnly = { d: AppData -> d.copy(templates = listOf(legs), history = emptyList()) }
        assertTrue(Issues.nextWeek(legsOnly(appBefore()), mon).contains("sets short"))
        val line = Issues.nextWeek(legsOnly(after), mon)
        Outside.LEGS.forEach { assertFalse("$it in: $line", line.contains(it)) }
    }

    @Test fun `a long Other with 300 m+ climbing loads the legs, without the climb only the note can`() {
        val other = { climb: Double? -> OutingSummary(at(sat, "09:00"), at(sat, "13:00"), 240, OutsideType.OTHER, climbM = climb) }
        assertEquals(Outside.LEGS, Outside.muscles(other(540.0)))
        assertTrue(Outside.muscles(other(120.0)).isEmpty())
        assertTrue(Outside.muscles(other(null).copy(minutes = 59)).isEmpty())
    }

    @Test fun `a note names the Other outing and the watch's label is kept`() {
        val days = appAfter().outsideDays
        val satOuting = days.single { it.date == sat.toString() }.outings.single()
        assertEquals(OutsideType.HIKING, satOuting.type)
        assertEquals(OutsideType.OTHER, satOuting.watchType)
        // Confirmed later without a live read: the stored days are relabelled from the note alone.
        val stored = Outside.summarise(scotland, emptyList())
        assertEquals(OutsideType.OTHER, stored.single { it.date == sat.toString() }.outings.single().type)
        val relabelled = Outside.withNotes(stored, listOf(note)).single { it.date == sat.toString() }.outings.single()
        assertEquals(OutsideType.HIKING, relabelled.type)
        assertEquals(OutsideType.OTHER, relabelled.watchType)
    }

    @Test fun `a watch session that was really a logged gym session isn't outside activity`() {
        // Real case (1 Nov): Upper A logged 15:19–16:35; the watch saved 15:18–16:37 as "Other", titled "CrossFit".
        val sun = mon.plusWeeks(1).minusDays(1)
        val gym = Workout(++n, "Upper A", 1, at(sun, "15:19"), at(sun, "16:35"), emptyList())
        val crossfit = hc(sun, OutsideType.OTHER, "15:18", "16:37", 1.5)
        val walkThere = hc(sun, OutsideType.WALKING, "14:00", "15:20", 5.0) // overlaps by a minute: still a walk
        val snap = HealthSnapshot(0, listOf(ContextDay(sun.toString(), outsideSessions = listOf(walkThere, crossfit))))
        // Without the logged workout, the two merge into one outing: 80 + 79 recorded minutes.
        assertEquals(159, Outside.merge(emptyList(), snap, emptyList(), back).single().outings.single().minutes)
        // With it, the "CrossFit" session is dropped before merging: only the 80-minute walk is left.
        val kept = Outside.merge(emptyList(), snap, emptyList(), back, listOf(gym)).single().outings.single()
        assertEquals(OutsideType.WALKING, kept.type)
        assertEquals(80, kept.minutes)
        assertEquals(1, kept.sessions)
        // A stored day from before the workout was logged is cleaned on the next sync.
        val stored = Outside.merge(emptyList(), HealthSnapshot(0, listOf(ContextDay(sun.toString(), outsideSessions = listOf(crossfit)))), emptyList(), back)
        assertTrue(Outside.merge(stored, null, emptyList(), back, listOf(gym)).single().outings.isEmpty())
    }

    @Test fun `stored days outlive Health Connect's window, a fresh read replaces the dates it covers`() {
        val old = OutsideDay("2026-08-01", steps = 30_000)
        val merged = Outside.merge(listOf(old, OutsideDay(fri.toString(), steps = 1)), scotland, emptyList(), back)
        assertEquals(old, merged.first())
        assertEquals(24_310L, merged.single { it.date == fri.toString() }.steps)
        assertTrue(Outside.merge(listOf(OutsideDay("2025-01-01")), null, emptyList(), back).isEmpty())
    }

    // ---- Readiness -------------------------------------------------------------------------------------

    private val now = at(back, "18:00")

    @Test fun `first session back after the trip - load from the watch makes it amber, partial without a check-in`() {
        val r = Rules.readiness(null, appAfter().outsideDays, now)
        assertEquals(ReadinessState.AMBER, r.state)
        assertTrue(r.partial)
        // Sat 3h57 + Sun 5h end inside the last 72 h; Sun's 420 m is inside 48 h.
        assertEquals(
            listOf(Fact("readiness.recentLoad", "8h 57 of hiking in the last 3 days, plus 1 day of 20,000+ steps"), Fact("readiness.climb", "Sun: 420 m climbed")),
            r.reasons,
        )
    }

    @Test fun `each check-in answer costs a point, load costs one however it's reached`() {
        val days = appAfter().outsideDays
        val poor = CheckIn(now, 1, SleepAnswer.POOR, Soreness.SOME, Energy.OK)
        assertEquals(ReadinessState.RED, Rules.readiness(poor, days, now).state)
        assertFalse(Rules.readiness(poor, days, now).partial)
        val fine = CheckIn(now, 1, SleepAnswer.GOOD, Soreness.NONE, Energy.HIGH)
        assertEquals(ReadinessState.GREEN, Rules.readiness(fine, emptyList(), now).state)
        assertEquals(-3, Rules.readiness(CheckIn(now, 1, SleepAnswer.POOR, Soreness.VERY, Energy.LOW), emptyList(), now).score)
        assertEquals(-4, Rules.readiness(CheckIn(now, 1, SleepAnswer.POOR, Soreness.VERY, Energy.LOW), days, now).score)
    }

    @Test fun `watch sleep replaces the sleep answer when it's there`() {
        val today = back.toString()
        val slept = { min: Int -> listOf(OutsideDay(today, sleepMinutes = min)) }
        assertEquals(ReadinessState.GREEN, Rules.readiness(CheckIn(now, 1, SleepAnswer.POOR), slept(7 * 60), now).state)
        val short = Rules.readiness(CheckIn(now, 1, SleepAnswer.GOOD), slept(5 * 60 + 40), now)
        assertEquals(listOf(Fact("sleep.watch", "5h 40 asleep last night (watch)")), short.reasons)
        // Under 3 h isn't trusted as a night (the real 2h 14): the answer decides.
        assertEquals(ReadinessState.GREEN, Rules.readiness(CheckIn(now, 1, SleepAnswer.GOOD), slept(134), now).state)
        assertEquals(listOf("checkin.sleep"), Rules.readiness(CheckIn(now, 1, SleepAnswer.POOR), slept(134), now).reasons.map { it.id })
    }

    @Test fun `a 20k step day counts as two hours of load`() {
        val walk = OutingSummary(at(back, "08:00"), at(back, "12:00"), 240, OutsideType.WALKING)
        val days = { s: Long -> listOf(OutsideDay(back.toString(), steps = s, outings = listOf(walk))) }
        assertNull(Rules.recentLoad(days(19_999), now))
        assertEquals("4h 00 of walking in the last 3 days, plus 1 day of 20,000+ steps", Rules.recentLoad(days(20_000), now)!!.text)
        assertEquals("3 days of 20,000+ steps in the last 3 days",
            Rules.recentLoad((0L..2).map { OutsideDay(back.minusDays(it).toString(), steps = 25_000) }, now)!!.text)
    }

    // ---- Gating the progression engine ---------------------------------------------------------------------

    @Test fun `right after the trip the next workout is capped at hold - amber options with real plate maths`() {
        val d = appAfter()
        val plan = Rules.plan(d, legs, checkIn = null, now = now)
        assertEquals(9L, plan.gate.daysSinceGym)
        assertTrue(plan.gate.firstBack)
        assertFalse(plan.gate.canIncrease)
        val squat = plan.exercises.first { it.exerciseId == "squat" }
        assertEquals("the engine still says level up", Progression.Kind.LEVEL_UP, squat.target!!.kind)
        assertEquals(listOf(
            Option(ActionId.HOLD_WEIGHT, 90.0, 8, 4), Option(ActionId.REDUCE_LOAD_5, 85.0, 8, 4), Option(ActionId.DROP_ONE_SET, 90.0, 8, 3),
        ), squat.options)
        assertFalse(plan.exercises.any { ActionId.INCREASE_LOAD in it.allowed })
    }

    @Test fun `green and not back from a break - the engine's target stands`() {
        val d = appBefore()
        val wed = at(mon.minusWeeks(1).plusDays(2), "18:00") // two days after a leg day
        val squat = Rules.plan(d.copy(history = history.filter { it.startedAt < wed }), legs, CheckIn(wed, 1, SleepAnswer.GOOD, Soreness.NONE, Energy.OK), wed)
            .exercises.first { it.exerciseId == "squat" }
        assertEquals(listOf(ActionId.INCREASE_LOAD, ActionId.HOLD_WEIGHT), squat.allowed)
        assertEquals(92.5, squat.options.first().weightKg!!, 0.0)
        assertEquals(6, squat.options.first().reps)
    }

    @Test fun `red - only cuts, rounded down to each exercise's own increment`() {
        val gate = Gate(Readiness(ReadinessState.RED, -2, false, emptyList()), 1)
        fun opts(kg: Double, inc: Double = 2.5, unit: WeightUnit = WeightUnit.KG, tracking: Tracking = Tracking.WEIGHT_REPS) =
            Rules.options(TemplateExercise("x", 3, 8, incrementKg = inc), Progression.Target(Progression.Kind.HOLD, kg, 8, kg, 7), tracking, gate, unit)
        // The design's own examples: 90 → 80, 62.5 → 55, 45 → 40.
        assertEquals(80.0, opts(90.0).first().weightKg!!, 0.0)
        assertEquals(55.0, opts(62.5).first().weightKg!!, 0.0)
        assertEquals(40.0, opts(45.0).first().weightKg!!, 0.0)
        assertEquals(listOf(ActionId.REDUCE_LOAD_10, ActionId.DROP_ONE_SET), opts(90.0).map { it.action })
        assertEquals(36.0, opts(40.0, inc = 1.0).first().weightKg!!, 0.0)
        // Pounds: the 2.5 kg default reads as 5 lb, so the cut lands on a 5 lb step.
        val lb = opts(Units.toKg(185.0, WeightUnit.LB), unit = WeightUnit.LB).first().weightKg!!
        assertEquals(165.0, Units.toDisplay(lb, WeightUnit.LB), 0.01)
        // Assisted: less load means more help, at least one step more.
        assertEquals(22.5, opts(20.0, tracking = Tracking.ASSISTED).first().weightKg!!, 0.0)
        // No added weight: nothing to cut, so only the dropped set.
        assertEquals(listOf(ActionId.DROP_ONE_SET), opts(0.0, tracking = Tracking.BODYWEIGHT_REPS).map { it.action })
    }

    @Test fun `Today's target card - a level-up the gate doesn't allow becomes a hold at last time's weight`() {
        val up = Progression.Target(Progression.Kind.LEVEL_UP, 92.5, 6, 90.0, 8)
        val green = Gate(Readiness(ReadinessState.GREEN, 0, false, emptyList()), 2)
        assertEquals(up, Rules.gated(up, green))
        assertEquals(Progression.Target(Progression.Kind.HOLD, 90.0, 8, 90.0, 8), Rules.gated(up, green.copy(daysSinceGym = 5)))
        assertEquals(Progression.Kind.HOLD, Rules.gated(up, green.copy(readiness = green.readiness.copy(state = ReadinessState.AMBER)))!!.kind)
        val lighter = Progression.Target(Progression.Kind.LIGHTER, 80.0, 6, 90.0, 6)
        assertEquals(lighter, Rules.gated(lighter, green.copy(daysSinceGym = 9)))
    }

    // ---- Everyday movement ----------------------------------------------------------------------------

    @Test fun `everyday movement - weekly totals, the biggest day, and high-step days`() {
        val m = Outside.movement(appAfter().outsideDays, mon)!!
        assertEquals(3L * 8_000 + 21_500 + 24_310 + 19_800 + 22_040, m.steps)
        assertEquals(fri.toString(), m.biggest!!.date)
        // The trip lifts the 28-day average to ~10,061, so 1.5× (15,092) beats the 15,000 floor. All four hike days clear it.
        assertEquals(15_092L, m.threshold)
        assertEquals(listOf(thu, fri, sat, sun).map(LocalDate::toString), m.highDays.map { it.date })
        assertTrue(m.worthALine)
        assertEquals(m.distanceM / 1609.344, m.miles, 1e-9)
        // A walker averaging ~12,000: 1.5× (18,268) beats the floor, so a 17,000 day isn't a high-step day for them.
        val busy = (0L..27).map { OutsideDay(sun.minusDays(it).toString(), steps = if (it == 1L) 17_000 else 12_000) }
        val b = Outside.movement(busy, mon)!!
        assertEquals(18_268L, b.threshold)
        assertTrue(b.highDays.isEmpty())
        // A quiet month (5,000 a day): the 15,000 floor applies.
        assertEquals(15_000L, Outside.movement((0L..27).map { OutsideDay(sun.minusDays(it).toString(), steps = 5_000) }, mon)!!.threshold)
    }

    // ---- Facts ---------------------------------------------------------------------------------------

    @Test fun `every computed fact has a stable unique id and plain text`() {
        val d = appAfter()
        val checkIn = CheckIn(now, 9, SleepAnswer.POOR, Soreness.SOME, Energy.OK)
        val facts = Rules.plan(d, legs, checkIn, now).facts
        assertEquals(facts.size, facts.map { it.id }.distinct().size)
        val byId = facts.associate { it.id to it.text }
        assertEquals("You said: poor sleep", byId["checkin.sleep"])
        assertEquals("9 days since your last gym session", byId["break.daysSinceGym"])
        assertEquals("Sat 24: 3h 57 hiking · 9.5 km · 540 m climbed", byId["outside.hike.2026-10-24"])
        assertEquals("Fri 23: 4h 23 hiking · 11.6 km · 540 m climbed", byId["outside.hike.2026-10-23"])
        assertEquals("4 outside days · 43.1 km · 2,110 m climbed", byId["outside.week.2026-W43"])
        assertEquals("Note: hiking, 4 days · quads, hamstrings, glutes, calves", byId["outside.note.1"])
        assertEquals("Fri: 24,310 steps", byId["movement.highStepDay.2026-10-23"])
        assertEquals("Back Squat last time: 90 kg × 8, 8, 8, 8", byId["lift.squat.last"]?.replace(Session.exercise(d, "squat").name, "Back Squat"))
        assertTrue(byId.getValue("lift.squat.lastTwo").endsWith("hit 8 on every set, two sessions running"))
        assertTrue(byId.containsKey("readiness.recentLoad"))
        assertTrue(facts.none { it.text.isBlank() })
    }
}
