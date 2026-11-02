package app.forge.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The context layer (docs/BUILD_BRIEF.md): what happened outside the gym and how recovered you feel.
 * Three sources: what the watch recorded in Health Connect (load), a one-line note turned into chips and
 * confirmed (what it was), and a three-tap check-in (how you feel). Pure: callers pass data and dates.
 */

/** Outside activity types Press reads from Health Connect. Strength sessions aren't read: Press logs those. */
@Serializable
enum class OutsideType(val label: String, val noun: String) {
    HIKING("Hike", "hiking"), WALKING("Walk", "walking"), RUNNING("Run", "running"),
    CYCLING("Ride", "cycling"), SWIMMING("Swim", "swimming"),
    /** "Other" or an unknown type on the watch. Only kept at [LifeContext.OTHER_MIN_MINUTES]+. */
    OTHER("“Other”", "other"),
}

/** One Health Connect exercise session, as recorded. Kept as evidence even after it's merged into an [Outing]. */
@Serializable
data class OutsideSession(
    /** The Health Connect record id. */
    val id: String,
    val type: OutsideType,
    val start: Long,
    val end: Long,
    val title: String? = null,
    val distanceM: Double? = null,
    val elevationGainM: Double? = null,
    /** The app that wrote it, e.g. "com.fitbit.FitbitMobile". */
    val source: String? = null,
) {
    val minutes: Int get() = ((end - start) / 60_000).toInt()
}

/** One day from Health Connect. Dates are ISO strings ("2026-10-24") so snapshots read back anywhere. */
@Serializable
data class ContextDay(
    val date: String,
    val steps: Long? = null,
    val distanceM: Double? = null,
    /** Only when the watch recorded sleep that night (ending on this date). */
    val sleepMinutes: Int? = null,
    val outsideSessions: List<OutsideSession> = emptyList(),
) {
    val localDate: LocalDate get() = LocalDate.parse(date)
}

/** One Health Connect read, newest day last. Also the format of the debug snapshot file. */
@Serializable
data class HealthSnapshot(val readAt: Long, val days: List<ContextDay>)

/** Same-day sessions with short gaps between them, shown as one outing ("Hike · 4h 13 · 2 sessions merged"). */
data class Outing(val date: LocalDate, val type: OutsideType, val sessions: List<OutsideSession>) {
    val start: Long get() = sessions.minOf { it.start }
    val end: Long get() = sessions.maxOf { it.end }
    /** Time actually recorded, not the span: the gaps between sessions don't count. */
    val minutes: Int get() = sessions.sumOf { it.minutes }
    val distanceM: Double? get() = sessions.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
    val elevationGainM: Double? get() = sessions.mapNotNull { it.elevationGainM }.takeIf { it.isNotEmpty() }?.sum()
}

// ---- The note ---------------------------------------------------------------------------------

@Serializable
enum class Intensity(val label: String) { LOW("Low"), MODERATE("Moderate"), HIGH("High") }

/** One activity from a note: "Hiking · 4 days · High intensity · Quads, Hamstrings, Glutes, Calves". */
@Serializable
data class NoteActivity(
    val type: String,
    val days: Int,
    val intensity: Intensity,
    /** Press's own muscle names ([Library.strengthMuscles]). */
    val muscles: List<String> = emptyList(),
    /** The words that set the intensity or muscles, e.g. "heavy pack" → high intensity. */
    val detail: String = "",
    /** The dates it covers (ISO), matched to the watch where it can be. */
    val dates: List<String> = emptyList(),
)

/** A note as confirmed by you. Nothing is saved before Confirm. */
@Serializable
data class ContextNote(
    val id: Long,
    val text: String,
    val activities: List<NoteActivity>,
    val confirmedAt: Long,
    /** False when the chips were filled in by hand (no key, no network, or a reply that failed validation). */
    val parsedByAi: Boolean = false,
)

// ---- The check-in -----------------------------------------------------------------------------

@Serializable
enum class SleepAnswer(val label: String, val said: String) { POOR("Poor", "poor sleep"), OK("OK", "OK sleep"), GOOD("Good", "good sleep") }

@Serializable
enum class Soreness(val label: String, val said: String) { NONE("None", "no soreness"), SOME("Some", "some soreness"), VERY("Very sore", "very sore") }

@Serializable
enum class Energy(val label: String, val said: String) { LOW("Low", "low energy"), OK("OK", "OK energy"), HIGH("High", "high energy") }

/** Three taps before a session. All null = skipped, so readiness is "partial: no check-in". */
@Serializable
data class CheckIn(
    val at: Long,
    /** The session it was asked for, so it's asked once per session. */
    val workoutId: Long? = null,
    val sleep: SleepAnswer? = null,
    val soreness: Soreness? = null,
    val energy: Energy? = null,
) {
    val skipped: Boolean get() = sleep == null && soreness == null && energy == null
}

/** A computed fact with a stable id. Evidence chips are only ever drawn from these. */
data class Fact(val id: String, val text: String)

object LifeContext {
    /** Days of activity read from Health Connect. */
    const val DAYS = 14L
    /** Days of steps read, for the 28-day daily average. */
    const val STEP_DAYS = 28L
    /** Same-day sessions this close together are one outing. */
    const val MERGE_GAP_MIN = 45
    /** "Other" or unknown sessions shorter than this are dropped (a stray tap on the watch). */
    const val OTHER_MIN_MINUTES = 60
    /** Check-ins kept in forge.json; older ones aren't used by anything. */
    const val CHECK_INS_KEPT = 60

