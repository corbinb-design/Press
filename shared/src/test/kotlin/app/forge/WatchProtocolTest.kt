package app.forge

import app.forge.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** docs/wear-design.md sections 2 and 3: the snapshot the phone sends and how a watch action changes the data. */
class WatchProtocolTest {
    private val now = 1_000_000L
    private fun set(id: Long, kg: Double? = 60.0, reps: Int? = 8, done: Boolean = false, skipped: Boolean = false) =
        SetEntry(id, weightKg = kg, reps = reps, done = done, skipped = skipped)

    private fun data(
        ends: Long? = null, total: Int = 0, unit: WeightUnit = WeightUnit.KG,
        entries: List<ExerciseEntry> = listOf(
            ExerciseEntry(1, "bench", listOf(set(11), set(12), set(13))),
            ExerciseEntry(2, "squat", listOf(set(21, kg = 100.0, reps = 5))),
        ),
    ) = AppData(
        active = Workout(5, "Push", null, 1_000, null, entries, restEndsAt = ends, restTotalSec = total),
        settings = Settings(autoRest = true, restSeconds = 90, unit = unit),
    )

    private fun act(type: WatchAction.Type, setId: Long? = 11, workoutId: Long = 5, w: Double? = null, r: Int? = null, v: Int = 1, id: String = "a") =
        WatchAction(v = v, actionId = id, workoutId = workoutId, setId = setId, type = type, weight = w, reps = r, sentAt = now)

    private fun AppData.sets(ex: Int = 0) = active!!.exercises[ex].sets

    // ---- snapshot ----
    @Test fun noActiveSessionGivesNoSnapshot() {
        assertNull(WatchProtocol.snapshot(AppData(), seq = 1))
    }

    @Test fun snapshotShowsTheNextSetAndPosition() {
        val s = WatchProtocol.snapshot(data(), seq = 7)!!
        assertEquals(7L, s.seq); assertEquals(5L, s.workoutId); assertEquals("Push", s.name); assertEquals(1_000L, s.startedAt)
        assertEquals(0, s.exIndex); assertEquals(2, s.exCount)
        val n = s.next!!
        assertEquals(11L, n.setId); assertEquals(60.0, n.weight, 0.0); assertEquals(8, n.reps)
        assertEquals(1, n.setNo); assertEquals(3, n.setCount); assertEquals("kg", s.unit)
        assertNull(s.restEndsAt)
    }

    @Test fun snapshotMovesOnAfterALogAndCarriesTheRest() {
        val d = Session.log(data(), 5, 11, now = now)
        val s = WatchProtocol.snapshot(d, seq = 2)!!
        assertEquals(12L, s.next!!.setId); assertEquals(2, s.next!!.setNo)
        assertEquals(now + 90_000, s.restEndsAt); assertEquals(90, s.restTotalSec)
    }

    @Test fun snapshotMovesToTheNextExerciseAndEndsWithNoNextSet() {
        var d = data()
        for (id in listOf(11L, 12, 13)) d = Session.log(d, 5, id, now = now)
        assertEquals(1, WatchProtocol.snapshot(d, 1)!!.exIndex)
        d = Session.log(d, 5, 21, now = now)
        assertNull(WatchProtocol.snapshot(d, 1)!!.next)
    }

    @Test fun weightsGoOutInKgAndOnlyTheUnitLabelFollowsTheSetting() {
        val s = WatchProtocol.snapshot(data(unit = WeightUnit.LB), 1)!!
        assertEquals("lb", s.unit); assertEquals(60.0, s.next!!.weight, 0.0)
        assertEquals(132.3, WatchProtocol.display(s.next!!.weight, s.unit), 0.05)
        assertEquals(60.0, WatchProtocol.display(60.0, "kg"), 0.0)
        assertEquals(60.0, WatchProtocol.display(60.0, "stone"), 0.0) // unknown label shows kg
    }

