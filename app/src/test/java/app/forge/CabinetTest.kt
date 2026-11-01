package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** G: the Cabinet's second shelf. Every rule is worked out from data already stored, so old history counts. */
class CabinetTest {
    private var id = 1L
    private val today = LocalDate.of(2026, 11, 1)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(Weeks.zone).toInstant().toEpochMilli()

    private fun w(day: LocalDate, vararg e: ExerciseEntry, hour: Int = 18, minute: Int = 0, minutes: Int = 60) =
        Workout(++id, "W", null, at(day, hour, minute), at(day, hour, minute) + minutes * 60_000L, e.toList().ifEmpty { listOf(lift("bench", 20.0)) })

    private fun lift(ex: String, kg: Double, reps: Int = 5, t: Tracking = Tracking.WEIGHT_REPS, bw: Double? = null, lighter: Boolean = false) =
        ExerciseEntry(++id, ex, listOf(SetEntry(++id, weightKg = kg, reps = reps, done = true)), tracking = t, bodyweightKg = bw, lighterWeek = lighter)

    private fun data(ws: List<Workout>, bw: Double? = null, goal: Int = 1, streak: StreakState = StreakState(), shared: Long? = null) =
        AppData(history = ws.sortedByDescending { it.startedAt }, settings = Settings(weeklyGoal = goal, bodyweightKg = bw), streak = streak, firstSharedAt = shared)

    private fun entry(d: AppData, no: Int) = Cabinet.entries(d, today).first { it.no == no }
    private fun earned(d: AppData, no: Int) = entry(d, no).earnedOn

    /** One session every Monday for [n] weeks from [from]. */
    private fun weekly(from: LocalDate, n: Int) = (0 until n).map { w(from.plusWeeks(it.toLong())) }

    // ---- The catalogue ------------------------------------------------------------------------------

    @Test fun numberedAfterTheFirstNine_withNoGapsOrRepeats() {
        val all = Cabinet.entries(data(emptyList(), bw = 80.0), today)
        assertEquals((1..31).toList(), all.map { it.no })
        assertEquals("the first nine are untouched", listOf("First Rep", "Four Weeks Strong", "Half a Million", "PR Week", "The Comeback", "Century", "Iron Year", "The Million", "The Long Game"), all.take(9).map { it.title })
        assertTrue("locked ones show what to do", all.filter { !it.secret }.all { it.goal.isNotBlank() && it.goal != "Secret" })
        assertEquals("a few are secret", listOf(9, 29, 30, 31), all.filter { it.secret }.map { it.no })
        assertTrue("new secrets give nothing away", all.filter { it.secret && it.no > 9 }.all { it.goal == "Secret" })
    }

    @Test fun bodyweightOnesHideWithoutABodyweight() {
        assertEquals(emptyList<Int>(), Cabinet.entries(data(emptyList()), today).map { it.no }.filter { it in 17..19 })
        assertEquals(listOf(17, 18, 19), Cabinet.entries(data(emptyList(), bw = 80.0), today).map { it.no }.filter { it in 17..19 })
    }

    // ---- Consistency ---------------------------------------------------------------------------------

    @Test fun streaksOf8_12_26() {
        val start = LocalDate.of(2026, 1, 5)
        val d = data(weekly(start, 12))
        assertEquals("Sunday of the 8th week", start.plusWeeks(7).plusDays(6), earned(d, 10))
        assertEquals(start.plusWeeks(11).plusDays(6), earned(d, 11))
        assertNull(earned(d, 12))
        assertNull("an 11-week run isn't 12", earned(data(weekly(start, 11)), 11))
    }

    @Test fun tenSessionsInAMonth() {
        val oct = (1..10).map { w(LocalDate.of(2026, 10, it * 3 - 2)) }
        assertEquals(LocalDate.of(2026, 10, 28), earned(data(oct), 13))
        val split = (1..9).map { w(LocalDate.of(2026, 10, it * 3)) } + w(LocalDate.of(2026, 9, 30))
        assertNull("months don't add up", earned(data(split), 13))
    }

    @Test fun aCleanQuarter_skipsPausedWeeks() {
        // Weekly from late March through June 2026 (Q2: Mondays 6 Apr to 29 Jun), one week paused.
        val paused = LocalDate.of(2026, 5, 11)
        val ws = weekly(LocalDate.of(2026, 3, 30), 14).filter { Weeks.monday(Weeks.date(it.startedAt)) != paused }
        val streak = StreakState(pausedWeeks = listOf(Weeks.key(paused)))
        assertEquals(LocalDate.of(2026, 6, 30), earned(data(ws, streak = streak), 14))
        assertNull("the same gap unpaused is a missed week", earned(data(ws), 14))
        assertNull("a quarter that isn't over yet doesn't count", Cabinet.entries(data(ws, streak = streak), LocalDate.of(2026, 6, 20)).first { it.no == 14 }.earnedOn)
    }

