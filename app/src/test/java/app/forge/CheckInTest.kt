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

/** Context layer I.4: three taps before the first set, once per session; Skip is allowed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class CheckInTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AppViewModel

    @Before fun setUp() {
        Gemini.offline = true
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        vm = AppViewModel(app).apply { completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first()) }
        vm.startWorkout(vm.data.value.templates.first())
        compose.setContent { ForgeApp(vm) }
        compose.waitForIdle()
    }

    private val id get() = vm.data.value.active!!.id

    @Test fun threeTapsAreSavedForThisSession() {
        compose.onNodeWithText("Before you start").assertExists()
        compose.onNodeWithText("SEE TODAY’S PLAN").assertIsNotEnabled()
        compose.onNodeWithText("Poor").performClick()
        compose.onNodeWithText("Very sore").performClick()
        compose.onAllNodesWithText("OK")[1].performClick() // energy (the first OK is sleep)
        compose.onNodeWithText("SEE TODAY’S PLAN").assertIsEnabled().performClick()
        compose.waitForIdle()
        val c = LifeContext.checkInFor(vm.data.value.checkIns, id)!!
        assertEquals(CheckIn(c.at, id, SleepAnswer.POOR, Soreness.VERY, Energy.OK), c)
        compose.onNodeWithText("Before you start").assertDoesNotExist()
    }

    @Test fun aRedCheckInBringsTheBannerAcceptDropsASetAndUndoPutsItBack() {
        val sets = { vm.data.value.active!!.exercises.first().sets.size }
        val before = sets()
        compose.onNodeWithText("Poor").performClick()
        compose.onNodeWithText("Very sore").performClick()
        compose.onAllNodesWithText("OK")[1].performClick()
        compose.onNodeWithText("SEE TODAY’S PLAN").performClick()
        compose.waitForIdle()
        // Red with no history: no weights to cut yet, so the template drops a set from each lift.
        compose.onNodeWithText("RED", substring = true).assertExists()
        compose.onNodeWithText("A lighter day today.").assertExists()
        compose.onNodeWithText("You said: poor sleep").assertExists()
        assertEquals("nothing changes before Accept", before, sets())
        compose.onNodeWithText("ACCEPT TWEAKS").performClick()
        compose.waitForIdle()
        assertEquals(before - 1, sets())
        compose.onNodeWithText("Lighter day applied", substring = true).assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(before, sets())
        compose.onNodeWithText("ACCEPT TWEAKS").assertExists()
    }

    @Test fun skipIsRecordedAndNotAskedAgain() {
        compose.onNodeWithText("SKIP").performClick()
        compose.waitForIdle()
        assertTrue(LifeContext.checkInFor(vm.data.value.checkIns, id)!!.skipped)
        compose.onNodeWithText("Before you start").assertDoesNotExist()
    }
}
