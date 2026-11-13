package app.forge

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Step 1 of docs/rest-design.md: one buzz per rest, on a quiet alerting channel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestAlarmTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before fun clean() {
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        RestAlarm.resetBuzzed()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val vm = AppViewModel(app)
        vm.completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first())
        vm.startWorkout(vm.data.value.templates.first())
        // ForgeRepository syncs the notification from a background collector. Wait for the sync that follows
        // startWorkout, or it can land after a test's buzz and replace it (the old intermittent failure).
        val deadline = System.currentTimeMillis() + 5_000
        while (shadowOf(nm).getNotification(RestNotifier.ONGOING_ID) == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertNotNull("the start-of-workout sync never posted", shadowOf(nm).getNotification(RestNotifier.ONGOING_ID))
    }

    @Test fun theSameEndBuzzesOnceAndANewEndBuzzesAgain() {
        assertTrue(RestAlarm.buzzOnce(app, 1_000))
        assertFalse(RestAlarm.buzzOnce(app, 1_000)) // the app's effect and the alarm both arrive
        assertTrue(RestAlarm.buzzOnce(app, 2_000)) // the next rest
        assertFalse(RestAlarm.buzzOnce(app, 2_000))
    }

    @Test fun theBuzzChannelIsAlertingButQuiet() {
        RestAlarm.buzzOnce(app, 1_000)
        val ch = nm.getNotificationChannel("rest_end")
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, ch.importance) // below HIGH: no heads-up
        assertTrue(ch.shouldVibrate())
        assertArrayEquals(RestAlarm.BUZZ, ch.vibrationPattern)
        assertNull(ch.sound)
    }

    @Test fun theBuzzIsOneNotificationAndTheNextSyncGoesBackToTheQuietChannel() {
        RestAlarm.buzzOnce(app, 1_000)
        assertEquals("rest_end", shadowOf(nm).getNotification(RestNotifier.ONGOING_ID).channelId)
        RestNotifier.sync(app, ForgeRepository.get(app).value)
        val n = shadowOf(nm).getNotification(RestNotifier.ONGOING_ID)
        assertEquals("workout", n.channelId)
        assertTrue(n.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(1, shadowOf(nm).size()) // replaced, not duplicated
    }

    @Test fun theBuzzNotificationItselfAlertsAgain() {
        val d = ForgeRepository.get(app).value
        val n = RestNotifier.ongoing(app, d, 0, "rest_end")
        assertEquals("rest_end", n.channelId)
        assertEquals(0, n.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test fun withNotificationsOffItVibratesDirectly() {
        val vm = AppViewModel(app)
        vm.updateSettings { it.copy(notifyRest = false) }
        assertTrue(RestAlarm.buzzOnce(app, 1_000))
        assertNull(shadowOf(nm).getNotification(RestNotifier.ONGOING_ID))
    }
}
