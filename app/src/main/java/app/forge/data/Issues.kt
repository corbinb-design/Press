package app.forge.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Sunday issue (section 12): a short magazine page about each week, generated from the data with a
 * few plain rules so it always says something true and specific. Pure: callers pass the data and dates.
 * Issues are numbered from the first full week of use (the first Monday-to-Sunday week the app saw
 * from its start) and every finished week has one, so the archive reads like back issues.
 */
object Issues {
    val PUBLISH_AT: LocalTime = LocalTime.of(19, 0)

    /** [cardioMinutes] is set for cardio-only sessions, which draw as thin ice spines sized by time. */
    data class Spine(val day: LocalDate, val colorKey: String?, val volume: Double, val record: Boolean, val cardioMinutes: Int? = null)

    data class Issue(
        val number: Int, val monday: LocalDate, val headline: String, val standfirst: String, val paragraph: String,
        val spines: List<Spine>, val lifted: Double, val records: Int, val streakWeeks: Int, val nextWeek: String,
        /** Context layer: "2 hiking days · 24.8 km · 559 m climbed", and the everyday-movement line when it applies. */
        val outside: String? = null, val movement: String? = null, val outsideActive: Boolean = false,
    ) {
        val key: String get() = Weeks.key(monday)
    }

    /** Monday of issue No. 1: the week of the first session if it was a Monday, else the week after. */
    fun firstWeek(history: List<Workout>): LocalDate? {
        val first = history.minOfOrNull { it.startedAt }?.let(Weeks::date) ?: return null
        val monday = Weeks.monday(first)
        return if (first == monday) monday else monday.plusWeeks(1)
    }

    /** The newest week with an issue out at [now]: this week from Sunday 19:00, else last week. */
    fun latestWeek(now: LocalDateTime): LocalDate {
        val monday = Weeks.monday(now.toLocalDate())
        val publish = monday.plusDays(6).atTime(PUBLISH_AT)
        return if (!now.isBefore(publish)) monday else monday.minusWeeks(1)
    }

    /** The newest issue out at [now], if any. */
    fun latest(d: AppData, now: LocalDateTime): Issue? {
        val first = firstWeek(d.history) ?: return null
        val last = latestWeek(now)
        return if (last.isBefore(first)) null else issue(d, last, first)
    }

    /** Every issue out at [now], newest first. */
    fun archive(d: AppData, now: LocalDateTime): List<Issue> {
        val first = firstWeek(d.history) ?: return emptyList()
        val last = latestWeek(now)
        if (last.isBefore(first)) return emptyList()
        return generateSequence(last) { it.minusWeeks(1) }.takeWhile { !it.isBefore(first) }.map { issue(d, it, first) }.toList()
    }

    fun number(first: LocalDate, monday: LocalDate) = ChronoUnit.WEEKS.between(first, monday).toInt() + 1

