package app.forge

import app.forge.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Phases 3–4 on real data (the git-ignored private folder; skipped without it). Prints the session banner and the
 * Sunday issue as the template writes them, then asks Gemini live (if this build has a key) and checks the reply
 * through the same validation the app uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealCoachTest {
    private val dir = File("src/test/resources/private")
    private fun newest(prefix: String) = dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }?.maxByOrNull { it.name }

    private fun data(): Pair<AppData, HealthSnapshot> {
        val s = newest("press-hc-snapshot"); val b = newest("press-backup")
        assumeTrue("private snapshot and backup needed", s != null && b != null)
        Gemini.offline = false // UI tests in the same JVM switch it on
        val snap = Json { ignoreUnknownKeys = true }.decodeFromString(HealthSnapshot.serializer(), s!!.readText())
        val d = ForgeStore(java.nio.file.Files.createTempDirectory("press").toFile()).decodeBackup(b!!.readText())
        return Outside.sync(d, snap, Weeks.date(snap.readAt), force = true) to snap
    }

    private fun show(label: String, d: AppData, plan: Plan, text: CoachText) {
        println("$label ${plan.gate.readiness.state} partial=${plan.gate.readiness.partial} firstBack=${plan.gate.firstBack} ai=${text.fromAi}")
        println("$label   \"${text.headline}\" ${text.summary}")
        println("$label   chips: ${(text.evidenceFactIds + text.choices.flatMap { it.reasonFactIds }).distinct().mapNotNull(text::fact).joinToString(" | ") { it.text }}")
        Coach.tweaks(plan, text).forEach { t ->
            println("$label   ${Session.exercise(d, t.exerciseId).name}: ${t.action} ${t.fromKg ?: "-"}→${t.toKg ?: "-"} kg, ${t.fromSets}→${t.toSets} sets, reps ${t.reps}")
        }
    }

    private fun live(plan: Plan, d: AppData): CoachText? = runBlocking {
        if (!Gemini.hasKey) return@runBlocking null
        val r = Gemini.generate(Coach.sessionRequest(plan) { Session.exercise(d, it).name }).mapCatching { Coach.parse(it, Coach.allowed(plan), plan.facts) }
        r.exceptionOrNull()?.let { println("LIVE   fell back to the template: ${it.message}") }
        r.getOrNull()
    }

    @Test fun `the banner right after the trip and for the next workout`() {
        val (d, snap) = data()
        // 26 Oct 18:00, first session back, with a poor-sleep check-in.
        val at = LocalDate.of(2026, 10, 26).atTime(LocalTime.of(18, 0)).atZone(Weeks.zone).toInstant().toEpochMilli()
        val then = d.copy(history = d.history.filter { it.startedAt < at })
        val next = Rules.nextUp(then)!!
        val back = Rules.plan(then, next, CheckIn(at, 1, SleepAnswer.POOR, Soreness.SOME, Energy.OK), at)
        show("BACK26", then, back, Coach.template(back))
        assertFalse(back.exercises.any { ActionId.INCREASE_LOAD in it.allowed })
        live(back, then)?.let { ai ->
            show("BACK26-AI", then, back, ai)
            ai.choices.forEach { c -> assertTrue(c.actionId in back.exercises.first { it.exerciseId == c.exerciseId }.allowed) }
        }

        val plan = Rules.plan(d, Rules.nextUp(d)!!, null, snap.readAt)
        show("NEXT", d, plan, Coach.template(plan))
        live(plan, d)?.let { show("NEXT-AI", d, plan, it) }
    }

    @Test fun `the Sunday issues for the Scotland week and the week after`() {
        val (d, _) = data()
        listOf(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26)).forEach { m ->
            val i = Issues.issue(d, m)
            val facts = Issues.facts(d, m)
            println("ISSUE ${Weeks.key(m)} No.${i.number} \"${i.headline}\" / ${i.standfirst}")
            println("ISSUE   ${i.paragraph}")
            println("ISSUE   outside: ${i.outside} | movement: ${i.movement} | streak ${i.streakWeeks} wk | next: ${i.nextWeek}")
            println("ISSUE   chips: ${Issues.templateEvidence(facts).joinToString(" | ") { id -> facts.first { it.id == id }.text }}")
            if (Gemini.hasKey) runBlocking {
                val r = Gemini.generate(Coach.issueRequest(facts)).mapCatching { Coach.parse(it, emptyMap(), facts) }
                println("ISSUE-AI ${r.getOrNull()?.let { "\"${it.headline}\" ${it.summary} | chips: ${it.evidence.joinToString(" | ") { f -> f.text }}" } ?: "fell back: ${r.exceptionOrNull()?.message}"}")
            }
        }
        assertTrue(Issues.issue(d, LocalDate.of(2026, 10, 19)).outsideActive)
    }
}
