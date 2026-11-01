package app.forge

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import app.forge.ui.ForgeApp
import app.forge.ui.Qr
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** F: sharing a template as a `.press` file or QR code, and adding one that was shared. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class TemplateShareTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var ids = 50_000L
    private val next = { ++ids }

    @Before fun clean() { app.filesDir.listFiles()?.forEach { it.delete() }; ForgeRepository.reset() }

    private val landmine = Exercise("custom_7", "Corner Press", "Shoulders", Equipment.BARBELL, custom = true, cues = listOf("Lean in"))
    private val sled = Exercise("custom_8", "Tyre Push", "Quads", Equipment.OTHER, custom = true, tracking = Tracking.DISTANCE_WEIGHT)

    /** The sender: a template with a library lift, two custom ones and history-derived extras that must stay behind. */
    private fun sender(unit: WeightUnit = WeightUnit.KG): Pair<AppData, Template> {
        val t = Template(1, "Push", listOf(
            TemplateExercise("bench", 4, 6, repMin = 5, repMax = 8, restSec = 150, method = ProgressionMethod.LINEAR, incrementKg = 5.0,
                lighterNextKg = 72.5, variationId = "pause_bench", variationUntil = 9_999_999_999_999L, stallHandledAt = 123L),
            TemplateExercise("custom_7", 3, 10, repMin = 10, repMax = 12, suggestLighter = false),
            TemplateExercise("custom_8", 3, 1),
        ), colorKey = "push")
        val history = listOf(Workout(9, "Push", 1, 0, 1, listOf(ExerciseEntry(10, "bench", listOf(SetEntry(11, weightKg = 80.0, reps = 6, done = true))))))
        val d = AppData(templates = listOf(t), history = history, customExercises = listOf(landmine, sled),
            settings = Settings(name = "Sender Name", unit = unit, bodyweightKg = 91.0), seeded = true)
        return d to t
    }

    private fun receiver(unit: WeightUnit = WeightUnit.KG, templates: List<Template> = emptyList(), custom: List<Exercise> = emptyList()) =
        AppData(templates = templates, customExercises = custom, settings = Settings(unit = unit), seeded = true)

    // ---- Round trip --------------------------------------------------------------------------------

    @Test fun roundTripKeepsThePlan() {
        val (d, t) = sender()
        val f = TemplateShare.read(TemplateShare.encode(TemplateShare.from(t, d)))
        val (after, added) = TemplateShare.add(receiver(), f, next)
        assertEquals("Push", added.name)
        assertEquals("push", added.colorKey)
        val bench = added.exercises[0]
        assertEquals("bench", bench.exerciseId)
        assertEquals(listOf(4, 5, 8, 150), listOf(bench.sets, bench.repMin, bench.repMax, bench.restSec))
        assertEquals(ProgressionMethod.LINEAR, bench.method)
        assertEquals(5.0, bench.incrementKg, 0.0)
        assertFalse(added.exercises[1].suggestLighter)
        assertEquals(listOf(10, 12), listOf(added.exercises[1].repMin, added.exercises[1].repMax))
        // Custom exercises arrive with what defines them.
        val made = after.customExercises.associateBy { it.name }
        assertEquals(listOf("Lean in"), made.getValue("Corner Press").cues)
        assertEquals(Tracking.DISTANCE_WEIGHT, made.getValue("Tyre Push").tracking)
        assertEquals(made.getValue("Corner Press").id, added.exercises[1].exerciseId)
    }

    @Test fun neverIncludesHistoryWeightsProfileOrSettings() {
        val (d, t) = sender()
        val text = TemplateShare.encode(TemplateShare.from(t, d))
        listOf("Sender Name", "91", "72.5", "80.0", "pause_bench", "lighterNextKg", "variation", "stallHandled", "history", "settings", "bodyweight")
            .forEach { assertFalse("leaked \"$it\": $text", it in text) }
        val (_, added) = TemplateShare.add(receiver(), TemplateShare.read(text), next)
        assertNull(added.exercises[0].lighterNextKg); assertNull(added.exercises[0].variationId); assertNull(added.exercises[0].stallHandledAt)
    }

    @Test fun qrRoundTrip_throughAnActualImage() {
        val (d, t) = sender()
        val f = TemplateShare.from(t, d)
        val payload = TemplateShare.qr(f)
        assertEquals(f, TemplateShare.read(payload))
        val bmp = Qr.bitmap(payload)!!
        assertEquals("read back from the drawn code", payload, Qr.read(bmp))
    }

    @Test fun aTemplateTooBigForAQrCodeStillShares() {
        val (d, _) = sender()
        // 120 custom exercises with names that don't compress: well past what one QR code holds.
        val many = (1..120).map { i -> Exercise("custom_x$i", java.util.UUID.nameUUIDFromBytes("$i".toByteArray()).toString(), "Other", Equipment.OTHER, custom = true) }
        val huge = Template(2, "Everything", many.map { TemplateExercise(it.id, 3, 8) })
        val big = d.copy(customExercises = many)
        assertNull(Qr.bitmap(TemplateShare.qr(TemplateShare.from(huge, big))))
        assertEquals(120, TemplateShare.read(TemplateShare.encode(TemplateShare.from(huge, big))).exercises.size)
    }

    // ---- Units ---------------------------------------------------------------------------------------

    @Test fun incrementsLandOnTheReceiversPlates() {
        val lb = WeightUnit.LB; val kg = WeightUnit.KG
        assertEquals("10 lb → 5 kg", 5.0, TemplateShare.increment(Units.toKg(10.0, lb), lb, kg), 0.001)
        assertEquals("5 lb → 2.5 kg", 2.5, TemplateShare.increment(Units.toKg(5.0, lb), lb, kg), 0.001)
        assertEquals("1.25 kg → 2.5 lb", Units.toKg(2.5, lb), TemplateShare.increment(1.25, kg, lb), 0.001)
        assertEquals("5 kg → 10 lb", Units.toKg(10.0, lb), TemplateShare.increment(5.0, kg, lb), 0.001)
        assertEquals("the default reads right in both", Progression.DEFAULT_INCREMENT_KG, TemplateShare.increment(Progression.DEFAULT_INCREMENT_KG, kg, lb), 0.0)
        assertEquals("same units: untouched", 1.25, TemplateShare.increment(1.25, kg, kg), 0.0)
    }

    @Test fun aPoundSendersTemplateArrivesInKilograms() {
        val (d, t) = sender(WeightUnit.LB)
        val lbRule = t.copy(exercises = t.exercises.mapIndexed { i, e -> if (i == 0) e.copy(incrementKg = Units.toKg(10.0, WeightUnit.LB)) else e })
        val (_, added) = TemplateShare.add(receiver(WeightUnit.KG), TemplateShare.from(lbRule, d), next)
        assertEquals(5.0, added.exercises[0].incrementKg, 0.001)
    }

    // ---- Adding, never replacing --------------------------------------------------------------------

    @Test fun duplicateNamesGetANumber() {
        val (d, t) = sender()
        val f = TemplateShare.from(t, d)
        var r = receiver(templates = listOf(Template(5, "Push", emptyList()), Template(6, "Legs", emptyList())))
        r = TemplateShare.add(r, f, next).first
        r = TemplateShare.add(r, f, next).first
        assertEquals(listOf("Push", "Legs", "Push (2)", "Push (3)"), r.templates.map { it.name })
        assertEquals("nothing replaced", emptyList<TemplateExercise>(), r.templates[0].exercises)
    }

    @Test fun customExercisesMatchByNameBeforeAnyAreCreated() {
        val (d, t) = sender()
        val mine = Exercise("custom_99", "corner press", "Shoulders", Equipment.BARBELL, custom = true)
        val (after, added) = TemplateShare.add(receiver(custom = listOf(mine)), TemplateShare.from(t, d), next)
        assertEquals("matched, not duplicated", "custom_99", added.exercises[1].exerciseId)
        assertEquals(listOf("corner press", "Tyre Push"), after.customExercises.map { it.name })
        assertNotEquals("a created one never reuses the sender's id", "custom_8", added.exercises[2].exerciseId)
        // A custom exercise named like a library one uses the library's.
        val named = TemplateShare.from(t, d.copy(customExercises = listOf(landmine.copy(name = "Bench Press"), sled)))
        assertEquals("bench", TemplateShare.add(receiver(), named, next).second.exercises[1].exerciseId)
    }

    @Test fun previewSaysWhatWillHappen() {
        val (d, t) = sender()
        val f = TemplateShare.from(t, d).let { it.copy(exercises = it.exercises + TemplateShare.Item("from_a_newer_press", 3, 8, 8)) }
        val p = TemplateShare.preview(receiver(templates = listOf(Template(5, "Push", emptyList()))), f)
        assertEquals("Push (2)", p.name)
        assertEquals(listOf("Bench Press" to "4 × 5–8", "Corner Press" to "3 × 10–12", "Tyre Push" to "3 × 1"), p.rows.map { it.name to it.detail })
        assertEquals(listOf(false, true, true), p.rows.map { it.isNew })
        assertEquals(2, p.newExercises)
        assertEquals(1, p.unknown)
    }

    // ---- Bad files ------------------------------------------------------------------------------------

    private fun error(text: String) = runCatching { TemplateShare.read(text) }.exceptionOrNull()!!.let { assertTrue(it is TemplateShare.BadFile); it.message }

    @Test fun badFilesSayWhy() {
        assertEquals("This isn't a Press template.", error("{}"))
        assertEquals("This isn't a Press template.", error("not json at all"))
        assertEquals("a backup isn't a template", "This isn't a Press template.", error(ForgeStore(app.filesDir).encode(sender().first)))
        assertEquals("This template has no exercises.", error("""{"press":1,"name":"Empty","exercises":[]}"""))
        assertEquals("This template was made by a newer version of Press. Update Press to open it.", error("""{"press":2,"name":"X","exercises":[{"exerciseId":"bench","sets":3,"repMin":8,"repMax":8}]}"""))
        assertEquals("That QR code isn't a Press template.", error("PRESS1:%%%not-base64"))
        // Nothing placeable: refused rather than adding an empty template.
        val unknown = TemplateShare.read("""{"press":1,"name":"X","exercises":[{"exerciseId":"nope","sets":3,"repMin":8,"repMax":8}]}""")
        assertEquals("None of this template's exercises are in this version of Press.", runCatching { TemplateShare.add(receiver(), unknown, next) }.exceptionOrNull()!!.message)
    }

    @Test fun aBadFileShowsAClearErrorInTheApp() {
        val vm = AppViewModel(app).apply { completeSetup("C", WeightUnit.KG, 4, Library.splits.first()) }
        compose.setContent { ForgeApp(vm) }
        vm.requestTemplateImport("{}")
        compose.waitForIdle()
        compose.onNodeWithText("Couldn't open this template").assertExists()
        compose.onNodeWithText("This isn't a Press template.").assertExists()
        val before = vm.data.value.templates
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(before, vm.data.value.templates)
    }

    @Test fun previewThenAdd() {
        val vm = AppViewModel(app).apply { completeSetup("C", WeightUnit.KG, 4, Library.splits.first()) }
        val (d, t) = sender()
        val count = vm.data.value.templates.size
        compose.setContent { ForgeApp(vm) }
        vm.requestTemplateImport(TemplateShare.encode(TemplateShare.from(t, d)))
        compose.waitForIdle()
        compose.onNodeWithText("SHARED TEMPLATE").assertExists()
        compose.onNodeWithText("Push (2)").assertExists() // the Push/Pull/Legs split already has a Push
        compose.onNodeWithText("2 custom exercises will be added to your library.").assertExists()
        compose.onNodeWithText("ADD TEMPLATE").performClick()
        compose.waitForIdle()
        assertEquals(count + 1, vm.data.value.templates.size)
        assertEquals("Push (2)", vm.data.value.templates.last().name)
        assertNull(vm.templateImport.value)
    }

    // ---- Opening a .press file -----------------------------------------------------------------------

    @Test fun openingAPressFileHandsItToTheApp() {
        val (d, t) = sender()
        val text = TemplateShare.encode(TemplateShare.from(t, d))
        val file = File(app.cacheDir, "push.press").apply { writeText(text) }
        val a = Robolectric.buildActivity(ImportTemplateActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.fromFile(file))).create().get()
        var started: Intent? = null
        repeat(50) { if (started == null) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle(); started = shadowOf(a).nextStartedActivity } }
        assertEquals(ForgeActivity::class.java.name, started!!.component!!.className)
        assertEquals(text, started!!.getStringExtra(ForgeActivity.EXTRA_TEMPLATE))
    }

    @Test fun sharingIsRememberedOnce() {
        val vm = AppViewModel(app).apply { completeSetup("C", WeightUnit.KG, 4, Library.splits.first()) }
        assertNull(vm.data.value.firstSharedAt)
        vm.markShared()
        val first = vm.data.value.firstSharedAt!!
        vm.markShared()
        assertEquals(first, vm.data.value.firstSharedAt)
    }

    @Test fun fileNames() {
        assertEquals("push-day.press", TemplateShare.fileName("Push Day"))
        assertEquals("upper-a.press", TemplateShare.fileName("  Upper / A! "))
        assertEquals("template.press", TemplateShare.fileName("💪"))
    }
}