    fun issue(d: AppData, monday: LocalDate, first: LocalDate = firstWeek(d.history) ?: monday): Issue {
        val goal = d.settings.weeklyGoal
        val unit = d.settings.unit
        val sessions = Weeks.allIn(d.history, monday).sortedBy { it.startedAt }
        val prsBy = Stats.prsBySession(d.history)
        val records = sessions.sumOf { prsBy[it.id] ?: 0 }
        val lifted = sessions.sumOf { Stats.volume(it) }
        val k = Weeks.key(monday)
        val paused = k in d.streak.pausedWeeks
        val n = sessions.count { it.countsForGoal }
        val colors = d.templates.associate { it.id to it.colorKey }

        // Context layer: the week outside the gym (counted outings), and whether it met the streak's outside rule.
        val outsideDays = Outside.between(d.outsideDays, monday, monday.plusDays(6)).filter { it.outings.any(Outside::counts) }
        val outsideActive = Outside.activeWeek(d.outsideDays, monday)
        val outCount = outsideDays.size

        val headline = when {
            outsideActive && n == 0 -> "${words(outCount).cap()} ${if (outCount == 1) "day" else "days"} outside."
            outsideActive && n < goal -> "${words(n).cap()} in the gym, ${words(outCount)} outside."
            paused && n == 0 -> "A week off, on purpose."
            k in d.streak.comebacksDone -> "The comeback."
            n == 0 -> "A quiet week."
            n == goal -> "${words(n).cap()} for ${words(goal)}."
            n > goal -> "${words(n).cap()} sessions. Goal beaten."
            else -> "${words(n).cap()} of ${words(goal)}."
        }

        // "Your best week since August": the last earlier week that lifted more, if it was a while ago.
        val earlier = (1..104).asSequence().map { monday.minusWeeks(it.toLong()) }
            .takeWhile { !it.isBefore(Weeks.monday(Weeks.date(d.history.minOfOrNull { w -> w.startedAt } ?: 0L))) }
        val beaten = earlier.firstOrNull { m -> Weeks.allIn(d.history, m).sumOf { Stats.volume(it) } >= lifted && lifted > 0 }
        val standfirst = when {
            outsideActive && n == 0 -> "No gym, and the streak still counts."
            outsideActive && n < goal -> "The days outside count toward the streak."
            n == 0 && paused -> "Rest is part of the plan. The streak waits for you."
            n == 0 -> "Nothing logged. Next week is a fresh page."
            lifted > 0 && beaten == null && earlier.any() -> "Your best week yet."
            beaten != null && ChronoUnit.WEEKS.between(beaten, monday) >= 4 ->
                "Your best week since ${beaten.plusWeeks(1).format(MONTH)}."
            records > 0 -> "${words(records).cap()} new ${if (records == 1) "record" else "records"}."
            n >= goal -> "Goal hit, streak kept."
            else -> "Every session counts toward the next one."
        }

        val days = sessions.map { Weeks.date(it.startedAt).format(DAY) }.distinct()
        val paragraph = if (n == 0) (if (outCount > 0) "No gym sessions this week; ${words(outCount)} ${if (outCount == 1) "day" else "days"} outside." else "No sessions this week.") else buildString {
            append("You trained ${list(days)}, lifted ${Units.grouped(lifted, unit)} ${unit.label}")
            append(if (records > 0) " and set ${words(records)} ${if (records == 1) "record" else "records"}." else ".")
            // The template that lifted the most, and the lift with the biggest gain over the last three months.
            val carried = sessions.maxByOrNull { Stats.volume(it) }?.name
            val gain = sessions.flatMap { w -> w.exercises.map { it.exerciseId } }.distinct().mapNotNull { id ->
                val series = Stats.e1rmSeries(d.history.filter { Weeks.date(it.startedAt).isBefore(monday.plusWeeks(1)) }, id)
                val from = series.firstOrNull { Weeks.date(it.first).isAfter(monday.minusMonths(3)) } ?: return@mapNotNull null
                Triple(id, series.last().second - from.second, Weeks.date(from.first))
            }.filter { it.second >= 1 }.maxByOrNull { it.second }
            if (carried != null && gain != null) {
                val name = Session.exercise(d, gain.first).name
                append(" $carried carried the week: your $name is up ${Units.w(gain.second.roundToInt().toDouble(), unit)} ${unit.label} since ${gain.third.format(MONTH)}.")
            }
        }

        val spines = sessions.map { w ->
            Spine(
                Weeks.date(w.startedAt), w.templateId?.let { colors[it] } ?: Library.colorKeyFor(w.name), Stats.volume(w), (prsBy[w.id] ?: 0) > 0,
                cardioMinutes = if (w.cardioOnly) Measure.roundMinutes(w.exercises.sumOf { e -> e.sets.filter { it.done }.sumOf { it.durationSec ?: 0 } }) else null,
            )
        }
        // The streak as it stood that Sunday: replayed from history up to that week (pauses kept).
        val streak = Streaks.evaluate(
            StreakState(pausedWeeks = d.streak.pausedWeeks, outsideWeeks = d.streak.outsideWeeks), d.history.filter { Weeks.date(it.startedAt).isBefore(monday.plusWeeks(1)) },
            goal, monday.plusWeeks(1),
        ).weeks
        return Issue(
            number(first, monday), monday, headline, standfirst, paragraph, spines, lifted, records, streak, nextWeek(d, monday),
            outsideLine(outsideDays), movementLine(d.outsideDays, monday), outsideActive,
        )
    }

