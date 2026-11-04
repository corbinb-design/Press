package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test

/** Final fix round B: "outside" lines are labelled by session type, not hiking-specific. */
class OutsideLinesTest {
    private fun outing(type: OutsideType, min: Int, km: Double? = null, climb: Double? = null) =
        OutingSummary(0, min * 60_000L, min, type, km?.times(1000), climb)

    @Test fun `kinds count each session type, most first`() {
        assertEquals("2 walks · 1 hike", LifeContext.kinds(listOf(OutsideType.HIKING, OutsideType.WALKING, OutsideType.WALKING)))
        assertEquals("1 ride", LifeContext.kinds(listOf(OutsideType.CYCLING)))
    }

    @Test fun `the outside line is general, labelled by the kinds`() {
        val days = listOf(
            OutsideDay("2026-10-23", outings = listOf(outing(OutsideType.WALKING, 70, 6.0, 40.0), outing(OutsideType.HIKING, 180, 11.4, 263.0))),
            OutsideDay("2026-10-24", outings = listOf(outing(OutsideType.WALKING, 90, 7.4, 256.0), outing(OutsideType.RUNNING, 20, 3.0))),
        )
        assertEquals("Outside the gym: 2 days · 24.8 km on the move · 559 m climbed", Issues.outsideLine(days))
        assertEquals("2 walks · 1 hike", Issues.outsideKinds(days))   // the 20-min run doesn't count
    }

    @Test fun `everyday movement says all walking, in km and miles`() {
        val sun = java.time.LocalDate.parse("2026-10-25")
        val days = (0L..6).map { OutsideDay(sun.minusDays(it).toString(), steps = if (it == 2L) 33_377 else 13_000, distanceM = 11_600.0) }
        val line = Issues.movementLine(days, sun.minusDays(6))!!
        assertEquals("You covered serious ground: 111,377 steps this week, all walking: 81.2 km (50.5 mi).", line)
    }
}

/** Home "Away from the gym": the outings line shows only what's new since the last note or dismissal. */
class AwayLineTest {
    @Test fun `only counted outings after the cutoff, within 14 days`() {
        val today = java.time.LocalDate.parse("2026-11-04")
        fun at(d: String, h: Int) = java.time.LocalDate.parse(d).atTime(h, 0).atZone(Weeks.zone).toInstant().toEpochMilli()
        fun o(d: String, type: OutsideType, min: Int) = OutingSummary(at(d, 9), at(d, 9) + min * 60_000L, min, type)
        val days = listOf(
            OutsideDay("2026-10-10", outings = listOf(o("2026-10-10", OutsideType.HIKING, 200))),   // older than 14 days
            OutsideDay("2026-11-01", outings = listOf(o("2026-11-01", OutsideType.HIKING, 120), o("2026-11-01", OutsideType.WALKING, 20))),
            OutsideDay("2026-11-03", outings = listOf(o("2026-11-03", OutsideType.HIKING, 90), o("2026-11-03", OutsideType.WALKING, 70))),
        )
        assertEquals("2 hikes · 1 walk", LifeContext.kinds(Outside.outingsSince(days, 0, today).map { it.type }))
        assertEquals("1 hike · 1 walk", LifeContext.kinds(Outside.outingsSince(days, at("2026-11-02", 12), today).map { it.type }))
        assertTrue(Outside.outingsSince(days, at("2026-11-04", 8), today).isEmpty())
    }
}