    // ---- Volume --------------------------------------------------------------------------------------

    @Test fun heavyMonthAndFiveMillion() {
        // 50,000 kg sessions: two in a month is 100,000.
        fun big(day: LocalDate) = w(day, lift("squat", 5_000.0, reps = 10))
        assertEquals(LocalDate.of(2026, 10, 20), earned(data(listOf(big(LocalDate.of(2026, 10, 2)), big(LocalDate.of(2026, 10, 20)))), 15))
        assertNull(earned(data(listOf(big(LocalDate.of(2026, 9, 30)), big(LocalDate.of(2026, 10, 1)))), 15))
        val hundred = (0 until 100).map { big(LocalDate.of(2026, 1, 1).plusDays(it.toLong())) }
        assertEquals(LocalDate.of(2026, 1, 1).plusDays(99), earned(data(hundred), 16))
    }

    // ---- Strength ------------------------------------------------------------------------------------

    @Test fun strengthAgainstBodyweight() {
        val d = data(listOf(
            w(LocalDate.of(2026, 3, 1), lift("bench", 77.5)),
            w(LocalDate.of(2026, 3, 8), lift("bench", 80.0, reps = 1), lift("squat", 120.0)),
            w(LocalDate.of(2026, 3, 15), lift("deadlift", 157.5)),
        ), bw = 80.0)
        assertEquals(LocalDate.of(2026, 3, 8), earned(d, 17))
        assertEquals(LocalDate.of(2026, 3, 8), earned(d, 18))
        assertNull("157.5 isn't 2 × 80", earned(d, 19))
        // The bodyweight logged with the session wins over today's profile.
        val lighterThen = data(listOf(w(LocalDate.of(2026, 3, 15), lift("deadlift", 150.0).copy(bodyweightKg = 75.0))), bw = 80.0)
        assertEquals(LocalDate.of(2026, 3, 15), earned(lighterThen, 19))
    }

    @Test fun tenRecordsInAMonth() {
        // A first session sets the baseline; each heavier one after is a record.
        val ws = (0..10).map { i -> w(LocalDate.of(2026, 10, 1 + i * 2), lift("bench", 60.0 + i * 2.5)) }
        assertEquals(LocalDate.of(2026, 10, 21), earned(data(ws), 20))
        assertNull(earned(data(ws.dropLast(1)), 20))
    }

    // ---- Assisted ------------------------------------------------------------------------------------

    @Test fun unassisted() {
        val helped = w(LocalDate.of(2026, 5, 1), lift("pullup", 20.0, t = Tracking.ASSISTED))
        assertEquals("0 kg of help", LocalDate.of(2026, 5, 8), earned(data(listOf(helped, w(LocalDate.of(2026, 5, 8), lift("pullup", 0.0, t = Tracking.ASSISTED)))), 21))
        assertEquals("switched to Weighted", LocalDate.of(2026, 5, 8), earned(data(listOf(helped, w(LocalDate.of(2026, 5, 8), lift("pullup", 0.0, t = Tracking.BODYWEIGHT_REPS)))), 21))
        val machine = w(LocalDate.of(2026, 5, 1), lift("assisted_pullup", 30.0, t = Tracking.ASSISTED))
        assertEquals("from the machine to the bar", LocalDate.of(2026, 5, 8), earned(data(listOf(machine, w(LocalDate.of(2026, 5, 8), lift("pullup", 0.0, t = Tracking.BODYWEIGHT_REPS)))), 21))
        assertNull("never assisted", earned(data(listOf(w(LocalDate.of(2026, 5, 8), lift("pullup", 0.0, t = Tracking.BODYWEIGHT_REPS)))), 21))
    }

    // ---- Variety -------------------------------------------------------------------------------------

    @Test fun everyMuscleGroupInAWeek() {
        val oneEach = Library.strengthMuscles.map { m -> lift(Library.exercises.first { it.muscle == m && it.tracking == Tracking.WEIGHT_REPS }.id, 20.0) }
        val d = data(listOf(w(LocalDate.of(2026, 6, 1), *oneEach.take(6).toTypedArray()), w(LocalDate.of(2026, 6, 4), *oneEach.drop(6).toTypedArray())))
        assertEquals(LocalDate.of(2026, 6, 4), earned(d, 22))
        val acrossWeeks = data(listOf(w(LocalDate.of(2026, 6, 1), *oneEach.take(6).toTypedArray()), w(LocalDate.of(2026, 6, 8), *oneEach.drop(6).toTypedArray())))
        assertNull(earned(acrossWeeks, 22))
    }

