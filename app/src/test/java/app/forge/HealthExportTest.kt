package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** Phase 2 step 1: the Health Connect record written for a finished session. */
class HealthExportTest {
    private fun set() = SetEntry(1, weightKg = 60.0, reps = 5, done = true)
    private fun lift() = ExerciseEntry(1, "squat", listOf(set()))
    private fun workout(end: Long? = 5_000_000, name: String = "Push A", entries: List<ExerciseEntry> = listOf(lift()), recordId: String? = null) =
        Workout(7, name, null, 1_000_000, end, entries, healthRecordId = recordId)

    @Test fun `a finished lifting session becomes one record with its name and times`() {
        val r = HealthExport.record(workout())!!
        assertEquals("Push A", r.title)
        assertEquals(1_000_000, r.startMs)
        assertEquals(5_000_000, r.endMs)
        assertEquals("press-7", r.clientId)
    }

    @Test fun `unfinished, already written and cardio-only sessions are not written`() {
        assertNull(HealthExport.record(workout(end = null)))
        assertNull(HealthExport.record(workout(recordId = "abc")))
        val cardio = ExerciseEntry(2, "run", listOf(set()), tracking = Tracking.CARDIO)
        assertNull(HealthExport.record(workout(entries = listOf(cardio))))
    }

    @Test fun `a blank name falls back and a zero-length session is stretched to a minute`() {
        val r = HealthExport.record(workout(end = 1_000_000, name = "  "))!!
        assertEquals("Workout", r.title)
        assertEquals(1_000_000 + HealthExport.MIN_MS, r.endMs)
    }

    @Test fun `files from before the export load with it off and no record ids`() {
        val dir = Files.createTempDirectory("press").toFile()
        val old = ForgeStore(dir).decode(
            """{"templates":[],"history":[{"id":3,"name":"Pull","startedAt":1,"endedAt":2,"exercises":[]}],"seeded":true,"settings":{"name":"C","onboarded":true}}""",
        )
        assertFalse(old.settings.writeHealth)
        assertNull(old.history.single().healthRecordId)
        dir.deleteRecursively()
    }
}
