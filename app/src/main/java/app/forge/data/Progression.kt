package app.forge.data

import kotlin.math.floor
import kotlin.math.roundToLong

enum class ProgressionMethod { DOUBLE, LINEAR, MANUAL }

/**
 * The progression engine (section 10). Pure: callers pass the rule, last session's sets and history.
 *
 * - Double: work up the rep range at one weight. Every working set at [TemplateExercise.repMax] at the
 *   same weight → add the increment and drop back to [TemplateExercise.repMin].
 * - Linear: every working set at [TemplateExercise.repMin] at the same weight → add the increment.
 * - Manual: no suggestions; sets start from last time.
 * - Stall: the estimated max (Epley) hasn't beaten the best before them in 3 sessions → offer a
 *   lighter week (~10% off), a variation for 2 weeks, or keep pushing.
 */
object Progression {
    const val DEFAULT_INCREMENT_KG = 2.5
    const val STALL_SESSIONS = 3
    const val LIGHTER_FACTOR = 0.9
    const val VARIATION_WEEKS = 2L
    const val LIGHTER_WEEK_XP = 50
    private const val EPS = 0.01

    enum class Kind { LEVEL_UP, LIGHTER, HOLD, BASELINE }

    /**
     * What to lift today. [fromKg]/[fromReps] describe last time's working sets. For timed sets,
     * [durationSec] is the time to aim for. For assisted sets the weights are assistance.
     */
    data class Target(
        val kind: Kind, val weightKg: Double?, val reps: Int?, val fromKg: Double? = null, val fromReps: Int? = null,
        val durationSec: Int? = null, val fromSec: Int? = null,
    )

    /** Seconds added to a timed set's target each session. */
    const val TIME_STEP_SEC = 5

    /** Weight increments offered on the rules screen, in the user's unit, and what each is in kg. */
    fun increments(unit: WeightUnit): List<Double> = if (unit == WeightUnit.KG) listOf(1.25, 2.5, 5.0) else listOf(2.5, 5.0, 10.0)

    /**
     * The increment in kg. The 2.5 kg default reads as 5 lb for pound users, so their suggestions land
     * on plates they have.
     */
    fun incrementKg(rule: TemplateExercise, unit: WeightUnit): Double =
        if (unit == WeightUnit.LB && rule.incrementKg == DEFAULT_INCREMENT_KG) Units.toKg(5.0, WeightUnit.LB) else rule.incrementKg

    /**
     * Done working sets with a weight and reps. Warm-ups, drop sets and skipped sets don't count toward the
     * rule, so "every set" means every set done. For bodyweight and assisted work a missing weight means
     * none added or no help, so it reads as 0.
     */
    fun working(sets: List<SetEntry>, tracking: Tracking = Tracking.WEIGHT_REPS): List<SetEntry> = sets
        .filter { it.done && !it.skipped && (it.type == SetType.NORMAL || it.type == SetType.FAILURE) && it.reps != null }
        .map { if (it.weightKg == null && (tracking == Tracking.BODYWEIGHT_REPS || tracking == Tracking.ASSISTED)) it.copy(weightKg = 0.0) else it }
        .filter { it.weightKg != null }