    @Test fun bodyweightSetIsFlagged() {
        val d = data(entries = listOf(ExerciseEntry(1, "pull-up", listOf(set(11, kg = null, reps = 6)), tracking = Tracking.BODYWEIGHT_REPS)))
        assertTrue(WatchProtocol.snapshot(d, 1)!!.next!!.bodyweight)
    }

    @Test fun snapshotRoundTripsThroughJsonUnderTheSizeLimit() {
        val s = WatchProtocol.snapshot(Session.log(data(), 5, 11, now = now), 3)!!
        val json = Json.encodeToString(WatchSnapshot.serializer(), s)
        assertEquals(s, Json.decodeFromString(WatchSnapshot.serializer(), json))
        assertTrue(json.length < 2_000)
    }

    // ---- apply ----
    @Test fun logLogsTheSetAndStartsTheRest() {
        val d = WatchProtocol.apply(data(), act(WatchAction.Type.LOG), now)
        assertTrue(d.sets()[0].done)
        assertEquals(now + 90_000, d.active!!.restEndsAt)
    }

    @Test fun loggingTwiceLogsOnce() {
        val once = WatchProtocol.apply(data(), act(WatchAction.Type.LOG), now)
        val twice = WatchProtocol.apply(once, act(WatchAction.Type.LOG, id = "b"), now + 5_000)
        assertSame(once, twice)
    }

    @Test fun logValuesTakesKgWhateverUnitThePhoneUses() {
        val d = WatchProtocol.apply(data(), act(WatchAction.Type.LOG_VALUES, w = 62.5, r = 6), now)
        assertEquals(62.5, d.sets()[0].weightKg!!, 0.001); assertEquals(6, d.sets()[0].reps); assertTrue(d.sets()[0].done)
        val lb = WatchProtocol.apply(data(unit = WeightUnit.LB), act(WatchAction.Type.LOG_VALUES, w = 62.5, r = 6), now)
        assertEquals(62.5, lb.sets()[0].weightKg!!, 0.001)
    }

    @Test fun changingTheDisplayUnitBetweenSnapshotAndActionDoesNotChangeTheLoggedWeight() {
        val kgPhone = data(unit = WeightUnit.KG)
        val snap = WatchProtocol.snapshot(kgPhone, 1)!!
        val sent = snap.next!!.weight + 2.5 // the watch stepper adds 2.5 kg to what it was shown
        val lbPhone = kgPhone.copy(settings = kgPhone.settings.copy(unit = WeightUnit.LB)) // user switched units meanwhile
        val d = WatchProtocol.apply(lbPhone, act(WatchAction.Type.LOG_VALUES, w = sent, r = 8), now)
        assertEquals(62.5, d.sets()[0].weightKg!!, 0.001)
        assertEquals("lb", WatchProtocol.snapshot(d, 2)!!.unit) // the next snapshot tells the watch to show lb
    }

    @Test fun logValuesWithoutRepsOrWeightFallsBackToWhatTheSetShows() {
        val d = WatchProtocol.apply(data(), act(WatchAction.Type.LOG_VALUES, w = null, r = 10), now)
        assertEquals(60.0, d.sets()[0].weightKg!!, 0.001); assertEquals(10, d.sets()[0].reps)
    }

    @Test fun aSetThatIsAlreadyLoggedOrSkippedIsLeftAlone() {
        val logged = Session.log(data(), 5, 11, now = now)
        assertSame(logged, WatchProtocol.apply(logged, act(WatchAction.Type.LOG_VALUES, w = 70.0, r = 1), now + 1))
        assertSame(logged, WatchProtocol.apply(logged, act(WatchAction.Type.SKIP_SET), now + 1))
    }

