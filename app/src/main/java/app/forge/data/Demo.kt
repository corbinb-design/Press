package app.forge.data

import java.time.LocalDate
import java.util.Random

/**
 * Twelve weeks of believable training for screenshots and first-run exploring.
 *
 * - Most weeks hit a goal of 4; one week has 3 and one has 2 (each uses a freeze), one has 5, and one
 *   week is paused for a holiday. The current week is partly done.
 * - Templates rotate Push → Pull → Legs. Every fourth time a template comes round is a "good day": the
 *   load steps up and the target reps are hit on every set. Other days stay at that load with the
 *   occasional rep short. So records land only on good days, about one session in four.
 */
object Demo {
    /** Sessions per week, oldest first. 0 = the paused week. */
    val weekPattern = listOf(4, 4, 4, 4, 3, 4, 5, 4, 4, 0, 2, 4)
    const val PAUSED_INDEX = 9

    private val base = mapOf(
        "incline_db" to 22.0, "bench" to 67.5, "db_shoulder" to 18.0, "lat_raise" to 8.0, "pushdown" to 25.0,
        "pullup" to 0.0, "bb_row" to 57.5, "face_pull" to 17.5, "rear_fly" to 6.0, "hammer" to 12.0,
        "squat" to 85.0, "rdl" to 75.0, "leg_press" to 150.0, "leg_curl" to 37.5, "standing_calf" to 60.0,
    )

    /** Days of the week (0 = Monday) trained, by sessions that week. */
    private fun days(n: Int) = when (n) { 2 -> listOf(1, 5); 3 -> listOf(0, 2, 4); 4 -> listOf(0, 1, 3, 5); 5 -> listOf(0, 1, 2, 4, 5); else -> emptyList() }

    private fun step(eq: Equipment) = when (eq) {
        Equipment.BARBELL -> 2.5; Equipment.DUMBBELL -> 1.0; Equipment.MACHINE -> 5.0
        Equipment.CABLE -> 2.5; Equipment.KETTLEBELL -> 2.0; Equipment.BODYWEIGHT -> 0.0
        Equipment.SMITH -> 2.5; Equipment.BANDS, Equipment.CARDIO, Equipment.OTHER -> 0.0
    }

    data class Result(val history: List<Workout>, val pausedWeek: String)

    fun build(templates: List<Template>, exercise: (String) -> Exercise, today: LocalDate): Result {
        val rnd = Random(7)
        val thisMonday = Weeks.monday(today)
        val first = thisMonday.minusWeeks(weekPattern.size.toLong())
        val rotation = templates.take(3).ifEmpty { Library.defaultTemplates }
        val seen = mutableMapOf<Long, Int>()          // occurrences per template
        val sessions = mutableListOf<Workout>()

        val dates = weekPattern.flatMapIndexed { i, n -> days(n).map { first.plusWeeks(i.toLong()).plusDays(it.toLong()) } } +
            listOf(0, 1, 3).map { thisMonday.plusDays(it.toLong()) }.filter { it.isBefore(today) } // this week so far

        dates.forEachIndexed { i, date ->
            val t = rotation[i % rotation.size]
            val k = seen.getOrDefault(t.id, 0).also { seen[t.id] = it + 1 }
            val goodDay = k % 4 == 2
            val level = (k + 2) / 4 // bumps once per good day, held until the next one
            val start = date.atTime(17 + rnd.nextInt(3), rnd.nextInt(4) * 15).atZone(Weeks.zone).toInstant().toEpochMilli()
            sessions += Workout(
                newId(), t.name, t.id, start, start + (48 + rnd.nextInt(25)) * 60_000L,
                exercises = t.exercises.map { te ->
                    val ex = exercise(te.exerciseId)
                    val kg = (base[te.exerciseId] ?: 20.0).let { if (it == 0.0) 0.0 else it + level * step(ex.equipment) }
                    ExerciseEntry(
                        newId(), te.exerciseId, perHand = ex.equipment == Equipment.DUMBBELL, tracking = ex.tracking,
                        sets = List(te.sets) { s ->
                            // Good days hit the top of the range on every set; other days fall a rep short here and there,
                            // and the very first time sets the bar.
                            val reps = if (goodDay || k == 0) te.repMax else (te.repMax - (if (s == 0) rnd.nextInt(2) else 1 + rnd.nextInt(2))).coerceAtLeast(1)
                            SetEntry(newId(), weightKg = kg, reps = reps, done = true)
                        },
                    )
                },
            )
        }
        // A few short cardio sessions on rest days (under 20 minutes, so they don't count toward the goal):
        // they show as thin ice spines on the shelf and fill the Cardio card.
        listOf(2 to 6, 5 to 6, 8 to 2, 11 to 6).forEach { (week, day) ->
            val date = first.plusWeeks(week.toLong()).plusDays(day.toLong())
            if (!date.isBefore(today)) return@forEach
            val start = date.atTime(8, 30).atZone(Weeks.zone).toInstant().toEpochMilli()
            val min = 15 + rnd.nextInt(4)
            sessions += Workout(
                newId(), "Cardio", null, start, start + min * 60_000L, countsForGoal = false,
                exercises = listOf(ExerciseEntry(newId(), if (week % 2 == 0) "rower" else "outdoor_run", tracking = Tracking.CARDIO, sets = listOf(
                    SetEntry(newId(), done = true, durationSec = min * 60, distanceM = if (week % 2 == 0) 3_400.0 + rnd.nextInt(300) else 2_600.0 + rnd.nextInt(400), heartRate = 138 + rnd.nextInt(12)),
                ))),
            )
        }
        return Result(sessions.sortedByDescending { it.startedAt }, Weeks.key(first.plusWeeks(PAUSED_INDEX.toLong())))
    }
}
