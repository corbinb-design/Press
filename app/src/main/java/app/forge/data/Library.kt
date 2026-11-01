package app.forge.data

import app.forge.data.Equipment.*

object Library {
    val muscles = listOf("Chest", "Back", "Shoulders", "Traps", "Biceps", "Triceps", "Forearms", "Quads", "Hamstrings", "Glutes", "Calves", "Core", "Cardio", "Other")

    /** Muscles that Balance tracks (7.3): not cardio, not "other". */
    val strengthMuscles get() = muscles.filter { it != "Cardio" && it != "Other" }


    /** Variations offered when a lift stalls (10.3): same pattern, different stimulus. */
    val variations = mapOf(
        "squat" to "pause_squat", "bench" to "pause_bench", "deadlift" to "rdl", "ohp" to "db_shoulder",
        "bb_row" to "pendlay", "rdl" to "db_rdl", "front_squat" to "squat", "incline_bench" to "incline_db",
        "pullup" to "chinup", "hip_thrust" to "good_morning", "leg_press" to "hack",
    )

    /** About 250 built-in exercises with muscles, equipment, tracking, aliases and form cues (see [LibraryData]). */
    val exercises: List<Exercise> = LibraryData.exercises

    /**
     * Pull-ups and dips: done on an assist machine or cable stack (Assisted, less is better) or with
     * weight added (Weighted). Each gets a switch, remembered per exercise.
     */
    private val assistable = setOf("pullup", "chinup", "neutral_pullup", "wide_pullup", "dips", "assisted_pullup", "assisted_dip")

    fun canSwitchAssist(ex: Exercise) =
        ex.id in assistable || (ex.custom && (ex.tracking == Tracking.ASSISTED || ex.tracking == Tracking.BODYWEIGHT_REPS))

    /** A timed hold done with your own body: bodyweight counts toward it, plus anything added. */
    fun isBodyweightHold(ex: Exercise) = ex.tracking == Tracking.DURATION && ex.equipment == BODYWEIGHT

    val defaultTemplates = listOf(
        Template(1, "Push", listOf(
            TemplateExercise("incline_db", 3, 8), TemplateExercise("bench", 3, 6), TemplateExercise("db_shoulder", 3, 10),
            TemplateExercise("lat_raise", 3, 15), TemplateExercise("pushdown", 3, 12),
        )),
        Template(2, "Pull", listOf(
            TemplateExercise("pullup", 3, 8), TemplateExercise("bb_row", 3, 8), TemplateExercise("face_pull", 3, 15),
            TemplateExercise("rear_fly", 3, 15), TemplateExercise("hammer", 3, 10),
        )),
        Template(3, "Legs", listOf(
            TemplateExercise("squat", 3, 5), TemplateExercise("rdl", 3, 8), TemplateExercise("leg_press", 3, 10),
            TemplateExercise("leg_curl", 3, 12), TemplateExercise("standing_calf", 4, 12),
        )),
    )

    val templateColorKeys = listOf("push", "pull", "legs", "upper", "lower", "full")

    /** Template colour guessed from its name ("Push Day" → "push"), or null when nothing matches. */
    fun colorKeyFor(name: String): String? {
        val n = name.lowercase()
        return when {
            "push" in n -> "push"
            "pull" in n -> "pull"
            "leg" in n -> "legs"
            "upper" in n -> "upper"
            "lower" in n -> "lower"
            "full" in n -> "full"
            else -> null
        }
    }

    fun withColor(t: Template) = if (t.colorKey != null) t else t.copy(colorKey = colorKeyFor(t.name))

    /** Starter splits offered on the first-launch screen; picking one seeds the templates. */
    data class Split(val label: String, val templates: List<Template>)

    val splits = listOf(
        Split("Push / Pull / Legs", defaultTemplates),
        Split("Upper / Lower", listOf(
            Template(4, "Upper", listOf(
                TemplateExercise("bench", 4, 6), TemplateExercise("bb_row", 4, 6), TemplateExercise("db_shoulder", 3, 10),
                TemplateExercise("lat_pd", 3, 10), TemplateExercise("skull", 3, 10), TemplateExercise("db_curl", 3, 12),
            )),
            Template(5, "Lower", listOf(
                TemplateExercise("squat", 4, 6), TemplateExercise("rdl", 3, 8), TemplateExercise("leg_press", 3, 10),
                TemplateExercise("leg_curl", 3, 12), TemplateExercise("standing_calf", 4, 12),
            )),
        )),
        Split("Full body", listOf(
            Template(6, "Full Body A", listOf(
                TemplateExercise("squat", 3, 5), TemplateExercise("bench", 3, 6), TemplateExercise("bb_row", 3, 8),
                TemplateExercise("lat_raise", 3, 15), TemplateExercise("pushdown", 3, 12),
            )),
            Template(7, "Full Body B", listOf(
                TemplateExercise("deadlift", 3, 5), TemplateExercise("ohp", 3, 6), TemplateExercise("lat_pd", 3, 10),
                TemplateExercise("leg_curl", 3, 12), TemplateExercise("hanging_leg", 3, 10),
            )),
        )),
        Split("Build my own", emptyList()),
    )
}
