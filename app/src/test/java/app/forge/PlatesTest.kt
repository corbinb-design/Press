package app.forge

import app.forge.data.*
import org.junit.Assert.*
import org.junit.Test

class PlatesTest {
    @Test fun `100 kg on a 20 kg bar is 25 + 15 per side`() {
        assertEquals(listOf(25.0, 15.0), Plates.perSide(100.0, WeightUnit.KG)?.plates)
    }

    @Test fun `awkward totals use the small plates`() {
        assertEquals(listOf(25.0, 15.0, 2.5, 1.25), Plates.perSide(107.5, WeightUnit.KG)?.plates)
    }

    @Test fun `the empty bar has no plates`() {
        assertEquals(emptyList<Double>(), Plates.perSide(20.0, WeightUnit.KG)?.plates)
    }

    @Test fun `lighter than the bar has no answer`() {
        assertNull(Plates.perSide(15.0, WeightUnit.KG))
        assertNull(Plates.perSide(0.0, WeightUnit.KG))
    }

    @Test fun `a load the plates cannot make reports what is left over`() {
        val r = Plates.perSide(21.0, WeightUnit.KG)!!
        assertEquals(0.5, r.leftOverPerSide, 1e-9)
    }

    @Test fun `pounds use a 45 bar`() {
        assertEquals(listOf(45.0, 25.0), Plates.perSide(185.0, WeightUnit.LB)?.plates)
        assertEquals(listOf(45.0), Plates.perSide(135.0, WeightUnit.LB)?.plates)
    }

    @Test fun `warm-ups ramp up to the working weight and stop below it`() {
        val w = Plates.warmups(100.0, WeightUnit.KG)
        assertEquals(listOf(20.0 to 10, 40.0 to 5, 60.0 to 3, 80.0 to 2), w.map { it.weight to it.reps })
        assertTrue(w.all { it.weight < 100.0 })
    }

    @Test fun `a light working weight gets fewer warm-ups`() {
        val w = Plates.warmups(40.0, WeightUnit.KG)
        assertEquals(20.0, w.first().weight, 0.0)
        assertTrue(w.size < 4 && w.all { it.weight < 40.0 })
    }

    @Test fun `the bar alone or lighter needs no warm-up`() {
        assertTrue(Plates.warmups(20.0, WeightUnit.KG).isEmpty())
        assertTrue(Plates.warmups(0.0, WeightUnit.KG).isEmpty())
    }

    @Test fun `warm-up weights are loadable and strictly increasing`() {
        val w = Plates.warmups(143.0, WeightUnit.KG).map { it.weight }
        assertEquals(w.sorted().distinct(), w)
        w.forEach { assertEquals(0.0, ((it - 20.0) / 2) % 1.25, 1e-9) }
    }
}
