package app.forge.data

/**
 * Changes to the session in progress that both the app and the rest notification (section 09) make.
 * Pure: every function takes the data and returns the new data, so the repository can apply them
 * atomically whichever side calls.
 */
/** Derived from the clock, never stored (docs/rest-design.md section 2). */
enum class RestPhase { WORKING, RESTING, OVER }

object Session {
    /** No rest running, a rest still counting down, or a rest that has run out and is counting on. */
    fun rest(w: Workout, now: Long): RestPhase {
        val ends = w.restEndsAt ?: return RestPhase.WORKING
        return if (now < ends) RestPhase.RESTING else RestPhase.OVER
    }

    /**
     * Seconds to show on the rest timer: positive while resting, 0 at the end, negative once over. Partial
     * seconds round away from zero, so 0.4 s over reads -1 (-0:01), never -0:00. 0 when not resting.
     */
    fun signedSecondsLeft(w: Workout, now: Long): Int {
        val left = (w.restEndsAt ?: return 0) - now
        return if (left > 0) ((left + 999) / 1000).toInt() else -(((-left) + 999) / 1000).toInt()
    }

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

    // ---- One logging path (docs/rest-design.md section 5). By set id, one repository update each. ----

    /** The values a set takes from a log or the stepper: everything except its id, type, tick and skip. */
    private fun SetEntry.withValues(v: SetEntry) =
        copy(weightKg = v.weightKg, reps = v.reps, durationSec = v.durationSec, distanceM = v.distanceM, level = v.level, heartRate = v.heartRate)

    private fun ExerciseEntry.replaceSets(f: (index: Int, SetEntry) -> SetEntry) = copy(sets = sets.mapIndexed(f))

    /** Where set [setId] is in workout [workoutId] (the active one), or null for a stale or removed target. */
    private data class At(val w: Workout, val ex: Int, val set: Int) { val e get() = w.exercises[ex] }

    private fun find(d: AppData, workoutId: Long, setId: Long): At? {
        val w = d.active?.takeIf { it.id == workoutId } ?: return null
        val ex = w.exercises.indexOfFirst { e -> e.sets.any { it.id == setId } }
        if (ex < 0) return null
        return At(w, ex, w.exercises[ex].sets.indexOfFirst { it.id == setId })
    }

    private fun AppData.withEntry(at: At, e: ExerciseEntry, rest: (Workout) -> Workout = { it }): AppData =
        copy(active = rest(at.w.copy(exercises = at.w.exercises.mapIndexed { j, x -> if (j == at.ex) e else x })))

    /**
     * Logs set [setId]: ticks it, then ends the current rest and, with auto-rest on and an exercise that isn't
     * cardio, starts a new one. [values] null logs the values the set shows (notification Log, app tick);
     * otherwise those values (app stepper, lock screen). The bodyweight-zero rule lives here only.
     * [applyToRemaining] copies the values to the later sets not yet logged. Idempotent: a set already done
     * or skipped, a stale workout, a removed set, or nothing to log yet all return [d] unchanged.
     */
    fun log(d: AppData, workoutId: Long, setId: Long, values: SetEntry? = null, applyToRemaining: Boolean = false, now: Long): AppData {
        val at = find(d, workoutId, setId) ?: return d
        val e = at.e
        val s = e.sets[at.set]
        if (s.done || s.skipped) return d
        val v = values ?: fill(d, e, at.set) ?: return d
        if (!Measure.hasValue(e.tracking, v)) return d
        val logged = if (v.weightKg == null) v.copy(weightKg = bodyweightZero(d, e)) else v
        val ticked = e.replaceSets { i, x ->
            when {
                i == at.set -> x.withValues(logged).copy(done = true)
                applyToRemaining && i > at.set && !x.done -> x.withValues(v)
                else -> x
            }
        }
        // Logging always ends the current rest; a new one starts only after a lifting set with auto-rest on.
        val rest = restFor(d, e.exerciseId)
        return d.withEntry(at, ticked) { w ->
            if (d.settings.autoRest && e.tracking != Tracking.CARDIO) w.copy(restEndsAt = now + rest * 1000L, restTotalSec = rest)
            else w.copy(restEndsAt = null)
        }
    }

    /** Unticks set [setId]. The rest is left alone (decision D6). A set that isn't ticked is a no-op. */
    fun untick(d: AppData, workoutId: Long, setId: Long): AppData {
        val at = find(d, workoutId, setId) ?: return d
        if (!at.e.sets[at.set].done) return d
        return d.withEntry(at, at.e.replaceSets { i, x -> if (i == at.set) x.copy(done = false) else x })
    }

    /** Changes a set's values without ticking it or touching the rest (the stepper on a logged set; 1.3 reuses it). */
    fun setValues(d: AppData, workoutId: Long, setId: Long, values: SetEntry, applyToRemaining: Boolean = false): AppData {
        val at = find(d, workoutId, setId) ?: return d
        return d.withEntry(at, at.e.replaceSets { i, x ->
            when {
                i == at.set -> x.withValues(values)
                applyToRemaining && i > at.set && !x.done -> x.withValues(values)
                else -> x
            }
        })
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
