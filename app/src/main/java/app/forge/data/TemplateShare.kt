package app.forge.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * F: a template shared with another phone, no account or server: a small `.press` file (JSON) or the same
 * data in a QR code. Only the plan travels: exercises, sets, rep ranges, rest, progression rules, colour,
 * and the custom exercises it uses. Never history, weights lifted, profile or settings; a planned
 * lighter week (a weight from history) and a running variation stay behind too.
 *
 * Importing always adds a new template and never replaces anything.
 */
object TemplateShare {
    const val FORMAT = 1
    const val MIME = "application/vnd.press.template+json"
    const val EXTENSION = "press"
    private const val QR_PREFIX = "PRESS1:"
    /** Larger than any real template; anything bigger isn't one. */
    const val MAX_BYTES = 256 * 1024

    /** The file. [press] marks it as one and is its format version; [unit] is the sender's. */
    @Serializable
    data class PressFile(
        val press: Int = FORMAT,
        val name: String,
        val colorKey: String? = null,
        val unit: WeightUnit = WeightUnit.KG,
        val exercises: List<Item>,
        val custom: List<Custom> = emptyList(),
    )

    @Serializable
    data class Item(
        val exerciseId: String, val sets: Int, val repMin: Int, val repMax: Int, val restSec: Int? = null,
        val method: ProgressionMethod = ProgressionMethod.DOUBLE,
        val incrementKg: Double = Progression.DEFAULT_INCREMENT_KG,
        val suggestLighter: Boolean = true,
    )

    @Serializable
    data class Custom(
        val id: String, val name: String, val muscle: String, val equipment: Equipment,
        val tracking: Tracking = Tracking.WEIGHT_REPS,
        val secondary: List<String> = emptyList(), val aliases: List<String> = emptyList(), val cues: List<String> = emptyList(),
    )

    /** A file that can't be imported, with a message to show as it is. */
    class BadFile(message: String) : IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // ---- Sending ---------------------------------------------------------------------------------------

    fun from(t: Template, d: AppData): PressFile {
        val customIds = d.customExercises.associateBy { it.id }
        return PressFile(
            name = t.name, colorKey = t.colorKey, unit = d.settings.unit,
            exercises = t.exercises.map { te ->
                Item(te.exerciseId, te.sets, te.repMin, te.repMax, te.restSec, te.method, te.incrementKg, te.suggestLighter)
            },
            custom = t.exercises.mapNotNull { customIds[it.exerciseId] }.distinctBy { it.id }.map { e ->
                Custom(e.id, e.name, e.muscle, e.equipment, e.tracking, e.secondary, e.aliases, e.cues)
            },
        )
    }

    fun encode(f: PressFile): String = json.encodeToString(PressFile.serializer(), f)

    /** The same data for a QR code: compressed, in a QR-safe alphabet, with a prefix that says what it is. */
    fun qr(f: PressFile): String {
        val raw = encode(f).encodeToByteArray()
        val out = ByteArrayOutputStream()
        val z = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(raw); finish() }
        val buf = ByteArray(1024)
        while (!z.finished()) out.write(buf, 0, z.deflate(buf))
        z.end()
        return QR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    /** "Push Day" → "push-day.press". */
    fun fileName(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "template" } + ".$EXTENSION"

    // ---- Receiving -------------------------------------------------------------------------------------

    /** A `.press` file's text, or a scanned QR code. Throws [BadFile] with a message for anything else. */
    fun read(text: String): PressFile {
        val t = text.trim().removePrefix("﻿")
        return decode(if (t.startsWith(QR_PREFIX)) fromQr(t) else t)
    }

    private fun decode(text: String): PressFile {
        val f = runCatching { json.decodeFromString(PressFile.serializer(), text) }.getOrElse { throw BadFile("This isn't a Press template.") }
        if (f.press > FORMAT) throw BadFile("This template was made by a newer version of Press. Update Press to open it.")
        if (f.press < 1 || f.name.isBlank()) throw BadFile("This isn't a Press template.")
        if (f.exercises.isEmpty()) throw BadFile("This template has no exercises.")
        return f
    }

