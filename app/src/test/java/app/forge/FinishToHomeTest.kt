package app.forge

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import app.forge.ui.ForgeApp
import app.forge.ui.Motion
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** After Done on the summary: History shows the session joining the shelf, then Home, with the week already updated. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class FinishToHomeTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AppViewModel
    private var weekBefore = 0

    @Before fun setUp() {
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        vm = AppViewModel(app).apply { completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first()); loadDemoHistory() }
        weekBefore = Weeks.sessionsIn(vm.data.value.history, Weeks.monday(Weeks.today())).size
        // A session with every set ticked, so Finish goes straight to the summary.
        vm.startWorkout(vm.data.value.templates.first())
        vm.data.value.active!!.exercises.forEachIndexed { ex, e -> e.sets.indices.forEach { vm.logSet(ex, it, 20.0, 8, applyToRemaining = false) } }
        vm.skipRest()
        vm.skipCheckIn(); compose.setContent { ForgeApp(vm) }
        compose.onNodeWithText("FINISH").performClick()
        compose.waitForIdle()
        compose.waitForIdle()
        compose.onNodeWithText("CONTINUE").performClick()
        // Then any stall question or level-up moment, then Rewards.
        repeat(6) {
            compose.waitForIdle()
            when {
                compose.onAllNodesWithText("DONE").fetchSemanticsNodes().isNotEmpty() -> return@repeat
                compose.onAllNodesWithText("KEEP PUSHING").fetchSemanticsNodes().isNotEmpty() -> compose.onAllNodesWithText("KEEP PUSHING").onLast().performClick()
                compose.onAllNodesWithText("LATER").fetchSemanticsNodes().isNotEmpty() -> compose.onNodeWithText("LATER").performClick()
                compose.onAllNodesWithText("CONTINUE").fetchSemanticsNodes().isNotEmpty() -> compose.onNodeWithText("CONTINUE").performClick()
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("DONE").performClick()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun onHistory() = compose.onAllNodesWithText("The last four weeks").fetchSemanticsNodes().isNotEmpty()
    private fun weekLine() = "${weekBefore + 1} / 4 SESSIONS"

    @Test fun showsTheShelfThenReturnsHomeOnItsOwn() {
        compose.mainClock.advanceTimeBy(300)
        assert(onHistory()) { "Done should land on History for the shelf moment" }
        compose.mainClock.advanceTimeBy(Motion.SHELF_RETURN_MS)
        compose.waitForIdle()
        assert(!onHistory()) { "should be back on Home after it settles" }
        compose.onNodeWithText(weekLine(), substring = true).assertExists()
        assert(vm.shelfDrop.value == null)
    }

    @Test fun anyTapSkipsStraightHome() {
        compose.mainClock.advanceTimeBy(300)
        assert(onHistory())
        compose.onRoot().performTouchInput { click(center) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assert(!onHistory()) { "a tap should go Home straight away" }
        compose.onNodeWithText(weekLine(), substring = true).assertExists()
        // And the timer that would have returned later does nothing more.
        compose.mainClock.advanceTimeBy(Motion.SHELF_RETURN_MS)
        assert(!onHistory())
    }
}