    /** Whether a recorded session is worth reading at all. */
    fun keep(type: OutsideType, minutes: Int): Boolean = type != OutsideType.OTHER || minutes >= OTHER_MIN_MINUTES

    /** Merges same-day sessions with gaps of [MERGE_GAP_MIN] minutes or less. The originals stay inside each outing. */
    fun outings(sessions: List<OutsideSession>): List<Outing> = sessions
        .groupBy { Weeks.date(it.start) }.toSortedMap()
        .flatMap { (date, day) ->
            val runs = mutableListOf<MutableList<OutsideSession>>()
            day.sortedBy { it.start }.forEach { s ->
                val last = runs.lastOrNull()
                if (last != null && s.start - last.maxOf { it.end } <= MERGE_GAP_MIN * 60_000L) last += s else runs += mutableListOf(s)
            }
            runs.map { Outing(date, typeOf(it), it) }
        }

    fun outings(snapshot: HealthSnapshot): List<Outing> = outings(snapshot.days.flatMap { it.outsideSessions })

    /** An outing is what most of its time was: a hike with a walk either side is a hike. "Other" only if nothing else. */
    private fun typeOf(sessions: List<OutsideSession>): OutsideType =
        sessions.filter { it.type != OutsideType.OTHER }.groupBy { it.type }.maxByOrNull { (_, v) -> v.sumOf { it.minutes } }?.key ?: OutsideType.OTHER

    /** The outside type a note's activity names, if it's one the watch records. */
    fun typeFromNote(type: String): OutsideType? {
        val t = type.lowercase()
        return when {
            listOf("hik", "trek", "hill", "mountain", "scramb").any { it in t } -> OutsideType.HIKING
            listOf("walk", "stroll").any { it in t } -> OutsideType.WALKING
            listOf("run", "jog").any { it in t } -> OutsideType.RUNNING
            listOf("cycl", "bike", "biking", "ride").any { it in t } -> OutsideType.CYCLING
            "swim" in t -> OutsideType.SWIMMING
            else -> null
        }
    }

    /**
     * The dates a note's activity covers: the most recent [days] days on which the watch recorded that kind of
     * outing (or a long "Other", which the note can name), within the last [DAYS] days. Without a match on the
     * watch, the [days] days up to and including [today].
     */
    fun datesFor(activity: NoteActivity, outings: List<Outing>, today: LocalDate): List<LocalDate> {
        val wanted = typeFromNote(activity.type)
        val from = today.minusDays(DAYS - 1)
        val matched = if (wanted == null) emptyList() else outings
            .filter { !it.date.isBefore(from) && !it.date.isAfter(today) && (it.type == wanted || it.type == OutsideType.OTHER) }
            .map { it.date }.distinct().sortedDescending().take(activity.days).sorted()
        return matched.ifEmpty { (activity.days - 1 downTo 0).map { today.minusDays(it.toLong()) } }
    }

    /**
     * Outings with the confirmed notes laid over them: an "Other" outing on a date a note covers takes the note's
     * type ("hiking"). The watch keeps its duration and distance; the recorded sessions stay as they were.
     */
    fun withNotes(outings: List<Outing>, notes: List<ContextNote>): List<Outing> = outings.map { o ->
        if (o.type != OutsideType.OTHER) return@map o
        val named = notes.flatMap { it.activities }.firstOrNull { a -> o.date.toString() in a.dates }?.let { typeFromNote(it.type) }
        if (named != null) o.copy(type = named) else o
    }

    /** The check-in for session [workoutId], if one was answered or skipped. */
    fun checkInFor(checkIns: List<CheckIn>, workoutId: Long): CheckIn? = checkIns.lastOrNull { it.workoutId == workoutId }

    /** A check-in's answers as facts: "You said: poor sleep". Nothing for a skipped one. */
    fun facts(c: CheckIn): List<Fact> = listOfNotNull(
        c.sleep?.let { Fact("checkin.sleep", "You said: ${it.said}") },
        c.soreness?.let { Fact("checkin.soreness", "You said: ${it.said}") },
        c.energy?.let { Fact("checkin.energy", "You said: ${it.said}") },
    )

    // ---- Display ------------------------------------------------------------------------------

    /** "4h 13", "45 min". */
    fun duration(minutes: Int): String = if (minutes < 60) "$minutes min" else "${minutes / 60}h ${"%02d".format(minutes % 60)}"

    /** "9.5 km". */
    fun km(m: Double): String = "${Units.num(m / 1000)} km"

    /** "Hike · 4h 13 · 2 sessions merged", "“Other” · 3h 57 · 9.5 km · 540 m". */
    fun describe(o: Outing): String = listOfNotNull(
        o.type.label, duration(o.minutes),
        o.distanceM?.takeIf { it >= 100 }?.let(::km),
        o.elevationGainM?.takeIf { it >= 1 }?.let { "${it.roundToInt()} m" },
        if (o.sessions.size > 1) "${o.sessions.size} sessions merged" else null,
    ).joinToString(" · ")

    /** "19–25 Oct", "28 Oct – 3 Nov". */
    fun range(from: LocalDate, to: LocalDate): String =
        if (from.month == to.month) "${from.dayOfMonth}–${to.dayOfMonth} ${to.format(SHORT_MONTH)}"
        else "${from.dayOfMonth} ${from.format(SHORT_MONTH)} – ${to.dayOfMonth} ${to.format(SHORT_MONTH)}"

    /** "Fri 23". */
    fun day(d: LocalDate): String = d.format(DAY)

    private val SHORT_MONTH = DateTimeFormatter.ofPattern("MMM", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEE d", Locale.US)
}
