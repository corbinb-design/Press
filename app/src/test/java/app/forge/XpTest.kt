package app.forge

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Section 13: XP only from training, unlocks from real milestones, icons via activity-alias. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XpTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var id = 0L
    private val w0 = LocalDate.of(2026, 9, 7) // a Monday

    private fun session(day: LocalDate, name: String = "Push", kg: Double = 60.0, templateId: Long? = null): Workout {
        val at = day.atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Workout(++id, name, templateId, at, at + 3_600_000, listOf(ExerciseEntry(++id, "bench", listOf(SetEntry(++id, weightKg = kg, reps = 5, done = true)))))
    }

    @Before fun clean() { app.filesDir.listFiles()?.forEach { it.delete() }; ForgeRepository.reset() }

    @Test fun workoutsRecordsAndGoalWeeks() {
        // Two sessions in one week with a goal of 2: 2 × 100, one record (the second, heavier), one goal week.
        val h = listOf(session(w0.plusDays(2), kg = 62.5), session(w0, kg = 60.0))
        assertEquals(2 * Xp.WORKOUT + Xp.RECORD + Xp.WEEKLY_GOAL, Xp.total(h, weeklyGoal = 2))
    }

    @Test fun questsAndComebacksEarnOncePerWeek() {
        val s = StreakState(questsDone = listOf("2026-W37", "2026-W37"), comebacksDone = listOf("2026-W40"))
        assertEquals(Xp.REPAIR_QUEST + Xp.COMEBACK, Xp.total(emptyList(), 4, s))
    }

    @Test fun theStreakRecordsCompletedQuestsAndComebacks() {
        val monday = w0
        val k = Streaks.key(monday)
        val quest = StreakState(weeks = 5, repair = RepairQuest(k, 5, 5, 1, accepted = true))
        assertEquals(listOf(k), Streaks.week(quest, monday, 5, 4).questsDone)
        assertTrue(Streaks.week(quest, monday, 3, 4).questsDone.isEmpty())
        val comeback = StreakState(comebackWeek = k)
        assertEquals(listOf(k), Streaks.week(comeback, monday, 2, 4).comebacksDone)
        assertTrue(Streaks.week(comeback, monday, 1, 4).comebacksDone.isEmpty())
    }

    @Test fun levelsAndTiersMatchFigma() {
        assertEquals(8, Xp.level(3_820)); assertEquals(Xp.Tier.IRON, Xp.tier(3_820))
        assertEquals(9, Xp.level(4_000)); assertEquals(Xp.Tier.STEEL, Xp.tier(4_000))
        assertEquals(Xp.Tier.STEEL, Xp.nextTier(3_820))
        assertNull(Xp.nextTier(40_000))
    }

    private fun progress(level: Int = 1, push: Int = 0, streak: Int = 0, sessions: Int = 0, kg: Double = 0.0) = Unlocks.Progress(level, push, streak, sessions, kg)

    @Test fun unlocksFollowTheirMilestones() {
        assertEquals(listOf(Unlocks.Icon.LIME, Unlocks.Icon.INK), Unlocks.icons(progress()))
        assertTrue(Unlocks.Icon.PAPER.met(progress(level = 9)))
        assertFalse(Unlocks.Icon.COBALT.met(progress(level = 11)))
        assertTrue(Unlocks.Icon.EMBER.met(progress(push = 100)))
        assertTrue(Unlocks.Icon.FOIL.met(progress(streak = 52)))
        assertEquals(listOf(Unlocks.Spine.SOLID), Unlocks.spines(progress()))
        assertTrue(Unlocks.Spine.LINEN.met(progress(level = 9)))
        assertTrue(Unlocks.Spine.BOUND.met(progress(sessions = 100)))
        assertTrue(Unlocks.Spine.FOIL.met(progress(kg = 1_000_000.0)))
    }

    @Test fun reachingSteelUnlocksPaperAndLinen() {
        val new = Unlocks.newlyUnlocked(progress(level = 8), progress(level = 9))
        assertEquals(listOf(Unlocks.Icon.PAPER), new.icons)
        assertEquals(listOf(Unlocks.Spine.LINEN), new.spines)
    }

    @Test fun pushDaysCountPushSessions() {
        val d = AppData(history = listOf(session(w0, "Push"), session(w0.plusDays(1), "Pull"), session(w0.plusDays(2), "Push Day")))
        assertEquals(2, Unlocks.progress(d).pushDays)
    }

    private fun enabled(alias: String) = app.packageManager.getComponentEnabledSetting(ComponentName(app.packageName, "app.forge.$alias"))

    @Test fun switchingIconsEnablesExactlyOneAlias() {
        val vm = AppViewModel(app)
        vm.setAppIcon(Unlocks.Icon.INK)
        assertEquals("ink", vm.data.value.settings.appIcon)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, enabled("IconInk"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, enabled("MainActivity"))
        assertEquals(Unlocks.Icon.INK, AppIcons.current(app))
        vm.setAppIcon(Unlocks.Icon.LIME)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, enabled("MainActivity"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, enabled("IconInk"))
    }

    @Test fun lockedIconsAndFinishesAreRefused() {
        val vm = AppViewModel(app)
        vm.setAppIcon(Unlocks.Icon.COBALT)
        vm.setSpineFinish(Unlocks.Spine.FOIL)
        assertEquals("lime", vm.data.value.settings.appIcon)
        assertEquals("solid", vm.data.value.settings.spineFinish)
    }
}