    /**
     * Today's target by the rule and [tracking]:
     * - weight × reps and bodyweight (added weight, which may start at 0): add the increment once every set hits the reps;
     * - assisted: take the increment off the assistance instead (never below none);
     * - timed: same weight, five seconds longer than the shortest set last time;
     * - carries and cardio: no target.
     */
    fun target(rule: TemplateExercise, prev: List<SetEntry>, unit: WeightUnit = WeightUnit.KG, tracking: Tracking = Tracking.WEIGHT_REPS): Target? {
        if (rule.method == ProgressionMethod.MANUAL) return null
        if (tracking == Tracking.CARDIO || tracking == Tracking.DISTANCE_WEIGHT) return null
        if (tracking == Tracking.DURATION) {
            val timed = prev.filter { it.done && !it.skipped && (it.type == SetType.NORMAL || it.type == SetType.FAILURE) && it.durationSec != null }
            if (timed.isEmpty()) return Target(Kind.BASELINE, null, null)
            val shortest = timed.minOf { it.durationSec!! }
            val kg = timed.maxOfOrNull { it.weightKg ?: 0.0 }
            return Target(Kind.HOLD, kg, null, kg, null, durationSec = shortest + TIME_STEP_SEC, fromSec = shortest)
        }
        val work = working(prev, tracking)
        rule.lighterNextKg?.let { return Target(Kind.LIGHTER, it, rule.repMin, work.maxOfOrNull { s -> s.weightKg!! }, work.minOfOrNull { s -> s.reps!! }) }
        if (work.isEmpty()) return Target(Kind.BASELINE, null, null)
        val assisted = tracking == Tracking.ASSISTED
        // The weight that matters: the heaviest, or for assisted work the most help used.
        val top = work.maxOf { it.weightKg!! }
        val minReps = work.minOf { it.reps!! }
        val sameWeight = work.all { kotlin.math.abs(it.weightKg!! - top) < EPS }
        val needed = if (rule.method == ProgressionMethod.DOUBLE) rule.repMax else rule.repMin
        val canMove = when (tracking) {
            Tracking.ASSISTED -> top > 0          // nothing left to take off
            Tracking.BODYWEIGHT_REPS -> true      // from no added weight to some
            else -> top > 0
        }
        return if (sameWeight && canMove && minReps >= needed) {
            val inc = incrementKg(rule, unit)
            Target(Kind.LEVEL_UP, if (assisted) (top - inc).coerceAtLeast(0.0) else top + inc, rule.repMin, top, minReps)
        } else {
            // Not there yet: same weight, one more rep on the weakest set (capped at what the rule asks for).
            Target(Kind.HOLD, top, (minReps + 1).coerceAtMost(needed).coerceAtLeast(rule.repMin), top, minReps)
        }
    }

    // ---- Stalls ---------------------------------------------------------------------------------

    data class Stall(val exerciseId: String, val bestE1rm: Double, val series: List<Pair<Long, Double>>, val windowStart: Long)

    /**
     * Stalled when the last [STALL_SESSIONS] sessions after [since] all failed to beat the best estimated
     * max from before them. Needs at least one earlier session to compare against.
     */
    fun stall(history: List<Workout>, exerciseId: String, since: Long?, loggedAs: Tracking? = null): Stall? {
        // Only rep-based lifting stalls: timed holds, carries and cardio have no lighter week. [loggedAs] is the
        // Assisted / Weighted mode being looked at; otherwise the way it was last logged.
        val tracking = loggedAs ?: history.firstNotNullOfOrNull { w -> w.exercises.firstOrNull { it.exerciseId == exerciseId }?.tracking }
        if (tracking != null && !tracking.usesReps) return null
        // Scores are always "higher is better"; assisted ones can be negative, so only drop the empty ones.
        // Only sessions logged the way the latest was: after an Assisted / Weighted switch the scores aren't comparable.
        val series = Stats.e1rmSeries(history, exerciseId, tracking).filter { it.second != 0.0 }
        val window = series.filter { since == null || it.first > since }.takeLast(STALL_SESSIONS)
        if (window.size < STALL_SESSIONS) return null
        val before = series.filter { it.first < window.first().first }
        val best = before.maxOfOrNull { it.second } ?: return null
        return if (window.all { it.second <= best + EPS }) Stall(exerciseId, best, series, window.first().first) else null
    }

    /**
     * ~10% under last time's top working weight, rounded down to the increment. For assisted work the
     * lighter week means more help: ~10% more assistance, rounded up, at least one increment more.
     */
    fun lighterKg(rule: TemplateExercise, prev: List<SetEntry>, unit: WeightUnit, tracking: Tracking = Tracking.WEIGHT_REPS): Double? {
        val step = incrementKg(rule, unit)
        if (tracking == Tracking.ASSISTED) {
            val help = working(prev, tracking).maxOfOrNull { it.weightKg!! } ?: return null
            val more = kotlin.math.ceil(help * (2 - LIGHTER_FACTOR) / step - 1e-6) * step
            return (maxOf(more, help + step) * 1000).roundToLong() / 1000.0
        }
        val top = working(prev, tracking).maxOfOrNull { it.weightKg!! }?.takeIf { it > 0 } ?: return null
        return (floor(top * LIGHTER_FACTOR / step + 1e-6) * step).let { (it * 1000).roundToLong() / 1000.0 }.coerceAtLeast(step)
    }

