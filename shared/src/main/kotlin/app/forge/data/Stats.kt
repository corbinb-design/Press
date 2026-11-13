package app.forge.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import kotlin.math.roundToInt

object Stats {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Epley estimated one-rep max. */
    fun e1rm(weightKg: Double, reps: Int): Double = when {
        reps <= 0 -> 0.0
        reps == 1 -> weightKg
        else -> weightKg * (1 + reps / 30.0)
    }

    fun setLoad(entry: ExerciseEntry, set: SetEntry): Double = Measure.load(entry, set)

    /** Kilograms lifted (see [Measure.volume]: assisted, timed, carried and cardio work add nothing). */
    fun volume(entry: ExerciseEntry): Double = entry.sets.sumOf { Measure.volume(entry, it) }

    fun volume(w: Workout): Double = w.exercises.sumOf { volume(it) }

    // Sets are strength work; a cardio entry isn't a set. A skipped set isn't one either.
    fun doneSets(w: Workout) = w.exercises.filter { it.tracking.isLifting }.sumOf { e -> e.sets.count { it.done } }
    fun totalSets(w: Workout) = w.exercises.filter { it.tracking.isLifting }.sumOf { e -> e.sets.count { !it.skipped } }
    fun skippedSets(w: Workout) = w.exercises.filter { it.tracking.isLifting }.sumOf { e -> e.sets.count { it.skipped } }

    /** Sets still to do: not ticked and not skipped. The finish check (5.1) asks about these. */
    fun openSets(e: ExerciseEntry) = e.sets.indices.filter { !e.sets[it].done && !e.sets[it].skipped }

    /** Every set ticked or skipped: the exercise is over. */
    fun finished(e: ExerciseEntry) = e.sets.isNotEmpty() && e.sets.all { it.done || it.skipped }

    /**
     * Sets from the most recent finished workout that included this exercise; with [tracking], the most
     * recent one logged that way (after an Assisted / Weighted switch, assistance isn't added weight).
     */
    fun previousSets(history: List<Workout>, exerciseId: String, tracking: Tracking? = null): List<SetEntry> =
        history.asSequence()
            .mapNotNull { w -> w.exercises.firstOrNull { it.exerciseId == exerciseId && (tracking == null || it.tracking == tracking) } }
            .firstOrNull()?.sets.orEmpty()

    data class Best(val weightKg: Double, val reps: Int, val e1rm: Double, val at: Long)

    /** The best set ever by [Measure.score]; [Best.e1rm] holds that score. */
    fun best(history: List<Workout>, exerciseId: String): Best? {
        var best: Best? = null
        for (w in history) for (e in w.exercises) if (e.exerciseId == exerciseId) for (s in e.sets) {
            if (!Measure.counts(e, s)) continue
            val est = Measure.score(e, s)
            if (best == null || est > best.e1rm) best = Best(setLoad(e, s), s.reps ?: 0, est, w.startedAt)
        }
        return best
    }

    /** Exercises in [w] that set a record (see [sessionPrs]). */
    fun prCount(w: Workout, prior: List<Workout>): Int = sessionPrs(w, prior).size

    fun daysAgo(ts: Long): Long =
        ChronoUnit.DAYS.between(Instant.ofEpochMilli(ts).atZone(zone).toLocalDate(), LocalDate.now(zone))

    fun agoLabel(ts: Long?): String = when (val d = ts?.let(::daysAgo)) {
        null -> "NOT YET PERFORMED"
        0L -> "LAST: TODAY"
        1L -> "LAST: YESTERDAY"
        else -> "LAST: $d DAYS AGO"
    }

    /** Consecutive ISO weeks (ending this week or last) with at least one workout. */
    fun weekStreak(history: List<Workout>): Int {
        val weeks = history.map { weekKey(it.startedAt) }.toSet()
        var cursor = LocalDate.now(zone)
        if (weekKey(cursor) !in weeks) cursor = cursor.minusWeeks(1)
        var n = 0
        while (weekKey(cursor) in weeks) { n++; cursor = cursor.minusWeeks(1) }
        return n
    }

