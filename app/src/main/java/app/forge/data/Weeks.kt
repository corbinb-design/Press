package app.forge.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

/** Monday–Sunday weeks in local time. */
object Weeks {
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun date(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
    fun today(): LocalDate = LocalDate.now(zone)
    fun monday(d: LocalDate): LocalDate = d.with(DayOfWeek.MONDAY)

    /** ISO week key, e.g. "2026-W42". */
    fun key(d: LocalDate): String = "%d-W%02d".format(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    /** The Monday of a [key] ("2026-W42"), or null if it isn't one. */
    fun fromKey(k: String): LocalDate? = runCatching {
        val (y, w) = k.split("-W").map { it.toInt() }
        LocalDate.of(y, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, w.toLong()).with(DayOfWeek.MONDAY)
    }.getOrNull()

    fun inWeek(w: Workout, monday: LocalDate): Boolean = date(w.startedAt).let { !it.isBefore(monday) && it.isBefore(monday.plusWeeks(1)) }

    /** Sessions that count toward the weekly goal (short or switched-off cardio doesn't; see [Workout.countsForGoal]). */
    fun sessionsIn(history: List<Workout>, monday: LocalDate) = history.filter { it.countsForGoal && inWeek(it, monday) }

    /** Every session that week, for showing (the shelf, an issue's spines). */
    fun allIn(history: List<Workout>, monday: LocalDate) = history.filter { inWeek(it, monday) }

    /** Days left in the week including today (Thursday → 4). */
    fun daysLeft(today: LocalDate): Int = 8 - today.dayOfWeek.value

    /** Training week number: week 1 is the week of the first session (or this week if there is none). */
    fun weekOfUse(history: List<Workout>, today: LocalDate = today()): Int {
        val first = history.minOfOrNull { it.startedAt }?.let(::date) ?: today
        return ChronoUnit.WEEKS.between(monday(first), monday(today)).toInt() + 1
    }
}
