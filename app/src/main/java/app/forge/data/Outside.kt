package app.forge.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToLong

/** One merged outing as kept in forge.json: times and totals only, never the raw records. */
@Serializable
data class OutingSummary(
    val start: Long,
    val end: Long,
    /** Recorded time (gaps between merged sessions don't count). */
    val minutes: Int,
    val type: OutsideType,
    val distanceM: Double? = null,
    val climbM: Double? = null,
    /** How many watch sessions were merged into it. */
    val sessions: Int = 1,
    /** What the watch called it, when a confirmed note renamed it ("Other" → hiking). */
    val watchType: OutsideType? = null,
)

/**
 * One day of outside context, kept so the rules work without a live Health Connect read (the streak, Balance, the
 * Sunday issue and its 19:00 notification). Derived from Health Connect and confirmed notes; dates are ISO strings.
 */
@Serializable
data class OutsideDay(
    val date: String,
    val steps: Long? = null,
    val distanceM: Double? = null,
    val sleepMinutes: Int? = null,
    val outings: List<OutingSummary> = emptyList(),
    /** From confirmed notes covering this date: what it was and which muscles it loaded. */
    val noteTypes: List<String> = emptyList(),
    val noteMuscles: List<String> = emptyList(),
) {
    val localDate: LocalDate get() = LocalDate.parse(date)
}

/**
 * Outside load (brief §3): what counts, which muscles it marks as trained outside the gym, the outside rule for the
 * streak, and everyday movement. Pure. Outside activity is never turned into "equivalent sets".
 */
object Outside {
    /** A session counts as outside load from this long. */
    const val SESSION_MIN = 60
    /** Climbing this much in one outing is a high leg load. */
    const val HIGH_CLIMB_M = 300.0
    /** Streak: a week with this many counted outings, totalling [STREAK_MINUTES], meets the weekly goal. */
    const val STREAK_SESSIONS = 2
    const val STREAK_MINUTES = 180
    /** Days kept in forge.json. Health Connect only goes back about a month, so older weeks live only here. */
    const val KEEP_DAYS = 400L

    val LEGS = listOf("Quads", "Hamstrings", "Glutes", "Calves")
    private val counted = setOf(OutsideType.HIKING, OutsideType.WALKING, OutsideType.RUNNING, OutsideType.CYCLING, OutsideType.OTHER)

    /** Hiking, walking, running, cycling or "Other", for at least an hour. */
    fun counts(o: OutingSummary): Boolean = o.type in counted && o.minutes >= SESSION_MIN

    /** Legs for a counted hike, walk, run or ride; a counted "Other" only with 300 m+ of climbing (the note can name more). */
    fun muscles(o: OutingSummary): List<String> = when {
        !counts(o) -> emptyList()
        o.type == OutsideType.OTHER -> if ((o.climbM ?: 0.0) >= HIGH_CLIMB_M) LEGS else emptyList()
        else -> LEGS
    }

    fun summary(o: Outing) = OutingSummary(
        o.start, o.end, o.minutes, o.type, o.distanceM, o.elevationGainM, o.sessions.size,
        watchType = OutsideType.OTHER.takeIf { o.type != OutsideType.OTHER && o.sessions.all { it.type == OutsideType.OTHER } },
    )

    /**
     * A Health Connect read as days, with "Other" outings named by the notes that cover them. Watch sessions that were
     * really logged gym sessions ([isGym]) are dropped before merging, so they can't ride along inside an outing.
     */
    fun summarise(snapshot: HealthSnapshot, notes: List<ContextNote>, history: List<Workout> = emptyList()): List<OutsideDay> {
        val sessions = snapshot.days.flatMap { it.outsideSessions }.filterNot { isGym(it.start, it.end, history) }
        val outings = LifeContext.withNotes(LifeContext.outings(sessions), notes).groupBy { it.date.toString() }
        return snapshot.days.map { d ->
            OutsideDay(d.date, d.steps, d.distanceM, d.sleepMinutes, outings[d.date].orEmpty().map(::summary))
        }
    }

