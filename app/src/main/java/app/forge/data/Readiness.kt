package app.forge.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class ReadinessState { GREEN, AMBER, RED }

/** The only things the coach (or anything else) may do to an exercise today. Kotlin works out every number. */
enum class ActionId { INCREASE_LOAD, HOLD_WEIGHT, REDUCE_LOAD_5, REDUCE_LOAD_10, DROP_ONE_SET }

/** [partial]: no check-in (or a skipped one), so it's from the watch's load only. [reasons] are facts, one per point lost. */
data class Readiness(val state: ReadinessState, val score: Int, val partial: Boolean, val reasons: List<Fact>)

/** What today allows: readiness, capped at hold for the first session back after a break. */
data class Gate(val readiness: Readiness, val daysSinceGym: Long?) {
    val firstBack: Boolean get() = daysSinceGym != null && daysSinceGym >= Rules.BREAK_DAYS
    val canIncrease: Boolean get() = readiness.state == ReadinessState.GREEN && !firstBack
    val allowed: List<ActionId> get() = when (readiness.state) {
        ReadinessState.GREEN -> if (firstBack) listOf(ActionId.HOLD_WEIGHT) else listOf(ActionId.INCREASE_LOAD, ActionId.HOLD_WEIGHT)
        ReadinessState.AMBER -> listOf(ActionId.HOLD_WEIGHT, ActionId.REDUCE_LOAD_5, ActionId.DROP_ONE_SET)
        ReadinessState.RED -> listOf(ActionId.REDUCE_LOAD_10, ActionId.DROP_ONE_SET)
    }
}

/** One allowed action with its numbers: the weight (kg; for assisted work, the help) and reps per set, and how many sets. */
data class Option(val action: ActionId, val weightKg: Double?, val reps: Int?, val sets: Int)

/** An exercise in the next workout: what the progression engine says, and what today allows instead. */
data class ExercisePlan(val exerciseId: String, val rule: TemplateExercise, val target: Progression.Target?, val options: List<Option>) {
    val allowed: List<ActionId> get() = options.map { it.action }
}

data class Plan(val template: Template, val gate: Gate, val exercises: List<ExercisePlan>, val facts: List<Fact>)

/**
 * The rules engine (brief §3, with the decisions in AI_LOG.md). Pure: rules handle the numbers, the AI only picks from
 * [ExercisePlan.options] later, and every claim links to a [Fact] made here.
 *
 * Readiness starts at 0 and loses a point each for: poor sleep (the check-in, or under 6 h if the watch recorded
 * sleep, which then replaces the answer), very sore, low energy, and recent load (6 h+ of outside sessions in the
 * last 72 h, a 20,000-step day counting as 2 h; or 300 m+ climbed in one outing in the last 48 h). 0 is green,
 * −1 amber, −2 or lower red. Readiness gates [Progression.target]; it never replaces it.
 */
object Rules {
    const val SHORT_SLEEP_MIN = 360
    const val LOAD_MINUTES = 360
    const val LOAD_WINDOW_MS = 72 * 3_600_000L
    const val CLIMB_WINDOW_MS = 48 * 3_600_000L
    const val BIG_STEP_DAY = 20_000L
    const val BIG_STEP_MINUTES = 120
    /** Days since the last gym session that make this the first session back. */
    const val BREAK_DAYS = 5L

    fun readiness(checkIn: CheckIn?, days: List<OutsideDay>, now: Long): Readiness {
        val reasons = mutableListOf<Fact>()
        val said = checkIn?.let(LifeContext::facts).orEmpty().associateBy { it.id }
        val watchSleep = days.firstOrNull { it.date == Weeks.date(now).toString() }?.sleepMinutes
        if (watchSleep != null) {
            if (watchSleep < SHORT_SLEEP_MIN) reasons += Fact("sleep.watch", "${LifeContext.duration(watchSleep)} asleep last night (watch)")
        } else if (checkIn?.sleep == SleepAnswer.POOR) reasons += said.getValue("checkin.sleep")
        if (checkIn?.soreness == Soreness.VERY) reasons += said.getValue("checkin.soreness")
        if (checkIn?.energy == Energy.LOW) reasons += said.getValue("checkin.energy")
        // Recent load is one point, however many ways it's reached; every way it was reached is evidence.
        val load = listOfNotNull(recentLoad(days, now), recentClimb(days, now))
        val score = -(reasons.size + if (load.isEmpty()) 0 else 1)
        val state = when { score >= 0 -> ReadinessState.GREEN; score == -1 -> ReadinessState.AMBER; else -> ReadinessState.RED }
        return Readiness(state, score, partial = checkIn == null || checkIn.skipped, reasons = reasons + load)
    }

