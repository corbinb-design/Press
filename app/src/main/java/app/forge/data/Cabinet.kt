package app.forge.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 7.4: achievements as a numbered back catalogue. Everything is worked out from what's already stored
 * (history, the streak, the profile), nothing extra is saved per achievement, so data from before an
 * entry existed counts toward it. Numbers never change; a new entry takes the next number.
 */
object Cabinet {
    enum class Cover { LIME, DARK, AMBER, ICE }
    enum class Glyph { CIRCLE, BARS, SQUARE, TRIANGLE, DOT, RING, DIAMOND, HALF, PLUS }

    /**
     * [goal] is the requirement shown while it's locked. A [secret] one shows neither its name nor its
     * requirement until it's earned.
     */
    data class Entry(
        val no: Int, val title: String, val goal: String, val cover: Cover, val glyph: Glyph,
        val earnedOn: LocalDate?, val secret: Boolean = false,
    )

    /** Assisted lifts and the lift they lead to, for "Unassisted". */
    private val unassistedOf = mapOf("assisted_pullup" to "pullup", "assisted_dip" to "dips")

    fun entries(d: AppData, today: LocalDate = Weeks.today()): List<Entry> {
        val history = d.history
        val weeklyGoal = d.settings.weeklyGoal
        val chrono = history.sortedBy { it.startedAt }
        val dates = chrono.map { Weeks.date(it.startedAt) }

        // Running tonnage → the date a threshold was crossed.
        fun tonnage(kg: Double): LocalDate? {
            var sum = 0.0
            chrono.forEachIndexed { i, w -> sum += Stats.volume(w); if (sum >= kg) return dates[i] }
            return null
        }

        // Consecutive weeks on goal → the Sunday the run reached [n].
        fun goalRun(n: Int): LocalDate? {
            val byWeek = chrono.filter { it.countsForGoal }.groupBy { Weeks.monday(Weeks.date(it.startedAt)) }
            val first = byWeek.keys.minOrNull() ?: return null
            var run = 0
            var m = first
            val last = Weeks.monday(today)
            while (!m.isAfter(last)) {
                run = if ((byWeek[m]?.size ?: 0) >= weeklyGoal) run + 1 else 0
                if (run >= n) return m.plusDays(6).coerceAtMost(today)
                m = m.plusWeeks(1)
            }
            return null
        }

        // The [n]th session that matches → its date.
        fun nth(n: Int, match: (Workout) -> Boolean): LocalDate? = chrono.filter(match).getOrNull(n - 1)?.let { Weeks.date(it.startedAt) }

        // Within one calendar month, the session on which a running [measure] first reached [target].
        fun inAMonth(target: Double, measure: (Workout) -> Double): LocalDate? {
            val sums = mutableMapOf<YearMonth, Double>()
            chrono.forEachIndexed { i, w ->
                val m = YearMonth.from(dates[i])
                val s = (sums[m] ?: 0.0) + measure(w)
                sums[m] = s
                if (s >= target) return dates[i]
            }
            return null
        }

        val prs = Stats.prsBySession(history)
        val prWeek = chrono.groupBy { Weeks.monday(Weeks.date(it.startedAt)) }.entries.sortedBy { it.key }
            .firstOrNull { (_, ws) -> ws.count { (prs[it.id] ?: 0) > 0 } >= 3 }
            ?.value?.filter { (prs[it.id] ?: 0) > 0 }?.getOrNull(2)?.let { Weeks.date(it.startedAt) }
        val comeback = dates.zipWithNext().firstOrNull { (a, b) -> ChronoUnit.DAYS.between(a, b) >= 14 }?.second

        val bw = d.settings.bodyweightKg
        fun strength(exerciseId: String, times: Double): LocalDate? = chrono.firstOrNull { w ->
            w.exercises.any { e ->
                e.exerciseId == exerciseId && e.tracking == Tracking.WEIGHT_REPS &&
                    e.sets.any { s -> Measure.counts(e, s) && Measure.load(e, s) >= times * (e.bodyweightKg ?: bw ?: return@any false) - 0.01 }
            }
        }?.let { Weeks.date(it.startedAt) }

        val hour = { w: Workout -> Instant.ofEpochMilli(w.startedAt).atZone(Weeks.zone).hour }
        val bodyweightOnes = listOf(
            Entry(17, "Bodyweight Bench", "Bench your bodyweight", Cover.LIME, Glyph.TRIANGLE, strength("bench", 1.0)),
            Entry(18, "Squat and a Half", "Squat 1.5× bodyweight", Cover.LIME, Glyph.TRIANGLE, strength("squat", 1.5)),
            Entry(19, "Double Bodyweight", "Deadlift 2× bodyweight", Cover.LIME, Glyph.TRIANGLE, strength("deadlift", 2.0)),
        )

        return listOf(
            Entry(1, "First Rep", "First session", Cover.LIME, Glyph.CIRCLE, dates.firstOrNull()),
            Entry(2, "Four Weeks Strong", "4 weeks on goal", Cover.DARK, Glyph.BARS, goalRun(4)),
            Entry(3, "Half a Million", "500,000 kg", Cover.AMBER, Glyph.SQUARE, tonnage(500_000.0)),
            Entry(4, "PR Week", "3 record sessions in a week", Cover.ICE, Glyph.TRIANGLE, prWeek),
            Entry(5, "The Comeback", "Back after two weeks off", Cover.DARK, Glyph.DOT, comeback),
            Entry(6, "Century", "100 sessions", Cover.LIME, Glyph.BARS, dates.getOrNull(99)),
            Entry(7, "Iron Year", "52-week streak", Cover.DARK, Glyph.BARS, goalRun(52)),
            Entry(8, "The Million", "1,000,000 kg", Cover.AMBER, Glyph.SQUARE, tonnage(1_000_000.0)),
            Entry(9, "The Long Game", "Keep lifting", Cover.ICE, Glyph.CIRCLE, dates.getOrNull(364), secret = true),
            // Consistency
            Entry(10, "Eight Weeks", "8-week streak", Cover.DARK, Glyph.BARS, goalRun(8)),
            Entry(11, "Twelve Weeks", "12-week streak", Cover.ICE, Glyph.BARS, goalRun(12)),
            Entry(12, "Half a Year", "26-week streak", Cover.LIME, Glyph.BARS, goalRun(26)),
            Entry(13, "Ten in a Month", "10 sessions in a month", Cover.LIME, Glyph.RING, inAMonth(10.0) { 1.0 }),
            Entry(14, "Clean Quarter", "Every planned week of a quarter on goal", Cover.ICE, Glyph.SQUARE, cleanQuarter(d, today)),
            // Volume
            Entry(15, "Heavy Month", "100,000 kg in a month", Cover.AMBER, Glyph.SQUARE, inAMonth(100_000.0) { Stats.volume(it) }),
            Entry(16, "Five Million", "5,000,000 kg", Cover.AMBER, Glyph.SQUARE, tonnage(5_000_000.0)),
        ) +
            // Strength: measured against bodyweight, so only shown once the profile has one.
            (if (bw != null) bodyweightOnes else emptyList()) +
            listOf(
                Entry(20, "Record Month", "10 records in a month", Cover.ICE, Glyph.TRIANGLE, inAMonth(10.0) { (prs[it.id] ?: 0).toDouble() }),
                // Assisted
                Entry(21, "Unassisted", "A rep with no assistance, after using some", Cover.ICE, Glyph.DIAMOND, unassisted(chrono)),
                // Variety
                Entry(22, "Full Body Week", "Every muscle group in one week", Cover.LIME, Glyph.PLUS, fullBodyWeek(d, chrono)),
                Entry(23, "Fifty Lifts", "50 different exercises logged", Cover.DARK, Glyph.HALF, fiftyLifts(chrono)),
                // Habits
                Entry(24, "Know When to Fold", "10 lighter weeks taken", Cover.AMBER, Glyph.HALF, nth(10) { w -> w.exercises.any { it.lighterWeek } }),
                Entry(25, "Repair Crew", "5 repair quests completed", Cover.DARK, Glyph.RING, d.streak.questsDone.mapNotNull(Weeks::fromKey).sorted().getOrNull(4)?.plusDays(6)),
                Entry(26, "Pass It On", "Share a template", Cover.ICE, Glyph.CIRCLE, d.firstSharedAt?.let(Weeks::date)),
                // Time
                Entry(27, "Early Bird", "5 sessions before 7 am", Cover.AMBER, Glyph.CIRCLE, nth(5) { hour(it) < 7 }),
                Entry(28, "Night Shift", "5 sessions after 9 pm", Cover.DARK, Glyph.CIRCLE, nth(5) { hour(it) >= 21 }),
                // Secret: revealed only once earned.
                Entry(29, "First Light", "Secret", Cover.LIME, Glyph.DIAMOND, nth(1) { Weeks.date(it.startedAt).let { d0 -> d0.monthValue == 1 && d0.dayOfMonth == 1 } }, secret = true),
                Entry(30, "Double Day", "Secret", Cover.ICE, Glyph.PLUS, dates.zipWithNext().firstOrNull { (a, b) -> a == b }?.second, secret = true),
                Entry(31, "The Long One", "Secret", Cover.AMBER, Glyph.RING, nth(1) { it.endedAt != null && it.endedAt - it.startedAt >= 2 * 3_600_000L }, secret = true),
            )
    }