    /**
     * Lays the confirmed notes over [days]: each activity's type and muscles on each date it covers, and an "Other"
     * outing on that date takes the note's type (the watch's own label is kept in [OutingSummary.watchType]).
     */
    fun withNotes(days: List<OutsideDay>, notes: List<ContextNote>): List<OutsideDay> {
        val byDate = days.associate { it.date to it.copy(noteTypes = emptyList(), noteMuscles = emptyList()) }.toMutableMap()
        notes.flatMap { it.activities }.forEach { a ->
            a.dates.forEach { date ->
                val d = byDate[date] ?: OutsideDay(date)
                byDate[date] = d.copy(noteTypes = (d.noteTypes + a.type).distinct(), noteMuscles = (d.noteMuscles + a.muscles).distinct())
            }
        }
        return byDate.values.sortedBy { it.date }.map { d ->
            val named = d.noteTypes.firstNotNullOfOrNull(LifeContext::typeFromNote) ?: return@map d
            d.copy(outings = d.outings.map { o -> if (o.type == OutsideType.OTHER) o.copy(type = named, watchType = OutsideType.OTHER) else o })
        }
    }

    /**
     * A watch session that mostly overlaps a session logged in Press is that gym session, not outside activity. Real
     * case: the watch saved a logged Upper A as "Other" titled "CrossFit" (1 Nov), which counted toward the streak twice.
     */
    fun isGym(start: Long, end: Long, history: List<Workout>): Boolean = history.any { w ->
        val wEnd = w.endedAt ?: return@any false
        val overlap = minOf(end, wEnd) - maxOf(start, w.startedAt)
        overlap > 0 && overlap * 2 >= end - start
    }

    /**
     * The stored days with a fresh read laid over them: the read replaces the dates it covers, older days are kept
     * (up to [KEEP_DAYS]), outings that were really logged gym sessions are dropped, and the notes are applied again.
     */
    fun merge(stored: List<OutsideDay>, snapshot: HealthSnapshot?, notes: List<ContextNote>, today: LocalDate, history: List<Workout> = emptyList()): List<OutsideDay> {
        val fresh = snapshot?.let { summarise(it, notes, history) }.orEmpty().associateBy { it.date }
        val from = today.minusDays(KEEP_DAYS).toString()
        // Stored days are checked again too: the workout may have been logged after the read.
        val kept = (stored.associateBy { it.date } + fresh).values.filter { it.date >= from }
            .map { d -> d.copy(outings = d.outings.filterNot { isGym(it.start, it.end, history) }) }
        return withNotes(kept, notes)
    }

    fun between(days: List<OutsideDay>, from: LocalDate, to: LocalDate): List<OutsideDay> =
        days.filter { !it.localDate.isBefore(from) && !it.localDate.isAfter(to) }

    /** Counted outings that ended after [since] (the last note or dismissal), within the last [LifeContext.DAYS] days. */
    fun outingsSince(days: List<OutsideDay>, since: Long, today: LocalDate): List<OutingSummary> =
        between(days, today.minusDays(LifeContext.DAYS - 1), today).flatMap { it.outings }.filter { counts(it) && it.end > since }

    /** Counted outings in a Monday–Sunday week. */
    fun outingsInWeek(days: List<OutsideDay>, monday: LocalDate): List<OutingSummary> =
        between(days, monday, monday.plusDays(6)).flatMap { it.outings }.filter(::counts)

    /** The streak's outside rule: 2+ counted outings totalling 3h+ that week. */
    fun activeWeek(days: List<OutsideDay>, monday: LocalDate): Boolean {
        val o = outingsInWeek(days, monday)
        return o.size >= STREAK_SESSIONS && o.sumOf { it.minutes } >= STREAK_MINUTES
    }