    /** 6 h+ of counted outings ending in the last 72 h, each 20,000-step day in the last three counting as 2 h. */
    fun recentLoad(days: List<OutsideDay>, now: Long): Fact? {
        val outings = days.flatMap { it.outings }.filter { Outside.counts(it) && it.end > now - LOAD_WINDOW_MS && it.start <= now }
        val minutes = outings.sumOf { it.minutes }
        val today = Weeks.date(now)
        val bigDays = Outside.between(days, today.minusDays(2), today).count { (it.steps ?: 0) >= BIG_STEP_DAY }
        if (minutes + bigDays * BIG_STEP_MINUTES < LOAD_MINUTES) return null
        val kind = outings.map { it.type }.distinct().singleOrNull()?.noun
        val steps = if (bigDays == 0) "" else "$bigDays ${if (bigDays == 1) "day" else "days"} of 20,000+ steps"
        val text = when {
            minutes == 0 -> "$steps in the last 3 days"
            else -> "${LifeContext.duration(minutes)} ${if (kind != null) "of $kind" else "outside"} in the last 3 days" + if (steps.isEmpty()) "" else ", plus $steps"
        }
        return Fact("readiness.recentLoad", text)
    }

    /** The biggest climb of 300 m+ in one outing in the last 48 h. */
    fun recentClimb(days: List<OutsideDay>, now: Long): Fact? = days.flatMap { it.outings }
        .filter { (it.climbM ?: 0.0) >= Outside.HIGH_CLIMB_M && it.end > now - CLIMB_WINDOW_MS && it.start <= now }
        .maxByOrNull { it.climbM!! }
        ?.let { Fact("readiness.climb", "${LifeContext.day(Weeks.date(it.start)).substringBefore(' ')}: ${it.climbM!!.roundToInt()} m climbed") }

    /** Whole days since the last gym session (anything but a cardio-only one), or null with no history. */
    fun daysSinceGym(history: List<Workout>, today: LocalDate): Long? =
        history.filter { !it.cardioOnly }.maxOfOrNull { it.startedAt }?.let { ChronoUnit.DAYS.between(Weeks.date(it), today) }

    fun gate(d: AppData, checkIn: CheckIn?, now: Long): Gate =
        Gate(readiness(checkIn, d.outsideDays, now), daysSinceGym(d.history, Weeks.date(now)))

    /**
     * "Today's target" under the gate: a level-up becomes a hold at last time's weight when today doesn't allow an
     * increase. Holds and planned lighter weeks are already within every gate.
     */
    fun gated(target: Progression.Target?, gate: Gate): Progression.Target? =
        if (target?.kind == Progression.Kind.LEVEL_UP && !gate.canIncrease) target.copy(kind = Progression.Kind.HOLD, weightKg = target.fromKg, reps = target.fromReps)
        else target

