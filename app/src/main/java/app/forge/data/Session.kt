package app.forge.data

/**
 * Changes to the session in progress that both the app and the rest notification (section 09) make.
 * Pure: every function takes the data and returns the new data, so the repository can apply them
 * atomically whichever side calls.
 */
object Session {
    /** An exercise as the library or the user defined it, before any Assisted / Weighted choice. */
    fun baseExercise(d: AppData, id: String): Exercise =
        (Library.exercises + d.customExercises).firstOrNull { it.id == id } ?: Exercise(id, id, "Other", Equipment.BARBELL)

    /** An exercise with the user's Assisted / Weighted choice applied. */
    fun exercise(d: AppData, id: String): Exercise {
        val ex = baseExercise(d, id)
        val mode = d.trackingModes[id]?.takeIf { Library.canSwitchAssist(ex) } ?: return ex
        return ex.copy(tracking = mode)
    }

    /** The set the notification offers to log: the first unticked set of the first unfinished exercise. */
    /** [planned] is the set as it would be logged, or null when it has nothing to log yet. */
    data class Next(val ex: Int, val set: Int, val entry: ExerciseEntry, val setEntry: SetEntry, val planned: SetEntry?) {
        val kg: Double? get() = planned?.weightKg
        val reps: Int? get() = planned?.reps
        val canLog: Boolean get() = planned != null
    }

    /** Cardio entries are logged in the app, not from the rest notification, so they're skipped here. */
    fun next(d: AppData): Next? {
        val w = d.active ?: return null
        w.exercises.forEachIndexed { ex, e ->
            if (e.tracking == Tracking.CARDIO) return@forEachIndexed
            val i = Stats.openSets(e).firstOrNull() ?: -1
            if (i >= 0) return Next(ex, i, e, e.sets[i], fill(d, e, i))
        }
        return null
    }

    /** Bodyweight-style sets with no weight entered are logged as 0 (nothing added, no assistance). */
    private fun bodyweightZero(d: AppData, e: ExerciseEntry): Double? =
        if (e.tracking == Tracking.BODYWEIGHT_REPS || e.tracking == Tracking.ASSISTED || exercise(d, e.exerciseId).equipment == Equipment.BODYWEIGHT) 0.0 else null

    /**
     * Set [index] ticked with the values it shows (its own, else last session's, else the set before), or
     * null when there's nothing to log yet: no reps, no time, or no distance, depending on the tracking.
     */
    fun fill(d: AppData, e: ExerciseEntry, index: Int): SetEntry? {
        val s = e.sets.getOrNull(index) ?: return null
        val g = Stats.ghostValues(e, index, Stats.previousSets(d.history, e.exerciseId, e.tracking))
        if (!Measure.hasValue(e.tracking, g)) return null
        return s.copy(
            done = true, weightKg = g.weightKg ?: bodyweightZero(d, e), reps = g.reps, durationSec = g.durationSec,
            distanceM = g.distanceM, level = g.level, heartRate = g.heartRate,
        )
    }

    /** A cardio-only session counts for the weekly goal when the setting allows it and it ran 20+ minutes. */
    const val CARDIO_GOAL_MINUTES = 20

    fun countsForGoal(w: Workout, s: Settings): Boolean = !w.cardioOnly ||
        (s.cardioCountsForGoal && w.exercises.sumOf { e -> e.sets.filter { it.done }.sumOf { it.durationSec ?: 0 } } >= CARDIO_GOAL_MINUTES * 60)

    /** Rest after a set of this exercise: the template's per-exercise rest, else the default. */
    fun restFor(d: AppData, exerciseId: String): Int {
        val te = d.templates.firstOrNull { it.id == d.active?.templateId }?.exercises?.firstOrNull { it.effectiveId(d.active!!.startedAt) == exerciseId }
        return te?.restSec ?: d.settings.restSeconds
    }

    /**
     * Ticks set [setId] of workout [workoutId] with the values it shows, then starts the rest. Idempotent:
     * a set that's already ticked, a finished workout or a removed set changes nothing, so a repeated or
     * late notification tap can never log twice.
     */
    fun logSet(d: AppData, workoutId: Long, setId: Long, now: Long): AppData {
        val w = d.active?.takeIf { it.id == workoutId } ?: return d
        val ex = w.exercises.indexOfFirst { e -> e.sets.any { it.id == setId } }
        if (ex < 0) return d
        val e = w.exercises[ex]
        val i = e.sets.indexOfFirst { it.id == setId }
        if (e.sets[i].done || e.sets[i].skipped) return d
        return tick(d, w, ex, i, fill(d, e, i) ?: return d, now)
    }

    /**
     * B: the lock-screen stepper's "Log set": set [setId] ticked with the weight and reps chosen there, then
     * the rest. Rep-based sets only. Idempotent like [logSet].
     */
    fun logSetAs(d: AppData, workoutId: Long, setId: Long, weightKg: Double?, reps: Int, now: Long): AppData {
        val w = d.active?.takeIf { it.id == workoutId } ?: return d
        val ex = w.exercises.indexOfFirst { e -> e.sets.any { it.id == setId } }
        if (ex < 0) return d
        val e = w.exercises[ex]
        val i = e.sets.indexOfFirst { it.id == setId }
        if (e.sets[i].done || e.sets[i].skipped || !e.tracking.usesReps || reps <= 0) return d
        return tick(d, w, ex, i, e.sets[i].copy(weightKg = weightKg ?: bodyweightZero(d, e), reps = reps, done = true), now)
    }

    /** Puts [filled] in place of set [i] of exercise [ex] and starts the rest. */
    private fun tick(d: AppData, w: Workout, ex: Int, i: Int, filled: SetEntry, now: Long): AppData {
        val e = w.exercises[ex]
        val ticked = e.copy(sets = e.sets.mapIndexed { j, s -> if (j == i) filled else s })
        val logged = w.copy(exercises = w.exercises.mapIndexed { j, x -> if (j == ex) ticked else x })
        val rest = restFor(d, e.exerciseId)
        return d.copy(active = if (d.settings.autoRest) logged.copy(restEndsAt = now + rest * 1000L, restTotalSec = rest) else logged)
    }

    fun startRest(d: AppData, seconds: Int, now: Long): AppData =
        d.copy(active = d.active?.copy(restEndsAt = now + seconds * 1000L, restTotalSec = seconds))

    /** ±[deltaSec] on a running rest; taking it to zero ends it. */
    fun adjustRest(d: AppData, deltaSec: Int, now: Long): AppData {
        val w = d.active ?: return d
        val ends = (w.restEndsAt ?: return d) + deltaSec * 1000L
        if (ends <= now) return skipRest(d)
        return d.copy(active = w.copy(restEndsAt = ends, restTotalSec = (w.restTotalSec + deltaSec).coerceAtLeast(1)))
    }

    fun skipRest(d: AppData): AppData = d.copy(active = d.active?.copy(restEndsAt = null))
}