    /** Template exercises in [w] that have stalled and want the 10.3 sheet. [history] includes [w]. */
    fun stalls(t: Template?, w: Workout, history: List<Workout>): List<Pair<Int, Stall>> = t?.exercises.orEmpty().mapIndexedNotNull { i, rule ->
        if (!rule.suggestLighter || rule.lighterNextKg != null || rule.effectiveId(w.startedAt) != rule.exerciseId) return@mapIndexedNotNull null
        if (w.exercises.none { it.exerciseId == rule.exerciseId }) return@mapIndexedNotNull null
        stall(history, rule.exerciseId, rule.stallHandledAt)?.takeIf { it.series.last().first == w.startedAt }?.let { i to it }
    }

    /**
     * For the Metrics lift page: whether [exerciseId] is stalled right now, by the rules of every template
     * that uses it. Nothing while a lighter week or variation is already planned, or if it's in no template.
     */
    fun liftStall(templates: List<Template>, history: List<Workout>, exerciseId: String, now: Long, tracking: Tracking? = null): Stall? {
        val rules = templates.flatMap { it.exercises }.filter { it.exerciseId == exerciseId }
        if (rules.none { it.suggestLighter } || rules.any { it.lighterNextKg != null || it.effectiveId(now) != exerciseId }) return null
        return stall(history, exerciseId, rules.mapNotNull { it.stallHandledAt }.maxOrNull(), tracking)
    }

    enum class Answer { LIGHTER, SWAP, KEEP }

    /** Applies a 10.3 answer to [rule]; the stall count restarts from [at]. */
    fun answer(rule: TemplateExercise, answer: Answer, lighterKg: Double?, variationId: String?, at: Long): TemplateExercise = when (answer) {
        Answer.LIGHTER -> if (lighterKg != null) planLighter(rule, lighterKg, at) else keepPushing(rule, at)
        Answer.SWAP -> if (variationId != null) swapVariation(rule, variationId, at) else keepPushing(rule, at)
        Answer.KEEP -> keepPushing(rule, at)
    }

    /** A lighter variation for 2 weeks: a hand-picked one, else the same muscle and equipment, else the same muscle. */
    fun variationFor(id: String, all: List<Exercise>): Exercise? {
        Library.variations[id]?.let { v -> all.firstOrNull { it.id == v }?.let { return it } }
        val ex = all.firstOrNull { it.id == id } ?: return null
        return all.firstOrNull { it.id != id && it.muscle == ex.muscle && it.equipment == ex.equipment }
            ?: all.firstOrNull { it.id != id && it.muscle == ex.muscle }
    }

    // ---- Applying answers -------------------------------------------------------------------------

    fun planLighter(rule: TemplateExercise, kg: Double, at: Long) = rule.copy(lighterNextKg = kg, stallHandledAt = at)

    fun swapVariation(rule: TemplateExercise, variationId: String, at: Long) =
        rule.copy(variationId = variationId, variationUntil = at + VARIATION_WEEKS * 7 * 24 * 3600 * 1000, stallHandledAt = at)

    fun keepPushing(rule: TemplateExercise, at: Long) = rule.copy(stallHandledAt = at)

    /**
     * After a session is logged: a planned lighter week that was done is cleared and marked, and the
     * stall count restarts from it. A finished variation is dropped.
     */
    fun afterSession(t: Template, w: Workout): Pair<Template, Workout> {
        var out = w
        val rules = t.exercises.map { rule ->
            var r = rule
            if (r.variationUntil != null && w.startedAt >= r.variationUntil) r = r.copy(variationId = null, variationUntil = null)
            val planned = r.lighterNextKg
            val entry = w.exercises.firstOrNull { it.exerciseId == r.exerciseId }
            if (planned != null && entry != null) {
                // Only a session actually done at the lighter weight counts (and earns XP); "Keep" just clears the plan.
                val work = working(entry.sets, entry.tracking)
                // Assisted: the lighter week is more help, so it counts when no set used less than planned.
                val tookIt = if (entry.tracking == Tracking.ASSISTED) work.minOfOrNull { it.weightKg!! }?.let { it >= planned - EPS }
                else work.maxOfOrNull { it.weightKg!! }?.let { it <= planned + EPS }
                if (tookIt == true) out =out.copy(exercises = out.exercises.map { if (it.id == entry.id) it.copy(lighterWeek = true) else it })
                r = r.copy(lighterNextKg = null, stallHandledAt = w.startedAt)
            }
            r
        }
        return t.copy(exercises = rules) to out
    }
}