    /**
     * The allowed actions for one exercise, each with its numbers. Hold is last time's weight (or the planned one);
     * reductions go down to the exercise's own increment (for assisted work, up: more help); dropping a set keeps
     * the hold weight. Actions that can't apply (a reduction with no weight, dropping the only set) are left out.
     */
    fun options(rule: TemplateExercise, target: Progression.Target?, tracking: Tracking, gate: Gate, unit: WeightUnit): List<Option> {
        if (tracking == Tracking.CARDIO) return emptyList()
        val levelUp = target?.kind == Progression.Kind.LEVEL_UP
        val holdKg = if (levelUp) target!!.fromKg else target?.weightKg
        val holdReps = if (levelUp) target!!.fromReps else target?.reps
        val step = Progression.incrementKg(rule, unit)
        fun reduce(by: Double): Double? {
            val kg = holdKg?.takeIf { tracking != Tracking.DURATION } ?: return null
            if (tracking == Tracking.ASSISTED) return round(maxOf(ceil(kg * (1 + by) / step - 1e-6) * step, kg + step))
            if (kg <= 0) return null
            return round(floor(kg * (1 - by) / step + 1e-6) * step).coerceAtLeast(0.0)
        }
        return gate.allowed.mapNotNull { a ->
            when (a) {
                ActionId.INCREASE_LOAD -> if (levelUp) Option(a, target!!.weightKg, target.reps, rule.sets) else null
                ActionId.HOLD_WEIGHT -> Option(a, holdKg, holdReps, rule.sets)
                ActionId.REDUCE_LOAD_5 -> reduce(0.05)?.let { Option(a, it, holdReps, rule.sets) }
                ActionId.REDUCE_LOAD_10 -> reduce(0.10)?.let { Option(a, it, holdReps, rule.sets) }
                ActionId.DROP_ONE_SET -> if (rule.sets > 1) Option(a, holdKg, holdReps, rule.sets - 1) else null
            }
        }
    }

    private fun round(kg: Double) = (kg * 1000).roundToLong() / 1000.0

    /** The template Home shows as next up: the one gone longest without (never-done ones first). */
    fun nextUp(d: AppData): Template? {
        val last = d.history.filter { it.templateId != null }.groupBy { it.templateId!! }.mapValues { (_, v) -> v.maxOf { it.startedAt } }
        return d.templates.minByOrNull { last[it.id] ?: Long.MIN_VALUE }
    }

    /** The next workout under today's gate: every exercise's target and allowed actions, and the facts behind them. */
    fun plan(d: AppData, t: Template, checkIn: CheckIn?, now: Long): Plan {
        val gate = gate(d, checkIn, now)
        val exercises = t.exercises.map { rule ->
            val id = rule.effectiveId(now)
            val tracking = Session.exercise(d, id).tracking
            val target = Progression.target(rule, Stats.previousSets(d.history, id, tracking), d.settings.unit, tracking)
            ExercisePlan(id, rule, target, options(rule, target, tracking, gate, d.settings.unit))
        }
        return Plan(t, gate, exercises, facts(d, checkIn, now, exercises.map { it.exerciseId to it.rule }))
    }

    // ---- Facts --------------------------------------------------------------------------------------------------

    /**
     * Every computed fact for now, each with a stable id: the check-in, readiness, the break, outside activity and
     * everyday movement this week and last, and the lifts in [lifts]. Evidence chips are only ever drawn from these.
     */
    fun facts(d: AppData, checkIn: CheckIn?, now: Long, lifts: List<Pair<String, TemplateExercise>> = emptyList()): List<Fact> {
        val out = LinkedHashMap<String, Fact>()
        fun add(f: Fact) { out.putIfAbsent(f.id, f) }
        val today = Weeks.date(now)
        checkIn?.let { LifeContext.facts(it).forEach(::add) }
        readiness(checkIn, d.outsideDays, now).reasons.forEach(::add)
        daysSinceGym(d.history, today)?.let { n -> add(Fact("break.daysSinceGym", "$n ${if (n == 1L) "day" else "days"} since your last gym session")) }
        val monday = Weeks.monday(today)
        listOf(monday, monday.minusWeeks(1)).forEach { m -> outsideFacts(d, m).forEach(::add); movementFacts(d.outsideDays, m).forEach(::add) }
        lifts.forEach { (id, rule) -> liftFacts(d, id, rule).forEach(::add) }
        return out.values.toList()
    }

