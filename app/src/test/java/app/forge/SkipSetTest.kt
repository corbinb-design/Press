package app.forge

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import app.forge.ui.ForgeApp
import app.forge.ui.components.SheetHost
import app.forge.ui.screens.WorkoutSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** v1.0.1: skipping a set. It stays on the page, but nothing counts it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class SkipSetTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AppViewModel

    private val w get() = vm.data.value.active!!

    @Before fun setUp() {
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        vm = AppViewModel(app).apply { completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first()); loadDemoHistory() }
        vm.startWorkout(vm.data.value.templates.first())
    }

    /** Ticks every set of every exercise except the ones in [except] (exercise, set), at 20 kg × 8. */
    private fun logAllBut(vararg except: Pair<Int, Int>) {
        w.exercises.forEachIndexed { ex, e -> e.sets.indices.forEach { if (ex to it !in except) vm.logSet(ex, it, 20.0, 8, applyToRemaining = false) } }
        vm.skipRest()
    }

    @Test fun aSkippedSetIsOpenToNothing() {
        val total = Stats.totalSets(w)
        vm.skipSet(0, 1)
        val s = w.exercises[0].sets[1]
        assertTrue(s.skipped); assertFalse(s.done)
        assertEquals("out of the set count", total - 1, Stats.totalSets(w))
        assertEquals("not something to finish", listOf(0) + (2 until w.exercises[0].sets.size), Stats.openSets(w.exercises[0]))
        assertFalse("can't be ticked while skipped", vm.toggleDone(0, 1))
        // The rest notification moves on past it.
        vm.logSet(0, 0, 20.0, 8, false)
        assertEquals(2, Session.next(vm.data.value)!!.set)
        val id = w.exercises[0].sets[1].id
        assertEquals("nor logged from the notification", vm.data.value, Session.logSet(vm.data.value, w.id, id, 0L))
    }

    @Test fun skippingATickedSetUnticksIt_andUndoBringsItBack() {
        vm.logSet(0, 1, 20.0, 8, false)
        vm.skipSet(0, 1)
        assertFalse(w.exercises[0].sets[1].done)
        vm.skipSet(0, 1, skipped = false)
        assertFalse(w.exercises[0].sets[1].skipped)
        assertTrue(1 in Stats.openSets(w.exercises[0]))
    }

    @Test fun notInVolumeOrRecords() {
        logAllBut()
        val before = Stats.volume(w)
        // A huge set, then skipped: it would be a record and most of the volume.
        vm.addSet(0)
        val last = w.exercises[0].sets.lastIndex
        vm.logSet(0, last, 500.0, 10, false)
        vm.skipSet(0, last)
        assertEquals(before, Stats.volume(w), 0.001)
        assertNull(Stats.setPr(w.exercises[0], last, vm.data.value.history, WeightUnit.KG))
        assertFalse(Measure.counts(w.exercises[0], w.exercises[0].sets[last].copy(done = true)))
    }

    @Test fun finishingLeavesItOutAndCountsIt() {
        logAllBut(0 to 1)
        vm.skipSet(0, 1)
        val volume = Stats.volume(w)
        // "I did them, log as shown" doesn't log a skipped set.
        val saved = vm.finishWorkout(logUnticked = true)!!
        assertEquals(1, saved.skippedSets)
        assertEquals(volume, Stats.volume(saved), 0.001)
        assertTrue(saved.exercises.flatMap { it.sets }.none { it.skipped || !it.done })
    }

    @Test fun progressionIgnoresSkippedSets() {
        val rule = TemplateExercise("bench", sets = 3, reps = 8, repMin = 8, repMax = 10)
        val prev = listOf(
            SetEntry(1, weightKg = 60.0, reps = 10, done = true),
            SetEntry(2, weightKg = 60.0, reps = 10, done = true),
            SetEntry(3, weightKg = 60.0, reps = 4, skipped = true),
        )
        val t = Progression.target(rule, prev)!!
        assertEquals("every set done hit 10, so the weight goes up", Progression.Kind.LEVEL_UP, t.kind)
        assertEquals(62.5, t.weightKg!!, 0.001)
    }

    @Test fun progressionIgnoresSkippedSets_throughARealSession() {
        val id = w.exercises[0].exerciseId
        val rule = vm.data.value.templates.first().exercises.first { it.exerciseId == id }
        // Every set at the top of the range, except one skipped.
        w.exercises.forEachIndexed { ex, e -> e.sets.indices.forEach { vm.logSet(ex, it, 40.0, rule.repMax, false) } }
        vm.skipSet(0, 1)
        vm.finishWorkout()
        val prev = Stats.previousSets(vm.data.value.history, id)
        assertEquals(Progression.Kind.LEVEL_UP, Progression.target(rule, prev, WeightUnit.KG, w0Tracking())!!.kind)
    }

    private fun w0Tracking() = vm.data.value.history.first().exercises[0].tracking

    // ---- UI ---------------------------------------------------------------------------------------

    private fun show() { compose.setContent { ForgeApp(vm) }; compose.waitForIdle() }

    private fun skipFromMenu() {
        compose.onNodeWithText("SKIP SET").performClick()
        compose.waitForIdle()
    }

    @Test fun longPressAValueOpensTheMenu_skipThenUndo() {
        show()
        compose.onAllNodes(hasContentDescription("Set 2 ", substring = true) and hasClickAction()).onFirst().performTouchInput { longClick() }
        compose.waitForIdle()
        skipFromMenu()
        assertTrue(w.exercises[0].sets[1].skipped)
        compose.onNodeWithContentDescription("Set 2 skipped").assertExists().performClick()
        compose.waitForIdle()
        assertFalse("Undo brings it back", w.exercises[0].sets[1].skipped)
    }

    @Test fun longPressAnywhereOnTheRow() {
        show()
        // The "previous" column has no tap of its own; the row takes the long press there.
        val row = compose.onAllNodes(hasContentDescription("Set 2 ", substring = true) and hasClickAction()).onFirst().fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { longClick(androidx.compose.ui.geometry.Offset(row.left - 80f, row.center.y)) }
        compose.waitForIdle()
        skipFromMenu()
        assertTrue(w.exercises[0].sets[1].skipped)
    }

    @Test fun theSetMenuHasSkip() {
        show()
        compose.onAllNodes(hasClickLabel("Set options")).onFirst().performClick()
        compose.waitForIdle()
        skipFromMenu()
        assertTrue(w.exercises[0].sets[0].skipped)
        compose.onAllNodes(hasClickLabel("Set options")).onFirst().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("UNDO SKIP").performClick()
        assertFalse(w.exercises[0].sets[0].skipped)
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label $label") { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == label }

    @Test fun finishSkipsTheCheckWhenOnlySkippedSetsAreLeft() {
        logAllBut(0 to 1)
        vm.skipSet(0, 1)
        show()
        compose.onNodeWithText("FINISH").performClick()
        compose.waitForIdle()
        assertTrue("no \"sets aren't ticked\" question", compose.onAllNodesWithText("ticked yet", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("1 skipped").assertExists()
    }

    @Test fun summarySaysHowManyWereSkipped() {
        logAllBut(0 to 1, 1 to 2)
        vm.skipSet(0, 1); vm.skipSet(1, 2)
        val prior = vm.data.value.history
        val saved = vm.finishWorkout()!!
        compose.setContent { MaterialTheme { SheetHost { WorkoutSummary(saved, prior, vm.data.value, vm, {}) {} } } }
        compose.onNodeWithText("2 skipped").assertExists()
    }
}
