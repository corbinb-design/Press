package app.forge.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * "What happened" note → structured context (Prompt A in the brief). Gemini only turns words into chips;
 * Kotlin checks every field and you confirm before anything is saved. Pure: the HTTP call is in Gemini.kt.
 */
object Notes {
    const val MAX_CHARS = 140
    /** Below this the confirm screen shows the parse in amber, with the model's question. */
    const val SURE = 0.75

    val SYSTEM = """
        You convert a short note about physical activity outside the gym into structured JSON.
        - Only use what the note says.
        - If something is unclear, lower the confidence and add a short question. If the question is whether more muscles were loaded, list them in questionMuscles.
        - Muscles must come from this list: ${Library.strengthMuscles.joinToString(", ")}.
        - Intensity is low, moderate or high.
        - Put the words from the note that set the intensity or muscles in detail.
        - Never give training advice.
    """.trimIndent()

    /** The note, parsed and checked but not yet confirmed. [fromAi] is false for the fill-it-in-yourself fallback. */
    data class Parsed(
        val activities: List<NoteActivity>,
        val confidence: Double?,
        val question: String? = null,
        val questionMuscles: List<String> = emptyList(),
        val fromAi: Boolean = true,
    ) {
        val unsure: Boolean get() = confidence != null && confidence < SURE
    }

    /** The generateContent body: system instruction, the note, JSON only, constrained by the schema. */
    fun request(note: String): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", SYSTEM) } } }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", note.take(MAX_CHARS)) } }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseSchema", schema)
            put("temperature", 0.2)
        }
    }

    private fun muscleArray() = buildJsonObject {
        put("type", "ARRAY")
        putJsonObject("items") { put("type", "STRING"); putJsonArray("enum") { Library.strengthMuscles.forEach { add(it) } } }
    }

    /** {activities:[{type, days, intensity, musclesLoaded, detail}], confidence, question?, questionMuscles?} */
    val schema: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("activities") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("type") { put("type", "STRING") }
                        putJsonObject("days") { put("type", "INTEGER") }
                        putJsonObject("intensity") { put("type", "STRING"); putJsonArray("enum") { add("low"); add("moderate"); add("high") } }
                        put("musclesLoaded", muscleArray())
                        putJsonObject("detail") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("type"); add("days"); add("intensity"); add("musclesLoaded") }
                }
            }
            putJsonObject("confidence") { put("type", "NUMBER") }
            putJsonObject("question") { put("type", "STRING") }
            put("questionMuscles", muscleArray())
        }
        putJsonArray("required") { add("activities"); add("confidence") }
    }

    @Serializable private class Reply(val activities: List<Act>, val confidence: Double, val question: String? = null, val questionMuscles: List<String> = emptyList())
    @Serializable private class Act(val type: String, val days: Int, val intensity: String, val musclesLoaded: List<String>, val detail: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    class Invalid(message: String) : IllegalArgumentException(message)

    /**
     * Checks a reply from Gemini. Anything off (bad JSON, a muscle Press doesn't track, days out of range, no
     * activities) throws [Invalid] and the whole reply is dropped; the caller falls back to [manual].
     */
    fun parse(text: String): Parsed {
        val r = runCatching { json.decodeFromString(Reply.serializer(), text) }.getOrElse { throw Invalid("Not the expected JSON: ${it.message?.take(120)}") }
        if (r.activities.isEmpty()) throw Invalid("No activities")
        if (r.confidence !in 0.0..1.0) throw Invalid("Confidence ${r.confidence} is outside 0–1")
        val known = Library.strengthMuscles.toSet()
        (r.activities.flatMap { it.musclesLoaded } + r.questionMuscles).firstOrNull { it !in known }?.let { throw Invalid("Unknown muscle \"$it\"") }
        val activities = r.activities.map { a ->
            val type = a.type.trim()
            if (type.isEmpty() || type.length > 40) throw Invalid("Activity type \"$type\"")
            if (a.days !in 1..LifeContext.DAYS.toInt()) throw Invalid("Days ${a.days} is outside 1–${LifeContext.DAYS}")
            val intensity = when (a.intensity.lowercase()) {
                "low" -> Intensity.LOW; "moderate" -> Intensity.MODERATE; "high" -> Intensity.HIGH
                else -> throw Invalid("Intensity \"${a.intensity}\"")
            }
            NoteActivity(type.replaceFirstChar { it.uppercase() }, a.days, intensity, a.musclesLoaded.distinct(), a.detail.trim().take(80))
        }
        val question = r.question?.trim()?.takeIf { it.isNotEmpty() }?.take(140)
        return Parsed(activities, r.confidence, question, if (question == null) emptyList() else r.questionMuscles.distinct())
    }

    /**
     * Fill-it-in-yourself: one activity guessed only from keywords in the note, no muscles, for you to set on
     * the confirm screen. Used when there's no key, no network, or the reply failed [parse].
     */
    fun manual(text: String): Parsed {
        val type = LifeContext.typeFromNote(text)?.noun?.replaceFirstChar { it.uppercase() } ?: "Activity"
        val days = Regex("""(\d{1,2})\s*days?""").find(text.lowercase())?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, LifeContext.DAYS.toInt()) ?: 1
        return Parsed(listOf(NoteActivity(type, days, Intensity.MODERATE)), confidence = null, fromAi = false)
    }
}
