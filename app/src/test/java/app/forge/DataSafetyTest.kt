package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Phase 3.1: a forge.json that fails to read is kept aside, never replaced by an empty app. */
class DataSafetyTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private lateinit var store: ForgeStore

    private fun data(name: String) = AppData(settings = Settings(name = name, onboarded = true), seeded = true)

    @Before fun setUp() { dir = tmp.newFolder(); store = ForgeStore(dir) }

    private fun corruptFiles() = dir.listFiles { f -> f.name.startsWith("forge.corrupt-") }!!.toList()

    @Test fun freshInstallHasNothingToRecover() {
        val r = store.load()
        assertNull(r.data); assertNull(r.recovery)
        assertTrue(corruptFiles().isEmpty())
    }

    @Test fun goodFileLoadsAndIsCopiedToPrev() {
        store.write(data("Ann"))
        val r = store.load()
        assertEquals(data("Ann"), r.data); assertNull(r.recovery)
        assertEquals(data("Ann"), store.decode(File(dir, "forge.prev.json").readText()))
    }

    @Test fun prevIsRollingNotAccumulating() {
        store.write(data("Ann")); store.load()
        store.write(data("Bea")); store.load()
        assertEquals(data("Bea"), store.decode(File(dir, "forge.prev.json").readText()))
    }

    @Test fun corruptFileIsMovedAsideAndPrevRestored() {
        store.write(data("Ann")); store.load()                 // prev = Ann
        store.file.writeText("""{"history": [ {"oops""")          // a half-written file
        val r = store.load(now = 1234)
        assertEquals(data("Ann"), r.data)
        assertEquals(Recovery("forge.corrupt-1234.json", restored = true), r.recovery)
        assertEquals("""{"history": [ {"oops""", File(dir, "forge.corrupt-1234.json").readText())
        // The restored data is already back on disk, so a kill right now loses nothing.
        assertEquals(data("Ann"), store.read())
    }

    @Test fun corruptFileWithoutPrevStartsEmptyButKeepsTheFile() {
        store.file.writeText("garbage")
        val r = store.load(now = 99)
        assertNull(r.data)
        assertEquals(Recovery("forge.corrupt-99.json", restored = false), r.recovery)
        assertEquals("garbage", File(dir, "forge.corrupt-99.json").readText())
    }

    @Test fun emptyFileCountsAsCorrupt() {
        store.file.writeText("")
        assertNotNull(store.load(now = 5).recovery)
    }

    @Test fun incompatibleFieldIsCorruptToo() {
        store.file.writeText("""{"history": "oops"}""")
        assertNotNull(store.load(now = 6).recovery)
        assertEquals(1, corruptFiles().size)
    }

    @Test fun corruptPrevIsNotTrusted() {
        File(dir, "forge.prev.json").writeText("junk")
        store.file.writeText("junk too")
        val r = store.load(now = 7)
        assertNull(r.data); assertFalse(r.recovery!!.restored)
    }

    @Test fun noticeSurvivesARestartUntilDismissed() {
        store.file.writeText("garbage")
        store.load(now = 8)
        val again = ForgeStore(dir).load()      // a new process
        assertEquals(Recovery("forge.corrupt-8.json", restored = false), again.recovery)
        ForgeStore(dir).dismissRecovery()
        assertNull(ForgeStore(dir).load().recovery)
    }

    @Test fun repeatedFailuresKeepEveryCorruptFile() {
        store.file.writeText("a"); store.load(now = 1)
        store.file.writeText("b"); store.load(now = 2)
        assertEquals(2, corruptFiles().size)
    }
}

/** Phase 3.1: the weekly export's pure rules. The folder writing itself is checked on a phone. */
class AutoBackupTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun dueWhenNeverRun() = assertTrue(AutoBackup.due(0, 20_000 * day))
    @Test fun notDueWithinAWeek() = assertFalse(AutoBackup.due(10 * day, 10 * day + 6 * day + day / 2))
    @Test fun dueAfterAWeek() = assertTrue(AutoBackup.due(10 * day, 17 * day))

    @Test fun fileNameIsDatedAndSortable() {
        assertEquals("press-auto-backup-2026-11-13.json", AutoBackup.fileName(java.time.LocalDate.of(2026, 11, 13)))
    }

    @Test fun pruneKeepsNewestEightAndOnlyOurFiles() {
        val ours = (1..11).map { "press-auto-backup-2026-01-%02d.json".format(it) }
        val other = listOf("press-backup-2026-01-01.json", "notes.txt")
        val doomed = AutoBackup.prune((ours + other).shuffled(), keep = 8)
        assertEquals(ours.take(3).toSet(), doomed.toSet())
    }

    @Test fun pruneDoesNothingUnderTheLimit() = assertTrue(AutoBackup.prune(listOf("press-auto-backup-2026-01-01.json"), 8).isEmpty())
}