    /** "Next week": planned lighter weeks and stalls, then the most under-trained muscle not trained outside the gym. */
    fun nextWeek(d: AppData, monday: LocalDate): String {
        val now = monday.plusDays(6).atTime(PUBLISH_AT).atZone(Weeks.zone).toInstant().toEpochMilli()
        val notes = mutableListOf<String>()
        val rules = d.templates.flatMap { it.exercises }
        rules.filter { it.lighterNextKg != null }.map { it.exerciseId }.distinct().firstOrNull()?.let {
            notes += "${Session.exercise(d, it).name} has a lighter week planned."
        } ?: rules.map { it.exerciseId }.distinct().firstOrNull { Progression.liftStall(d.templates, d.history, it, now, Session.exercise(d, it).takeIf(Library::canSwitchAssist)?.tracking) != null }?.let {
            notes += "${Session.exercise(d, it).name} is due a lighter week."
        }
        val sets = Stats.setsPerMuscle(d.history, { Session.exercise(d, it).muscle }, today = monday.plusDays(6), secondaryOf = { Session.exercise(d, it).secondary })
        // Context layer: muscles trained outside the gym that week aren't "short"; nothing is turned into sets.
        val outside = Outside.musclesBetween(d.outsideDays, monday, monday.plusDays(6)).keys
        val trained = rules.map { Session.exercise(d, it.exerciseId).muscle }.toSet().intersect(Library.strengthMuscles.toSet()) - outside
        trained.filter { (sets[it] ?: 0.0) < 8 }.minByOrNull { sets[it] ?: 0.0 }?.let { m ->
            val short = kotlin.math.ceil(10 - (sets[m] ?: 0.0)).toInt()
            notes += "$m ${if (m.endsWith("s")) "are" else "is"} $short ${if (short == 1) "set" else "sets"} short of the 10–20 target."
        }
        return notes.joinToString(" ").ifEmpty { "Same plan. Keep the streak going." }
    }

    /** "2 hiking days · 24.8 km · 559 m climbed" (or "outside days" when the kinds differ), from the watch only. */
    fun outsideLine(days: List<OutsideDay>): String? {
        if (days.isEmpty()) return null
        val outings = days.flatMap { it.outings }.filter(Outside::counts)
        val kind = outings.map { it.type }.distinct().singleOrNull()?.noun ?: "outside"
        val km = outings.sumOf { it.distanceM ?: 0.0 }
        val climb = outings.sumOf { it.climbM ?: 0.0 }
        return listOfNotNull(
            "${days.size} $kind ${if (days.size == 1) "day" else "days"}",
            km.takeIf { it >= 100 }?.let(LifeContext::km),
            climb.takeIf { it >= 1 }?.let { "%,d m climbed".format(it.roundToInt()) },
        ).joinToString(" · ")
    }

    /** "You covered serious ground: 116,045 steps and 50.3 miles this week." Only with a high-step day or a big week. */
    fun movementLine(days: List<OutsideDay>, monday: LocalDate): String? = Outside.movement(days, monday)?.takeIf { it.worthALine }?.let {
        "You covered serious ground: %,d steps and %s miles this week.".format(it.steps, Units.num(it.miles))
    }

    /** The facts a week's issue may cite: the outside days, everyday movement, gym sessions and the streak. */
    fun facts(d: AppData, monday: LocalDate): List<Fact> {
        val i = issue(d, monday)
        val n = Weeks.sessionsIn(d.history, monday).size
        return Rules.outsideFacts(d, monday) + Rules.movementFacts(d.outsideDays, monday) + listOfNotNull(
            Fact("issue.gymSessions", "$n gym ${if (n == 1) "session" else "sessions"}"),
            Fact("issue.records", "${i.records} ${if (i.records == 1) "record" else "records"}"),
            Fact("streak.weeks", "Streak: ${i.streakWeeks} ${if (i.streakWeeks == 1) "week" else "weeks"}"),
            Fact("streak.activeWeek", "Active week: 2+ outside sessions, 3h+").takeIf { i.outsideActive },
        )
    }

    /** Chips when there's no AI reply: the week outside, the active-week rule, high-step days and gym sessions. */
    fun templateEvidence(facts: List<Fact>): List<String> = facts.map { it.id }.filter {
        it.startsWith("outside.week.") || it == "streak.activeWeek" || it.startsWith("movement.highStepDay.") || it == "issue.gymSessions"
    }

    /** When the next issue goes out: the coming Sunday at 19:00 local time (or next week's, if past). */
    fun nextPublish(now: ZonedDateTime): ZonedDateTime {
        val sunday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(PUBLISH_AT).atZone(now.zone)
        return if (sunday.isAfter(now)) sunday else sunday.plusWeeks(1)
    }

    fun weekLabel(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        return if (monday.month == sunday.month) "Week of ${monday.dayOfMonth}–${sunday.dayOfMonth} ${sunday.format(FULL_MONTH)}"
        else "Week of ${monday.dayOfMonth} ${monday.format(SHORT_MONTH)} – ${sunday.dayOfMonth} ${sunday.format(SHORT_MONTH)}"
    }

    private val MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.US)
    private val FULL_MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.US)
    private val SHORT_MONTH = DateTimeFormatter.ofPattern("MMM", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEEE", Locale.US)

    private val WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    fun words(n: Int) = WORDS.getOrNull(n) ?: n.toString()
    private fun String.cap() = replaceFirstChar { it.uppercase() }
    private fun list(items: List<String>) = when (items.size) {
        0 -> ""; 1 -> items[0]; else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}