    /** "Sat 24: 3h 57 hiking · 9.5 km · 540 m climbed" per counted outing, the week's total, and the notes behind it. */
    fun outsideFacts(d: AppData, monday: LocalDate): List<Fact> {
        val days = Outside.between(d.outsideDays, monday, monday.plusDays(6))
        val facts = mutableListOf<Fact>()
        days.forEach { day ->
            day.outings.filter(Outside::counts).forEachIndexed { i, o ->
                val id = "outside.${o.type.key}.${day.date}" + if (i > 0) ".${i + 1}" else ""
                facts += Fact(id, "${LifeContext.day(day.localDate)}: " + listOfNotNull(
                    "${LifeContext.duration(o.minutes)} ${o.type.noun}",
                    o.distanceM?.takeIf { it >= 100 }?.let(LifeContext::km),
                    o.climbM?.takeIf { it >= 1 }?.let { "${it.roundToInt()} m climbed" },
                ).joinToString(" · "))
            }
        }
        val counted = days.filter { it.outings.any(Outside::counts) }
        if (counted.isNotEmpty()) {
            val outings = counted.flatMap { it.outings }.filter(Outside::counts)
            val km = outings.sumOf { it.distanceM ?: 0.0 }
            val climb = outings.sumOf { it.climbM ?: 0.0 }
            facts += Fact("outside.week.${Weeks.key(monday)}", listOfNotNull(
                "${counted.size} outside ${if (counted.size == 1) "day" else "days"}",
                km.takeIf { it >= 100 }?.let(LifeContext::km),
                climb.takeIf { it >= 1 }?.let { "%,d m climbed".format(it.roundToInt()) },
            ).joinToString(" · "))
        }
        val dates = days.map { it.date }.toSet()
        d.contextNotes.filter { n -> n.activities.any { a -> a.dates.any { it in dates } } }.forEach { n ->
            // "Note: hiking, 4 days · quads, hamstrings, glutes, calves"
            facts += Fact("outside.note.${n.id}", "Note: " + n.activities.joinToString("; ") { a ->
                listOfNotNull(
                    "${a.type.lowercase()}, ${a.days} ${if (a.days == 1) "day" else "days"}",
                    a.muscles.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.lowercase() },
                ).joinToString(" · ")
            })
        }
        return facts
    }

    /** "61,240 steps · 29.8 mi (48.0 km)", the biggest day, and each high-step day. */
    fun movementFacts(days: List<OutsideDay>, monday: LocalDate): List<Fact> {
        val m = Outside.movement(days, monday) ?: return emptyList()
        val k = Weeks.key(monday)
        val facts = mutableListOf(Fact("movement.week.$k", "%,d steps · %s mi (%s)".format(m.steps, Units.num(m.miles), LifeContext.km(m.distanceM))))
        m.biggest?.let { b -> facts += Fact("movement.biggestDay.$k", "Biggest day: ${dayName(b)} %,d steps".format(b.steps)) }
        m.highDays.forEach { h -> facts += Fact("movement.highStepDay.${h.date}", "${dayName(h)}: %,d steps".format(h.steps)) }
        return facts
    }

    private fun dayName(d: OutsideDay) = LifeContext.day(d.localDate).substringBefore(' ')

    /** "Back Squat last time: 90 kg × 8, 8, 8", and "…hit 8 on every set, two sessions running" when it did. */
    fun liftFacts(d: AppData, exerciseId: String, rule: TemplateExercise): List<Fact> {
        val ex = Session.exercise(d, exerciseId)
        val entries = d.history.sortedByDescending { it.startedAt }
            .mapNotNull { w -> w.exercises.firstOrNull { it.exerciseId == exerciseId } }.take(2)
            .map { Progression.working(it.sets, it.tracking) }.filter { it.isNotEmpty() }
        val last = entries.firstOrNull() ?: return emptyList()
        val top = last.maxOf { it.weightKg!! }
        val facts = mutableListOf(Fact("lift.$exerciseId.last", "${ex.name} last time: ${Measure.weight(ex.tracking, top, d.settings.unit)} × ${last.joinToString(", ") { "${it.reps}" }}"))
        val needed = if (rule.method == ProgressionMethod.DOUBLE) rule.repMax else rule.repMin
        fun hit(sets: List<SetEntry>) = sets.all { it.reps!! >= needed }
        if (entries.size == 2 && entries.all(::hit)) facts += Fact("lift.$exerciseId.lastTwo", "${ex.name}: hit $needed on every set, two sessions running")
        return facts
    }
}
