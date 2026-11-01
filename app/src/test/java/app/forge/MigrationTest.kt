package app.forge

import android.app.Application
import app.forge.data.ProgressionMethod
import app.forge.data.Tracking
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** forge.json written by older builds must keep loading, and existing users must skip first launch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val file get() = app.filesDir.resolve("forge.json")

    @Before fun clean() { file.delete(); ForgeRepository.reset() }

    private val v010 = """
        {"templates":[{"id":1,"name":"Push","exercises":[{"exerciseId":"bench","sets":3,"reps":6}]}],
         "history":[],"active":null,
         "settings":{"name":"NAME","unit":"LB","restSeconds":120,"autoRest":true,"avatarPath":null},
         "customExercises":[],"seeded":true}
    """.trimIndent()

    @Test fun oldFileWithNameSkipsSetup() {
        file.writeText(v010.replace("NAME", "Corbin"))
        ForgeRepository.reset()
        val s = AppViewModel(app).data.value.settings
        assertEquals("Corbin", s.name)
        assertEquals(120, s.restSeconds)
        assertEquals(4, s.weeklyGoal)
        assertTrue(s.onboarded)
        assertFalse(s.needsSetup)
    }

    @Test fun oldFileWithBlankNameStillSkipsSetup() {
        file.writeText(v010.replace("NAME", ""))
        ForgeRepository.reset()
        assertFalse(AppViewModel(app).data.value.settings.needsSetup)
    }

    @Test fun oldTemplatesGetAColourFromTheirName() {
        file.writeText(v010.replace("NAME", "Corbin"))
        ForgeRepository.reset()
        assertEquals("push", AppViewModel(app).data.value.templates.single().colorKey)
    }

    @Test fun oldTemplatesGetDoubleProgressionAtTheirReps() {
        file.writeText(v010.replace("NAME", "Corbin"))
        ForgeRepository.reset()
        val te = AppViewModel(app).data.value.templates.single().exercises.single()
        assertEquals(ProgressionMethod.DOUBLE, te.method)
        assertEquals(6, te.repMin)
        assertEquals(6, te.repMax)
        assertEquals(2.5, te.incrementKg, 0.0)
        assertTrue(te.suggestLighter)
        assertNull(te.lighterNextKg)
        assertNull(te.variationId)
    }

    /** A v1.0 file with history, a custom exercise and an active session: every v1.1 field takes its default. */
    @Test fun v10FileLoadsWithTrackingDefaults() {
        file.writeText("""
            {"templates":[],"customExercises":[{"id":"custom_1","name":"Sled Drag","muscle":"Quads","equipment":"MACHINE","custom":true}],
             "history":[{"id":5,"name":"Core","startedAt":1757232000000,"endedAt":1757235600000,
               "exercises":[{"id":6,"exerciseId":"plank","sets":[{"id":7,"weightKg":0.0,"reps":60,"done":true}]}]}],
             "active":{"id":8,"name":"Push","startedAt":1757318400000,"exercises":[{"id":9,"exerciseId":"bench","sets":[{"id":10}]}]},
             "settings":{"name":"Corbin","unit":"KG","onboarded":true},"seeded":true}
        """.trimIndent())
        ForgeRepository.reset()
        val d = AppViewModel(app).data.value
        val old = d.history.single()
        assertTrue(old.countsForGoal)
        // v1.0.1 (E): the old plank ("Plank (reps = sec)") is read as the hold it was: 60 reps were 60 seconds.
        assertEquals(Tracking.DURATION, old.exercises.single().tracking)
        assertEquals(60, old.exercises.single().sets.single().durationSec)
        assertNull(old.exercises.single().sets.single().reps)
        assertTrue(old.exercises.single().legacy)
        assertEquals(Tracking.WEIGHT_REPS, d.customExercises.single().tracking)
        assertTrue(d.customExercises.single().cues.isEmpty())
        assertTrue(d.settings.cardioCountsForGoal)
        assertEquals(Tracking.WEIGHT_REPS, d.active!!.exercises.single().tracking)
    }

    @Test fun oldBackupImportSkipsSetup() {
        val vm = AppViewModel(app)
        val d = vm.parseBackup(v010.replace("NAME", "")).getOrThrow()
        assertFalse(d.settings.needsSetup)
    }

    @Test fun freshInstallShowsSetup() {
        assertTrue(AppViewModel(app).data.value.settings.needsSetup)
    }

    @Test fun newFileMidSetupShowsSetupAgain() {
        file.writeText(v010.replace("NAME", "").replace("\"avatarPath\":null", "\"avatarPath\":null,\"onboarded\":false"))
        ForgeRepository.reset()
        assertTrue(AppViewModel(app).data.value.settings.needsSetup)
    }
}