    /** ISO keys of every week that meets the outside rule, oldest first. */
    fun activeWeeks(days: List<OutsideDay>): List<String> =
        days.map { Weeks.monday(it.localDate) }.distinct().sorted().filter { activeWeek(days, it) }.map(Weeks::key)

    /**
     * Muscles trained outside the gym between [from] and [to], each with what trained them ("hiking"). From counted
     * outings and from confirmed notes.
     */
    fun musclesBetween(days: List<OutsideDay>, from: LocalDate, to: LocalDate): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        between(days, from, to).forEach { d ->
            d.outings.forEach { o -> muscles(o).forEach { out.getOrPut(it) { mutableListOf() }.add(o.type.noun) } }
            d.noteMuscles.forEach { m -> out.getOrPut(m) { mutableListOf() }.addAll(d.noteTypes.map { it.lowercase() }.ifEmpty { listOf("your note") }) }
        }
        return out.mapValues { (_, v) -> v.distinct() }
    }

    /**
     * Rewrites the stored days from [snapshot] (if any) and the notes, and rebuilds the streak when the weeks that meet
     * the outside rule changed, or always with [force] (confirming a note).
     */
    fun sync(d: AppData, snapshot: HealthSnapshot?, today: LocalDate, force: Boolean = false): AppData {
        val days = merge(d.outsideDays, snapshot, d.contextNotes, today, d.history)
        // Weeks once counted stay counted, even after their days age out of Health Connect.
        val weeks = (d.streak.outsideWeeks + activeWeeks(days)).distinct().sorted()
        val changed = weeks != d.streak.outsideWeeks
        val streak = if (changed || force) Streaks.rebuild(d.streak.copy(outsideWeeks = weeks), d.history, d.settings.weeklyGoal, today) else d.streak
        return d.copy(outsideDays = days, streak = streak)
    }

    // ---- Everyday movement ----------------------------------------------------------------------

    const val HIGH_STEPS = 15_000L
    const val HIGH_STEPS_FACTOR = 1.5
    const val ABOVE_USUAL = 1.25
    const val M_PER_MILE = 1609.344

    data class Movement(
        val monday: LocalDate,
        val steps: Long,
        val distanceM: Double,
        val biggest: OutsideDay?,
        /** 15,000+ steps, or 1.5× the 28-day daily average, whichever is higher. */
        val highDays: List<OutsideDay>,
        val threshold: Long,
        /** Average daily steps over the 28 days to the week's Sunday, if there are any. */
        val dailyAverage: Double?,
        /** Average week over the four before this one, if there are at least seven days of them. */
        val usualWeek: Double?,
    ) {
        val aboveUsual: Boolean get() = usualWeek != null && usualWeek > 0 && steps >= usualWeek * ABOVE_USUAL
        /** Whether the Sunday issue gets an "Everyday movement" line. */
        val worthALine: Boolean get() = highDays.isNotEmpty() || aboveUsual
        val miles: Double get() = distanceM / M_PER_MILE
    }

    /** Steps and distance for the Monday–Sunday week, or null if the watch has no steps for it. */
    fun movement(days: List<OutsideDay>, monday: LocalDate): Movement? {
        val sunday = monday.plusDays(6)
        val week = between(days, monday, sunday).filter { it.steps != null }
        if (week.isEmpty()) return null
        val month = between(days, sunday.minusDays(27), sunday).mapNotNull { it.steps }
        val average = month.takeIf { it.isNotEmpty() }?.average()
        val threshold = maxOf(HIGH_STEPS, ((average ?: 0.0) * HIGH_STEPS_FACTOR).roundToLong())
        val before = between(days, monday.minusDays(28), monday.minusDays(1)).mapNotNull { it.steps }
        return Movement(
            monday, week.sumOf { it.steps!! }, week.sumOf { it.distanceM ?: 0.0 }, week.maxBy { it.steps!! },
            week.filter { it.steps!! >= threshold }, threshold, average, before.takeIf { it.size >= 7 }?.let { it.average() * 7 },
        )
    }
}
