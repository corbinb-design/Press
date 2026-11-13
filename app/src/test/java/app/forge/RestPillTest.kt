package app.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.forge.data.*
import app.forge.ui.screens.RestPillBody
import app.forge.ui.theme.Forge
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Step 8 of docs/rest-design.md: the rest pill renders Working, Resting and Over from `Session.rest`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class RestPillTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_000_000_000L
    private fun w(ends: Long?) = Workout(
        id = 1, name = "Push", startedAt = now - 754_000, exercises = emptyList(), restEndsAt = ends, restTotalSec = 90,
    )

    private val log = mutableListOf<String>()

    private fun show(w: Workout) {
        log.clear()
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Forge.Bg), contentAlignment = Alignment.BottomCenter) {
                RestPillBody(
                    w, now, 90, Modifier.padding(bottom = 20.dp),
                    onStart = { log += "start $it" }, onAdjust = { log += "adjust $it" }, onSkip = { log += "skip" },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun label(s: String) = hasContentDescription(s)

    @Test fun workingShowsTheSessionClockAndTapToRest() {
        show(w(null))
        compose.onNodeWithText("TAP TO REST").assertExists()
        compose.onNodeWithTag("rest-pill-session-clock", useUnmergedTree = true).assertTextEquals("12:34")
        compose.onAllNodesWithTag("rest-pill-clock", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodes(label("Skip rest")).assertCountEquals(0)
        compose.onAllNodes(label("15 seconds more rest")).assertCountEquals(0)
        compose.onNodeWithText("TAP TO REST").performClick()
        assertEquals(listOf("start 90"), log)
    }

    @Test fun restingCountsDownWithAllThreeButtons() {
        show(w(now + 83_000))
        compose.onNodeWithText("REST").assertExists()
        compose.onNodeWithTag("rest-pill-clock", useUnmergedTree = true).assertTextEquals("1:23")
        compose.onNode(label("15 seconds less rest")).performClick()
        compose.onNode(label("15 seconds more rest")).performClick()
        compose.onNode(label("Skip rest")).performClick()
        assertEquals(listOf("adjust -15", "adjust 15", "skip"), log)
    }

    @Test fun minusFifteenPastZeroShowsNegativeTimeAndSkipButNotMinusFifteen() {
        // The app's state after -15 on a rest with 10 s left: five seconds past the end.
        val after = Session.adjustRest(AppData(active = w(now + 10_000)), -15, now).active!!
        show(after)
        compose.onNodeWithText("OVER").assertExists()
        compose.onNodeWithTag("rest-pill-clock", useUnmergedTree = true).assertTextEquals("−0:05")
        compose.onAllNodes(label("15 seconds less rest")).assertCountEquals(0)
        compose.onNode(label("15 seconds more rest")).assertExists()
        compose.onNode(label("Skip rest")).performClick()
        assertEquals(listOf("skip"), log)
    }

    @Test fun overIsNotTappableToRestart() {
        show(w(now - 12_000))
        compose.onNodeWithTag("rest-pill-clock", useUnmergedTree = true).assertTextEquals("−0:12").performClick()
        assertEquals(emptyList<String>(), log)
    }

    private fun shot(name: String, w: Workout) {
        show(w)
        compose.mainClock.advanceTimeBy(700) // let the slide-up finish
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun shotWorking() = shot("8.1a_pill_working", w(null))
    @Test fun shotResting() = shot("8.1b_pill_resting", w(now + 83_000))
    @Test fun shotOver() = shot("8.1c_pill_over", w(now - 12_000))
}
