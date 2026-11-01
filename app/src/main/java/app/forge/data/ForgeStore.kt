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
