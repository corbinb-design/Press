package app.forge.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Reads and writes forge.json. All writes go through [write]: a tmp file renamed over the real one. */
class ForgeStore(private val dir: File) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val file = File(dir, "forge.json")

    fun read(): AppData? = runCatching { decode(file.readText()) }.getOrNull()

    private val prevFile = File(dir, "forge.prev.json")
    private val noticeFile = File(dir, "forge.recovery.txt")

    /**
     * What the app starts from. A file that exists but can't be read is never overwritten: it is moved to
     * forge.corrupt-<time>.json, and the last good load (forge.prev.json) is restored if there is one.
     * [Loaded.recovery] stays set, across restarts, until [dismissRecovery].
     */
    @Synchronized fun load(now: Long = System.currentTimeMillis()): Loaded {
        if (!file.exists()) return Loaded(null, pendingRecovery())
        runCatching { decode(file.readText()) }.getOrNull()?.let { d ->
            // The file just read fine, so it becomes the fallback for the next bad one.
            runCatching {
                val tmp = File(dir, "forge.prev.json.tmp")
                file.copyTo(tmp, overwrite = true)
                Files.move(tmp.toPath(), prevFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return Loaded(d, pendingRecovery())
        }
        val name = "forge.corrupt-$now.json"
        val aside = File(dir, name)
        runCatching { Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { runCatching { file.copyTo(aside, overwrite = true) } }
        val prev = runCatching { decode(prevFile.readText()) }.getOrNull()
        val r = Recovery(name, restored = prev != null)
        runCatching { noticeFile.writeText("$name\n${r.restored}") }
        if (prev != null) runCatching { write(prev) }
        return Loaded(prev, r)
    }

    private fun pendingRecovery(): Recovery? = runCatching {
        val (name, restored) = noticeFile.readLines()
        Recovery(name, restored.toBoolean())
    }.getOrNull()

    fun dismissRecovery() { noticeFile.delete() }

    class Loaded(val data: AppData?, val recovery: Recovery?)

    fun encode(d: AppData): String = json.encodeToString(AppData.serializer(), d)
    fun decode(text: String): AppData {
        val el = json.parseToJsonElement(text)
        val d = json.decodeFromJsonElement(AppData.serializer(), el)
        // Migration: files (and backups) from before the first-launch screen have no "onboarded" key.
        // Those users are already set up and must never see it, even if they cleared their name.
        val hasFlag = el.jsonObject["settings"]?.jsonObject?.containsKey("onboarded") == true
        return if (hasFlag) d else d.copy(settings = d.settings.copy(onboarded = true))
    }

    /** Parses a backup file. Rejects JSON that isn't a Press (Forge) export (e.g. `{}`) rather than importing an empty app. */
    fun decodeBackup(text: String): AppData {
        val keys = json.parseToJsonElement(text).jsonObject.keys
        if ("history" !in keys && "templates" !in keys) throw NotABackupException()
        return decode(text)
    }

    class NotABackupException : IllegalArgumentException("This isn't a Press backup.")

    @Synchronized fun write(d: AppData) {
        val tmp = File(dir, "forge.json.tmp")
        tmp.writeText(encode(d))
        // Unlike File.renameTo, this replaces an existing file everywhere (incl. the JVM tests on Windows) and throws on failure.
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Keeps the current file as forge.before-import.json so one bad import can be undone. */
    @Synchronized fun backupBeforeImport() {
        if (file.exists()) file.copyTo(File(dir, "forge.before-import.json"), overwrite = true)
    }
}

/** forge.json couldn't be read at start-up; [corruptName] is where it was kept, [restored] says whether the last good copy was used. */
data class Recovery(val corruptName: String, val restored: Boolean)
