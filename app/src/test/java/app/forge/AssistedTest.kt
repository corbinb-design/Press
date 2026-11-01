package app.forge

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import app.forge.ui.ForgeApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * C, D and E: the Assisted / Weighted switch, data from build 31 (everything logged as weight × reps),
 * the Unassisted moment, and bodyweight on timed holds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class AssistedTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val day = 86_400_000L
    private var id = 100L

    @Before fun clean() { app.filesDir.listFiles()?.forEach { it.delete() }; ForgeRepository.reset() }

    private fun set(kg: Double?, reps: Int? = null, sec: Int? = null) = SetEntry(++id, weightKg = kg, reps = reps, durationSec = sec, done = true)
    private fun session(daysAgo: Int, vararg e: ExerciseEntry): Workout {
        val at = System.currentTimeMillis() - daysAgo * day
        return Workout(++id, "Pull", null, at, at + 3_600_000, e.toList())
    }
    /** An entry the way build 31 wrote it: no tracking field, so weight × reps. */
    private fun old(ex: String, vararg sets: SetEntry) = ExerciseEntry(++id, ex, sets.toList())

    /** Writes [history] as the saved file, loads it the way the app starts, and returns the view model. */
    private fun load(vararg history: Workout, bodyweight: Double? = 80.0): AppViewModel {
        val d = AppData(history = history.sortedByDescending { it.startedAt }, seeded = true, settings = Settings(name = "C", onboarded = true, bodyweightKg = bodyweight))
        ForgeStore(app.filesDir).write(d)
        ForgeRepository.reset()
        return AppViewModel(app)
    }

    private fun AppViewModel.entries(ex: String) = data.value.history.sortedBy { it.startedAt }.mapNotNull { w -> w.exercises.firstOrNull { it.exerciseId == ex } }

    // ---- Migration --------------------------------------------------------------------------------

    @Test fun build31PullUpsBecomeWeightedUntilChosen_andHoldsGetTheirSeconds() {
        val vm = load(
            session(14, old("pullup", set(20.0, 8), set(20.0, 8)), old("plank", set(0.0, 45))),
            session(7, old("pullup", set(15.0, 8))),
            session(1, old("bench", set(60.0, 8))),
        )
        val pull = vm.entries("pullup")
        assertTrue(pull.all { it.tracking == Tracking.BODYWEIGHT_REPS && it.legacy })
        assertEquals("filled from the profile, for volume and records", 80.0, pull[0].bodyweightKg)
        val plank = vm.entries("plank").single()
        assertEquals(Tracking.DURATION, plank.tracking)
        assertTrue(plank.withBodyweight)
        assertEquals(45, plank.sets[0].durationSec); assertNull(plank.sets[0].reps)
        assertEquals("weight × reps lifts are untouched", Tracking.WEIGHT_REPS, vm.entries("bench").single().tracking)
        assertFalse(vm.entries("bench").single().legacy)
        assertEquals(2, Migrate.undecided(vm.data.value, "pullup"))
    }

    @Test fun migrationRunsOnce() {
        val d = AppData(history = listOf(session(3, old("plank", set(0.0, 45)))), seeded = true)
        val once = Migrate.run(d)
        assertEquals(once, Migrate.run(once))
    }

    @Test fun choosingAssistedRereadsOldSessions_andPrsFollow() {
        val older = session(14, old("pullup", set(20.0, 8)))
        val newer = session(7, old("pullup", set(15.0, 8)))
        val vm = load(older, newer)
        // Read as added weight, the newer session would be lighter: no record.
        assertTrue(Stats.sessionPrs(vm.data.value.history.first(), vm.data.value.history.drop(1)).isEmpty())
        vm.setTrackingMode("pullup", Tracking.ASSISTED)
        val h = vm.data.value.history
        assertTrue(vm.entries("pullup").all { it.tracking == Tracking.ASSISTED && !it.legacy })
        assertEquals("15 kg of help after 20 is the record", "LEAST HELP", Stats.sessionPrs(h.first(), h.drop(1)).single().kind)
        assertEquals(0, Migrate.undecided(vm.data.value, "pullup"))
        assertEquals(Tracking.ASSISTED, vm.exercise("pullup").tracking)
    }

    @Test fun switchingToWeightedLaterKeepsOldAssistance() {
        val vm = load(session(7, old("pullup", set(15.0, 8))))
        vm.setTrackingMode("pullup", Tracking.ASSISTED)
        vm.setTrackingMode("pullup", Tracking.BODYWEIGHT_REPS)
        assertEquals(Tracking.ASSISTED, vm.entries("pullup").single().tracking)
        assertEquals(Tracking.BODYWEIGHT_REPS, vm.exercise("pullup").tracking)
    }

    @Test fun theSwitchIsRememberedAndAppliesToNewSessions() {
        val vm = load(session(7, old("pullup", set(15.0, 8))))
        vm.setTrackingMode("pullup", Tracking.ASSISTED)
        ForgeRepository.get(app).flush()
        ForgeRepository.reset()
        val again = AppViewModel(app)
        assertEquals(Tracking.ASSISTED, again.data.value.trackingModes["pullup"])
        again.startWorkout(Template(1, "Pull", listOf(TemplateExercise("pullup", 3, 8))))
        val e = again.data.value.active!!.exercises.single()
        assertEquals(Tracking.ASSISTED, e.tracking)
        // Last time's assistance is today's starting point.
        assertEquals(15.0, Stats.ghostValues(e, 0, Stats.previousSets(again.data.value.history, "pullup", e.tracking)).weightKg)
    }

    @Test fun switchingMidSessionOnlyChangesAnExerciseNotStarted() {
        val vm = load()
        vm.startWorkout(Template(1, "Pull", listOf(TemplateExercise("pullup", 3, 8), TemplateExercise("dips", 3, 8))))
        vm.logSet(0, 0, 10.0, 8, false)
        vm.setTrackingMode("pullup", Tracking.ASSISTED)
        vm.setTrackingMode("dips", Tracking.ASSISTED)
        val ex = vm.data.value.active!!.exercises
        assertEquals("logged sets keep their meaning", Tracking.BODYWEIGHT_REPS, ex[0].tracking)
        assertEquals(Tracking.ASSISTED, ex[1].tracking)
    }

    @Test fun onlyPullUpsAndDipsHaveTheSwitch() {
        listOf("pullup", "chinup", "dips", "assisted_pullup", "assisted_dip").forEach { assertTrue(it, Library.canSwitchAssist(Library.exercises.first { e -> e.id == it })) }
        listOf("bench", "pushup", "plank").forEach { id -> Library.exercises.firstOrNull { it.id == id }?.let { assertFalse(id, Library.canSwitchAssist(it)) } }
    }

    // ---- Comparing across a switch ---------------------------------------------------------------

    @Test fun assistanceIsNeverComparedWithAddedWeight() {
        val assisted = session(14, ExerciseEntry(++id, "pullup", listOf(set(30.0, 8)), tracking = Tracking.ASSISTED))
        val weighted = ExerciseEntry(++id, "pullup", listOf(set(5.0, 8)), tracking = Tracking.BODYWEIGHT_REPS)
        // The first weighted session is a baseline, not "25 kg less" or a record.
        assertNull(Stats.setPr(weighted, 0, listOf(assisted), WeightUnit.KG))
        assertEquals("NEW", Stats.vsLast(weighted, listOf(assisted), System.currentTimeMillis(), WeightUnit.KG)!!.text)
        assertTrue(Stats.previousSets(listOf(assisted), "pullup", Tracking.BODYWEIGHT_REPS).isEmpty())
    }

    @Test fun fallingAssistanceAfterWeightedSessionsIsNotAStall() {
        // Weighted sessions first (bodyweight + added scores high), then assisted ones getting better.
        val weighted = (10 downTo 7).map { session(it * 7, ExerciseEntry(++id, "pullup", listOf(set(10.0, 8)), tracking = Tracking.BODYWEIGHT_REPS, bodyweightKg = 80.0)) }
        val assisted = (6 downTo 1).map { i -> session(i * 7, ExerciseEntry(++id, "pullup", listOf(set(12.0 + i * 3, 8)), tracking = Tracking.ASSISTED)) }
        val h = (weighted + assisted).sortedByDescending { it.startedAt }
        assertNull(Progression.stall(h, "pullup", null))
    }

    // ---- E: assisted is scored the right way round ------------------------------------------------

    @Test fun progressionTakesHelpOff() {
        val rule = TemplateExercise("assisted_pullup", 3, 8, repMin = 6, repMax = 8, incrementKg = 5.0)
        val t = Progression.target(rule, List(3) { set(20.0, 8) }, WeightUnit.KG, Tracking.ASSISTED)!!
        assertEquals(Progression.Kind.LEVEL_UP, t.kind)
        assertEquals("−15 kg, from −20 kg", 15.0, t.weightKg!!, 0.001)
        assertEquals(20.0, t.fromKg!!, 0.001)
        assertEquals("−15 kg", Measure.weight(Tracking.ASSISTED, t.weightKg, WeightUnit.KG))
    }

    @Test fun assistedScoresRiseAsHelpFalls() {
        val e = ExerciseEntry(1, "assisted_pullup", emptyList(), tracking = Tracking.ASSISTED)
        assertTrue(Measure.score(e, set(10.0, 8)) > Measure.score(e, set(20.0, 8)))
        assertTrue(Measure.score(e, set(20.0, 10)) > Measure.score(e, set(20.0, 8)))
        assertTrue(Measure.lowerIsBetter(Tracking.ASSISTED))
        assertEquals(0.0, Measure.volume(e, set(20.0, 8)), 0.0)
    }

    @Test fun unassistedIsTheFirstZeroAfterHelp() {
        val past = session(7, ExerciseEntry(++id, "assisted_pullup", listOf(set(20.0, 8)), tracking = Tracking.ASSISTED))
        val today = ExerciseEntry(++id, "assisted_pullup", listOf(set(10.0, 8), set(0.0, 3), set(0.0, 2)), tracking = Tracking.ASSISTED)
        assertFalse(Stats.firstUnassisted(today, 0, listOf(past)))
        assertTrue(Stats.firstUnassisted(today, 1, listOf(past)))
        assertFalse("only the first", Stats.firstUnassisted(today, 2, listOf(past)))
        assertFalse("not without having used help", Stats.firstUnassisted(today.copy(sets = listOf(set(0.0, 3))), 0, emptyList()))
    }

    @Test fun unassistedMomentOffersWeighted() {
        val vm = load(session(7, ExerciseEntry(++id, "assisted_pullup", listOf(set(5.0, 8)), tracking = Tracking.ASSISTED)))
        vm.startWorkout(Template(1, "Pull", listOf(TemplateExercise("assisted_pullup", 3, 8))))
        vm.skipRest()
        compose.setContent { ForgeApp(vm) }
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription("Set 1 ", substring = true) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        repeat(2) { compose.onNodeWithContentDescription("Less weight").performClick() }
        compose.onNodeWithText("LOG SET", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("UNASSISTED").assertExists()
        compose.onNodeWithText("SWITCH TO WEIGHTED").performClick()
        compose.waitForIdle()
        assertEquals(Tracking.BODYWEIGHT_REPS, vm.data.value.trackingModes["assisted_pullup"])
        assertEquals("today's sets stay assisted", Tracking.ASSISTED, vm.data.value.active!!.exercises[0].tracking)
    }

    // ---- D: bodyweight on timed holds -------------------------------------------------------------

    @Test fun holdsReadBodyweightPlusAdded() {
        val kg = WeightUnit.KG
        assertEquals("BW + 10 kg · 0:45", Measure.format(Tracking.DURATION, set(10.0, sec = 45), kg, bodyweight = true))
        assertEquals("BW · 0:45", Measure.format(Tracking.DURATION, set(0.0, sec = 45), kg, bodyweight = true))
        assertEquals("a plate pinch holds the plate", "20 kg · 0:30", Measure.format(Tracking.DURATION, set(20.0, sec = 30), kg))
        assertEquals("0:30", Measure.format(Tracking.DURATION, set(null, sec = 30), kg))
    }

    @Test fun holdsCountBodyweight() {
        val hang = ExerciseEntry(1, "dead_hang", emptyList(), tracking = Tracking.DURATION, bodyweightKg = 80.0, withBodyweight = true)
        assertEquals(90.0, Measure.holdLoad(hang, set(10.0, sec = 45)), 0.0)
        assertEquals("without a profile bodyweight, just what's added", 10.0, Measure.holdLoad(hang.copy(bodyweightKg = null), set(10.0, sec = 45)), 0.0)
        val pinch = ExerciseEntry(2, "plate_pinch", emptyList(), tracking = Tracking.DURATION, bodyweightKg = 80.0)
        assertEquals(20.0, Measure.holdLoad(pinch, set(20.0, sec = 30)), 0.0)
        // Same time: the heavier hold scores higher.
        assertTrue(Measure.score(hang, set(10.0, sec = 45)) > Measure.score(hang, set(0.0, sec = 45)))
    }

    @Test fun newHoldEntriesAreBodyweightHolds() {
        val vm = load()
        vm.startWorkout(Template(1, "Core", listOf(TemplateExercise("plank", 3, 8), TemplateExercise("plate_pinch", 3, 8), TemplateExercise("bench", 3, 8))))
        val (plank, pinch, bench) = vm.data.value.active!!.exercises
        assertTrue(plank.withBodyweight); assertEquals(80.0, plank.bodyweightKg)
        assertFalse(pinch.withBodyweight); assertFalse(bench.withBodyweight)
        listOf("dead_hang", "plank", "l_sit", "side_plank").forEach { id -> assertTrue(id, Library.isBodyweightHold(Library.exercises.first { it.id == id })) }
    }
}
