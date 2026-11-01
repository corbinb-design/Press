package app.forge

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Section 09: the rest notification's actions write through the same safe path as the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RestNotificationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before fun clean() {
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        ForgeActivity.inForeground = false
    }

    /** A Push session in progress, saved to disk, with the app then killed. Returns the workout. */
    private fun sessionThenKill(): Workout {
        val vm = AppViewModel(app)
        vm.completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first())
        vm.loadDemoHistory()
        vm.startWorkout(vm.data.value.templates.first())
        ForgeRepository.get(app).flush()
        ForgeRepository.reset() // the process is gone
        return ForgeStore(app.filesDir).read()!!.active!!
    }

    private fun onDisk() = ForgeStore(app.filesDir).read()!!

    private fun log(w: Workout, setId: Long, now: Long = System.currentTimeMillis()) =
        RestActionReceiver.handle(app, RestActionReceiver.LOG, w.id, setId, now)

    @Test fun loggingWithTheAppClosedIsSavedOnce() {
        val w = sessionThenKill()
        val historyBefore = onDisk().history
        val first = w.exercises[0].sets[0]

        log(w, first.id)
        log(w, first.id) // a double tap, or the same intent delivered again
        ForgeRepository.reset() // and the process is killed straight after

        val d = onDisk()
        val sets = d.active!!.exercises[0].sets
        assertEquals(w.exercises[0].sets.size, sets.size)
        assertTrue(sets[0].done)
        assertEquals(1, d.active!!.exercises.sumOf { e -> e.sets.count { it.done } })
        // Logged with the numbers the notification showed: last session's set 1.
        val prev = Stats.previousSets(historyBefore, w.exercises[0].exerciseId)[0]
        assertEquals(prev.weightKg, sets[0].weightKg)
        assertEquals(prev.reps, sets[0].reps)
        assertNotNull(d.active!!.restEndsAt) // and the next rest started
        assertEquals(historyBefore, d.history) // nothing else touched
        assertEquals(w.exercises.map { it.id }, d.active!!.exercises.map { it.id })
    }

    @Test fun theNextSetFollowsEachLog() {
        val w = sessionThenKill()
        val ids = w.exercises[0].sets.map { it.id }
        ids.forEach { id ->
            assertEquals(id, Session.next(ForgeRepository.get(app).value)!!.setEntry.id)
            log(w, id)
        }
        ForgeRepository.reset()
        assertTrue(onDisk().active!!.exercises[0].sets.all { it.done })
        assertEquals(w.exercises[1].sets[0].id, Session.next(onDisk())!!.setEntry.id)
    }

    @Test fun appAndNotificationChangesBothSurvive() {
        val w = sessionThenKill()
        val vm = AppViewModel(app) // the app is opened again
        log(w, w.exercises[0].sets[0].id) // logged from the shade while the app is open
        assertTrue(vm.data.value.active!!.exercises[0].sets[0].done) // the screen sees it at once
        vm.toggleDone(0, 1, null) // then the next set in the app
        vm.flush(); Thread.sleep(200)
        ForgeRepository.get(app).flush()
        ForgeRepository.reset()
        val sets = onDisk().active!!.exercises[0].sets
        assertTrue(sets[0].done && sets[1].done)
        assertEquals(2, onDisk().active!!.exercises.sumOf { e -> e.sets.count { it.done } })
    }

    @Test fun staleTapsChangeNothing() {
        val w = sessionThenKill()
        val before = onDisk()
        RestActionReceiver.handle(app, RestActionReceiver.LOG, w.id + 1, w.exercises[0].sets[0].id) // a different session
        RestActionReceiver.handle(app, RestActionReceiver.LOG, w.id, 12345L) // a set since removed
        ForgeRepository.reset()
        assertEquals(before, onDisk())
        // A tap after the workout was finished in the app.
        val vm = AppViewModel(app)
        vm.cancelWorkout()
        log(w, w.exercises[0].sets[0].id)
        assertNull(ForgeRepository.get(app).value.active)
    }

    @Test fun plusFifteenAndSkip() {
        val w = sessionThenKill()
        val now = System.currentTimeMillis()
        log(w, w.exercises[0].sets[0].id, now)
        val ends = ForgeRepository.get(app).value.active!!.restEndsAt!!
        RestActionReceiver.handle(app, RestActionReceiver.PLUS15, w.id, 0, now)
        assertEquals(ends + 15_000, ForgeRepository.get(app).value.active!!.restEndsAt)
        RestActionReceiver.handle(app, RestActionReceiver.SKIP, w.id, 0, now)
        ForgeRepository.reset()
        assertNull(onDisk().active!!.restEndsAt)
    }

    @Test fun ongoingNotificationWhileTrainingAndGoneAfter() {
        val w = sessionThenKill()
        log(w, w.exercises[0].sets[0].id)
        val n = shadowOf(nm).getNotification(RestNotifier.ONGOING_ID)
        assertNotNull(n)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        AppViewModel(app).cancelWorkout()
        RestNotifier.sync(app, ForgeRepository.get(app).value)
        assertNull(shadowOf(nm).getNotification(RestNotifier.ONGOING_ID))
    }

    @Test fun restEndingInTheBackgroundPostsRestsUp() {
        val w = sessionThenKill()
        val now = System.currentTimeMillis()
        log(w, w.exercises[0].sets[0].id, now)
        val ends = ForgeRepository.get(app).value.active!!.restEndsAt!!
        ForgeRepository.reset()
        RestActionReceiver.handle(app, RestActionReceiver.REST_END, 0, 0, ends)
        assertNotNull(shadowOf(nm).getNotification(RestNotifier.REST_UP_ID))
        assertNull(onDisk().active!!.restEndsAt)
        // Logging from it clears it.
        log(w, Session.next(onDisk())!!.setEntry.id, ends + 5_000)
        assertNull(shadowOf(nm).getNotification(RestNotifier.REST_UP_ID))
    }

    @Test fun lockScreenActionsCanBeTurnedOff() {
        val w = sessionThenKill()
        val vm = AppViewModel(app)
        vm.updateSettings { it.copy(logFromLockScreen = false) }
        RestNotifier.sync(app, ForgeRepository.get(app).value)
        val n = shadowOf(nm).getNotification(RestNotifier.ONGOING_ID)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull(n.publicVersion)
        assertTrue(w.id != 0L)
    }

    // ---- Screenshots (9.1 shade, 9.3 heads-up) --------------------------------------------------

    private fun render(name: String, n: Notification, big: Boolean) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val frame = FrameLayout(activity).apply { setBackgroundColor(Color.parseColor("#0E0D0B")); setPadding(48, 48, 48, 48) }
        @Suppress("DEPRECATION")
        val rv = if (big) n.bigContentView else n.contentView
        frame.addView(rv.apply(activity, frame), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(frame)
        frame.captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun shot9_1_shade() {
        val w = sessionThenKill()
        val now = System.currentTimeMillis()
        log(w, w.exercises[0].sets[0].id, now)
        val d = ForgeRepository.get(app).value
        render("9.1_rest_notification", RestNotifier.ongoing(app, d, now + 6_000), big = true)
        render("9.1b_rest_notification_collapsed", RestNotifier.ongoing(app, d, now + 6_000), big = false)
    }

    @Test fun shot9_3_restsUp() {
        val w = sessionThenKill()
        val now = System.currentTimeMillis()
        log(w, w.exercises[0].sets[0].id, now)
        val ends = ForgeRepository.get(app).value.active!!.restEndsAt!!
        RestActionReceiver.handle(app, RestActionReceiver.REST_END, 0, 0, ends)
        @Suppress("DEPRECATION")
        render("9.3_rests_up", shadowOf(nm).getNotification(RestNotifier.REST_UP_ID).let { Notification.Builder.recoverBuilder(app, it).build() }, big = false)
    }
}
