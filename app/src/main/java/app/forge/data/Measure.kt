package app.forge.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How each [Tracking] type counts (v1.1): which sets count, volume, a single comparable score
 * (higher is always better) and how a set reads. Stats, records, progression and every screen go
 * through here so the rules live in one place.
 */
object Measure {
    /** The logged weight, doubled for per-hand entries. For assisted sets it's the assistance. */
    fun load(e: ExerciseEntry, s: SetEntry): Double = (s.weightKg ?: 0.0) * (if (e.perHand) 2 else 1)

    /** What a timed hold held: bodyweight (when it's a bodyweight hold and the profile had one) plus anything added. */
    fun holdLoad(e: ExerciseEntry, s: SetEntry): Double = (if (e.withBodyweight) e.bodyweightKg ?: 0.0 else 0.0) + load(e, s)

    /** Has the value this type is measured by. */
    fun hasValue(t: Tracking, s: SetEntry): Boolean = when {
        t.usesReps -> s.reps != null
        t == Tracking.DURATION || t == Tracking.CARDIO -> s.durationSec != null
        else -> s.distanceM != null
    }

    /** A done working set of a strength type: counts toward records, scores and Balance. Cardio never does. */
    fun counts(e: ExerciseEntry, s: SetEntry): Boolean =
        e.tracking.isLifting && s.done && !s.skipped && s.type != SetType.WARMUP && hasValue(e.tracking, s)

    /**
     * Kilograms lifted for the totals. Weight × reps; bodyweight sets add the logged bodyweight when the
     * profile had one. Assisted, timed, carried and cardio work isn't volume.
     */
    fun volume(e: ExerciseEntry, s: SetEntry): Double {
        if (!counts(e, s)) return 0.0
        val reps = s.reps ?: 0
        return when (e.tracking) {
            Tracking.WEIGHT_REPS -> load(e, s) * reps
            Tracking.BODYWEIGHT_REPS -> ((e.bodyweightKg ?: 0.0) + load(e, s)) * reps
            else -> 0.0
        }
    }

    /**
     * One number per set where higher is always better, so records, "best", charts and stalls work for
     * every type:
     * - weight × reps: estimated 1RM (Epley);
     * - bodyweight: estimated 1RM of bodyweight + added (just added without a bodyweight), with reps breaking ties;
     * - assisted: estimated 1RM of bodyweight − assistance; without a bodyweight, minus the assistance
     *   spread over the reps, so less assistance or more reps both score higher;
     * - timed: seconds (what was held, bodyweight included, breaks ties); carries: weight × distance; cardio: 0.
     */
    fun score(e: ExerciseEntry, s: SetEntry): Double {
        if (!counts(e, s)) return 0.0
        val l = load(e, s)
        return when (e.tracking) {
            Tracking.WEIGHT_REPS -> Stats.e1rm(l, s.reps!!)
            Tracking.BODYWEIGHT_REPS -> Stats.e1rm((e.bodyweightKg ?: 0.0) + l, s.reps!!) + s.reps / 1000.0
            Tracking.ASSISTED -> e.bodyweightKg?.let { Stats.e1rm(it - l, s.reps!!) } ?: (-l / (1 + s.reps!! / 30.0))
            Tracking.DURATION -> s.durationSec!! + holdLoad(e, s) / 10_000.0
            Tracking.DISTANCE_WEIGHT -> l * (s.distanceM ?: 0.0)
            Tracking.CARDIO -> 0.0
        }
    }

    /** Lower is better for assisted work: less help from the machine or band. */
    fun lowerIsBetter(t: Tracking) = t == Tracking.ASSISTED

    // ---- How things read ------------------------------------------------------------------------

