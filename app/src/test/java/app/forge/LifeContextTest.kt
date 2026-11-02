package app.forge

import app.forge.data.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalTime

/** Context layer, phase 1: Health Connect sessions → outings, the note's parse and fallback, check-in facts. */
class LifeContextTest {
    private val oct23 = LocalDate.of(2026, 10, 23)
    private val oct24 = LocalDate.of(2026, 10, 24)
    private var n = 0

    private fun at(d: LocalDate, hm: String) = d.atTime(LocalTime.parse(hm)).atZone(Weeks.zone).toInstant().toEpochMilli()
    private fun s(d: LocalDate, type: OutsideType, from: String, to: String, km: Double? = null, climb: Double? = null) =
        OutsideSession("r${++n}", type, at(d, from), at(d, to), distanceM = km?.times(1000), elevationGainM = climb)

    // ---- Outings ----------------------------------------------------------------------------

    @Test fun `23 Oct - split hike with walks either side is one hiking outing that keeps every record`() {
        val day = listOf(
            s(oct23, OutsideType.WALKING, "09:00", "09:15", km = 1.1),
            s(oct23, OutsideType.HIKING, "09:22", "10:29", km = 3.0, climb = 210.0),
            s(oct23, OutsideType.HIKING, "10:51", "13:35", km = 6.5, climb = 330.0),
            s(oct23, OutsideType.WALKING, "13:50", "14:07", km = 1.0),
        )
        val outings = LifeContext.outings(day.shuffled())
        assertEquals(1, outings.size)
        val o = outings.single()
        assertEquals(OutsideType.HIKING, o.type)
        assertEquals(4, o.sessions.size)
        assertEquals(day.map { it.id }, o.sessions.map { it.id })
        // Recorded time only, not the span: 15 + 67 + 164 + 17.
        assertEquals(263, o.minutes)
        assertEquals(11_600.0, o.distanceM!!, 0.001)
        assertEquals(540.0, o.elevationGainM!!, 0.001)
        assertEquals("Hike · 4h 23 · 11.6 km · 540 m · 4 sessions merged", LifeContext.describe(o))
    }

    @Test fun `a gap over 45 minutes starts a new outing, and days never merge`() {
        val outings = LifeContext.outings(listOf(
            s(oct23, OutsideType.WALKING, "08:00", "08:30"),
            s(oct23, OutsideType.WALKING, "09:15", "09:45"),  // 45 min gap: merged
            s(oct23, OutsideType.RUNNING, "10:31", "11:00"),  // 46 min gap: new outing
            s(oct24, OutsideType.RUNNING, "07:00", "07:30"),
        ))
        assertEquals(listOf(2, 1, 1), outings.map { it.sessions.size })
        assertEquals(listOf(oct23, oct23, oct24), outings.map { it.date })
    }

