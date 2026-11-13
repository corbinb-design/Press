package app.forge.data

import kotlin.math.abs
import kotlin.math.round

/**
 * Plate maths for barbell lifts, in the display unit (kg or lb). Pure. The bar and plate set are the usual gym
 * defaults; they aren't settings yet, so no data changes.
 */
object Plates {
    data class PerSide(val plates: List<Double>, val leftOverPerSide: Double)
    data class WarmupSet(val weight: Double, val reps: Int)

    fun bar(unit: WeightUnit) = if (unit == WeightUnit.KG) 20.0 else 45.0
    private fun plateSet(unit: WeightUnit) =
        if (unit == WeightUnit.KG) listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25) else listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5)
    /** Smallest step between loadable weights: a pair of the smallest plate. */
    private fun step(unit: WeightUnit) = plateSet(unit).last() * 2

    /** The plates on each side for a total [weight] (bar included), heaviest first; null if it's lighter than the bar. */
    fun perSide(weight: Double, unit: WeightUnit): PerSide? {
        val bar = bar(unit)
        if (weight < bar - 1e-9) return null
        var left = (weight - bar) / 2
        val out = mutableListOf<Double>()
        for (p in plateSet(unit)) while (left + 1e-9 >= p) { out += p; left -= p }
        return PerSide(out, if (abs(left) < 1e-9) 0.0 else left)
    }

    /**
     * A warm-up ramp below [working]: the bar for 10, then about 40%, 60% and 80% for 5, 3 and 2, each rounded to a
     * loadable weight. Steps that land on the bar, on a repeat or at the working weight are left out.
     */
    fun warmups(working: Double, unit: WeightUnit): List<WarmupSet> {
        val bar = bar(unit)
        if (working <= bar + 1e-9) return emptyList()
        val out = mutableListOf(WarmupSet(bar, 10))
        for ((frac, reps) in listOf(0.4 to 5, 0.6 to 3, 0.8 to 2)) {
            val w = bar + round((working * frac - bar).coerceAtLeast(0.0) / step(unit)) * step(unit)
            if (w > out.last().weight + 1e-9 && w < working - 1e-9) out += WarmupSet(w, reps)
        }
        return out
    }
}
