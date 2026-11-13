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
        RestAlarm.resetBuzzed()
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

    @Test fun aTapOnAStaleScreenCannotUndoALogFromTheShade() {
        val w = sessionThenKill()
        val vm = AppViewModel(app)
        log(w, w.exercises[0].sets[0].id) // the shade logs set 0 while the app still shows it unticked
        val changed = vm.toggleDone(0, 0, shownDone = false) // the tap was built from the stale view
        assertFalse(changed)
        assertTrue(vm.data.value.active!!.exercises[0].sets[0].done)
        assertEquals(1, vm.data.value.active!!.exercises.sumOf { e -> e.sets.count { it.done } })
        // A tap on the fresh view (shown ticked) does untick it.
        assertTrue(vm.toggleDone(0, 0, shownDone = true))
        assertFalse(vm.data.value.active!!.exercises[0].sets[0].done)
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
        vm.toggleDone(0, 1) // then the next set in the app
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

    @Test fun plusFifteenFromOverStartsFifteenMoreFromNow() {
        val w = sessionThenKill()
        val now = System.currentTimeMillis()
        log(w, w.exercises[0].sets[0].id, now)
        val over = now + 200_000 // the rest ended a while ago
        RestActionReceiver.handle(app, RestActionReceiver.PLUS15, w.id, 0, over)
        assertEquals(over + 15_000, ForgeRepository.get(app).value.active!!.restEndsAt)
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

    @Test fun restEndingLeavesTheRestOnDisk() {
        val w = sessionThenKill()
        val now = System.currentTimeMillis()
        log(w, w.exercises[0].sets[0].id, now)
        val ends = ForgeRepository.get(app).value.active!!.restEndsAt!!
        ForgeRepository.get(app).flush()
        ForgeRepository.reset() // the alarm cold-starts the process
        RestAlarm.resetBuzzed()
        RestActionReceiver.handle(app, RestActionReceiver.REST_END, 0, 0, ends)
        assertEquals(ends, onDisk().active!!.restEndsAt) // zero writes nothing
        assertEquals(RestPhase.OVER, Session.rest(onDisk().active!!, ends))
        assertFalse(RestAlarm.buzzOnce(app, ends)) // the alarm already buzzed this rest
        // Logging the next set from the notification then starts a fresh rest.
        log(w, Session.next(onDisk())!!.setEntry.id, ends + 5_000)
        assertTrue(onDisk().active!!.restEndsAt!! > ends)
    }

    @Test fun anAlarmAMomentEarlyStillBuzzes() {
        val w = sessionThenKill()
        log(w, w.exercises[0].sets[0].id)
        val ends = ForgeRepository.get(app).value.active!!.restEndsAt!!
        RestAlarm.resetBuzzed()
        RestActionReceiver.handle(app, RestActionReceiver.REST_END, 0, 0, ends - 2_000)
        assertEquals(ends, onDisk().active!!.restEndsAt)
        assertFalse(RestAlarm.buzzOnce(app, ends)) // it buzzed, 2 s inside the window
    }

    @Test fun theAppAndTheAlarmTogetherBuzzOnce() {
        val w = sessionThenKill()
        log(w, w.exercises[0].sets[0].id)
        val ends = ForgeRepository.get(app).value.active!!.restEndsAt!!
        RestAlarm.resetBuzzed()
        assertTrue(RestAlarm.buzzOnce(app, ends)) // the app's effect, exact, while visible
        val before = onDisk()
        RestActionReceiver.handle(app, RestActionReceiver.REST_END, 0, 0, ends + 1_000) // the alarm, a moment later
        assertFalse(RestAlarm.buzzOnce(app, ends))
        assertEquals(before.active!!.restEndsAt, onDisk().active!!.restEndsAt)
    }

    @Test fun aStaleAlarmForAReplacedRestDoesNothing() {
        val w = sessionThenKill()
        val now = System.currentTimeMillis()
        log(w, w.exercises[0].sets[0].id, now)
        val ends = ForgeRepository.get(app).value.active!!.restEndsAt!!
        RestAlarm.resetBuzzed()
        RestActionReceiver.handle(app, RestActionReceiver.REST_END, 0, 0, now + 1_000) // far from the end
        assertTrue(RestAlarm.buzzOnce(app, ends)) // nothing buzzed yet
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

    @Suppress("DEPRECATION")
    private fun wristActions(n: Notification) = Notification.WearableExtender(n).actions.map { it.title.toString() }

    @Test fun wristMirrorHasTitleTextAndActionsWhileResting() {
        val w = sessionThenKill()
        val now = System.currentTimeMillis()
        log(w, w.exercises[0].sets[0].id, now)
        val d = ForgeRepository.get(app).value
        val n = RestNotifier.ongoing(app, d, now + 1_000)
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(title, title.startsWith("Rest "))
        val next = Session.next(d)!!
        assertTrue(text, text.contains(Session.exercise(d, next.entry.exerciseId).name))
        assertEquals(listOf("Log set", "+15", "Skip"), wristActions(n))
    }

    @Test fun wristActionsWithoutRestOnlyOfferLog() {
        val w = sessionThenKill()
        val n = RestNotifier.ongoing(app, ForgeRepository.get(app).value, System.currentTimeMillis())
        assertEquals(listOf("Log set"), wristActions(n))
        assertTrue(w.id != 0L)
    }

    // ---- Three states (rest-design step 7) ------------------------------------------------------

    private fun applied(n: Notification, big: Boolean): android.view.View {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val frame = FrameLayout(activity)
        @Suppress("DEPRECATION")
        val rv = if (big) n.bigContentView else n.contentView
        return rv.apply(activity, frame)
    }

    private fun bgRes(v: android.view.View) = shadowOf(v.background).createdFromResId

    /** A session with a rest started at [now]; returns the data and the end of that rest. */
    private fun restingData(): Triple<AppData, Long, Long> {
        val w = sessionThenKill()
        val now = System.currentTimeMillis()
        log(w, w.exercises[0].sets[0].id, now)
        val d = ForgeRepository.get(app).value
        return Triple(d, d.active!!.restEndsAt!!, now)
    }

    @Test fun workingStateShowsTheSessionClockWithNoRestControls() {
        val w = sessionThenKill()
        val d = ForgeRepository.get(app).value
        val v = applied(RestNotifier.ongoing(app, d, System.currentTimeMillis()), big = true)
        assertEquals("WORKOUT", v.findViewById<android.widget.TextView>(R.id.kicker).text.toString())
        assertFalse(v.findViewById<android.widget.Chronometer>(R.id.timer).isCountDown)
        assertEquals(android.view.View.GONE, v.findViewById<android.view.View>(R.id.progress).visibility)
        assertEquals(android.view.View.GONE, v.findViewById<android.view.View>(R.id.plus15).visibility)
        assertEquals(android.view.View.GONE, v.findViewById<android.view.View>(R.id.skip).visibility)
        assertEquals(R.drawable.notif_bg, bgRes(v.findViewById(R.id.root)))
        assertTrue(w.id != 0L)
    }

    @Test fun restingStateIsLimeWithProgressAndCountsDown() {
        val (d, ends, now) = restingData()
        val v = applied(RestNotifier.ongoing(app, d, now + 1_000), big = true)
        assertEquals("REST", v.findViewById<android.widget.TextView>(R.id.kicker).text.toString())
        val timer = v.findViewById<android.widget.Chronometer>(R.id.timer)
        assertTrue(timer.isCountDown)
        assertEquals(app.getColor(R.color.forge_bg), timer.currentTextColor) // ink text on lime (D10)
        assertEquals(android.view.View.VISIBLE, v.findViewById<android.view.View>(R.id.progress).visibility)
        assertEquals(android.view.View.VISIBLE, v.findViewById<android.view.View>(R.id.plus15).visibility)
        assertEquals(android.view.View.VISIBLE, v.findViewById<android.view.View>(R.id.skip).visibility)
        assertEquals(R.drawable.notif_bg_rest, bgRes(v.findViewById(R.id.root)))
        assertTrue(ends > now)
    }

    @Test fun overStateIsInkWithLimeTimerNoProgressAndStillCountsDown() {
        val (d, ends, _) = restingData()
        val v = applied(RestNotifier.ongoing(app, d, ends + 12_000), big = true)
        assertEquals("OVER", v.findViewById<android.widget.TextView>(R.id.kicker).text.toString())
        val timer = v.findViewById<android.widget.Chronometer>(R.id.timer)
        assertTrue(timer.isCountDown) // a countdown chronometer runs on past zero, to -0:12
        assertEquals(app.getColor(R.color.forge_lime), timer.currentTextColor)
        assertEquals(android.view.View.GONE, v.findViewById<android.view.View>(R.id.progress).visibility)
        assertEquals(android.view.View.VISIBLE, v.findViewById<android.view.View>(R.id.plus15).visibility)
        assertEquals(android.view.View.VISIBLE, v.findViewById<android.view.View>(R.id.skip).visibility)
        assertEquals(android.view.View.VISIBLE, v.findViewById<android.view.View>(R.id.log).visibility)
        assertEquals(R.drawable.notif_bg, bgRes(v.findViewById(R.id.root)))
    }

    @Test fun phaseBoundaryIsExactlyAtTheEnd() {
        val (d, ends, _) = restingData()
        fun kicker(now: Long) = applied(RestNotifier.ongoing(app, d, now), big = true)
            .findViewById<android.widget.TextView>(R.id.kicker).text.toString()
        assertEquals("REST", kicker(ends - 1))
        assertEquals("OVER", kicker(ends))
    }

    @Test fun collapsedViewsFollowThePhaseToo() {
        val (d, ends, now) = restingData()
        val resting = applied(RestNotifier.ongoing(app, d, now + 1_000), big = false)
        assertEquals(R.drawable.notif_bg_rest, bgRes(resting.findViewById(R.id.root)))
        assertTrue(resting.findViewById<android.widget.Chronometer>(R.id.timer).isCountDown)
        assertEquals(app.getColor(R.color.forge_bg), resting.findViewById<android.widget.Chronometer>(R.id.timer).currentTextColor)
        val over = applied(RestNotifier.ongoing(app, d, ends + 5_000), big = false)
        assertEquals(R.drawable.notif_bg, bgRes(over.findViewById(R.id.root)))
        assertTrue(over.findViewById<android.widget.Chronometer>(R.id.timer).isCountDown)
        assertEquals(app.getColor(R.color.forge_lime), over.findViewById<android.widget.Chronometer>(R.id.timer).currentTextColor)
    }

    @Test fun editIsOnlyInTheOverNotificationAndOpensTheLockEditorForTheNextSet() {
        val (d, ends, now) = restingData()
        val over = applied(RestNotifier.ongoing(app, d, ends + 5_000), big = true)
        val edit = over.findViewById<android.view.View>(R.id.edit)
        assertEquals(android.view.View.VISIBLE, edit.visibility)
        edit.performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(LockEditActivity::class.java.name, started.component!!.className)
        val next = Session.next(d)!!
        assertEquals(next.setEntry.id, started.getLongExtra(LockEditActivity.EXTRA_SET, -1))
        assertEquals(d.active!!.id, started.getLongExtra(LockEditActivity.EXTRA_WORKOUT, -1))
        val resting = applied(RestNotifier.ongoing(app, d, now + 1_000), big = true)
        assertEquals(android.view.View.GONE, resting.findViewById<android.view.View>(R.id.edit).visibility)
    }

    @Test fun theRestsUpChannelIsDeletedAndNothingPostsTheOldHeadsUp() {
        nm.createNotificationChannel(android.app.NotificationChannel("rest", "Rest's up", NotificationManager.IMPORTANCE_HIGH))
        val (d, ends, _) = restingData()
        RestNotifier.sync(app, d, ends + 1_000)
        assertNull(nm.getNotificationChannel("rest"))
        assertNotNull(nm.getNotificationChannel("rest_end"))
    }

    @Test fun overOffersLogPlusFifteenAndSkipOnTheWrist() {
        val (d, ends, _) = restingData()
        val n = RestNotifier.ongoing(app, d, ends + 5_000)
        assertEquals(listOf("Log set", "+15", "Skip"), wristActions(n))
        assertEquals("Rest over", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
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

    @Test fun shot9_1c_over() {
        val (d, ends, _) = restingData()
        val n = RestNotifier.ongoing(app, d, ends + 12_000)
        render("9.1c_over", n, big = true)
        render("9.1d_over_collapsed", n, big = false)
    }
}
