package app.forge

import app.forge.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Pure logic behind the Phase 2 screens: no Android needed. */
class Phase2LogicTest {
    private fun set(kg: Double, reps: Int, done: Boolean = true) = SetEntry(newId(), weightKg = kg, reps = reps, done = done)
    private fun entry(id: String, vararg sets: SetEntry) = ExerciseEntry(newId(), id, sets.toList())
    private fun workout(at: Long, templateId: Long?, vararg e: ExerciseEntry) = Workout(newId(), "Push", templateId, at, at + 3_600_000, e.toList())

    @Test fun oldTemplateExerciseKeepsItsFixedRepsAsTheRange() {
        val te = Json { ignoreUnknownKeys = true }.decodeFromString(TemplateExercise.serializer(), """{"exerciseId":"squat","sets":3,"reps":5}""")
        assertEquals(5, te.repMin)
        assertEquals(5, te.repMax)
        assertNull(te.restSec)
        assertEquals(8 to 12, TemplateExercise.fresh("bench").let { it.repMin to it.repMax })
    }

    @Test fun ghostUsesOwnValuesThenLastSessionThenEarlierSet() {
        val e = entry("bench", set(60.0, 8), SetEntry(newId()), SetEntry(newId(), weightKg = 70.0))
        val prev = listOf(set(55.0, 10), set(57.5, 9))
        assertEquals(60.0 to 8, Stats.ghost(e, 0, prev))
        assertEquals(57.5 to 9, Stats.ghost(e, 1, prev))
        assertEquals(70.0 to 8, Stats.ghost(e, 2, prev)) // own weight, reps from the set before
    }

    @Test fun setPrSpotsHeavierAndMoreRepsButNotFirstTimes() {
        val before = listOf(workout(1_000, null, entry("bench", set(60.0, 8), set(60.0, 8))))
        val heavier = entry("bench", set(62.5, 6))
        assertTrue(Stats.setPr(heavier, 0, before, WeightUnit.KG)!!.detail.contains("2.5 kg over your best"))
        val moreReps = entry("bench", set(60.0, 10))
        assertTrue(Stats.setPr(moreReps, 0, before, WeightUnit.KG)!!.detail.contains("2 more reps"))
        assertNull(Stats.setPr(entry("bench", set(55.0, 5)), 0, before, WeightUnit.KG))
        assertNull(Stats.setPr(entry("squat", set(100.0, 5)), 0, before, WeightUnit.KG)) // first time on a lift
    }

    @Test fun sessionPrsLabelsTheKind() {
        val prior = listOf(workout(1_000, null, entry("bench", set(60.0, 8)), entry("row", set(50.0, 8))))
        val now = workout(2_000, null, entry("bench", set(62.5, 8)), entry("row", set(50.0, 8), set(50.0, 8)))
        val prs = Stats.sessionPrs(now, prior).associate { it.exerciseId to it.kind }
        assertEquals("BEST WEIGHT", prs["bench"])
        assertEquals("BEST VOLUME", prs["row"])
    }

    @Test fun templateDiffProposesSwapsAddsAndExtraSetsOnly() {
        val t = Template(1, "Push", listOf(TemplateExercise("bench", 3), TemplateExercise("fly", 3), TemplateExercise("dips", 3)))
        // fly swapped for cable_fly, dips skipped entirely, an extra bench set, curls added.
        val w = workout(1_000, 1,
            entry("bench", set(60.0, 8), set(60.0, 8), set(60.0, 8), set(60.0, 8)),
            entry("cable_fly", set(20.0, 12)),
            entry("curl", set(12.0, 10)),
        )
        val changes = TemplateDiff.changes(t, w)
        assertTrue(TemplateDiff.Change.MoreSets("bench", 4) in changes)
        assertTrue(changes.any { it is TemplateDiff.Change.Swapped && it.from == "fly" && it.to == "cable_fly" })
        // "dips" missing pairs with the second new exercise as a swap; nothing is ever removed.
        val updated = TemplateDiff.apply(t, changes)
        assertEquals(listOf("bench", "cable_fly", "curl"), updated.exercises.map { it.exerciseId })
        assertEquals(4, updated.exercises[0].sets)
        assertTrue(TemplateDiff.changes(t, workout(1_000, 1, entry("bench", set(60.0, 8)))).isEmpty()) // skipping isn't a change
    }

    @Test fun quickBuildRespectsTimeEquipmentAndGoal() {
        val home = QuickBuild.build("", QuickBuild.Focus.PUSH, QuickBuild.Goal.STRENGTH, QuickBuild.Kit.DUMBBELLS, 45)
        assertEquals("Push", home.name)
        assertEquals(4, home.exercises.size)
        assertTrue(home.exercises.all { id -> Library.exercises.first { it.id == id.exerciseId }.equipment in QuickBuild.Kit.DUMBBELLS.allowed })
        assertEquals(3 to 6, home.exercises[0].repMin to home.exercises[0].repMax)
        assertEquals("push", home.colorKey)
        val long = QuickBuild.build("Upper A", QuickBuild.Focus.UPPER, QuickBuild.Goal.MUSCLE, QuickBuild.Kit.GYM, 75)
        assertEquals(7, long.exercises.size)
        assertEquals(long.exercises.size, long.exercises.map { it.exerciseId }.distinct().size)
    }
}
