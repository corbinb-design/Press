package app.forge.data

/**
 * Phase 2 step 1: what a finished session looks like as a Health Connect exercise record. Pure, so the
 * decisions (what is written, under which id) are testable without Health Connect.
 *
 * No per-exercise segments: sets carry no timestamps, so a segment's start and end would be invented, and
 * Health Connect rejects segments outside the session. One record per session, strength training.
 */
object HealthExport {
    data class Record(val clientId: String, val title: String, val startMs: Long, val endMs: Long)

    /** Health Connect needs an end after the start; a session shorter than this is stretched to it. */
    const val MIN_MS = 60_000L

    /**
     * The record for [w], or null when it shouldn't be written: not finished, already written, or a
     * cardio-only session (it isn't strength training, and the watch usually records those itself).
     */
    fun record(w: Workout): Record? {
        val end = w.endedAt ?: return null
        if (w.healthRecordId != null || w.cardioOnly) return null
        return Record(clientId(w.id), w.name.trim().ifBlank { "Workout" }, w.startedAt, maxOf(end, w.startedAt + MIN_MS))
    }

    /** Stable per workout, so writing the same session twice updates one record instead of adding another. */
    fun clientId(workoutId: Long) = "press-$workoutId"
}
