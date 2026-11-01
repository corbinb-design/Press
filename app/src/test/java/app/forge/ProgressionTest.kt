package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test

/** Section 10: double/linear progression suggestions, stalls and lighter weeks. */
class ProgressionTest {
    private val rdl = TemplateExercise("rdl", sets = 3, reps = 8, repMin = 8, repMax = 12)
    private var id = 0L
    private fun set(kg: Double?, reps: Int?, type: SetType = SetType.NORMAL, done: Boolean = true) = SetEntry(++id, type, kg, reps, done)
    private fun sets(kg: Double, vararg reps: Int) = reps.map { set(kg, it) }

    // ---- Double progression ----------------------------------------------------------------------

    @Test fun doubleLevelsUpWhenEverySetHitsTheTopAtOneWeight() {
        val t = Progression.target(rdl, sets(62.5, 12, 12, 12))!!
        assertEquals(Progression.Kind.LEVEL_UP, t.kind)
        assertEquals(65.0, t.weightKg!!, 0.001)
        assertEquals(8, t.reps)
        assertEquals(62.5, t.fromKg!!, 0.001)
        assertEquals(12, t.fromReps)
    }

    @Test fun doubleHoldsWhenOneSetFallsShort() {
        val t = Progression.target(rdl, sets(62.5, 12, 12, 11))!!
        assertEquals(Progression.Kind.HOLD, t.kind)
        assertEquals(62.5, t.weightKg!!, 0.001)
        assertEquals(12, t.reps) // one more on the weakest set, capped at the top of the range
    }

    @Test fun holdAsksForOneMoreRepOnTheWeakestSet() {
        val t = Progression.target(rdl, sets(60.0, 10, 10, 9))!!
        assertEquals(Progression.Kind.HOLD, t.kind)
        assertEquals(10, t.reps)
    }

    @Test fun holdNeverAsksForFewerThanTheBottomOfTheRange() {
        assertEquals(8, Progression.target(rdl, sets(60.0, 5, 5, 5))!!.reps)
    }

    @Test fun mixedWeightsDoNotLevelUp() {
        val t = Progression.target(rdl, sets(60.0, 12) + sets(62.5, 12, 12))!!
        assertEquals(Progression.Kind.HOLD, t.kind)
        assertEquals(62.5, t.weightKg!!, 0.001)
    }

    @Test fun warmUpsAndDropSetsAreIgnoredButFailureSetsCount() {
        val prev = listOf(set(40.0, 5, SetType.WARMUP)) + sets(62.5, 12, 12) + set(62.5, 12, SetType.FAILURE) + set(50.0, 6, SetType.DROP)
        assertEquals(Progression.Kind.LEVEL_UP, Progression.target(rdl, prev)!!.kind)
        val failedShort = sets(62.5, 12, 12) + set(62.5, 9, SetType.FAILURE)
        assertEquals(Progression.Kind.HOLD, Progression.target(rdl, failedShort)!!.kind)
    }

    @Test fun untickedSetsAreIgnored() {
        val prev = sets(62.5, 12, 12, 12) + set(62.5, 4, done = false)
        assertEquals(Progression.Kind.LEVEL_UP, Progression.target(rdl, prev)!!.kind)
    }

    @Test fun firstTimeIsABaseline() {
        val t = Progression.target(rdl, emptyList())!!
        assertEquals(Progression.Kind.BASELINE, t.kind)
        assertNull(t.weightKg)
    }

    @Test fun bodyweightNeverLevelsUpFromZero() {
        assertEquals(Progression.Kind.HOLD, Progression.target(rdl, sets(0.0, 12, 12, 12))!!.kind)
    }

    @Test fun customIncrementIsUsed() {
        assertEquals(63.75, Progression.target(rdl.copy(incrementKg = 1.25), sets(62.5, 12, 12, 12))!!.weightKg!!, 0.001)
        assertEquals(67.5, Progression.target(rdl.copy(incrementKg = 5.0), sets(62.5, 12, 12, 12))!!.weightKg!!, 0.001)
    }

    @Test fun poundUsersGetFivePoundsByDefault() {
        val start = Units.toKg(135.0, WeightUnit.LB)
        val t = Progression.target(rdl, sets(start, 12, 12, 12), WeightUnit.LB)!!
        assertEquals("140", Units.w(t.weightKg!!, WeightUnit.LB))
    }

    @Test fun oldFixedRepTemplatesProgressAtTheirReps() {
        // A pre-range "3 × 5" decodes as 5–5: five on every set moves the weight up.
        val squat = TemplateExercise("squat", 3, 5)
        val t = Progression.target(squat, sets(100.0, 5, 5, 5))!!
        assertEquals(Progression.Kind.LEVEL_UP, t.kind)
        assertEquals(102.5, t.weightKg!!, 0.001)
        assertEquals(5, t.reps)
    }

    // ---- Linear and manual ------------------------------------------------------------------------