    /**
     * The first calendar quarter, fully past, in which every week that wasn't paused hit the goal (weeks
     * are counted in the quarter their Monday falls in). Needs at least one planned week, and a quarter
     * that starts on or after the first session.
     */
    private fun cleanQuarter(d: AppData, today: LocalDate): LocalDate? {
        val first = d.history.minOfOrNull { it.startedAt }?.let(Weeks::date) ?: return null
        val paused = d.streak.pausedWeeks.toSet()
        val byWeek = d.history.filter { it.countsForGoal }.groupingBy { Weeks.monday(Weeks.date(it.startedAt)) }.eachCount()
        var q = LocalDate.of(first.year, (first.monthValue - 1) / 3 * 3 + 1, 1).let { if (it.isBefore(first)) it.plusMonths(3) else it }
        while (true) {
            val end = q.plusMonths(3).minusDays(1)
            if (!end.isBefore(today)) return null
            val mondays = generateSequence(Weeks.monday(q).let { if (it.isBefore(q)) it.plusWeeks(1) else it }) { it.plusWeeks(1) }.takeWhile { !it.isAfter(end) }.toList()
            val planned = mondays.filter { Weeks.key(it) !in paused }
            if (planned.isNotEmpty() && planned.all { (byWeek[it] ?: 0) >= d.settings.weeklyGoal }) return end
            q = q.plusMonths(3)
        }
    }

