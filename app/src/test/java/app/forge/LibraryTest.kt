package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test

/** v1.1 library: size, data quality, the original IDs, and forgiving search. */
class LibraryTest {
    private val all = Library.exercises

    @Test fun aboutTwoHundredFiftyExercisesWithUniqueIds() {
        assertTrue("library has ${all.size}", all.size in 240..280)
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test fun originalIdsAreKept() {
        val original = """bench incline_bench pause_bench db_bench incline_db db_fly cable_fly pec_deck chest_press dips pushup deadlift bb_row
            pendlay db_row pullup chinup lat_pd seated_row tbar straight_arm ohp db_shoulder arnold lat_raise cable_lat rear_fly face_pull upright_row
            bb_curl db_curl hammer incline_curl preacher cable_curl pushdown oh_ext skull cgbp db_kickback squat front_squat pause_squat leg_press
            hack leg_ext bulgarian goblet lunge rdl db_rdl leg_curl seated_curl good_morning hip_thrust kb_swing abduction standing_calf seated_calf
            plank hanging_leg cable_crunch ab_wheel""".split(Regex("\\s+"))
        val ids = all.map { it.id }.toSet()
        assertEquals(emptyList<String>(), original.filter { it !in ids })
    }

    @Test fun everyExerciseIsComplete() {
        all.forEach { e ->
            assertTrue("${e.id} cues", e.cues.size in 2..3)
            assertTrue("${e.id} muscle ${e.muscle}", e.muscle in Library.muscles)
            e.secondary.forEach { assertTrue("${e.id} secondary $it", it in Library.muscles) }
            assertTrue("${e.id} cue too long", e.cues.all { it.length <= 60 })
            if (e.tracking == Tracking.CARDIO) assertEquals(Equipment.CARDIO, e.equipment)
        }
    }

    @Test fun includesTheRequestedExercises() {
        val names = all.map { it.name.lowercase() }
        listOf(
            "assisted pull-up", "assisted dip", "diverging lat pulldown", "diverging row", "diverging chest press", "diverging shoulder press",
            "hack squat", "pendulum squat", "belt squat", "chest-supported t-bar row", "reverse pec deck", "seated calf raise", "hip thrust machine",
            "glute kickback machine", "45° back extension", "dead hang", "plank", "farmer's carry", "sled push",
            "treadmill", "outdoor run", "walk", "bike", "rower", "elliptical", "stair climber", "ski erg", "swim",
        ).forEach { assertTrue("missing $it", it in names) }
        Equipment.entries.forEach { eq -> assertTrue("no $eq exercises", all.any { it.equipment == eq }) }
    }

    @Test fun trackingTypesAreRight() {
        val by = all.associateBy { it.id }
        assertEquals(Tracking.ASSISTED, by.getValue("assisted_pullup").tracking)
        assertEquals(Tracking.BODYWEIGHT_REPS, by.getValue("pullup").tracking)
        assertEquals(Tracking.DURATION, by.getValue("plank").tracking)
        assertEquals(Tracking.DURATION, by.getValue("dead_hang").tracking)
        assertEquals(Tracking.DISTANCE_WEIGHT, by.getValue("farmers").tracking)
        assertEquals(Tracking.DISTANCE_WEIGHT, by.getValue("sled_push").tracking)
        assertEquals(Tracking.CARDIO, by.getValue("rower").tracking)
        assertEquals(Tracking.WEIGHT_REPS, by.getValue("bench").tracking)
    }

    private fun top(q: String) = ExerciseSearch.search(all, q).first().id

    @Test fun searchMatchesAliases() {
        assertEquals("rdl", top("RDL"))
        assertEquals("ohp", top("OHP"))
        assertEquals("lat_pd", top("pull down"))
        assertEquals("lat_pd", top("pulldown"))
        assertEquals("farmers", top("farmers walk"))
        assertEquals("assisted_pullup", top("gravitron"))
    }

    @Test fun searchForgivesTypos() {
        assertEquals("rdl", top("romainan deadlift"))
        assertEquals("pushdown", top("tricep pushdwn"))
        assertEquals("bulgarian", top("bulgrian split"))
        assertEquals("hip_thrust", top("hip thurst"))
    }

    @Test fun searchRanksBetterMatchesFirst() {
        // Exact names beat partial ones: "squat" is the back squat, not "goblet squat".
        assertEquals("squat", top("squat"))
        assertEquals("bench", top("bench"))
        assertTrue(ExerciseSearch.search(all, "zzzz").isEmpty())
    }
}
