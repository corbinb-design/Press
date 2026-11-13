package app.forge.data

import java.time.LocalDate

/** Phase 3.1: the rules for the weekly export to a folder the user picked. The folder I/O lives in the view model. */
object AutoBackup {
    const val PERIOD_MS = 7L * 24 * 60 * 60 * 1000
    const val KEEP = 8
    private const val PREFIX = "press-auto-backup-"

    fun due(lastAt: Long, now: Long) = now - lastAt >= PERIOD_MS

    fun fileName(date: LocalDate) = "$PREFIX$date.json"

    /** Names of our own auto-backups beyond the newest [keep]; other files in the folder are never listed for deletion. */
    fun prune(names: List<String>, keep: Int = KEEP): List<String> =
        names.filter { it.startsWith(PREFIX) && it.endsWith(".json") }.sorted().dropLast(keep)
}
