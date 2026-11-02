package app.forge.data

import kotlinx.serialization.Serializable

enum class Equipment(val label: String) {
    BARBELL("Barbell"), DUMBBELL("Dumbbell"), MACHINE("Machine"), CABLE("Cable"), BODYWEIGHT("Bodyweight"), KETTLEBELL("Kettlebell"),
    // v1.1. Appended, so older files (which store the name) still read.
    BANDS("Bands"), SMITH("Smith machine"), CARDIO("Cardio"), OTHER("Other");

    /** Implements that are loaded one per hand, so "per hand" logging makes sense. */
    val isUnilateralLoad get() = this == DUMBBELL || this == KETTLEBELL || this == CABLE
}

/**
 * What a set records (v1.1). Old data has no tracking and reads as [WEIGHT_REPS], so it's unchanged.
 * - [WEIGHT_REPS]: weight × reps.
 * - [ASSISTED]: the weight is assistance, so lower is better; shown as "−20 kg"; not counted in volume.
 * - [BODYWEIGHT_REPS]: optional added weight × reps; volume uses bodyweight when the profile has one.
 * - [DURATION]: time instead of reps, optional added weight; the record is the longest time.
 * - [DISTANCE_WEIGHT]: carries and sleds, weight × distance.
 * - [CARDIO]: one entry of time with distance, level and heart rate; never volume, records or Balance.
 */
enum class Tracking(val label: String) {
    WEIGHT_REPS("Weight × reps"), ASSISTED("Assisted"), BODYWEIGHT_REPS("Bodyweight"),
    DURATION("Timed"), DISTANCE_WEIGHT("Weight × distance"), CARDIO("Cardio");

    val usesReps get() = this == WEIGHT_REPS || this == ASSISTED || this == BODYWEIGHT_REPS
    val usesTime get() = this == DURATION || this == CARDIO
    val usesDistance get() = this == DISTANCE_WEIGHT || this == CARDIO
    /** Strength work: everything except cardio. */
    val isLifting get() = this != CARDIO
}

enum class SetType(val short: String) { NORMAL(""), WARMUP("WARM"), DROP("DROP"), FAILURE("FAIL") }

enum class WeightUnit(val label: String) { KG("kg"), LB("lb") }

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val muscle: String,
    val equipment: Equipment,
    val custom: Boolean = false,
    // v1.1, all defaulted so older files (custom exercises) read unchanged.
    val tracking: Tracking = Tracking.WEIGHT_REPS,
    /** Muscles that also work; they count half a set each in Balance. */
    val secondary: List<String> = emptyList(),
    /** Other names people search for ("RDL", "OHP"). */
    val aliases: List<String> = emptyList(),
    /** Two or three one-line form cues. */
    val cues: List<String> = emptyList(),
)

/**
 * Weights are always stored in kilograms and distances in metres; the UI converts for display.
 * What's filled in depends on the entry's [Tracking]: reps for the rep types, [durationSec] for timed
 * sets and cardio, [distanceM] for carries and cardio, [level] and [heartRate] for cardio.
 */
@Serializable
data class SetEntry(
    val id: Long,
    val type: SetType = SetType.NORMAL,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val done: Boolean = false,
    val durationSec: Int? = null,
    val distanceM: Double? = null,
    val level: Int? = null,
    val heartRate: Int? = null,
    /**
     * v1.0.1: left out on purpose during a session. Never done at the same time; not logged, so it's
     * out of volume, records, progression and the finish check. Finished workouts don't keep it.
     */
    val skipped: Boolean = false,
)

@Serializable
data class ExerciseEntry(
    val id: Long,
    val exerciseId: String,
    val sets: List<SetEntry>,
    /** When true, [SetEntry.weightKg] is the load in ONE hand and volume counts both hands. */
    val perHand: Boolean = false,
    /** The 10.2 target card was answered (Use or Keep) for this session. */
    val targetSeen: Boolean = false,
    /** This was a planned lighter week (10.3); earns XP. */
    val lighterWeek: Boolean = false,
    /** What the sets record, copied from the exercise when it's added, so history reads the same later. */
    val tracking: Tracking = Tracking.WEIGHT_REPS,
    /** Profile bodyweight when this was logged, for bodyweight and assisted volume and records. */
    val bodyweightKg: Double? = null,
    /**
     * Logged before tracking types existed (build 31 and earlier), when everything was weight × reps. Its
     * [tracking] was worked out on load and follows the exercise's Assisted / Weighted switch.
     */
    val legacy: Boolean = false,
    /** A timed hold done with your own body (plank, dead hang): bodyweight counts, plus anything added. */
    val withBodyweight: Boolean = false,
)

@Serializable
data class Workout(
    val id: Long,
    val name: String,
    val templateId: Long? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val exercises: List<ExerciseEntry>,
    val restEndsAt: Long? = null,
    val restTotalSec: Int = 0,
    /** Free-text note shown on the session page (6.2). */
    val note: String = "",
    /**
     * Counts toward the weekly goal and streak. Always true for lifting; a cardio-only session counts only
     * when it ran 20+ minutes and the setting allows it. Decided when the session is finished, so changing
     * the setting later doesn't rewrite past weeks.
     */
    val countsForGoal: Boolean = true,
    /** v1.0.1: strength sets skipped in the session, for "1 skipped" on the summary. */
    val skippedSets: Int = 0,
) {
    /** Every entry is cardio: a standalone cardio session. */
    val cardioOnly: Boolean get() = exercises.isNotEmpty() && exercises.all { it.tracking == Tracking.CARDIO }
}