    @Test fun fiftyDistinctLifts() {
        val ids = Library.exercises.filter { it.tracking == Tracking.WEIGHT_REPS }.take(50).map { it.id }
        val ws = ids.chunked(5).mapIndexed { i, c -> w(LocalDate.of(2026, 2, 1).plusDays(i.toLong()), *c.map { lift(it, 20.0) }.toTypedArray()) }
        assertEquals(LocalDate.of(2026, 2, 10), earned(data(ws), 23))
        assertNull("the same lift twice counts once", earned(data(ws + w(LocalDate.of(2026, 3, 1), lift(ids[0], 30.0)) - ws.last()), 23))
    }

    // ---- Habits --------------------------------------------------------------------------------------

    @Test fun lighterWeeksRepairsAndSharing() {
        val lighter = (1..10).map { w(LocalDate.of(2026, 1, 1).plusWeeks(it.toLong()), lift("squat", 90.0, lighter = true)) }
        assertEquals(LocalDate.of(2026, 1, 1).plusWeeks(10), earned(data(lighter), 24))
        assertNull(earned(data(lighter.drop(1)), 24))

        val quests = listOf("2026-W10", "2026-W02", "2026-W30", "2026-W21", "2026-W15", "2026-W40")
        assertEquals("Sunday of the 5th repaired week", Weeks.fromKey("2026-W30")!!.plusDays(6), earned(data(emptyList(), streak = StreakState(questsDone = quests)), 25))
        assertNull(earned(data(emptyList(), streak = StreakState(questsDone = quests.take(4))), 25))

        assertEquals(LocalDate.of(2026, 7, 4), earned(data(emptyList(), shared = at(LocalDate.of(2026, 7, 4), 12)), 26))
        assertNull(earned(data(emptyList()), 26))
    }

    // ---- Time ----------------------------------------------------------------------------------------

    @Test fun earlyBirdAndNightShift() {
        val early = (1..5).map { w(LocalDate.of(2026, 4, it), hour = 6, minute = 45) }
        val late = (1..5).map { w(LocalDate.of(2026, 4, 10 + it), hour = 21, minute = 5) }
        val d = data(early + late + w(LocalDate.of(2026, 4, 30), hour = 7))
        assertEquals(LocalDate.of(2026, 4, 5), earned(d, 27))
        assertEquals(LocalDate.of(2026, 4, 15), earned(d, 28))
        assertNull("7:00 isn't before 7", earned(data(early.drop(1) + w(LocalDate.of(2026, 4, 30), hour = 7)), 27))
        assertNull("20:59 isn't after 9 pm", earned(data((1..5).map { w(LocalDate.of(2026, 4, it), hour = 20, minute = 59) }), 28))
    }

    // ---- Secrets -------------------------------------------------------------------------------------

    @Test fun secrets() {
        assertEquals(LocalDate.of(2027, 1, 1), Cabinet.entries(data(listOf(w(LocalDate.of(2027, 1, 1)))), LocalDate.of(2027, 1, 2)).first { it.no == 29 }.earnedOn)
        assertEquals(LocalDate.of(2026, 8, 3), earned(data(listOf(w(LocalDate.of(2026, 8, 3), hour = 7), w(LocalDate.of(2026, 8, 3), hour = 19))), 30))
        assertEquals(LocalDate.of(2026, 8, 3), earned(data(listOf(w(LocalDate.of(2026, 8, 3), minutes = 125))), 31))
        assertNull(earned(data(listOf(w(LocalDate.of(2026, 8, 3), minutes = 119))), 31))
    }

    // ---- Old history counts ------------------------------------------------------------------------------

    @Test fun earnedFromHistoryAlreadyThere() {
        // Nothing is stored per achievement: data that predates them unlocks them on the first look.
        val d = data(weekly(LocalDate.of(2025, 1, 6), 30) + (1..5).map { w(LocalDate.of(2025, 9, it), hour = 6) })
        assertNotNull(earned(d, 10)); assertNotNull(earned(d, 12)); assertNotNull(earned(d, 27))
    }

    @Test fun weekKeysRoundTrip() {
        listOf(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 4)).forEach { d ->
            assertEquals(Weeks.monday(d), Weeks.fromKey(Weeks.key(d)))
        }
        assertNull(Weeks.fromKey("nonsense"))
    }
}
