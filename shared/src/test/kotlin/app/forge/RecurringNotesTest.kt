package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test

/** "What happened?" pills appear only when something recurs in confirmed notes. */
class RecurringNotesTest {
    private var id = 0L
    private fun note(type: String, date: String, days: Int = 1) =
        ContextNote(++id, type, listOf(NoteActivity(type, days, Intensity.MODERATE, dates = listOf(date))), 0L)

    @Test fun `no pattern, no pills`() {
        assertEquals(emptyList<String>(), Notes.recurring(emptyList()))
        assertEquals(emptyList<String>(), Notes.recurring(listOf(note("Football", "2026-10-04"))))
        assertEquals(emptyList<String>(), Notes.recurring(listOf(note("Walking", "2026-08-01"), note("Walking", "2026-10-20"))))
    }

    @Test fun `two Sundays make Football on Sunday`() {
        assertEquals(listOf("Football on Sunday"), Notes.recurring(listOf(note("Football", "2026-10-25"), note("football", "2026-11-01"))))
    }

    @Test fun `twice within four weeks on different days makes a plain pill`() {
        assertEquals(listOf("Walking"), Notes.recurring(listOf(note("Walking", "2026-10-20"), note("Walking", "2026-11-02"))))
    }
}
