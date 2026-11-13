package app.forge.data

import kotlinx.serialization.Serializable

/** What the phone shows the watch (docs/wear-design.md section 2). Weights are in [unit], the unit the user sees. */
@Serializable
data class WatchSnapshot(
    val v: Int = 1, val seq: Long,
    val workoutId: Long, val name: String, val startedAt: Long,
    val exIndex: Int, val exCount: Int,
    /** Null: every set is done. */
    val next: NextSet?,
    /** Epoch ms; null when not resting. */
    val restEndsAt: Long?,
    val restTotalSec: Int,
    /** "kg" or "lb". */
    val unit: String,
)

@Serializable
data class NextSet(
    val setId: Long, val exerciseName: String,
    val weight: Double, val reps: Int, val setNo: Int, val setCount: Int,
    val bodyweight: Boolean,
)

/** What the watch asks the phone to do (section 3). [weight] is in the snapshot's unit. */
@Serializable
data class WatchAction(
    val v: Int = 1, val actionId: String, val workoutId: Long, val setId: Long?,
    val type: Type, val weight: Double? = null, val reps: Int? = null, val sentAt: Long,
) {
    enum class Type { LOG, LOG_VALUES, ADD15, SKIP, FINISH }
}

/** What the watch recorded, sent after the session (section 3). Heart-rate samples are deltas from [hrStart]. */
@Serializable
data class WatchSummary(
    val v: Int = 1, val workoutId: Long, val startedAt: Long, val endedAt: Long,
    val avgHr: Int?, val maxHr: Int?, val calories: Double?,
    val hrStart: Long, val hrDeltas: List<Int>,
    /** "finished", "other_app", "permission_lost", ... */
    val endReason: String,
)

/** Pure mapping between the phone's data and the watch protocol. The watch path reuses [Session]. */
object WatchProtocol {
    const val VERSION = 1
    const val ADD_REST_SEC = 15

    /** The snapshot for the session in progress, or null when there isn't one (the item is then deleted). */
    fun snapshot(d: AppData, seq: Long): WatchSnapshot? {
        val w = d.active ?: return null
        val n = Session.next(d)
        val unit = d.settings.unit
        val next = n?.let {
            val ex = Session.exercise(d, it.entry.exerciseId)
            NextSet(
                setId = it.setEntry.id, exerciseName = ex.name,
                weight = Units.toDisplay(it.kg ?: 0.0, unit), reps = it.reps ?: 0,
                setNo = it.set + 1, setCount = it.entry.sets.size,
                bodyweight = it.entry.tracking == Tracking.BODYWEIGHT_REPS || ex.equipment == Equipment.BODYWEIGHT,
            )
        }
        return WatchSnapshot(
            seq = seq, workoutId = w.id, name = w.name, startedAt = w.startedAt,
            exIndex = n?.ex ?: (w.exercises.size - 1).coerceAtLeast(0), exCount = w.exercises.size,
            next = next, restEndsAt = w.restEndsAt, restTotalSec = w.restTotalSec, unit = unit.label,
        )
    }

    /**
     * Applies a watch action. Returns [d] itself, unchanged, for an unknown version, another workout, a missing
     * set, or a set already logged or skipped, which is what makes a repeated tap safe. FINISH is left to the
     * phone's own finish path, so it changes nothing here.
     */
    fun apply(d: AppData, a: WatchAction, now: Long): AppData {
        if (a.v != VERSION) return d
        val w = d.active?.takeIf { it.id == a.workoutId } ?: return d
        return when (a.type) {
            WatchAction.Type.LOG -> a.setId?.let { Session.log(d, w.id, it, now = now) } ?: d
            WatchAction.Type.LOG_VALUES -> a.setId?.let { logValues(d, w, it, a, now) } ?: d
            WatchAction.Type.ADD15 -> Session.adjustRest(d, ADD_REST_SEC, now)
            WatchAction.Type.SKIP -> a.setId?.let { skip(d, w, it) } ?: d
            WatchAction.Type.FINISH -> d
        }
    }

    private fun logValues(d: AppData, w: Workout, setId: Long, a: WatchAction, now: Long): AppData {
        val ex = w.exercises.indexOfFirst { e -> e.sets.any { it.id == setId } }
        if (ex < 0) return d
        val e = w.exercises[ex]
        val i = e.sets.indexOfFirst { it.id == setId }
        // Start from what the set shows, then put the watch's values over it.
        val base = Session.fill(d, e, i) ?: e.sets[i]
        val v = base.copy(
            weightKg = a.weight?.let { Units.toKg(it, d.settings.unit) } ?: base.weightKg,
            reps = a.reps ?: base.reps,
        )
        return Session.log(d, w.id, setId, values = v, now = now)
    }

    private fun skip(d: AppData, w: Workout, setId: Long): AppData {
        val ex = w.exercises.indexOfFirst { e -> e.sets.any { it.id == setId } }
        if (ex < 0) return d
        val e = w.exercises[ex]
        val s = e.sets.first { it.id == setId }
        if (s.done || s.skipped) return d
        val sets = e.sets.map { if (it.id == setId) it.copy(skipped = true) else it }
        return d.copy(active = w.copy(exercises = w.exercises.mapIndexed { j, x -> if (j == ex) e.copy(sets = sets) else x }))
    }
}
