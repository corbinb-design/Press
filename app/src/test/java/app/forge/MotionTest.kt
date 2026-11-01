package app.forge

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import app.forge.ui.LocalReduceMotion
import app.forge.ui.Motion
import app.forge.ui.Nav
import app.forge.ui.components.SetRow
import app.forge.ui.components.SetState
import app.forge.ui.components.SheetHost
import app.forge.ui.screens.*
import app.forge.ui.theme.Forge
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Section 11: steps the Compose clock through each storyboard and records the keyframes
 * (11A_80ms.png …) so they can be laid next to the Figma frames. Also checks the reduced-motion
 * fallback lands on the final state straight away.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class MotionTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun set(reduce: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduce) {
                MaterialTheme { SheetHost { Box(Modifier.fillMaxSize().background(Forge.Bg).padding(24.dp)) { content() } } }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun frames(name: String, at: List<Long>) {
        var now = 0L
        at.forEach { t ->
            compose.mainClock.advanceTimeBy(t - now); now = t
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/motion/${name}_${t}ms.png")
        }
    }

    @Test fun a_setTick() {
        var done by mutableStateOf(false)
        set {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SetRow("2", "62.5kg × 10", "62.5", "10", if (done) SetState.Done else SetState.Active)
                SetRow("3", "62.5kg × 10", "62.5", "10", if (done) SetState.Active else SetState.Upcoming)
            }
        }
        done = true
        frames("11A_set_tick", listOf(0, 80, 160, 280, Motion.TICK_MS.toLong()))
    }

    @Test fun b_prMoment() {
        set { PrToast("NEW PR · ROMANIAN DEADLIFT", "62.5 kg × 10 — 2.5 kg over your best", animate = true) }
        frames("11B_pr", listOf(0, 180, 300, 600, 800))
    }

    @Test fun d_weekRing() {
        set { Column { WeekRing(done = 4, goal = 4, animateFrom = 3) } }
        compose.onNodeWithText("3/4").assertExists() // the last state first
        frames("11D_ring", listOf(0, 400, 800, 950, Motion.RING_GOAL_MS.toLong()))
        compose.onNodeWithText("4/4").assertExists()
    }

    @Test fun d_weekRingReducedMotionJumpsToTheEnd() {
        set(reduce = true) { Column { WeekRing(done = 4, goal = 4, animateFrom = 3) } }
        compose.mainClock.advanceTimeBy(Motion.FADE_MS + 50L)
        compose.onNodeWithText("4/4").assertExists()
    }

    @Test fun c_sessionJoinsTheShelf() {
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        val vm = AppViewModel(app).apply { completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first()); loadDemoHistory() }
        vm.dropOnShelf(vm.data.value.history.first().id)
        set { HistoryScreen(vm.data.value, vm, Nav({}, {}, {})) }
        frames("11C_shelf", listOf(0, 200, 450, 700, Motion.SHELF_MS.toLong() + 50))
        compose.mainClock.advanceTimeBy(100)
        assert(vm.shelfDrop.value == null) // played once
    }
}