    @Test fun linearAddsWeightOnceTheBottomIsHit() {
        val rule = rdl.copy(method = ProgressionMethod.LINEAR, repMax = 8)
        val t = Progression.target(rule, sets(60.0, 8, 8, 8))!!
        assertEquals(Progression.Kind.LEVEL_UP, t.kind)
        assertEquals(62.5, t.weightKg!!, 0.001)
        assertEquals(8, t.reps)
        assertEquals(Progression.Kind.HOLD, Progression.target(rule, sets(60.0, 8, 8, 7))!!.kind)
    }

    @Test fun manualSuggestsNothing() {
        assertNull(Progression.target(rdl.copy(method = ProgressionMethod.MANUAL), sets(62.5, 12, 12, 12)))
    }

    // ---- Lighter weeks ----------------------------------------------------------------------------

    @Test fun aPlannedLighterWeekOverridesTheRule() {
        val t = Progression.target(rdl.copy(lighterNextKg = 80.0), sets(90.0, 12, 12, 12))!!
        assertEquals(Progression.Kind.LIGHTER, t.kind)
        assertEquals(80.0, t.weightKg!!, 0.001)
        assertEquals(8, t.reps)
    }

    @Test fun lighterIsAboutTenPercentRoundedDownToTheIncrement() {
        assertEquals(80.0, Progression.lighterKg(rdl, sets(90.0, 7, 7, 7), WeightUnit.KG)!!, 0.001)
        assertEquals(90.0, Progression.lighterKg(rdl, sets(100.0, 5, 5), WeightUnit.KG)!!, 0.001)
        assertEquals(55.0, Progression.lighterKg(rdl.copy(incrementKg = 5.0), sets(62.5, 8), WeightUnit.KG)!!, 0.001)
        assertNull(Progression.lighterKg(rdl, sets(0.0, 10), WeightUnit.KG))
    }

    // ---- Stalls -----------------------------------------------------------------------------------

    private fun session(at: Long, kg: Double, reps: Int, ex: String = "squat") =
        Workout(at, "Legs", 1L, at, at + 1, listOf(ExerciseEntry(at, ex, sets(kg, reps, reps, reps))))

    /** Newest first, as stored. */
    private fun history(vararg kgReps: Pair<Double, Int>) = kgReps.mapIndexed { i, (kg, r) -> session((i + 1) * 1000L, kg, r) }.reversed()

    @Test fun threeSessionsWithoutBeatingTheBestIsAStall() {
        val h = history(90.0 to 8, 92.5 to 8, 92.5 to 7, 90.0 to 8, 92.5 to 8)
        val s = Progression.stall(h, "squat", null)!!
        assertEquals(Stats.e1rm(92.5, 8), s.bestE1rm, 0.001)
        assertEquals(3000L, s.windowStart)
    }

    @Test fun anyNewBestInTheWindowIsNotAStall() {
        assertNull(Progression.stall(history(90.0 to 8, 92.5 to 8, 92.5 to 7, 95.0 to 8, 92.5 to 8), "squat", null))
    }

    @Test fun twoFlatSessionsAreNotYetAStall() {
        assertNull(Progression.stall(history(90.0 to 8, 92.5 to 8, 92.5 to 7, 92.5 to 8), "squat", null))
    }

    @Test fun needsAnEarlierSessionToCompareWith() {
        assertNull(Progression.stall(history(92.5 to 8, 92.5 to 8, 92.5 to 8), "squat", null))
    }

    @Test fun onlySessionsAfterTheLastAnswerCount() {
        val h = history(90.0 to 8, 92.5 to 8, 92.5 to 7, 90.0 to 8, 92.5 to 8)
        assertNull(Progression.stall(h, "squat", since = 3000L)) // two sessions since "keep pushing"
        val later = listOf(session(6000L, 92.5, 8)) + h
        assertNotNull(Progression.stall(later, "squat", since = 3000L))
    }

    @Test fun stallsAreOfferedOnlyForTheSessionThatCompletesThem() {
        val h = history(90.0 to 8, 92.5 to 8, 92.5 to 7, 90.0 to 8, 92.5 to 8)
        val t = Template(1L, "Legs", listOf(TemplateExercise("squat", 3, 8)))
        assertEquals(listOf(0), Progression.stalls(t, h.first(), h).map { it.first })
        assertTrue(Progression.stalls(t, h[1], h).isEmpty())
        assertTrue(Progression.stalls(t.copy(exercises = listOf(TemplateExercise("squat", 3, 8, suggestLighter = false))), h.first(), h).isEmpty())
        assertTrue(Progression.stalls(t.copy(exercises = listOf(TemplateExercise("squat", 3, 8, lighterNextKg = 80.0))), h.first(), h).isEmpty())
    }

    // ---- After a session --------------------------------------------------------------------------