    private fun weekKey(ts: Long) = weekKey(Instant.ofEpochMilli(ts).atZone(zone).toLocalDate())
    private fun weekKey(d: LocalDate) = d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /** Total volume per week for the last [n] weeks, oldest first. Label is the Monday's date. */
    fun weeklyVolume(history: List<Workout>, n: Int = 8): List<Pair<LocalDate, Double>> {
        val monday = LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY)
        return (n - 1 downTo 0).map { i ->
            val start = monday.minusWeeks(i.toLong())
            val vol = history.filter {
                val d = Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
                !d.isBefore(start) && d.isBefore(start.plusWeeks(1))
            }.sumOf { volume(it) }
            start to vol
        }
    }

    /**
     * Best score per session for one exercise, oldest first: the estimated 1RM for weight × reps, and the
     * comparable [Measure.score] for the other types (always higher = better).
     */
    fun e1rmSeries(history: List<Workout>, exerciseId: String, tracking: Tracking? = null): List<Pair<Long, Double>> =
        history.mapNotNull { w ->
            val e = w.exercises.firstOrNull { it.exerciseId == exerciseId && (tracking == null || it.tracking == tracking) } ?: return@mapNotNull null
            val m = e.sets.filter { Measure.counts(e, it) }.maxOfOrNull { Measure.score(e, it) } ?: return@mapNotNull null
            w.startedAt to m
        }.sortedBy { it.first }

    fun durationMin(w: Workout) = (((w.endedAt ?: System.currentTimeMillis()) - w.startedAt) / 60000.0).roundToInt()

    fun durationMs(w: Workout) = (w.endedAt ?: System.currentTimeMillis()) - w.startedAt

    // ---- Ghost values ---------------------------------------------------------------------

    /**
     * What a set shows before it's edited: its own values, else the same set last session,
     * else the nearest earlier set today. Ticking a set logs exactly these.
     */
    fun ghost(entry: ExerciseEntry, index: Int, previous: List<SetEntry>): Pair<Double?, Int?> =
        ghostValues(entry, index, previous).let { it.weightKg to it.reps }

    /** [ghost] for every field: time, distance, level and heart rate fill the same way. */
    fun ghostValues(entry: ExerciseEntry, index: Int, previous: List<SetEntry>): SetEntry {
        val s = entry.sets.getOrNull(index)
        val earlier = entry.sets.take(index)
        fun <T> pick(f: (SetEntry) -> T?): T? = s?.let(f) ?: previous.getOrNull(index)?.let(f) ?: earlier.lastOrNull { f(it) != null }?.let(f)
        return SetEntry(
            s?.id ?: 0, s?.type ?: SetType.NORMAL, pick { it.weightKg }, pick { it.reps }, s?.done ?: false,
            pick { it.durationSec }, pick { it.distanceM }, pick { it.level }, pick { it.heartRate },
        )
    }

    // ---- Records --------------------------------------------------------------------------

    /** A set that beat what came before it, phrased for the live toast (4.3). */
    data class SetPr(val detail: String)

    /**
     * Checks a just-logged set against all earlier sessions and earlier sets today.
     * A lift's first-ever session sets the baseline and isn't celebrated.
     */
    fun setPr(entry: ExerciseEntry, setIndex: Int, prior: List<Workout>, unit: WeightUnit): SetPr? {
        val set = entry.sets.getOrNull(setIndex) ?: return null
        if (!Measure.counts(entry, set)) return null
        // Only sets logged the same way compare: assistance and added weight are different numbers.
        val before = prior.flatMap { w -> w.exercises.filter { it.exerciseId == entry.exerciseId && it.tracking == entry.tracking }.flatMap { e -> e.sets.filter { Measure.counts(e, it) }.map { e to it } } } +
            entry.sets.take(setIndex).filter { Measure.counts(entry, it) }.map { entry to it }
        if (before.isEmpty()) return null
        val t = entry.tracking
        val shown = Measure.format(entry, set, unit)
        val load = setLoad(entry, set)
        when (t) {
            Tracking.DURATION -> {
                val longest = before.maxOf { it.second.durationSec!! }
                val sec = set.durationSec!!
                return if (sec > longest) SetPr("$shown — ${Measure.secondsDelta(sec - longest).removePrefix("+")} longer than ever") else null
            }
            Tracking.DISTANCE_WEIGHT -> {
                val maxLoad = before.maxOf { (e, s) -> setLoad(e, s) }
                if (load > maxLoad + 0.01) return SetPr("$shown — heaviest carry yet")
                val farthest = before.filter { (e, s) -> setLoad(e, s) >= load - 0.01 }.maxOfOrNull { it.second.distanceM ?: 0.0 }
                return if (farthest != null && (set.distanceM ?: 0.0) > farthest + 0.01) SetPr("$shown — farthest at this weight") else null
            }
            Tracking.ASSISTED -> {
                // Less help is the record here.
                val least = before.minOf { (e, s) -> setLoad(e, s) }
                if (load < least - 0.01) return SetPr("$shown — ${Units.w(least - load, unit)} ${unit.label} less assistance than ever")
                val bestRepsHere = before.filter { (e, s) -> setLoad(e, s) <= load + 0.01 }.maxOfOrNull { it.second.reps!! }
                if (bestRepsHere != null && set.reps!! > bestRepsHere) {
                    val more = set.reps - bestRepsHere
                    return SetPr("$shown — $more more ${if (more == 1) "rep" else "reps"} than ever with this help")
                }
                return null
            }
            else -> {
                val reps = set.reps!!
                val maxLoad = before.maxOf { (e, s) -> setLoad(e, s) }
                if (load > maxLoad + 0.01) return SetPr("$shown — ${Units.w(load - maxLoad, unit)} ${unit.label} over your best")
                val bestRepsHere = before.filter { (e, s) -> setLoad(e, s) >= load - 0.01 }.maxOfOrNull { it.second.reps!! }
                if (bestRepsHere != null && reps > bestRepsHere) {
                    val more = reps - bestRepsHere
                    return SetPr("$shown — $more more ${if (more == 1) "rep" else "reps"} than ever at this weight")
                }
                if (t != Tracking.WEIGHT_REPS) return null
                val bestE1rm = before.maxOf { (e, s) -> Measure.score(e, s) }
                val est = Measure.score(entry, set)
                return if (est > bestE1rm + 0.01) SetPr("$shown — est. 1RM up ${Units.w(est - bestE1rm, unit)} ${unit.label}") else null
            }
        }
    }

    /**
     * E: set [setIndex] of an assisted lift is its first with no assistance after having used some (in
     * earlier sessions or earlier today): the "Unassisted" moment.
     */
    fun firstUnassisted(entry: ExerciseEntry, setIndex: Int, prior: List<Workout>): Boolean {
        if (entry.tracking != Tracking.ASSISTED) return false
        val set = entry.sets.getOrNull(setIndex) ?: return false
        if (!Measure.counts(entry, set) || Measure.load(entry, set) > 0.0) return false
        val before = prior.flatMap { w -> w.exercises.filter { it.exerciseId == entry.exerciseId && it.tracking == Tracking.ASSISTED } }
            .flatMap { e -> e.sets.filter { Measure.counts(e, it) }.map { Measure.load(e, it) } } +
            entry.sets.take(setIndex).filter { Measure.counts(entry, it) }.map { Measure.load(entry, it) }
        return before.any { it > 0.0 } && before.none { it <= 0.0 }
    }

    /** A record set in a finished session, for the summary (5.2) and session page (6.2). */
    data class SessionPr(
        val exerciseId: String, val kind: String, val weightKg: Double, val reps: Int,
        val set: SetEntry? = null, val tracking: Tracking = Tracking.WEIGHT_REPS, val perHand: Boolean = false, val bodyweight: Boolean = false,
    )

    fun sessionPrs(w: Workout, prior: List<Workout>): List<SessionPr> = w.exercises.mapNotNull { e ->
        val working = e.sets.filter { Measure.counts(e, it) }
        if (working.isEmpty()) return@mapNotNull null
        val past = prior.flatMap { p -> p.exercises.filter { it.exerciseId == e.exerciseId && it.tracking == e.tracking } }
        if (past.isEmpty()) return@mapNotNull null
        val pastSets = past.flatMap { pe -> pe.sets.filter { Measure.counts(pe, it) }.map { pe to it } }
        if (pastSets.isEmpty()) return@mapNotNull null
        fun pr(kind: String, s: SetEntry) = SessionPr(e.exerciseId, kind, setLoad(e, s), s.reps ?: 0, s, e.tracking, e.perHand, e.withBodyweight)
        when (e.tracking) {
            Tracking.DURATION -> working.maxBy { it.durationSec!! }.takeIf { it.durationSec!! > pastSets.maxOf { p -> p.second.durationSec!! } }?.let { pr("LONGEST", it) }
            Tracking.DISTANCE_WEIGHT -> {
                val top = working.maxBy { setLoad(e, it) }
                when {
                    setLoad(e, top) > pastSets.maxOf { (pe, s) -> setLoad(pe, s) } + 0.01 -> pr("HEAVIEST", top)
                    working.any { Measure.score(e, it) > pastSets.maxOf { (pe, s) -> Measure.score(pe, s) } + 0.01 } -> pr("BEST CARRY", working.maxBy { Measure.score(e, it) })
                    else -> null
                }
            }
            Tracking.ASSISTED -> {
                val least = working.minBy { setLoad(e, it) }
                when {
                    setLoad(e, least) < pastSets.minOf { (pe, s) -> setLoad(pe, s) } - 0.01 -> pr("LEAST HELP", least)
                    working.any { Measure.score(e, it) > pastSets.maxOf { (pe, s) -> Measure.score(pe, s) } + 0.0001 } -> pr("BEST SET", working.maxBy { Measure.score(e, it) })
                    else -> null
                }
            }
            Tracking.CARDIO -> null
            else -> {
                val top = working.maxWith(compareBy({ setLoad(e, it) }, { it.reps }))
                when {
                    setLoad(e, top) > pastSets.maxOf { (pe, s) -> setLoad(pe, s) } + 0.01 -> pr("BEST WEIGHT", top)
                    volume(e) > 0 && volume(e) > past.maxOf { volume(it) } + 0.01 -> pr("BEST VOLUME", top)
                    working.any { Measure.score(e, it) > pastSets.maxOf { (pe, s) -> Measure.score(pe, s) } + 0.01 } ->
                        pr(if (e.tracking == Tracking.WEIGHT_REPS) "BEST 1RM" else "MOST REPS", working.maxBy { Measure.score(e, it) })
                    else -> null
                }
            }
        }
    }

    /** Records per session id, computed in one chronological pass ([history] is newest first). */
    fun prsBySession(history: List<Workout>): Map<Long, Int> {
        val chrono = history.sortedBy { it.startedAt }
        return chrono.mapIndexed { i, w -> w.id to sessionPrs(w, chrono.subList(0, i)).size }.toMap()
    }

    // ---- Comparisons ----------------------------------------------------------------------

    /** The previous finished session of the same template (or the same name for freestyle ones). */
    fun lastLike(w: Workout, prior: List<Workout>): Workout? = prior
        .filter { it.id != w.id && it.startedAt < w.startedAt && (if (w.templateId != null) it.templateId == w.templateId else it.name == w.name) }
        .maxByOrNull { it.startedAt }

    /**
     * Top working set of an exercise entry: heaviest load then most reps (least assistance for assisted
     * work, longest time for timed sets, heaviest × farthest for carries). Cardio: its one entry.
     */
    fun topSet(e: ExerciseEntry): SetEntry? {
        if (e.tracking == Tracking.CARDIO) return e.sets.firstOrNull { it.done && it.durationSec != null }
        val working = e.sets.filter { Measure.counts(e, it) }
        return when (e.tracking) {
            Tracking.ASSISTED -> working.minWithOrNull(compareBy<SetEntry>({ setLoad(e, it) }, { -(it.reps ?: 0) }))
            Tracking.DURATION -> working.maxWithOrNull(compareBy<SetEntry>({ it.durationSec }, { setLoad(e, it) }))
            Tracking.DISTANCE_WEIGHT -> working.maxWithOrNull(compareBy<SetEntry>({ setLoad(e, it) }, { it.distanceM }))
            else -> working.maxWithOrNull(compareBy<SetEntry>({ setLoad(e, it) }, { it.reps }))
        }
    }

    /** Change in top-set load against the last time this exercise was done, in kg; null when there's nothing to compare. */
    fun topLoadDelta(e: ExerciseEntry, prior: List<Workout>, before: Long): Double? {
        val last = lastEntry(e, prior, before) ?: return null
        val now = topSet(e) ?: return null
        val then = topSet(last) ?: return null
        return setLoad(e, now) - setLoad(last, then)
    }

    private fun lastEntry(e: ExerciseEntry, prior: List<Workout>, before: Long) = prior.filter { it.startedAt < before }.sortedByDescending { it.startedAt }
        .firstNotNullOfOrNull { p -> p.exercises.firstOrNull { it.exerciseId == e.exerciseId && it.tracking == e.tracking } }

    /** "vs last time" for the summary: the text and whether it's an improvement (null when level or new). */
    data class VsLast(val text: String, val better: Boolean?)

    fun vsLast(e: ExerciseEntry, prior: List<Workout>, before: Long, unit: WeightUnit): VsLast? {
        val last = lastEntry(e, prior, before) ?: return VsLast("NEW", null)
        val now = topSet(e) ?: return null
        val then = topSet(last) ?: return null
        fun kg(d: Double) = "${Units.w(kotlin.math.abs(d), unit)} ${unit.label}"
        return when (e.tracking) {
            Tracking.CARDIO -> null
            Tracking.DURATION -> {
                val d = (now.durationSec ?: 0) - (then.durationSec ?: 0)
                when { d > 0 -> VsLast("▲ ${Measure.secondsDelta(d)}", true); d < 0 -> VsLast("▼ ${Measure.secondsDelta(d)}", false); else -> VsLast("=", null) }
            }
            else -> {
                val d = setLoad(e, now) - setLoad(last, then)
                // Assisted: less help is the improvement, shown as the change in assistance.
                val better = if (Measure.lowerIsBetter(e.tracking)) d < -0.01 else d > 0.01
                when {
                    kotlin.math.abs(d) <= 0.01 -> VsLast("=", null)
                    better -> VsLast("▲ ${if (d > 0) "+" else "−"}${kg(d)}", true)
                    else -> VsLast("▼ ${if (d > 0) "+" else "−"}${kg(d)}", false)
                }
            }
        }
    }

    /**
     * One-line headline for the summary (5.2), from the session's own numbers. Rules, in order:
     * best-ever volume for this template, a long gap, a heavier session than last time, records, otherwise plain.
     */
    fun headline(w: Workout, prior: List<Workout>): String {
        // A standalone cardio session: say how long, not how heavy.
        if (w.cardioOnly) {
            val min = Measure.roundMinutes(w.exercises.sumOf { e -> e.sets.filter { it.done }.sumOf { it.durationSec ?: 0 } })
            return "$min minutes of cardio."
        }
        // "Legs" → "leg day"; multi-word names read better as-is: "Upper A session".
        val day = w.name.trim().let { n -> if (' ' in n) "$n session" else n.lowercase().removeSuffix("s").let { if (it.length > 2) "$it day" else "$n day" } }
        val same = prior.filter { it.startedAt < w.startedAt && (if (w.templateId != null) it.templateId == w.templateId else it.name == w.name) }
        val last = same.maxByOrNull { it.startedAt }
        val gapDays = prior.filter { it.startedAt < w.startedAt }.maxOfOrNull { it.startedAt }
            ?.let { ChronoUnit.DAYS.between(Weeks.date(it), Weeks.date(w.startedAt)) }
        val prs = sessionPrs(w, prior).size
        return when {
            same.size >= 2 && volume(w) > same.maxOf { volume(it) } -> "Strongest $day yet."
            gapDays != null && gapDays >= 9 -> "Back after $gapDays days."
            last != null && volume(w) > volume(last) * 1.02 -> "Heavier than last time."
            prs >= 2 -> "$prs records in one session."
            prs == 1 -> "A new record."
            last == null -> "The first $day."
            else -> "Another one in the book."
        }
    }

    /** Longest run of consecutive weeks that hit the weekly goal. */
    fun bestGoalStreak(history: List<Workout>, weeklyGoal: Int): Int {
        val byWeek = history.filter { it.countsForGoal }.groupBy { Weeks.monday(Weeks.date(it.startedAt)) }.mapValues { it.value.size }
        var m = byWeek.keys.minOrNull() ?: return 0
        val last = Weeks.monday(Weeks.today())
        var run = 0; var best = 0
        while (!m.isAfter(last)) {
            run = if ((byWeek[m] ?: 0) >= weeklyGoal) run + 1 else 0
            best = maxOf(best, run)
            m = m.plusWeeks(1)
        }
        return best
    }

    /**
     * Sets per muscle over the last [days] days (working sets only), for the balance view (7.3). Each set
     * counts 1 for the primary muscle and ½ for each secondary one. Cardio never counts.
     */
    fun setsPerMuscle(
        history: List<Workout>, muscleOf: (String) -> String, days: Long = 7, today: LocalDate = Weeks.today(),
        secondaryOf: (String) -> List<String> = { emptyList() },
    ): Map<String, Double> {
        val from = today.minusDays(days - 1)
        val out = mutableMapOf<String, Double>()
        history.filter { !Weeks.date(it.startedAt).isBefore(from) }.forEach { w ->
            w.exercises.filter { it.tracking.isLifting }.forEach { e ->
                val n = e.sets.count { it.done && it.type != SetType.WARMUP }.toDouble()
                if (n == 0.0) return@forEach
                out.merge(muscleOf(e.exerciseId), n, Double::plus)
                secondaryOf(e.exerciseId).forEach { out.merge(it, n / 2, Double::plus) }
            }
        }
        return out
    }
}

