package app.forge.data

/**
 * Data written before tracking types (build 31 and earlier) logged everything as weight × reps. On
 * load, those entries get the type their exercise has now, so records, charts and progression read
 * them the right way round:
 * - pull-ups and dips follow the exercise's Assisted / Weighted switch (Weighted until it's set): the
 *   number logged was the assistance or the weight added, and only the user knows which;
 * - timed holds logged seconds as reps ("Plank (reps = sec)"), which become the time.
 *
 * Idempotent: an entry already of a type other than weight × reps is never touched again, except to
 * follow the switch when it's [ExerciseEntry.legacy].
 */
object Migrate {
    fun run(d: AppData): AppData = d.copy(
        history = d.history.map { w -> w.copy(exercises = w.exercises.map { entry(d, it) }) },
        active = d.active?.let { w -> w.copy(exercises = w.exercises.map { entry(d, it) }) },
    )

    private val converts = setOf(Tracking.ASSISTED, Tracking.BODYWEIGHT_REPS, Tracking.DURATION)

    private fun entry(d: AppData, e: ExerciseEntry): ExerciseEntry {
        val ex = Session.exercise(d, e.exerciseId)
        var out = e
        if (e.tracking == Tracking.WEIGHT_REPS && !e.legacy && Session.baseExercise(d, e.exerciseId).tracking in converts) {
            out = retag(e.copy(legacy = true, bodyweightKg = e.bodyweightKg ?: d.settings.bodyweightKg), ex.tracking)
        }
        if (out.tracking == Tracking.DURATION && Library.isBodyweightHold(ex) && !out.withBodyweight) out = out.copy(withBodyweight = true)
        return out
    }

    /** [e] read as [to]. From weight × reps to timed, the reps were seconds. */
    fun retag(e: ExerciseEntry, to: Tracking): ExerciseEntry {
        if (e.tracking == to) return e
        val wasReps = e.tracking.usesReps
        return e.copy(tracking = to, sets = e.sets.map { s ->
            if (to == Tracking.DURATION && wasReps) s.copy(durationSec = s.durationSec ?: s.reps, reps = null) else s
        })
    }

    /**
     * The Assisted / Weighted switch for [exerciseId]: remembered for new sessions, and applied to today's
     * if nothing of it is logged yet. Old entries whose meaning was never recorded ([ExerciseEntry.legacy])
     * take the first choice made and keep it: switching to Weighted after going unassisted mustn't turn
     * old assistance into added weight. Sessions logged with a type always keep it.
     */
    fun setMode(d: AppData, exerciseId: String, mode: Tracking): AppData {
        val modes = d.copy(trackingModes = d.trackingModes + (exerciseId to mode))
        val history = modes.history.map { w ->
            w.copy(exercises = w.exercises.map { e -> if (e.exerciseId == exerciseId && e.legacy) retag(e, mode).copy(legacy = false) else e })
        }
        val active = modes.active?.let { w ->
            w.copy(exercises = w.exercises.map { e -> if (e.exerciseId == exerciseId && e.sets.none { it.done }) retag(e, mode) else e })
        }
        return modes.copy(history = history, active = active)
    }

    /** Old entries of [exerciseId] that would change with the first Assisted / Weighted choice. */
    fun undecided(d: AppData, exerciseId: String): Int = d.history.count { w -> w.exercises.any { it.exerciseId == exerciseId && it.legacy } }
}
