package app.forge

import android.app.Application
import android.app.KeyguardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** B: the notification's Edit over the lock screen: next set only, and only when the setting allows it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class LockEditTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AppViewModel
    private val repo get() = ForgeRepository.get(app)

    @Before fun setUp() {
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        vm = AppViewModel(app).apply { completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first()); loadDemoHistory() }
        vm.startWorkout(vm.data.value.templates.first())
        vm.logSet(0, 0, 20.0, 8, false)
        vm.skipRest()
    }

    private fun lock(locked: Boolean) = shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(locked)
    private val next get() = Session.next(repo.value)!!
    private fun launch() = ActivityScenario.launch<LockEditActivity>(LockEditActivity.intent(app, repo.value.active!!.id, next.setEntry.id))

    // ---- Logging -------------------------------------------------------------------------------------

    @Test fun logSetAsLogsTheChosenValuesOnce() {
        val d = repo.value
        val n = Session.next(d)!!
        val after = Session.logSetAs(d, d.active!!.id, n.setEntry.id, 30.0, 10, 1_000L)
        val s = after.active!!.exercises[n.ex].sets[n.set]
        assertTrue(s.done); assertEquals(30.0, s.weightKg); assertEquals(10, s.reps)
        assertNotNull("the rest starts", after.active!!.restEndsAt)
        assertSame("a second tap changes nothing", after, Session.logSetAs(after, d.active!!.id, n.setEntry.id, 40.0, 12, 2_000L))
        assertSame("a finished or other workout changes nothing", d, Session.logSetAs(d, 42L, n.setEntry.id, 30.0, 10, 1_000L))
    }

    @Test fun onlyRepSetsCanBeLoggedThisWay() {
        vm.addExercises(listOf("plank"))
        val d = repo.value
        val plank = d.active!!.exercises.last()
        assertSame(d, Session.logSetAs(d, d.active!!.id, plank.sets[0].id, null, 10, 0L))
    }

    // ---- The screen ----------------------------------------------------------------------------------

    @Test fun lockedShowsTheStepperOverTheLockScreenAndLogs() {
        lock(true)
        val n = next
        val start = n.planned!!
        launch().use { s ->
            s.onActivity { assertTrue("shows when locked", shadowOf(it).showWhenLocked); assertTrue(shadowOf(it).turnScreenOn) }
            compose.onNodeWithText("SET ${n.set + 1} OF ${n.entry.sets.size}").assertExists()
            repeat(2) { compose.onNodeWithContentDescription("More weight").performClick() }
            compose.onNodeWithContentDescription("More reps").performClick()
            compose.onNodeWithText("LOG SET").performClick()
            compose.waitForIdle()
            val set = repo.value.active!!.exercises[n.ex].sets[n.set]
            assertTrue(set.done)
            assertEquals(start.weightKg!! + 5.0, set.weightKg!!, 0.001)
            assertEquals(start.reps!! + 1, set.reps)
            s.onActivity { assertTrue("closes after logging", it.isFinishing) }
        }
    }

    @Test fun nothingElseIsReachableWithoutUnlocking() {
        lock(true)
        launch().use {
            // No tabs, history or settings: just the set, Log, Open Press and Close.
            listOf("HISTORY", "PROFILE", "METRICS", "FINISH").forEach { t -> assertTrue(t, compose.onAllNodesWithText(t).fetchSemanticsNodes().isEmpty()) }
            compose.onNodeWithText("Everything else in Press needs your phone unlocked.").assertExists()
        }
    }

    // Handing over sets no content of its own, so these drive the activity directly.
    private fun build() = org.robolectric.Robolectric.buildActivity(LockEditActivity::class.java, LockEditActivity.intent(app, repo.value.active!!.id, next.setEntry.id)).create().get()

    @Test fun withTheSettingOffItAsksToUnlockInstead() {
        vm.updateSettings { it.copy(logFromLockScreen = false) }
        lock(true)
        val before = repo.value
        val a = build()
        assertNull("no stepper over the lock screen", shadowOf(a).contentView)
        assertNull("nothing opens until the phone is unlocked", shadowOf(a).nextStartedActivity)
        assertEquals(before.active, repo.value.active)
    }

    @Test fun unlockedItOpensTheAppsOwnStepper() {
        lock(false)
        val id = next.setEntry.id
        val a = build()
        val started = shadowOf(a).nextStartedActivity
        assertEquals(ForgeActivity::class.java.name, started.component!!.className)
        assertEquals(id, started.getLongExtra(ForgeActivity.EXTRA_EDIT_SET, 0))
        assertTrue(a.isFinishing)
    }

    @Test fun aTimedNextSetHandsOverToTheApp() {
        vm.data.value.active!!.exercises.forEachIndexed { ex, e -> e.sets.indices.forEach { if (!e.sets[it].done) vm.logSet(ex, it, 20.0, 8, false) } }
        vm.addExercises(listOf("plank"))
        vm.skipRest()
        lock(true)
        assertNull(shadowOf(build()).contentView)
    }
}
