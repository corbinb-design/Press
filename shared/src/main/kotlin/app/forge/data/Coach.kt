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

/** One exercise's pick: an allowed action and the facts that justify it. */
@Serializable
data class Choice(val exerciseId: String, val actionId: ActionId, val reasonFactIds: List<String> = emptyList())

/** The coach's words, checked. [facts] are the ones it may cite, kept so evidence chips render later. */
@Serializable
data class CoachText(
    val headline: String,
    val summary: String,
    val choices: List<Choice> = emptyList(),
    val evidenceFactIds: List<String> = emptyList(),
    /** False: the deterministic template (no key, no network, or a reply that failed validation). */
    val fromAi: Boolean = false,
    val facts: List<Fact> = emptyList(),
) {
    fun fact(id: String): Fact? = facts.firstOrNull { it.id == id }
    val evidence: List<Fact> get() = evidenceFactIds.mapNotNull(::fact)
}

/** One change to the plan, before → after. Kotlin worked out every number. */
@Serializable
data class Tweak(
    val exerciseId: String, val action: ActionId,
    val fromKg: Double?, val toKg: Double?, val reps: Int?, val fromSets: Int, val toSets: Int,
    val reasonFactIds: List<String> = emptyList(),
) {
    /** False for a hold that keeps the weight and sets as they were: nothing to show as a change. */
    val changes: Boolean get() = fromKg != toKg || fromSets != toSets
}

enum class Decision { ACCEPTED, IGNORED }

/** The "Accept tweaks" banner for one session, cached so it's asked for once. [before] is kept for Undo. */
@Serializable
data class SessionCoach(
    val workoutId: Long,
    val state: ReadinessState,
    val partial: Boolean,
    val firstBack: Boolean,
    val text: CoachText,
    val tweaks: List<Tweak>,
    val decision: Decision? = null,
    val before: List<ExerciseEntry> = emptyList(),
)

/** The Sunday issue's AI headline and summary for one week (ISO key), cached. */
@Serializable
data class IssueCoach(val week: String, val text: CoachText)

/**
 * The coach call (Prompt B). The AI only picks an allowed action per exercise and writes the words; Kotlin checks
 * every pick and every cited fact, and anything off drops the whole reply for [template]. Pure: HTTP is in Gemini.kt.
 */
object Coach {
    const val MAX_HEADLINE_WORDS = 8
    const val MAX_SUMMARY_WORDS = 60

    val SYSTEM = """
        You write the training summary for a strength app.
        - You are given computed facts and, for each exercise, a list of allowed actions.
        - Choose one allowed action per exercise and cite the fact IDs that justify it.
        - Never invent numbers, weights, sets or facts, and never choose an action that isn't listed.
        - Plain, warm, specific British English. No hype, no pseudo-science, no medical claims.
        - Headline: max 8 words. Summary: max 60 words.
        - For a weekly issue there are no exercises: write the headline and summary, and cite evidence.
    """.trimIndent()

    // ---- Requests -----------------------------------------------------------------------------------

    /** The next session: readiness, the facts, and each exercise's allowed actions. Derived facts only. */
    fun sessionRequest(plan: Plan, name: (String) -> String): JsonObject = request(buildJsonObject {
        put("task", "session")
        putJsonObject("readiness") {
            put("state", plan.gate.readiness.state.name)
            put("partial", plan.gate.readiness.partial)
            put("firstSessionBack", plan.gate.firstBack)
        }
        putFacts(plan.facts)
        putJsonArray("exercises") {
            plan.exercises.filter { it.options.isNotEmpty() }.forEach { e ->
                addJsonObject {
                    put("exerciseId", e.exerciseId)
                    put("name", name(e.exerciseId))
                    putJsonArray("allowedActions") { e.allowed.forEach { add(it.name) } }
                }
            }
        }
    })

