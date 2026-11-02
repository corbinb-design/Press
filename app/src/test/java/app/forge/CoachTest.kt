package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test

/** Phases 3–4: the coach reply is checked field by field; anything off falls back to the template. */
class CoachTest {
    private val facts = listOf(
        Fact("checkin.sleep", "You said: poor sleep"),
        Fact("readiness.recentLoad", "8h 44 of hiking in the last 3 days"),
        Fact("break.daysSinceGym", "7 days since your last gym session"),
    )
    private fun gate(state: ReadinessState, days: Long = 7) =
        Gate(Readiness(state, if (state == ReadinessState.GREEN) 0 else -1, false, facts.take(2)), days)

    private val squat = TemplateExercise("squat", 4, 8, repMin = 6, repMax = 8)
    private val press = TemplateExercise("leg_press", 3, 10, repMin = 10, repMax = 12)
    private fun plan(state: ReadinessState, days: Long = 7): Plan {
        val g = gate(state, days)
        val up = Progression.Target(Progression.Kind.LEVEL_UP, 92.5, 6, 90.0, 8)
        val hold = Progression.Target(Progression.Kind.HOLD, 140.0, 11, 140.0, 10)
        return Plan(Template(3, "Legs", listOf(squat, press)), g, listOf(
            ExercisePlan("squat", squat, up, Rules.options(squat, up, Tracking.WEIGHT_REPS, g, WeightUnit.KG)),
            ExercisePlan("leg_press", press, hold, Rules.options(press, hold, Tracking.WEIGHT_REPS, g, WeightUnit.KG)),
        ), facts)
    }

    private fun reply(
        headline: String = "Legs had a big week outside.",
        summary: String = "Four days on the hills and a short night, so we're holding today. Back up next time.",
        squat: String = "HOLD_WEIGHT", press: String = "DROP_ONE_SET",
        reasons: String = "\"readiness.recentLoad\"", evidence: String = "\"checkin.sleep\"", extra: String = "",
    ) = """{"headline":"$headline","summary":"$summary","choices":[
        {"exerciseId":"squat","actionId":"$squat","reasonFactIds":[$reasons]},
        {"exerciseId":"leg_press","actionId":"$press","reasonFactIds":[$reasons]}$extra],
        "evidenceFactIds":[$evidence]}"""

    @Test fun `a valid reply keeps its words and picks, with the facts to show`() {
        val p = plan(ReadinessState.AMBER)
        val c = Coach.parse(reply(), Coach.allowed(p), p.facts)
        assertTrue(c.fromAi)
        assertEquals(listOf(Choice("squat", ActionId.HOLD_WEIGHT, listOf("readiness.recentLoad")), Choice("leg_press", ActionId.DROP_ONE_SET, listOf("readiness.recentLoad"))), c.choices)
        assertEquals(listOf(Fact("checkin.sleep", "You said: poor sleep")), c.evidence)
    }

    @Test fun `anything off drops the whole reply`() {
        val p = plan(ReadinessState.AMBER)
        fun bad(r: String) = assertThrows(Coach.Invalid::class.java) { Coach.parse(r, Coach.allowed(p), p.facts) }
        bad("not json")
        bad(reply(squat = "INCREASE_LOAD"))                        // not allowed on an amber day
        bad(reply(squat = "ADD_TEN_KG"))                           // not an action at all
        bad(reply(reasons = "\"lift.made.up\""))                   // a fact that doesn't exist
        bad(reply(evidence = "\"sleep.6h\""))
        bad(reply(extra = """,{"exerciseId":"bench","actionId":"HOLD_WEIGHT","reasonFactIds":[]}"""))  // not in the plan
        bad(reply(extra = """,{"exerciseId":"squat","actionId":"DROP_ONE_SET","reasonFactIds":[]}"""))  // two picks for one
        bad("""{"headline":"x","summary":"y","choices":[{"exerciseId":"squat","actionId":"HOLD_WEIGHT","reasonFactIds":[]}],"evidenceFactIds":[]}""") // leg press missing
        bad(reply(headline = "One two three four five six seven eight nine"))
        bad(reply(summary = (1..61).joinToString(" ") { "word" }))
        bad(reply(summary = "Drop to 80 kg today."))                // a number no fact has: invented
    }

    @Test fun `numbers that are in the facts are fine`() {
        val p = plan(ReadinessState.AMBER)
        assertTrue(Coach.parse(reply(summary = "Seven days off and 8h 44 on the hills: holding today."), Coach.allowed(p), p.facts).fromAi)
    }

    @Test fun `the weekly issue takes words and evidence only`() {
        val ok = """{"headline":"Two days in the hills.","summary":"A week outside, and the streak still counts.","choices":[],"evidenceFactIds":["readiness.recentLoad"]}"""
        assertTrue(Coach.parse(ok, emptyMap(), facts).fromAi)
        assertThrows(Coach.Invalid::class.java) {
            Coach.parse(ok.replace("\"choices\":[]", "\"choices\":[{\"exerciseId\":\"squat\",\"actionId\":\"HOLD_WEIGHT\",\"reasonFactIds\":[]}]"), emptyMap(), facts)
        }
    }

    @Test fun `the template - the most sensible allowed action, and plain words from the reasons`() {
        val amber = Coach.template(plan(ReadinessState.AMBER))
        assertFalse(amber.fromAi)
        assertEquals("Holding steady today.", amber.headline)
        assertEquals("Short sleep, so we're holding your weights today.", amber.summary)
        assertTrue(amber.choices.all { it.actionId == ActionId.HOLD_WEIGHT })
        assertEquals(listOf("checkin.sleep", "readiness.recentLoad", "break.daysSinceGym"), amber.evidenceFactIds)

        assertTrue(Coach.template(plan(ReadinessState.RED)).choices.all { it.actionId == ActionId.REDUCE_LOAD_10 })
        val green = Coach.template(plan(ReadinessState.GREEN, days = 2))
        assertEquals(ActionId.INCREASE_LOAD, green.choices.first { it.exerciseId == "squat" }.actionId)
        assertEquals(ActionId.HOLD_WEIGHT, green.choices.first { it.exerciseId == "leg_press" }.actionId)
    }

    @Test fun `tweaks show before and after, and Accept writes them into the open sets only`() {
        val p = plan(ReadinessState.RED)
        val t = Coach.tweaks(p, Coach.template(p))
        assertEquals(Tweak("squat", ActionId.REDUCE_LOAD_10, 92.5, 80.0, 8, 4, 4, Coach.template(p).choices[0].reasonFactIds), t[0])

        val drop = Tweak("leg_press", ActionId.DROP_ONE_SET, 140.0, 140.0, 11, 3, 2)
        val entry = ExerciseEntry(1, "leg_press", listOf(
            SetEntry(1, SetType.WARMUP, 60.0, 10), SetEntry(2, weightKg = 140.0, reps = 10, done = true), SetEntry(3), SetEntry(4),
        ))
        val after = Coach.apply(entry, drop)
        assertEquals(listOf(1L, 2L, 3L), after.sets.map { it.id })                 // the last open set went
        assertEquals(60.0, after.sets[0].weightKg!!, 0.0)                         // warm-up untouched
        assertEquals(10, after.sets[1].reps)                                       // a ticked set untouched
        assertEquals(SetEntry(3, weightKg = 140.0, reps = 11), after.sets[2])
        assertTrue(after.targetSeen)
        // Never drops the only open set.
        assertEquals(1, Coach.apply(ExerciseEntry(2, "x", listOf(SetEntry(9))), drop).sets.size)
    }
}