    @Test fun aLighterWeekDoneAtTheLighterWeightIsMarkedAndCleared() {
        val t = Template(1L, "Legs", listOf(TemplateExercise("squat", 3, 8, lighterNextKg = 80.0)))
        val (t2, w2) = Progression.afterSession(t, session(5000L, 80.0, 8))
        assertNull(t2.exercises[0].lighterNextKg)
        assertEquals(5000L, t2.exercises[0].stallHandledAt)
        assertTrue(w2.exercises[0].lighterWeek)
    }

    @Test fun keepingTheNormalWeightClearsThePlanWithoutXp() {
        val t = Template(1L, "Legs", listOf(TemplateExercise("squat", 3, 8, lighterNextKg = 80.0)))
        val (t2, w2) = Progression.afterSession(t, session(5000L, 92.5, 8))
        assertNull(t2.exercises[0].lighterNextKg)
        assertFalse(w2.exercises[0].lighterWeek)
    }

    @Test fun lighterWeeksEarnXp() {
        val w = session(5000L, 80.0, 8).let { it.copy(exercises = it.exercises.map { e -> e.copy(lighterWeek = true) }) }
        assertEquals(Xp.total(listOf(w.copy(exercises = w.exercises.map { it.copy(lighterWeek = false) })), 4) + Progression.LIGHTER_WEEK_XP, Xp.total(listOf(w), 4))
    }

    @Test fun aVariationStandsInForTwoWeeksThenEnds() {
        val rule = Progression.swapVariation(TemplateExercise("squat", 3, 8), "pause_squat", at = 0L)
        assertEquals("pause_squat", rule.effectiveId(1L))
        assertEquals("pause_squat", rule.effectiveId(13L * 24 * 3600 * 1000))
        assertEquals("squat", rule.effectiveId(14L * 24 * 3600 * 1000))
        val t = Template(1L, "Legs", listOf(rule))
        val (t2, _) = Progression.afterSession(t, session(15L * 24 * 3600 * 1000, 100.0, 5))
        assertNull(t2.exercises[0].variationId)
    }

    @Test fun aRunningVariationIsNotProposedAsATemplateSwap() {
        val rule = Progression.swapVariation(TemplateExercise("squat", 3, 8), "pause_squat", at = 0L)
        val t = Template(1L, "Legs", listOf(rule))
        assertTrue(TemplateDiff.changes(t, session(1000L, 80.0, 8, ex = "pause_squat")).isEmpty())
    }

    @Test fun variationsPreferTheHandPickedOne() {
        val all = Library.exercises
        assertEquals("pause_squat", Progression.variationFor("squat", all)!!.id)
        val curl = Progression.variationFor("leg_curl", all)!!
        assertEquals("Hamstrings", curl.muscle)
        assertNotEquals("leg_curl", curl.id)
    }

    // ---- Metrics lift page --------------------------------------------------------------------------

    private val stalled = history(90.0 to 8, 92.5 to 8, 92.5 to 7, 90.0 to 8, 92.5 to 8)
    private fun legs(vararg rules: TemplateExercise) = Template(1L, "Legs", rules.toList())

    @Test fun metricsShowsAStallForATemplateLift() {
        assertNotNull(Progression.liftStall(listOf(legs(TemplateExercise("squat", 3, 8))), stalled, "squat", 6000L))
        assertNull(Progression.liftStall(emptyList(), stalled, "squat", 6000L)) // in no template: nothing to change
        assertNull(Progression.liftStall(listOf(legs(TemplateExercise("squat", 3, 8, suggestLighter = false))), stalled, "squat", 6000L))
    }

    @Test fun answeringFromMetricsResetsTheCountEverywhere() {
        val templates = listOf(legs(TemplateExercise("squat", 3, 8)), Template(2L, "Full", listOf(TemplateExercise("squat", 3, 5))))
        val answered = templates.map { t -> t.copy(exercises = t.exercises.map { Progression.answer(it, Progression.Answer.KEEP, null, null, at = 5500L) }) }
        assertNull(Progression.liftStall(answered, stalled, "squat", 6000L))
        // Three more flat sessions bring it back.
        val later = listOf(6000L, 7000L, 8000L).map { session(it, 90.0, 8) }.reversed() + stalled
        assertNotNull(Progression.liftStall(answered, later, "squat", 9000L))
    }

    @Test fun aPlannedLighterWeekHidesTheTag() {
        val lighter = Progression.answer(TemplateExercise("squat", 3, 8), Progression.Answer.LIGHTER, 80.0, null, at = 5500L)
        assertEquals(80.0, lighter.lighterNextKg!!, 0.0)
        assertNull(Progression.liftStall(listOf(legs(lighter)), stalled, "squat", 6000L))
        val swapped = Progression.answer(TemplateExercise("squat", 3, 8), Progression.Answer.SWAP, null, "pause_squat", at = 5500L)
        assertNull(Progression.liftStall(listOf(legs(swapped)), stalled, "squat", 6000L))
    }
}