    /**
     * A counted rep with no assistance (0 kg assisted, or the lift done as Weighted) on a lift that was
     * done with assistance before. The assisted machine versions lead to their free lift.
     */
    private fun unassisted(chrono: List<Workout>): LocalDate? {
        val helped = mutableSetOf<String>()
        for (w in chrono) {
            for (e in w.exercises) {
                val lift = unassistedOf[e.exerciseId] ?: e.exerciseId
                val sets = e.sets.filter { Measure.counts(e, it) }
                val free = when (e.tracking) {
                    Tracking.ASSISTED -> sets.any { Measure.load(e, it) <= 0.0 }
                    Tracking.BODYWEIGHT_REPS -> sets.isNotEmpty()
                    else -> false
                }
                if (free && lift in helped) return Weeks.date(w.startedAt)
                if (e.tracking == Tracking.ASSISTED && sets.any { Measure.load(e, it) > 0.0 }) helped += lift
            }
        }
        return null
    }

    /** A Monday–Sunday week in which every muscle group Balance tracks got work, primary or secondary. */
    private fun fullBodyWeek(d: AppData, chrono: List<Workout>): LocalDate? {
        val groups = Library.strengthMuscles.toSet()
        val hit = mutableMapOf<LocalDate, MutableSet<String>>()
        for (w in chrono) {
            val week = hit.getOrPut(Weeks.monday(Weeks.date(w.startedAt))) { mutableSetOf() }
            w.exercises.filter { e -> e.tracking.isLifting && e.sets.any { Measure.counts(e, it) } }.forEach { e ->
                val ex = Session.exercise(d, e.exerciseId)
                week += ex.muscle; week += ex.secondary
            }
            if (week.containsAll(groups)) return Weeks.date(w.startedAt)
        }
        return null
    }

    private fun fiftyLifts(chrono: List<Workout>): LocalDate? {
        val seen = mutableSetOf<String>()
        for (w in chrono) {
            w.exercises.filter { e -> e.sets.any { it.done } }.forEach { seen += it.exerciseId }
            if (seen.size >= 50) return Weeks.date(w.startedAt)
        }
        return null
    }
}