    /** 65 → "1:05", 3725 → "1:02:05". */
    fun time(sec: Int): String {
        val h = sec / 3600; val m = sec % 3600 / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Distance for cardio: km or mi by the profile's unit. */
    fun cardioDistance(m: Double, unit: WeightUnit): String =
        if (unit == WeightUnit.KG) "${Units.num(m / 1000)} km" else "${Units.num(m / 1609.344)} mi"

    /** Distance for carries and sleds: metres, or yards for pound users. */
    fun carryDistance(m: Double, unit: WeightUnit): String =
        if (unit == WeightUnit.KG) "${Units.num(m)} m" else "${Units.num(m * 1.09361)} yd"

    fun distanceLabel(t: Tracking, unit: WeightUnit) = when {
        t == Tracking.CARDIO -> if (unit == WeightUnit.KG) "km" else "mi"
        else -> if (unit == WeightUnit.KG) "m" else "yd"
    }

    /** Converts a shown distance back to metres. */
    fun toMetres(v: Double, t: Tracking, unit: WeightUnit): Double = when {
        t == Tracking.CARDIO -> if (unit == WeightUnit.KG) v * 1000 else v * 1609.344
        else -> if (unit == WeightUnit.KG) v else v / 1.09361
    }

    fun fromMetres(m: Double, t: Tracking, unit: WeightUnit): Double = when {
        t == Tracking.CARDIO -> if (unit == WeightUnit.KG) m / 1000 else m / 1609.344
        else -> if (unit == WeightUnit.KG) m else m * 1.09361
    }

    /** The weight part: "62.5 kg", "−20 kg" (assistance), "BW", "BW + 10 kg". */
    fun weight(t: Tracking, kg: Double?, unit: WeightUnit): String {
        val w = kg ?: 0.0
        val v = "${Units.w(abs(w), unit)} ${unit.label}"
        return when (t) {
            Tracking.ASSISTED -> if (w <= 0.0) "BW" else "−$v"
            Tracking.BODYWEIGHT_REPS -> if (w <= 0.0) "BW" else "BW + $v"
            Tracking.DURATION -> if (w <= 0.0) "" else "+$v"
            else -> v
        }
    }

    /** [format] for a set of [e], with its per-hand and bodyweight-hold settings. */
    fun format(e: ExerciseEntry, s: SetEntry, unit: WeightUnit): String = format(e.tracking, s, unit, e.perHand, e.withBodyweight)

    /**
     * One set on one line, the way every screen shows it. Timed sets lead with what was held:
     * "BW + 10 kg · 0:45" for a bodyweight hold ([bodyweight]), "20 kg · 0:30" for a plate pinch.
     */
    fun format(t: Tracking, s: SetEntry, unit: WeightUnit, perHand: Boolean = false, bodyweight: Boolean = false): String {
        val kg = s.weightKg?.let { if (perHand) it * 2 else it }
        return when (t) {
            Tracking.WEIGHT_REPS, Tracking.ASSISTED, Tracking.BODYWEIGHT_REPS -> "${weight(t, kg, unit)} × ${s.reps ?: "–"}"
            Tracking.DURATION -> {
                val held = when {
                    bodyweight -> weight(Tracking.BODYWEIGHT_REPS, kg, unit)
                    (kg ?: 0.0) > 0.0 -> weight(Tracking.WEIGHT_REPS, kg, unit)
                    else -> null
                }
                listOfNotNull(held, s.durationSec?.let(::time) ?: "–").joinToString(" · ")
            }
            Tracking.DISTANCE_WEIGHT -> "${weight(t, kg, unit)} × ${s.distanceM?.let { carryDistance(it, unit) } ?: "–"}"
            Tracking.CARDIO -> cardio(s, unit)
        }
    }

    /** "25:00 · 4.2 km · L8 · 142 bpm", leaving out what wasn't logged. */
    fun cardio(s: SetEntry, unit: WeightUnit): String = listOfNotNull(
        s.durationSec?.let(::time), s.distanceM?.let { cardioDistance(it, unit) }, s.level?.let { "L$it" }, s.heartRate?.let { "$it bpm" },
    ).joinToString(" · ").ifEmpty { "–" }

    /** The two input columns of a set row, and their headers. */
    fun columns(t: Tracking, unit: WeightUnit, perHand: Boolean): Pair<String, String> = when (t) {
        Tracking.WEIGHT_REPS -> unit.label.uppercase() + (if (perHand) "/H" else "") to "REPS"
        Tracking.ASSISTED -> "−" + unit.label.uppercase() to "REPS"
        Tracking.BODYWEIGHT_REPS -> "+" + unit.label.uppercase() to "REPS"
        Tracking.DURATION -> "+" + unit.label.uppercase() to "TIME"
        Tracking.DISTANCE_WEIGHT -> unit.label.uppercase() + (if (perHand) "/H" else "") to distanceLabel(t, unit).uppercase()
        Tracking.CARDIO -> "" to ""
    }

    /** The two input values of a set row as shown in the fields. */
    fun cells(t: Tracking, kg: Double?, reps: Int?, sec: Int?, metres: Double?, unit: WeightUnit): Pair<String, String> {
        val w = kg?.let { if (t == Tracking.WEIGHT_REPS || t == Tracking.DISTANCE_WEIGHT) Units.w(it, unit) else if (it <= 0.0) "0" else Units.w(it, unit) } ?: "–"
        return when (t) {
            Tracking.DURATION -> w to (sec?.let(::time) ?: "–")
            Tracking.DISTANCE_WEIGHT -> w to (metres?.let { Units.num(fromMetres(it, t, unit)) } ?: "–")
            else -> w to (reps?.toString() ?: "–")
        }
    }

    /** "+1:05" / "−5 s": a difference in seconds. */
    fun secondsDelta(d: Int): String = if (abs(d) >= 60) (if (d > 0) "+" else "−") + time(abs(d)) else (if (d > 0) "+" else "−") + "${abs(d)} s"

    fun roundMinutes(sec: Int) = (sec / 60.0).roundToInt()
}