    @Test fun `24 Oct - Other at 3h57 is kept and described honestly, short Other is dropped, gym work isn't read`() {
        assertTrue(LifeContext.keep(OutsideType.OTHER, 237))
        assertTrue(LifeContext.keep(OutsideType.OTHER, 60))
        assertFalse(LifeContext.keep(OutsideType.OTHER, 59))
        assertTrue(LifeContext.keep(OutsideType.WALKING, 10))
        val o = LifeContext.outings(listOf(s(oct24, OutsideType.OTHER, "09:03", "13:00", km = 9.5, climb = 540.0))).single()
        assertEquals(OutsideType.OTHER, o.type)
        assertEquals("“Other” · 3h 57 · 9.5 km · 540 m", LifeContext.describe(o))

        assertEquals(OutsideType.HIKING, HealthConnectReader.typeOf(androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_HIKING))
        assertEquals(OutsideType.OTHER, HealthConnectReader.typeOf(androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT))
        assertNull(HealthConnectReader.typeOf(androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING))
        assertNull(HealthConnectReader.typeOf(androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
    }

    // ---- Note dates and merging ----------------------------------------------------------------

    private val scotland = LifeContext.outings(listOf(
        s(oct23.minusDays(1), OutsideType.HIKING, "09:00", "14:00"),
        s(oct23, OutsideType.HIKING, "09:22", "13:35"),
        s(oct24, OutsideType.OTHER, "09:03", "13:00"),
        s(oct24.plusDays(1), OutsideType.HIKING, "10:00", "15:00"),
        s(oct24.plusDays(3), OutsideType.WALKING, "12:00", "12:20"), // a walk to the shop after getting home
    ))

    @Test fun `the note's dates are the latest matching outings on the watch, Other included`() {
        val hiking = NoteActivity("Hiking", 4, Intensity.HIGH)
        val today = oct24.plusDays(3)
        assertEquals(listOf(oct23.minusDays(1), oct23, oct24, oct24.plusDays(1)), LifeContext.datesFor(hiking, scotland, today))
        assertEquals(listOf(oct24, oct24.plusDays(1)), LifeContext.datesFor(hiking.copy(days = 2), scotland, today))
    }

    @Test fun `without a match on the watch, the note covers the days up to today`() {
        val today = oct24.plusDays(3)
        assertEquals(listOf(today.minusDays(2), today.minusDays(1), today), LifeContext.datesFor(NoteActivity("Ill", 3, Intensity.LOW), scotland, today))
        // Outings older than the 14-day window don't count.
        assertEquals(listOf(today.plusDays(30)), LifeContext.datesFor(NoteActivity("Hiking", 1, Intensity.HIGH), scotland, today.plusDays(30)))
    }

    @Test fun `a confirmed note names the Other outing, the watch's numbers and records are untouched`() {
        val note = ContextNote(1, "Hiking in Scotland", listOf(NoteActivity("Hiking", 4, Intensity.HIGH, dates = listOf(oct24.toString()))), 0)
        val merged = LifeContext.withNotes(scotland, listOf(note))
        val sat = merged.single { it.date == oct24 }
        assertEquals(OutsideType.HIKING, sat.type)
        assertEquals(OutsideType.OTHER, sat.sessions.single().type)
        assertEquals(scotland.single { it.date == oct24 }.minutes, sat.minutes)
        assertEquals(scotland.filter { it.date != oct24 }, merged.filter { it.date != oct24 })
    }

    // ---- Gemini reply validation -------------------------------------------------------------------

    private val good = """
        {"activities":[{"type":"hiking","days":4,"intensity":"high","musclesLoaded":["Quads","Hamstrings","Glutes","Calves","Quads"],"detail":"heavy pack"}],
         "confidence":0.62,"question":"Did the pack load your back and core too?","questionMuscles":["Back","Core"]}
    """.trimIndent()

    @Test fun `a valid reply becomes chips in Press's own muscle names`() {
        val p = Notes.parse(good)
        val a = p.activities.single()
        assertEquals(NoteActivity("Hiking", 4, Intensity.HIGH, listOf("Quads", "Hamstrings", "Glutes", "Calves"), "heavy pack"), a)
        assertTrue(p.fromAi)
        assertTrue(p.unsure)
        assertEquals(listOf("Back", "Core"), p.questionMuscles)
    }

    @Test fun `anything off drops the whole reply`() {
        fun bad(json: String) = assertThrows(Notes.Invalid::class.java) { Notes.parse(json) }
        bad("not json")
        bad(good.replace("\"Calves\"", "\"arms\""))               // a muscle Press doesn't track
        bad(good.replace("\"Back\"", "\"Lats\""))                 // in the question too
        bad(good.replace("\"days\":4", "\"days\":0"))
        bad(good.replace("\"days\":4", "\"days\":15"))
        bad(good.replace("\"high\"", "\"extreme\""))
        bad(good.replace("0.62", "1.5"))
        bad("""{"activities":[],"confidence":0.9}""")
        bad("""{"activities":[{"type":"  ","days":1,"intensity":"low","musclesLoaded":[]}],"confidence":0.9}""")
    }

    @Test fun `no question means no question muscles`() {
        val p = Notes.parse("""{"activities":[{"type":"walking","days":1,"intensity":"low","musclesLoaded":[]}],"confidence":0.9,"questionMuscles":["Back"]}""")
        assertNull(p.question)
        assertTrue(p.questionMuscles.isEmpty())
        assertFalse(p.unsure)
    }

    @Test fun `the fallback guesses only type and days from the words, for you to finish`() {
        val p = Notes.manual("Hiking in Scotland, 4 days, heavy pack")
        assertFalse(p.fromAi)
        assertNull(p.confidence)
        assertEquals(NoteActivity("Hiking", 4, Intensity.MODERATE), p.activities.single())
        assertEquals(NoteActivity("Activity", 1, Intensity.MODERATE), Notes.manual("Moved house").activities.single())
    }

    @Test fun `the request is JSON only, schema-constrained, with Press's muscles as the enum`() {
        val r = Notes.request("Hiking in Scotland")
        val config = r["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
        val items = config["responseSchema"]!!.jsonObject["properties"]!!.jsonObject["activities"]!!.jsonObject["items"]!!.jsonObject
        val muscles = items["properties"]!!.jsonObject["musclesLoaded"]!!.jsonObject["items"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(Library.strengthMuscles, muscles)
        assertEquals(12, muscles.size)
        assertTrue(Notes.request("x".repeat(500)).toString().contains("x".repeat(Notes.MAX_CHARS) + "\""))
    }

    // ---- Check-in ----------------------------------------------------------------------------------

    @Test fun `check-in answers are facts with stable ids, a skip has none`() {
        val c = CheckIn(0, 7, SleepAnswer.POOR, Soreness.SOME, Energy.LOW)
        assertEquals(
            listOf(Fact("checkin.sleep", "You said: poor sleep"), Fact("checkin.soreness", "You said: some soreness"), Fact("checkin.energy", "You said: low energy")),
            LifeContext.facts(c),
        )
        val skip = CheckIn(1, 8)
        assertTrue(skip.skipped)
        assertTrue(LifeContext.facts(skip).isEmpty())
        assertEquals(skip, LifeContext.checkInFor(listOf(c, skip), 8))
        assertNull(LifeContext.checkInFor(listOf(c, skip), 9))
    }

    // ---- Storage --------------------------------------------------------------------------------

    @Test fun `files from before the context layer load, and notes and check-ins round-trip`() {
        val dir = Files.createTempDirectory("press").toFile()
        val store = ForgeStore(dir)
        val old = store.decode("""{"templates":[],"history":[],"seeded":true,"settings":{"name":"Corbin","onboarded":true}}""")
        assertTrue(old.contextNotes.isEmpty())
        assertTrue(old.checkIns.isEmpty())
        assertFalse(old.settings.healthSkipped)

        val d = old.copy(
            contextNotes = listOf(ContextNote(1, "Hiking", listOf(NoteActivity("Hiking", 4, Intensity.HIGH, listOf("Quads"), dates = listOf("2026-10-24"))), 5, parsedByAi = true)),
            checkIns = listOf(CheckIn(9, 3, SleepAnswer.POOR)),
        )
        store.write(d)
        assertEquals(d, store.read())
        dir.deleteRecursively()
    }

    @Test fun `a snapshot file round-trips`() {
        val snap = HealthSnapshot(1, listOf(ContextDay("2026-10-24", steps = 24_310, distanceM = 18_000.0, outsideSessions = listOf(s(oct24, OutsideType.OTHER, "09:03", "13:00", 9.5, 540.0)))))
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(snap, json.decodeFromString(HealthSnapshot.serializer(), json.encodeToString(HealthSnapshot.serializer(), snap)))
    }
}
