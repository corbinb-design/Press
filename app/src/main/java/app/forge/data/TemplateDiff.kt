package app.forge.data

/**
 * What a finished workout changed relative to its template (5.4). Only changes worth keeping are
 * proposed: added exercises, swaps, extra sets and a new order. Skipping an exercise or a set once is not
 * treated as a change to the template.
 */
object TemplateDiff {
    sealed interface Change {
        data class Added(val exerciseId: String, val sets: Int) : Change
        data class Swapped(val index: Int, val from: String, val to: String) : Change
        data class MoreSets(val exerciseId: String, val sets: Int) : Change
        /** A: the session did the template's exercises in a different order; [order] is the session's. */
        data class Reordered(val order: List<String>) : Change
    }

    fun changes(t: Template, w: Workout): List<Change> {
        // A running variation (10.3) stands in for its lift, so it isn't proposed as a swap.
        val tIds = t.exercises.map { it.effectiveId(w.startedAt) }
        val wIds = w.exercises.map { it.exerciseId }
        val added = wIds.filter { it !in tIds }
        val missing = tIds.filter { it !in wIds }
        // A missing template exercise plus a new one in its place reads as a swap.
        val swaps = missing.zip(added).map { (from, to) -> Change.Swapped(tIds.indexOf(from), from, to) }
        val extra = added.drop(swaps.size).map { id -> Change.Added(id, w.exercises.first { it.exerciseId == id }.sets.size) }
        val more = w.exercises.mapNotNull { e ->
            val te = t.exercises.firstOrNull { it.effectiveId(w.startedAt) == e.exerciseId } ?: return@mapNotNull null
            if (e.sets.size > te.sets) Change.MoreSets(e.exerciseId, e.sets.size) else null
        }
        // The template's exercises that were done (a swapped-in one in its place), in the order they were done,
        // against the template's order.
        val swapped = swaps.associate { it.from to it.to }
        val planned = tIds.map { swapped[it] ?: it }
        val done = wIds.filter { it in planned }
        val reordered = if (done != planned.filter { it in wIds }) listOf(Change.Reordered(done)) else emptyList()
        return swaps + more + extra + reordered
    }

    fun apply(t: Template, changes: List<Change>): Template {
        val list = t.exercises.toMutableList()
        // Reordering goes last: swap indices refer to the template's original positions.
        for (c in changes.sortedBy { it is Change.Reordered }) when (c) {
            is Change.Swapped -> list[c.index] = list[c.index].copy(exerciseId = c.to, variationId = null, variationUntil = null)
            is Change.MoreSets -> list.indexOfFirst { it.exerciseId == c.exerciseId || it.variationId == c.exerciseId }.takeIf { it >= 0 }?.let { list[it] = list[it].copy(sets = c.sets) }
            is Change.Added -> list += TemplateExercise.fresh(c.exerciseId).copy(sets = c.sets)
            is Change.Reordered -> {
                // The exercises in [order] fill their own slots in the new order; the rest stay where they were.
                fun matches(te: TemplateExercise, id: String) = te.exerciseId == id || te.variationId == id
                val slots = list.indices.filter { i -> c.order.any { matches(list[i], it) } }
                val moved = c.order.mapNotNull { id -> list.firstOrNull { matches(it, id) } }
                if (moved.size == slots.size) slots.forEachIndexed { k, i -> list[i] = moved[k] }
            }
        }
        return t.copy(exercises = list)
    }
}