@Serializable
data class TemplateExercise(
    val exerciseId: String,
    val sets: Int = 3,
    /** Fixed target from before rep ranges. Kept in step with [repMin] so older builds still read something sensible. */
    val reps: Int = 8,
    // Older files have no range: it defaults to the fixed reps they were written with ("3 × 5" stays 3 × 5).
    val repMin: Int = reps,
    val repMax: Int = reps,
    /** Rest after each set; null uses [Settings.restSeconds]. */
    val restSec: Int? = null,
    // Progression rules (10.1). Older files get double progression with +2.5 kg.
    val method: ProgressionMethod = ProgressionMethod.DOUBLE,
    val incrementKg: Double = Progression.DEFAULT_INCREMENT_KG,
    /** Offer the 10.3 lighter week after 3 sessions without progress. */
    val suggestLighter: Boolean = true,
    /** A planned lighter week: next session's weight, cleared once that session is logged. */
    val lighterNextKg: Double? = null,
    /** 10.3 "Swap to a variation": done in place of [exerciseId] until [variationUntil] (epoch ms). */
    val variationId: String? = null,
    val variationUntil: Long? = null,
    /** When a stall was last dealt with; only sessions after it count toward the next one. */
    val stallHandledAt: Long? = null,
) {
    /** The exercise actually done at [at]: the variation while one is running, else [exerciseId]. */
    fun effectiveId(at: Long): String = variationId?.takeIf { variationUntil != null && at < variationUntil } ?: exerciseId

    companion object {
        /** A newly added exercise: 3 × 8–12 with the default rest. */
        fun fresh(exerciseId: String) = TemplateExercise(exerciseId, sets = 3, reps = 8, repMin = 8, repMax = 12)
    }
}

@Serializable
data class Template(
    val id: Long,
    val name: String,
    val exercises: List<TemplateExercise>,
    /** One of [Library.templateColorKeys]; null until guessed from the name or picked in the editor. */
    val colorKey: String? = null,
)

@Serializable
data class Settings(
    val name: String = "",
    val unit: WeightUnit = WeightUnit.KG,
    val restSeconds: Int = 90,
    val autoRest: Boolean = true,
    val avatarPath: String? = null,
    /** Sessions per Monday–Sunday week that count towards the streak. */
    val weeklyGoal: Int = 4,
    /** Set once the first-launch screen is done. Files written before it existed are migrated to true in load(). */
    val onboarded: Boolean = false,
    // Notification toggles in Profile (14.1). The notifications themselves arrive in Phases 3, 5 and 7.
    val notifyRest: Boolean = true,
    val notifySunday: Boolean = true,
    /** No streak-nudge notification exists yet, so Profile doesn't show this; kept so files that have it still load. */
    val notifyNudges: Boolean = false,
    /** 9.2: the rest notification's actions work from the lock screen (logging a planned set is low-risk). */
    val logFromLockScreen: Boolean = true,
    /** 13.2 cosmetics: chosen app icon and spine finish (keys from [Unlocks]). */
    val appIcon: String = "lime",
    val spineFinish: String = "solid",
    /** Optional, for bodyweight lifts (14.2). Stored in kg like every other weight. */
    val bodyweightKg: Double? = null,
    /** v1.1: a standalone cardio session of 20+ minutes counts toward the weekly goal. */
    val cardioCountsForGoal: Boolean = true,
    /** Context layer: "Skip, use a note only" was chosen, so the note opens without asking for Health Connect. */
    val healthSkipped: Boolean = false,
) {
    /** First launch: shown while the name is empty and setup hasn't been completed. */
    val needsSetup: Boolean get() = !onboarded && name.isBlank()
}

@Serializable
data class AppData(
    val templates: List<Template> = emptyList(),
    val history: List<Workout> = emptyList(),
    val active: Workout? = null,
    val settings: Settings = Settings(),
    val customExercises: List<Exercise> = emptyList(),
    val seeded: Boolean = false,
    /** Weekly-goal streak (section 02). Older files get it rebuilt from history on first load. */
    val streak: StreakState = StreakState(),
    /** Assisted or Weighted, per exercise that has the switch (see [Library.canSwitchAssist]). */
    val trackingModes: Map<String, Tracking> = emptyMap(),
    /** F: when a template was first shared with someone (sent as a file or shown as a QR code), for the Cabinet. */
    val firstSharedAt: Long? = null,
    /** Context layer: confirmed "what happened" notes, oldest first. */
    val contextNotes: List<ContextNote> = emptyList(),
    /** Context layer: readiness check-ins (answered or skipped), oldest first; the last [LifeContext.CHECK_INS_KEPT]. */
    val checkIns: List<CheckIn> = emptyList(),
    /** Context layer: outside activity, steps and sleep per day, derived from Health Connect and notes (see [Outside]). */
    val outsideDays: List<OutsideDay> = emptyList(),
    /** Context layer: the "Accept tweaks" banner per session (the last few), and the AI issue headline per week. */
    val sessionCoach: List<SessionCoach> = emptyList(),
    val issueCoach: List<IssueCoach> = emptyList(),
) {
    fun coachFor(workoutId: Long): SessionCoach? = sessionCoach.lastOrNull { it.workoutId == workoutId }
    fun issueCoachFor(week: String): IssueCoach? = issueCoach.lastOrNull { it.week == week }
}

private var idCounter = 0L
fun newId(): Long = System.currentTimeMillis() * 1000 + (idCounter++ % 1000)
