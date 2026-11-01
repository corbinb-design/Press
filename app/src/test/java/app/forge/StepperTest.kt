package app.forge

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import app.forge.ui.ForgeApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.0.1: the 4.2 +/− steppers, driven through the whole app (pager, page scroll and sheet all in
 * place), so a parent gesture taking the touch would show up here too.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class StepperTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun demoVm(): AppViewModel {
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        return AppViewModel(app).apply { completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first()); loadDemoHistory() }
    }

    /** Demo data plus a template of [ids] with [past] logged a week ago, and a session of it started. */
    private fun sessionWith(ids: List<String>, past: List<ExerciseEntry>): AppViewModel {
        val d = demoVm().data.value
        val t = Template(9_001, "Test", ids.map { TemplateExercise.fresh(it) })
        val at = System.currentTimeMillis() - 6 * 86_400_000L
        ForgeStore(app.filesDir).write(d.copy(templates = d.templates + t, history = listOf(Workout(9_002, "Test", t.id, at, at + 3_000_000, past)) + d.history))
        ForgeRepository.reset()
        return AppViewModel(app).also { it.startWorkout(it.data.value.templates.first { x -> x.id == t.id }) }
    }

    private fun clickLabel(label: String) = SemanticsMatcher("click label $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    /** Shows the app and opens the 4.2 sheet with whatever [opener] matches first. */
    private fun open(vm: AppViewModel, opener: SemanticsMatcher) {
        compose.setContent { ForgeApp(vm) }
        compose.waitForIdle()
        compose.onAllNodes(opener).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun openSet2(vm: AppViewModel) = open(vm, hasContentDescription("Set 2 ", substring = true) and hasClickAction())

    private fun shown(what: String) =
        compose.onNodeWithTag("value-$what").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }

    /** "82.5 kg" → 82.5, "−20 kg" → −20, "21:05" → 1265, "–" (none) → 0. */
    private fun number(s: String): Double {
        val first = s.trim().substringBefore(' ').replace('−', '-')
        if (first == "–") return 0.0
        if (':' in first) return first.split(':').fold(0.0) { acc, p -> acc * 60 + p.toDouble() }
        return first.toDouble()
    }

    /**
     * Taps [label] 20 times at a fast thumb's pace (60 ms apart) and checks the value moved on every one,
     * then 20 more with all the taps landing inside a single frame, and checks all 20 counted.
     */
    private fun tapTwenty(label: String, what: String, step: Double, max: Double) {
        val button = compose.onNodeWithContentDescription(label)
        var before = shown(what)
        repeat(20) { i ->
            button.performTouchInput { click() }
            compose.mainClock.advanceTimeBy(60)
            val now = shown(what)
            assertNotEquals("$label: tap ${i + 1} of 20 didn't change the $what (still $now)", before, now)
            before = now
        }
        val start = number(shown(what))
        button.performTouchInput { repeat(20) { click(); advanceEventTime(30) } }
        compose.waitForIdle()
        assertEquals("$label: 20 taps inside one frame", (start + 20 * step).coerceIn(0.0, max), number(shown(what)), 0.001)
    }

    /** Up 20, then back down 20, so minus never runs into zero. [max] is the stepper's cap, if any. */
    private fun bothWays(what: String, step: Double, max: Double = Double.MAX_VALUE) {
        tapTwenty("More $what", what, step, max)
        tapTwenty("Less $what", what, -step, max)
    }

    @Test fun weightAndReps() {
        val vm = demoVm().apply { startWorkout(data.value.templates.first()) }
        openSet2(vm)
        bothWays("weight", 2.5)
        bothWays("reps", 1.0)
    }

    @Test fun timedSet() {
        val past = ExerciseEntry(9_003, "plank", listOf(60, 55, 50).mapIndexed { i, s -> SetEntry(9_010L + i, weightKg = 0.0, durationSec = s, done = true) }, tracking = Tracking.DURATION)
        val vm = sessionWith(listOf("plank"), listOf(past))
        openSet2(vm)
        bothWays("weight", 2.5)
        bothWays("time", 5.0)
    }

    @Test fun cardio() {
        val past = ExerciseEntry(9_003, "rower", listOf(SetEntry(9_010, durationSec = 1_200, distanceM = 4_600.0, level = 6, heartRate = 141, done = true)), tracking = Tracking.CARDIO)
        val vm = sessionWith(listOf("rower"), listOf(past))
        open(vm, clickLabel("Edit cardio"))
        bothWays("time", 60.0)
        bothWays("distance", 0.1)
        bothWays("level", 1.0, max = 30.0)
        bothWays("heart rate", 1.0, max = 240.0)
    }

    @Test fun holdingRepeatsAfter400msAndSpeedsUp() {
        val vm = demoVm().apply { startWorkout(data.value.templates.first()) }
        openSet2(vm)
        val start = number(shown("reps"))
        compose.mainClock.autoAdvance = false
        val plus = compose.onNodeWithContentDescription("More reps")
        plus.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(380)
        assertEquals("the press steps once, with no repeat before 400 ms", start + 1, number(shown("reps")), 0.0)
        compose.mainClock.advanceTimeBy(320) // 700 ms: repeats at 400, 500, 585, 657
        val at700 = number(shown("reps"))
        assertEquals(start + 5, at700, 0.0)
        compose.mainClock.advanceTimeBy(300) // 1000 ms: the gaps keep shrinking
        val at1000 = number(shown("reps"))
        assertTrue("faster in the second 300 ms (${at1000 - at700}) than the first (4)", at1000 - at700 > 4)
        plus.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("letting go stops it", at1000, number(shown("reps")), 0.0)
    }

    @Test fun talkBackDoubleTapStepsOnce() {
        val vm = demoVm().apply { startWorkout(data.value.templates.first()) }
        openSet2(vm)
        val start = number(shown("reps"))
        compose.onNodeWithContentDescription("More reps").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(start + 1, number(shown("reps")), 0.0)
    }

    // ---- 1 · typing a number --------------------------------------------------------------------

    private fun typing() = compose.onNodeWithTag("typing")
    private fun typed() = typing().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun typingReplacesThePrefilledNumber() {
        val vm = demoVm().apply { startWorkout(data.value.templates.first()) }
        openSet2(vm)
        val prefilled = shown("weight").substringBefore(' ')
        compose.onNodeWithTag("value-weight").performClick()
        assertEquals("opens holding the pre-filled number", prefilled, typed())
        typing().performTextInput("62.5")
        assertEquals("all selected on focus, so typing replaces it", "62.5", typed())
        typing().performImeAction()
        assertEquals("62.5 kg", shown("weight"))
    }

    @Test fun zeroOpensEmptyWithAFadedPlaceholder() {
        // No history and no weight yet: the stepper shows 0.
        val vm = sessionWith(listOf("deadlift"), emptyList())
        open(vm, hasContentDescription("Set 1 ", substring = true) and hasClickAction())
        assertEquals("0 kg", shown("weight"))
        compose.onNodeWithTag("value-weight").performClick()
        assertEquals("", typed())
        compose.onNodeWithTag("value-weight").assert(hasText("0"))
        typing().performTextInput("62.5")
        assertEquals("never \"062.5\"", "62.5", typed())
        typing().performImeAction()
        assertEquals("62.5 kg", shown("weight"))
    }

    @Test fun leavingItEmptyKeepsThePrefilledNumber() {
        val vm = demoVm().apply { startWorkout(data.value.templates.first()) }
        openSet2(vm)
        val before = shown("reps")
        compose.onNodeWithTag("value-reps").performClick()
        typing().performTextInput("1")
        typing().performTextClearance()
        compose.onNodeWithTag("value-reps").assert(hasText(before))
        typing().performImeAction()
        assertEquals(before, shown("reps"))
    }

    @Test fun timeTypesTheSameWay() {
        val past = ExerciseEntry(9_003, "plank", listOf(60, 55, 50).mapIndexed { i, s -> SetEntry(9_010L + i, weightKg = 0.0, durationSec = s, done = true) }, tracking = Tracking.DURATION)
        val vm = sessionWith(listOf("plank"), listOf(past))
        openSet2(vm)
        compose.onNodeWithTag("value-time").performClick()
        assertEquals("0:55", typed())
        typing().performTextInput("1:30")
        typing().performImeAction()
        assertEquals("1:30", shown("time"))
    }

    @Test fun aStepWhileTypingShowsTheNewNumber() {
        val vm = demoVm().apply { startWorkout(data.value.templates.first()) }
        openSet2(vm)
        val start = number(shown("weight"))
        compose.onNodeWithTag("value-weight").performClick()
        compose.onNodeWithContentDescription("More weight").performClick()
        assertEquals("the field steps aside and the stepped number shows", start + 2.5, number(shown("weight")), 0.001)
    }
}
