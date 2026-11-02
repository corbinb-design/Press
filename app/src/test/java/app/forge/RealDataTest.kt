package app.forge

import app.forge.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * The rules on real data: the newest Health Connect snapshot ("press-hc-snapshot*.json", saved from Profile) and Press
 * backup ("press-backup*.json", Profile → Export data) in src/test/resources/private/. That folder is git-ignored: it's
 * personal data and never committed. Skipped cleanly when either file is missing. The committed fixture is synthetic
 * (RulesTest).
 */
class RealDataTest {
    private val dir = File("src/test/resources/private")
    private val week = LocalDate.of(2026, 10, 19) // Mon of the Scotland week, 2026-W43
    private val json = Json { ignoreUnknownKeys = true }

    private fun newest(prefix: String): File? = dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }?.maxByOrNull { it.name }

    private fun snapshot(): HealthSnapshot {
        val f = newest("press-hc-snapshot")
        assumeTrue("no press-hc-snapshot*.json in ${dir.absolutePath}", f != null)
        return json.decodeFromString(HealthSnapshot.serializer(), f!!.readText())
    }

    private fun backup(): AppData {
        val f = newest("press-backup")
        assumeTrue("no press-backup*.json in ${dir.absolutePath}", f != null)
        return ForgeStore(java.nio.file.Files.createTempDirectory("press").toFile()).decodeBackup(f!!.readText())
    }

    @Test fun `22-26 Oct as the merging rules read it`() {
        val snap = snapshot()
        println("SNAPSHOT read ${java.time.Instant.ofEpochMilli(snap.readAt)}, ${snap.days.size} days ${snap.days.first().date}..${snap.days.last().date}")
        val outings = LifeContext.outings(snap)
        (22..26).map { LocalDate.of(2026, 10, it) }.forEach { d ->
            val day = snap.days.firstOrNull { it.date == d.toString() }
            println("REPORT ${LifeContext.day(d)}: steps=${day?.steps ?: "-"} distance=${day?.distanceM?.let(LifeContext::km) ?: "-"} sleep=${day?.sleepMinutes?.let(LifeContext::duration) ?: "-"}")
            day?.outsideSessions?.forEach { s ->
                println("REPORT     raw ${s.type} ${LifeContext.duration(s.minutes)} ${Weeks.date(s.start)} ${java.time.Instant.ofEpochMilli(s.start).atZone(Weeks.zone).toLocalTime()}-${java.time.Instant.ofEpochMilli(s.end).atZone(Weeks.zone).toLocalTime()} " +
                    "dist=${s.distanceM?.let(LifeContext::km) ?: "-"} climb=${s.elevationGainM?.let { "%.0f m".format(it) } ?: "-"} src=${s.source}")
            }
            outings.filter { it.date == d }.forEach { println("REPORT   outing ${LifeContext.describe(it)}  counts=${it.minutes >= Outside.SESSION_MIN}") }
        }
        val days = Outside.summarise(snap, emptyList())
        println("REPORT W43 active=${Outside.activeWeek(days, week)} muscles=${Outside.musclesBetween(days, week, week.plusDays(6))}")
        Outside.movement(days, week)?.let { println("REPORT W43 movement steps=${it.steps} miles=${Units.num(it.miles)} km=${LifeContext.km(it.distanceM)} biggest=${it.biggest?.date} threshold=${it.threshold} high=${it.highDays.map { h -> h.date }}") }
        assertTrue("the Scotland week meets the outside rule", Outside.activeWeek(days, week))
        assertTrue("legs trained outside the gym", Outside.musclesBetween(days, week, week.plusDays(6)).keys.containsAll(Outside.LEGS))
    }

    @Test fun `the real history - Scotland keeps the streak, and the session after the trip caps at hold`() {
        val snap = snapshot()
        val d0 = backup()
        val today = Weeks.date(snap.readAt)
        val before = d0.copy(streak = Streaks.rebuild(d0.streak.copy(outsideWeeks = emptyList()), d0.history, d0.settings.weeklyGoal, today))
        val d = Outside.sync(before, snap, today, force = true)
        println("STREAK without context: weeks=${before.streak.weeks} repair=${before.streak.repair} | with: weeks=${d.streak.weeks} repair=${d.streak.repair} outsideWeeks=${d.streak.outsideWeeks}")
        assertTrue("2026-W43" in d.streak.outsideWeeks)
        assertTrue("the streak is no worse with context", d.streak.weeks >= before.streak.weeks)

        // The first session after the trip: Mon 26 Oct at 18:00, with history up to then.
        val after = LocalDate.of(2026, 10, 26).atTime(LocalTime.of(18, 0)).atZone(Weeks.zone).toInstant().toEpochMilli()
        val then = d.copy(history = d.history.filter { it.startedAt < after })
        val gate = Rules.gate(then, null, after)
        println("BACK 26 Oct: daysSinceGym=${gate.daysSinceGym} firstBack=${gate.firstBack} readiness=${gate.readiness}")
        assertFalse("no increase right after the trip", gate.canIncrease)

        // The next workout from the snapshot's read time, with no check-in yet.
        val next = Rules.nextUp(d) ?: return
        val plan = Rules.plan(d, next, null, snap.readAt)
        println("NEXT ${next.name} at ${java.time.Instant.ofEpochMilli(snap.readAt).atZone(Weeks.zone).toLocalDateTime()}: ${plan.gate.readiness.state} partial=${plan.gate.readiness.partial} score=${plan.gate.readiness.score} daysSinceGym=${plan.gate.daysSinceGym} firstBack=${plan.gate.firstBack}")
        plan.gate.readiness.reasons.forEach { println("NEXT   reason ${it.id}: ${it.text}") }
        plan.exercises.forEach { e ->
            println("NEXT   ${Session.exercise(d, e.exerciseId).name}: engine=${e.target?.kind} ${e.target?.weightKg}×${e.target?.reps} | " +
                e.options.joinToString { "${it.action} ${it.weightKg?.let { kg -> Units.w(kg, d.settings.unit) } ?: "-"}${d.settings.unit.label}×${it.reps}×${it.sets}" })
        }
        plan.facts.forEach { println("FACT   ${it.id}: ${it.text}") }
    }
}
