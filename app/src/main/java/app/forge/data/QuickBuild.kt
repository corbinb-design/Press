package app.forge.data

/** Figma 3.1: a sensible first draft from a few choices. The user edits it in 3.2 before saving. */
object QuickBuild {
    enum class Focus(val label: String, val colorKey: String, val slots: List<Slot>) {
        PUSH("Push", "push", listOf(Slot("Chest"), Slot("Chest"), Slot("Shoulders"), Slot("Triceps"), Slot("Shoulders", "lat_raise", "cable_lat"), Slot("Triceps"), Slot("Chest"))),
        PULL("Pull", "pull", listOf(Slot("Back", "pullup", "lat_pd"), Slot("Back", "bb_row", "seated_row"), Slot("Back", "db_row", "seated_row", "lat_pd"), Slot("Biceps"), Slot("Shoulders", "face_pull", "rear_fly"), Slot("Biceps"), Slot("Back", "straight_arm", "seated_row"))),
        LEGS("Legs", "legs", listOf(Slot("Quads"), Slot("Hamstrings"), Slot("Quads"), Slot("Hamstrings"), Slot("Glutes"), Slot("Calves"), Slot("Core"))),
        UPPER("Upper", "upper", listOf(Slot("Chest"), Slot("Back", "bb_row", "db_row"), Slot("Chest"), Slot("Back", "lat_pd", "pullup"), Slot("Shoulders", "db_shoulder", "lat_raise"), Slot("Biceps"), Slot("Triceps"))),
        LOWER("Lower", "lower", listOf(Slot("Quads"), Slot("Hamstrings"), Slot("Glutes"), Slot("Quads"), Slot("Hamstrings"), Slot("Calves"), Slot("Core"))),
        FULL("Full body", "full", listOf(Slot("Quads"), Slot("Chest"), Slot("Back", "bb_row", "db_row", "lat_pd"), Slot("Hamstrings"), Slot("Shoulders"), Slot("Biceps"), Slot("Triceps"))),
    }

    enum class Goal(val label: String, val range: String) {
        STRENGTH("Strength", "3–6"), MUSCLE("Muscle", "8–12"), ENDURANCE("Endurance", "12–15"),
    }

    enum class Kit(val label: String, val allowed: Set<Equipment>) {
        GYM("Full gym", Equipment.entries.toSet()),
        DUMBBELLS("Dumbbells only", setOf(Equipment.DUMBBELL, Equipment.BODYWEIGHT)),
        HOME("Home", setOf(Equipment.DUMBBELL, Equipment.KETTLEBELL, Equipment.BODYWEIGHT, Equipment.BANDS)),
    }

    /** One exercise slot: a muscle, optionally with preferred exercises (e.g. rear delts on a pull day). */
    class Slot(val muscle: String, vararg val prefer: String)

    val minutes = listOf(45, 60, 75)

    /** Exercises per session for a time budget. */
    fun count(minutes: Int) = when { minutes <= 45 -> 4; minutes <= 60 -> 6; else -> 7 }

    fun build(name: String, focus: Focus, goal: Goal, kit: Kit, minutes: Int, library: List<Exercise> = Library.exercises): Template {
        // Drafts are strength work you load: no cardio, carries or assisted machines.
        val usable = library.filter { it.equipment in kit.allowed && it.tracking != Tracking.CARDIO && it.tracking != Tracking.DISTANCE_WEIGHT && it.tracking != Tracking.ASSISTED }
        val used = mutableSetOf<String>()
        val picks = focus.slots.take(count(minutes)).mapNotNull { slot ->
            val pick = slot.prefer.firstNotNullOfOrNull { id -> usable.firstOrNull { it.id == id && it.id !in used } }
                ?: usable.firstOrNull { it.muscle == slot.muscle && it.id !in used }
            pick?.also { used += it.id }
        }
        return Template(newId(), name.trim().ifEmpty { focus.label }, picks.mapIndexed { i, ex -> prescribe(ex.id, i, goal) }, focus.colorKey)
    }

    /** Sets, rep range and rest by goal; earlier (compound) slots get heavier work and longer rest. */
    fun prescribe(id: String, index: Int, goal: Goal): TemplateExercise {
        val (sets, range, rest) = when (goal) {
            Goal.STRENGTH -> if (index < 2) Triple(4, 3..6, 180) else Triple(3, 6..8, 120)
            Goal.MUSCLE -> when (index) {
                0 -> Triple(4, 6..8, 150)
                1 -> Triple(3, 8..12, 120)
                2, 3 -> Triple(3, 8..12, 90)
                else -> Triple(3, 10..12, 60)
            }
            Goal.ENDURANCE -> Triple(3, 12..15, 60)
        }
        return TemplateExercise(id, sets, reps = range.first, repMin = range.first, repMax = range.last, restSec = rest)
    }
}
