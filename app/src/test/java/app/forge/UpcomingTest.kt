package app.forge

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
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

/** A: the rest of the session as tiles under the current exercise; finished ones collapse above. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class UpcomingTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AppViewModel
    private val names get() = vm.data.value.active!!.exercises.map { vm.exercise(it.exerciseId).name }

    @Before fun setUp() {
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        vm = AppViewModel(app).apply { completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first()); loadDemoHistory() }
        vm.startWorkout(vm.data.value.templates.first()) // Push: 5 exercises
        vm.skipRest()
    }

    private fun show() { vm.skipCheckIn(); compose.setContent { ForgeApp(vm) }; compose.waitForIdle() }
    private fun tile(name: String) = compose.onNode(hasContentDescription(name) and hasClickAction())
    private fun handle(name: String) = compose.onNodeWithContentDescription("Drag to reorder $name")

    @Test fun everyRemainingExerciseIsATile() {
        show()
        names.drop(1).forEach { handle(it).assertExists() }
        assertTrue("not the current one", compose.onAllNodesWithContentDescription("Drag to reorder ${names[0]}").fetchSemanticsNodes().isEmpty())
        tile(names[1]).assert(hasText("3 × ", substring = true)).assert(hasText("last ", substring = true))
    }

    @Test fun tappingATileJumpsToIt() {
        show()
        val target = names[3]
        tile(target).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("EXERCISE 4 OF 5").assertExists()
        compose.onNodeWithContentDescription("$target options").assertExists()
    }

    @Test fun finishedExercisesCollapseAboveWithATick() {
        val first = names[0]
        vm.data.value.active!!.exercises[0].sets.indices.forEach { vm.logSet(0, it, 20.0, 8, false) }
        vm.skipRest()
        show()
        tile(names[2]).performClick()
        compose.waitForIdle()
        compose.onNode(hasContentDescription("$first, done") and hasClickAction()).assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("EXERCISE 1 OF 5").assertExists()
        assertTrue("finished ones aren't in the list to reorder", compose.onAllNodesWithContentDescription("Drag to reorder $first").fetchSemanticsNodes().isEmpty())
    }

    @Test fun talkBackMovesDownAndUp() {
        show()
        val before = names
        fun act(name: String, label: String) = compose.runOnIdle {
            tile(name).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }.action()
        }
        act(before[1], "Move down")
        compose.waitForIdle()
        assertEquals(listOf(before[0], before[2], before[1]) + before.drop(3), names)
        act(before[1], "Move up")
        compose.waitForIdle()
        assertEquals(before, names)
        assertEquals("the template isn't touched", before, vm.data.value.templates.first().exercises.map { vm.exercise(it.exerciseId).name })
    }

    @Test fun draggingTheHandleReorders() {
        show()
        val before = names
        val h = handle(before[1]).fetchSemanticsNode().boundsInRoot
        val tileHeight = tile(before[1]).fetchSemanticsNode().boundsInRoot.height
        // A finger's drag arrives over many frames.
        handle(before[1]).performTouchInput { down(center) }
        repeat(10) {
            compose.onRoot().performTouchInput { moveBy(Offset(0f, tileHeight * 2.2f / 10)) }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf(before[0], before[2], before[3], before[1], before[4]), names)
        assertEquals("still on the same exercise", "EXERCISE 1 OF 5", compose.onAllNodesWithText("EXERCISE", substring = true).fetchSemanticsNodes().first().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text })
        assertTrue(h.height >= 48 * 3 - 1) // the handle is a 48 dp target (xxhdpi = 3 px/dp)
    }

    // ---- Offering the order to the template --------------------------------------------------------

    private val t = Template(1, "Push", listOf("a", "b", "c", "d").map { TemplateExercise(it, 3, 8) })
    private fun w(vararg ids: String) = Workout(2, "Push", 1, 0, 1, ids.map { ExerciseEntry(it.hashCode().toLong(), it, listOf(SetEntry(1, done = true))) })

    @Test fun aNewOrderIsOfferedAndApplied() {
        val c = TemplateDiff.changes(t, w("a", "c", "b", "d"))
        assertEquals(listOf(TemplateDiff.Change.Reordered(listOf("a", "c", "b", "d"))), c)
        assertEquals(listOf("a", "c", "b", "d"), TemplateDiff.apply(t, c).exercises.map { it.exerciseId })
    }

    @Test fun theSameOrderOffersNothing_andASkippedExerciseKeepsItsPlace() {
        assertTrue(TemplateDiff.changes(t, w("a", "b", "c", "d")).isEmpty())
        assertTrue("skipping b isn't a reorder", TemplateDiff.changes(t, w("a", "c", "d")).isEmpty())
        // d moved first, b skipped: b stays in its slot.
        val c = TemplateDiff.changes(t, w("d", "a", "c"))
        assertEquals(listOf("d", "b", "a", "c"), TemplateDiff.apply(t, c).exercises.map { it.exerciseId })
    }

    @Test fun reorderWithASwap() {
        // c swapped for x, and d done before a.
        val c = TemplateDiff.changes(t, w("d", "a", "b", "x"))
        assertTrue(c.any { it is TemplateDiff.Change.Swapped })
        assertEquals(listOf("d", "a", "b", "x"), TemplateDiff.apply(t, c).exercises.map { it.exerciseId })
    }

    @Test fun finishingAReorderedSessionCanSaveTheOrder() {
        vm.data.value.active!!.exercises.forEachIndexed { ex, e -> e.sets.indices.forEach { vm.logSet(ex, it, 20.0, 8, false) } }
        vm.moveExerciseTo(4, 0)
        val order = vm.data.value.active!!.exercises.map { it.exerciseId }
        val saved = vm.finishWorkout()!!
        vm.updateTemplateFrom(saved)
        assertEquals(order, vm.data.value.templates.first().exercises.map { it.exerciseId })
    }
}