    @Test fun aWrongWorkoutOrUnknownSetChangesNothing() {
        val d = data()
        assertSame(d, WatchProtocol.apply(d, act(WatchAction.Type.LOG, workoutId = 99), now))
        assertSame(d, WatchProtocol.apply(d, act(WatchAction.Type.LOG, setId = 999), now))
        assertSame(d, WatchProtocol.apply(d, act(WatchAction.Type.LOG, setId = null), now))
        val none = AppData()
        assertSame(none, WatchProtocol.apply(none, act(WatchAction.Type.LOG), now))
    }

    @Test fun anUnknownVersionChangesNothing() {
        val d = data()
        assertSame(d, WatchProtocol.apply(d, act(WatchAction.Type.LOG, v = 2), now))
    }

    @Test fun addFifteenMovesTheRestEnd() {
        val d = data(ends = now + 30_000, total = 90)
        val r = WatchProtocol.apply(d, act(WatchAction.Type.ADD15, setId = null), now)
        assertEquals(now + 45_000, r.active!!.restEndsAt)
    }

    @Test fun addFifteenWithNoRestChangesNothing() {
        val d = data()
        assertSame(d, WatchProtocol.apply(d, act(WatchAction.Type.ADD15, setId = null), now))
    }

    @Test fun skipSetMarksTheSetSkippedNotDoneAndTheNextSetMovesOn() {
        val d = WatchProtocol.apply(data(), act(WatchAction.Type.SKIP_SET), now)
        assertTrue(d.sets()[0].skipped); assertFalse(d.sets()[0].done)
        assertEquals(12L, WatchProtocol.snapshot(d, 1)!!.next!!.setId)
        assertNull(d.active!!.restEndsAt) // skipping doesn't start a rest
    }

    @Test fun skipSetLeavesARunningRestAlone() {
        val d = data(ends = now + 30_000, total = 90)
        val r = WatchProtocol.apply(d, act(WatchAction.Type.SKIP_SET), now)
        assertTrue(r.sets()[0].skipped)
        assertEquals(now + 30_000, r.active!!.restEndsAt); assertEquals(90, r.active!!.restTotalSec)
    }

    @Test fun skipRestEndsTheRestLikeTheNotificationSkip() { // rest-design.md T10
        val d = data(ends = now + 30_000, total = 90)
        val r = WatchProtocol.apply(d, act(WatchAction.Type.SKIP_REST, setId = null), now)
        assertNull(r.active!!.restEndsAt)
        assertEquals(Session.skipRest(d), r)
        assertFalse(r.sets().any { it.skipped || it.done }) // no set was touched
    }

    @Test fun skipRestWhileOverEndsItToo() {
        val d = data(ends = now - 5_000, total = 90)
        assertNull(WatchProtocol.apply(d, act(WatchAction.Type.SKIP_REST, setId = null), now).active!!.restEndsAt)
    }

    @Test fun skipRestWithNoRestRunningChangesNothing() {
        val d = data()
        assertSame(d, WatchProtocol.apply(d, act(WatchAction.Type.SKIP_REST, setId = null), now))
    }

    @Test fun skipRestSentTwiceIsTheSameAsOnce() {
        val once = WatchProtocol.apply(data(ends = now + 30_000, total = 90), act(WatchAction.Type.SKIP_REST, setId = null), now)
        val twice = WatchProtocol.apply(once, act(WatchAction.Type.SKIP_REST, setId = null, id = "b"), now + 1_000)
        assertSame(once, twice)
    }

    @Test fun skipRestForAnotherWorkoutOrVersionChangesNothing() {
        val d = data(ends = now + 30_000, total = 90)
        assertSame(d, WatchProtocol.apply(d, act(WatchAction.Type.SKIP_REST, setId = null, workoutId = 99), now))
        assertSame(d, WatchProtocol.apply(d, act(WatchAction.Type.SKIP_REST, setId = null, v = 2), now))
    }

    @Test fun finishIsLeftToThePhoneSoApplyDoesNotTouchTheData() {
        val d = data()
        assertSame(d, WatchProtocol.apply(d, act(WatchAction.Type.FINISH, setId = null), now))
    }
}