    /** The week's issue: facts only, no exercises. */
    fun issueRequest(facts: List<Fact>): JsonObject = request(buildJsonObject {
        put("task", "weekly_issue")
        putFacts(facts)
        putJsonArray("exercises") {}
    })

    private fun kotlinx.serialization.json.JsonObjectBuilder.putFacts(facts: List<Fact>) =
        putJsonArray("facts") { facts.forEach { f -> addJsonObject { put("id", f.id); put("text", f.text) } } }

    private fun request(payload: JsonObject): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", SYSTEM) } } }
        putJsonArray("contents") {
            addJsonObject { put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", payload.toString()) } } }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseSchema", schema)
            put("temperature", 0.3)
        }
    }

    val schema: JsonObject = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("headline") { put("type", "STRING") }
            putJsonObject("summary") { put("type", "STRING") }
            putJsonObject("choices") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("exerciseId") { put("type", "STRING") }
                        putJsonObject("actionId") { put("type", "STRING"); putJsonArray("enum") { ActionId.entries.forEach { add(it.name) } } }
                        putJsonObject("reasonFactIds") { put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") } }
                    }
                    putJsonArray("required") { add("exerciseId"); add("actionId"); add("reasonFactIds") }
                }
            }
            putJsonObject("evidenceFactIds") { put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") } }
        }
        putJsonArray("required") { add("headline"); add("summary"); add("choices"); add("evidenceFactIds") }
    }

    // ---- Validation ---------------------------------------------------------------------------------

    @Serializable private class Reply(val headline: String, val summary: String, val choices: List<RawChoice> = emptyList(), val evidenceFactIds: List<String> = emptyList())
    @Serializable private class RawChoice(val exerciseId: String, val actionId: String, val reasonFactIds: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** [badNumber]: the words used a number no fact has (shown to people as its own reason). */
    class Invalid(message: String, val badNumber: Boolean = false) : IllegalArgumentException(message)

    private fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    /**
     * Checks a reply. [allowed] is each exercise's allowed actions (empty for the weekly issue). Every exercise gets
     * exactly one allowed action, every cited id is in [facts], the word limits hold, and every number in the words
     * appears in some fact. Anything off throws [Invalid] and the whole reply is dropped.
     */
    fun parse(text: String, allowed: Map<String, List<ActionId>>, facts: List<Fact>): CoachText {
        val r = runCatching { json.decodeFromString(Reply.serializer(), text) }.getOrElse { throw Invalid("Not the expected JSON: ${it.message?.take(120)}") }
        val headline = r.headline.trim()
        val summary = r.summary.trim()
        if (headline.isEmpty() || words(headline) > MAX_HEADLINE_WORDS) throw Invalid("Headline is ${words(headline)} words")
        if (summary.isEmpty() || words(summary) > MAX_SUMMARY_WORDS) throw Invalid("Summary is ${words(summary)} words")
        val ids = facts.map { it.id }.toSet()
        val factText = facts.joinToString(" ") { it.text }.replace(",", "")
        Regex("""\d+(?:\.\d+)?""").findAll("$headline $summary".replace(",", "")).map { it.value }.firstOrNull { it !in factText }
            ?.let { throw Invalid("Number $it isn't in any fact", badNumber = true) }
        val choices = r.choices.map { c ->
            val action = ActionId.entries.firstOrNull { it.name == c.actionId } ?: throw Invalid("Unknown action ${c.actionId}")
            val ok = allowed[c.exerciseId] ?: throw Invalid("Unknown exercise ${c.exerciseId}")
            if (action !in ok) throw Invalid("${c.actionId} isn't allowed for ${c.exerciseId}")
            c.reasonFactIds.firstOrNull { it !in ids }?.let { throw Invalid("Unknown fact $it") }
            Choice(c.exerciseId, action, c.reasonFactIds.distinct())
        }
        r.evidenceFactIds.firstOrNull { it !in ids }?.let { throw Invalid("Unknown fact $it") }
        val picked = choices.groupingBy { it.exerciseId }.eachCount()
        allowed.filterValues { it.isNotEmpty() }.keys.firstOrNull { picked[it] != 1 }?.let { throw Invalid("$it needs exactly one choice, got ${picked[it] ?: 0}") }
        return CoachText(headline, summary, choices, r.evidenceFactIds.distinct(), fromAi = true, facts = facts)
    }

    fun allowed(plan: Plan): Map<String, List<ActionId>> = plan.exercises.filter { it.options.isNotEmpty() }.associate { it.exerciseId to it.allowed }

    // ---- The template -------------------------------------------------------------------------------

    /**
     * The deterministic session text: the most cautious-but-sensible allowed action per exercise (increase only when
     * the gate allows it), and "Short sleep, so we're holding your weights today."-style words from the reasons.
     */
    fun template(plan: Plan): CoachText {
        val g = plan.gate
        val reasons = g.readiness.reasons.map { it.id } + listOfNotNull("break.daysSinceGym".takeIf { g.firstBack })
        val choices = plan.exercises.filter { it.options.isNotEmpty() }.map { e ->
            val pick = listOf(ActionId.INCREASE_LOAD, ActionId.HOLD_WEIGHT, ActionId.REDUCE_LOAD_10, ActionId.DROP_ONE_SET).first { it in e.allowed }
            Choice(e.exerciseId, pick, reasons)
        }
        val lead = when (reasons.firstOrNull()) {
            "checkin.sleep", "sleep.watch" -> "Short sleep"
            "checkin.soreness" -> "You're very sore"
            "checkin.energy" -> "Low energy today"
            "readiness.recentLoad" -> "A big few days outside"
            "readiness.climb" -> "A lot of climbing lately"
            "break.daysSinceGym" -> "First session back"
            else -> null
        }
        val (headline, action) = when {
            g.readiness.state == ReadinessState.RED -> "A lighter day today." to "so today goes lighter."
            g.readiness.state == ReadinessState.AMBER || g.firstBack -> "Holding steady today." to "so we're holding your weights today."
            else -> "Good to go." to null
        }
        val summary = listOfNotNull(
            if (lead != null && action != null) "$lead, $action" else "The plan stands.",
            "Partial: no check-in, so this is from your watch alone.".takeIf { g.readiness.partial },
        ).joinToString(" ")
        return CoachText(headline, summary, choices, reasons, fromAi = false, facts = plan.facts)
    }

    /**
     * One accepted tweak on a live exercise: its unticked working sets take the new weight and reps, and dropping a set
     * removes the last unticked one (never the only one). The "Today's target" card is then answered.
     */
    fun apply(e: ExerciseEntry, t: Tweak): ExerciseEntry {
        fun open(s: SetEntry) = !s.done && !s.skipped && (s.type == SetType.NORMAL || s.type == SetType.FAILURE)
        var sets = e.sets.map { s -> if (open(s)) s.copy(weightKg = t.toKg ?: s.weightKg, reps = t.reps ?: s.reps) else s }
        if (t.toSets < t.fromSets && sets.count(::open) > 1) sets.indexOfLast(::open).let { last -> sets = sets.filterIndexed { i, _ -> i != last } }
        return e.copy(sets = sets, targetSeen = true)
    }

    /** The before → after changes the choices make. "Before" is what the progression engine would have done. */
    fun tweaks(plan: Plan, text: CoachText): List<Tweak> = text.choices.mapNotNull { c ->
        val e = plan.exercises.firstOrNull { it.exerciseId == c.exerciseId } ?: return@mapNotNull null
        val o = e.options.firstOrNull { it.action == c.actionId } ?: return@mapNotNull null
        if (o.weightKg == null && c.actionId != ActionId.DROP_ONE_SET) return@mapNotNull null // nothing to show yet (a baseline)
        Tweak(e.exerciseId, c.actionId, e.target?.weightKg, o.weightKg, o.reps, e.rule.sets, o.sets, c.reasonFactIds)
    }
}