    private fun fromQr(s: String): String = runCatching {
        val bytes = Base64.getUrlDecoder().decode(s.removePrefix(QR_PREFIX))
        val z = Inflater().apply { setInput(bytes) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!z.finished()) {
            val n = z.inflate(buf)
            if (n == 0 && (z.needsInput() || z.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) break
        }
        z.end()
        out.toByteArray().decodeToString()
    }.getOrElse { throw BadFile("That QR code isn't a Press template.") }

    /** What an import will do: each exercise as it will read here, and what it couldn't place. */
    data class Preview(val name: String, val rows: List<Row>, val newExercises: Int, val unknown: Int)
    data class Row(val name: String, val detail: String, val isNew: Boolean)

    /**
     * Where each exercise id in [f] lands here: the library's own id, an existing exercise with the same
     * name (custom ones are matched by name before anything is created), or null for a custom exercise
     * that will be created. Ids that are neither in the library nor described in the file are left out.
     */
    private fun placements(d: AppData, f: PressFile): Map<String, String?> {
        val known = Library.exercises + d.customExercises
        val byName = known.associateBy { it.name.trim().lowercase() }
        val custom = f.custom.associateBy { it.id }
        return f.exercises.map { it.exerciseId }.distinct().mapNotNull { id ->
            val c = custom[id]
            when {
                c != null -> id to byName[c.name.trim().lowercase()]?.id
                Library.exercises.any { it.id == id } -> id to id
                else -> null
            }
        }.toMap()
    }

    fun preview(d: AppData, f: PressFile): Preview {
        val at = placements(d, f)
        val custom = f.custom.associateBy { it.id }
        val rows = f.exercises.filter { it.exerciseId in at }.map { item ->
            val placed = at[item.exerciseId]
            val name = placed?.let { Session.exercise(d, it).name } ?: custom.getValue(item.exerciseId).name
            Row(name, "${item.sets} × " + if (item.repMin == item.repMax) "${item.repMin}" else "${item.repMin}–${item.repMax}", isNew = placed == null)
        }
        return Preview(uniqueName(d, f.name), rows, rows.count { it.isNew }, f.exercises.count { it.exerciseId !in at })
    }

    /** "Push", or "Push (2)", "Push (3)"… when that name is taken. */
    fun uniqueName(d: AppData, name: String): String {
        val taken = d.templates.map { it.name.trim().lowercase() }.toSet()
        val base = name.trim().take(40)
        if (base.lowercase() !in taken) return base
        return (2..999).map { "$base ($it)" }.first { it.lowercase() !in taken }
    }

    /**
     * Adds [f] as a new template, creating only the custom exercises that don't exist here yet. Progression
     * increments are moved onto the receiver's plates when the units differ (5 lb → 2.5 kg). Returns the
     * new data and the template.
     */
    fun add(d: AppData, f: PressFile, nextId: () -> Long = ::newId): Pair<AppData, Template> {
        val at = placements(d, f)
        val created = f.custom.filter { it.id in at && at[it.id] == null }.associate { c ->
            c.id to Exercise(
                "custom_${nextId()}", c.name.trim().take(40), c.muscle, c.equipment, custom = true, tracking = c.tracking,
                secondary = c.secondary, aliases = c.aliases, cues = c.cues.take(3),
            )
        }
        val unit = d.settings.unit
        val exercises = f.exercises.filter { it.exerciseId in at }.map { i ->
            val id = at[i.exerciseId] ?: created.getValue(i.exerciseId).id
            val min = i.repMin.coerceIn(1, 100); val max = i.repMax.coerceIn(min, 100)
            TemplateExercise(
                id, sets = i.sets.coerceIn(1, 20), reps = min, repMin = min, repMax = max, restSec = i.restSec?.coerceIn(0, 600),
                method = i.method, incrementKg = increment(i.incrementKg, f.unit, unit), suggestLighter = i.suggestLighter,
            )
        }
        if (exercises.isEmpty()) throw BadFile("None of this template's exercises are in this version of Press.")
        val t = Library.withColor(Template(nextId(), uniqueName(d, f.name), exercises, f.colorKey?.takeIf { it in Library.templateColorKeys }))
        return d.copy(templates = d.templates + t, customExercises = d.customExercises + created.values) to t
    }

    /** An increment in kg from a sender in [from] units, as the nearest plate step in [to] units. */
    fun increment(kg: Double, from: WeightUnit, to: WeightUnit): Double {
        if (from == to || kg == Progression.DEFAULT_INCREMENT_KG) return kg
        return Progression.increments(to).map { Units.toKg(it, to) }.minBy { abs(it - kg) }
    }
}