object Units {
    private const val LB_PER_KG = 2.20462

    fun toDisplay(kg: Double, unit: WeightUnit) = if (unit == WeightUnit.KG) kg else kg * LB_PER_KG
    fun toKg(value: Double, unit: WeightUnit) = if (unit == WeightUnit.KG) value else value / LB_PER_KG

    fun num(v: Double): String {
        val r = (v * 10).roundToInt() / 10.0
        return if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
    }

    fun w(kg: Double, unit: WeightUnit) = num(toDisplay(kg, unit))

    /** 14830 -> "14.8k", 480 -> "480". */
    fun compact(kg: Double, unit: WeightUnit): String {
        val v = toDisplay(kg, unit)
        return if (v >= 1000) num(v / 1000) + "k" else v.roundToInt().toString()
    }

    fun grouped(kg: Double, unit: WeightUnit): String = "%,d".format(toDisplay(kg, unit).roundToInt())

    /** Lifetime totals: 1,210,000 → "1.21M", 412,860 → "413k", 9,870 → "9,870". */
    fun abbreviated(kg: Double, unit: WeightUnit): String {
        val v = toDisplay(kg, unit)
        return when {
            v >= 1_000_000 -> "%.2fM".format(v / 1_000_000)
            v >= 100_000 -> "${(v / 1000).roundToInt()}k"
            else -> grouped(kg, unit)
        }
    }
}
