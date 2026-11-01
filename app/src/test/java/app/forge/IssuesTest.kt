package app.forge

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import app.forge.ui.Posters
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** Section 12: the Sunday issue's rules, numbering, schedule, notification and posters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IssuesTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var id = 0L
    private val mon = LocalDate.of(2026, 9, 7) // a Monday

    private fun session(day: LocalDate, name: String = "Push", kg: Double = 60.0): Workout {
        val at = day.atTime(18, 0).atZone(Weeks.zone).toInstant().toEpochMilli()
        return Workout(++id, name, null, at, at + 3_600_000, listOf(ExerciseEntry(++id, "bench", List(3) { SetEntry(++id, weightKg = kg, reps = 5, done = true) })))
    }

    private fun data(vararg w: Workout, goal: Int = 4, streak: StreakState = StreakState()) =
        AppData(history = w.sortedByDescending { it.startedAt }, settings = Settings(name = "Corbin", weeklyGoal = goal, onboarded = true), streak = streak)

    @Before fun clean() { app.filesDir.listFiles()?.forEach { it.delete() }; ForgeRepository.reset() }

    @Test fun numberedFromTheFirstFullWeek() {
        // First session on a Wednesday: that week is a partial one, so No. 1 is the week after.
        val d = data(session(mon.plusDays(2)), session(mon.plusWeeks(1)))
        assertEquals(mon.plusWeeks(1), Issues.firstWeek(d.history))
        val now = mon.plusWeeks(3).plusDays(6).atTime(19, 30) // Sunday evening, three weeks on
        assertEquals(listOf(3, 2, 1), Issues.archive(d, now).map { it.number })
        // Started on a Monday: that week counts.
        assertEquals(mon, Issues.firstWeek(listOf(session(mon))))
    }

    @Test fun anIssueComesOutSundayAtSeven() {
        val sunday = mon.plusDays(6)
        assertEquals(mon.minusWeeks(1), Issues.latestWeek(sunday.atTime(18, 59)))
        assertEquals(mon, Issues.latestWeek(sunday.atTime(19, 0)))
        assertEquals(mon, Issues.latestWeek(mon.plusWeeks(1).atTime(9, 0)))
    }

    @Test fun nextPublishIsTheComingSundayEvening() {
        val z = Weeks.zone
        assertEquals(mon.plusDays(6).atTime(19, 0).atZone(z), Issues.nextPublish(mon.atTime(8, 0).atZone(z)))
        assertEquals(mon.plusDays(13).atTime(19, 0).atZone(z), Issues.nextPublish(mon.plusDays(6).atTime(19, 0).atZone(z)))
        assertEquals(mon.plusDays(6).atTime(19, 0).atZone(z), Issues.nextPublish(mon.plusDays(6).atTime(18, 0).atZone(z)))
    }

    @Test fun headlinesSaySomethingTrue() {
        val four = data(session(mon), session(mon.plusDays(1)), session(mon.plusDays(3)), session(mon.plusDays(5)))
        assertEquals("Four for four.", Issues.issue(four, mon).headline)
        assertEquals("Two of four.", Issues.issue(data(session(mon), session(mon.plusDays(2))), mon).headline)
        assertEquals("Five sessions. Goal beaten.", Issues.issue(data(*(0..4).map { session(mon.plusDays(it.toLong())) }.toTypedArray()), mon).headline)
        assertEquals("A quiet week.", Issues.issue(data(session(mon.minusWeeks(1))), mon).headline)
        val paused = data(session(mon.minusWeeks(1)), streak = StreakState(pausedWeeks = listOf(Weeks.key(mon))))
        assertEquals("A week off, on purpose.", Issues.issue(paused, mon).headline)
        val comeback = data(session(mon), session(mon.plusDays(2)), streak = StreakState(comebacksDone = listOf(Weeks.key(mon))))
        assertEquals("The comeback.", Issues.issue(comeback, mon).headline)
    }

    @Test fun paragraphNamesTheDaysAndTheNumbers() {
        val d = data(session(mon), session(mon.plusDays(1)), session(mon.plusDays(3)), session(mon.plusDays(5)))
        val p = Issues.issue(d, mon).paragraph
        assertTrue(p, p.startsWith("You trained Monday, Tuesday, Thursday and Saturday, lifted 3,600 kg"))
    }

    @Test fun bestWeekSinceNamesTheMonth() {
        // A heavy week in July, lighter weeks after, then this week beats everything since July.
        val july = LocalDate.of(2026, 7, 6)
        val weeks = (0..8).map { july.plusWeeks(it.toLong()) }
        val d = data(
            *(listOf(session(july, kg = 200.0)) + weeks.drop(1).map { session(it, kg = 50.0) } + listOf(session(mon, kg = 150.0))).toTypedArray(),
            goal = 1,
        )
        assertEquals("Your best week since July.", Issues.issue(d, mon).standfirst)
        // Nothing earlier ever beat it.
        assertEquals("Your best week yet.", Issues.issue(data(session(mon.minusWeeks(1), kg = 10.0), session(mon, kg = 90.0), goal = 1), mon).standfirst)
    }

    @Test fun nextWeekMentionsAPlannedLighterWeek() {
        val d = data(session(mon)).copy(templates = listOf(Template(1, "Push", listOf(TemplateExercise("bench", 3, 5, lighterNextKg = 60.0)))))
        assertTrue(Issues.nextWeek(d, mon).startsWith("Bench Press has a lighter week planned."))
    }

    @Test fun schedulingSetsTheSundayAlarm() {
        val now = ZonedDateTime.of(mon.atTime(10, 0), Weeks.zone)
        val at = SundayIssue.schedule(app, now)
        val alarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertEquals(at.toInstant().toEpochMilli(), alarm.triggerAtTime)
        // Boot puts it back.
        shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.clear()
        SundayIssueReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    }

    @Test fun postsOneNotificationWithTheHeadline() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val d = data(session(mon), session(mon.plusDays(1)), session(mon.plusDays(3)), session(mon.plusDays(5)))
        ForgeStore(app.filesDir).write(d.copy(seeded = true, templates = Library.defaultTemplates))
        ForgeRepository.reset()
        assertTrue(SundayIssue.post(app, mon.plusDays(6).atTime(19, 0)))
        val n = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(SundayIssue.NOTIFICATION_ID)
        assertEquals("Issue No. 1: Four for four.", n.extras.getString("android.title"))
        // Turned off in Profile: nothing.
        ForgeRepository.get(app).update { it.copy(settings = it.settings.copy(notifySunday = false)) }
        assertFalse(SundayIssue.post(app, mon.plusDays(6).atTime(19, 0)))
    }

    @Test fun postersRenderStorySized() {
        val d = data(session(mon), session(mon.plusDays(1), "Pull"), session(mon.plusDays(3), "Legs", kg = 100.0), session(mon.plusDays(5), "Upper"))
        val issue = Issues.issue(d, mon)
        listOf(false, true).forEach { paper ->
            val bmp = Posters.render(app, issue, "Corbin", WeightUnit.KG, paper)
            assertEquals(1080, bmp.width); assertEquals(1920, bmp.height)
            val out = File("build/outputs/roborazzi/12.3_poster_${if (paper) "paper" else "ink"}.png").apply { parentFile?.mkdirs() }
            out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun nowIsSane() { assertNotNull(LocalDateTime.now()) }
}
