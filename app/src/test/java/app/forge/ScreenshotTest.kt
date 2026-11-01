package app.forge

import app.forge.ui.components.SheetHost
import app.forge.ui.components.BottomSheet
import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import app.forge.ui.ForgeApp
import app.forge.ui.Nav
import app.forge.ui.screens.*
import app.forge.ui.theme.Forge
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One screenshot per Figma screen, at the Figma frame size (402×874), into app/build/outputs/roborazzi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nav = Nav({}, {}, {})

    private fun newUserVm(): AppViewModel { app.filesDir.resolve("forge.json").delete(); ForgeRepository.reset(); return AppViewModel(app) }

    private fun freshVm() = newUserVm().also { it.completeSetup("Corbin", WeightUnit.KG, 4, Library.splits.first()) }

    private fun demoVm() = freshVm().also { it.loadDemoHistory() }

    /** A Push session in progress with the first set logged. */
    private fun workoutVm() = demoVm().also { vm ->
        vm.startWorkout(vm.data.value.templates.first())
        vm.toggleDone(0, 0, Stats.previousSets(vm.data.value.history, vm.data.value.active!!.exercises[0].exerciseId).firstOrNull())
        vm.skipRest()
    }

    /** Renders [content] full-screen (dialogs included) and saves it. */
    private fun shot(name: String, content: @Composable BoxScope.() -> Unit) {
        compose.setContent { MaterialTheme { SheetHost { Box(Modifier.fillMaxSize().background(Forge.Bg)) { content() } } } }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
        assertEverythingTappableIsLabelled(name)
    }

    /** TalkBack: every element with a click action must have text or a content description to read. */
    private fun assertEverythingTappableIsLabelled(screen: String) {
        val unlabelled = compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes().filter { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).isNullOrEmpty() &&
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription).isNullOrEmpty() &&
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText).isNullOrEmpty()
        }
        org.junit.Assert.assertTrue(
            "$screen: ${unlabelled.size} tappable element(s) with nothing for TalkBack to read, at ${unlabelled.map { it.boundsInRoot }}",
            unlabelled.isEmpty(),
        )
    }

    // 01 Open & Home
    @Test fun s1_0_firstLaunch() { val vm = newUserVm(); shot("1.0_first_launch") { ForgeApp(vm) } }
    @Test fun s1_1_home() { val vm = demoVm(); shot("1.1_home") { ForgeApp(vm) } }
    @Test fun s1_2_homeNudge() {
        // One session today only, so the rest of the week gets tight.
        val vm = freshVm()
        vm.startWorkout(vm.data.value.templates.first())
        vm.setWeight(0, 0, 24.0); vm.setReps(0, 0, 8); vm.toggleDone(0, 0, null)
        vm.finishWorkout()
        shot("1.2_home_nudge") { ForgeApp(vm) }
    }

    // 03 Templates
    @Test fun s3_1_quickBuild() { val vm = demoVm(); shot("3.1_quick_build") { QuickBuildScreen(vm.data.value, vm, nav) } }
    @Test fun s3_2_editor() {
        val vm = demoVm()
        val draft = QuickBuild.build("Upper A", QuickBuild.Focus.UPPER, QuickBuild.Goal.MUSCLE, QuickBuild.Kit.GYM, 60)
        shot("3.2_template_editor") { TemplateEditor(draft, true, vm.data.value, vm, nav) }
    }
    @Test fun s3_3_picker() { val vm = demoVm(); shot("3.3_exercise_picker") { ExercisePicker(vm.data.value, vm, nav, false, null) {} } }

    // 04 Track
    @Test fun s4_1_workout() { val vm = workoutVm(); shot("4.1_active_workout") { ForgeApp(vm) } }
    @Test fun s4_2_stepper() {
        val vm = workoutVm()
        shot("4.2_adjust_set") {
            Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {} }
            SetStepperSheet(vm.data.value.active!!.exercises[0], 1, vm.data.value, vm, {}) { _, _ -> }
        }
    }
    @Test fun s4_3_prToast() {
        val vm = workoutVm()
        shot("4.3_live_pr") {
            WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {}
            Box(Modifier.align(Alignment.TopCenter).padding(horizontal = 24.dp, vertical = 8.dp)) {
                PrToast("NEW PR · INCLINE DUMBBELL PRESS", "37.5 kg × 10 — 2.5 kg over your best")
            }
        }
    }

    // 05 Finish & rewards
    @Test fun s5_1_finishCheck() {
        val vm = workoutVm()
        shot("5.1_finish_check") {
            WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {}
            FinishCheckSheet(vm.data.value.active!!, vm.data.value, vm, {}) {}
        }
    }
    @Test fun s5_2_summary() {
        val vm = demoVm()
        val h = vm.data.value.history
        shot("5.2_summary") { WorkoutSummary(h.first(), h.drop(1), vm.data.value, vm, {}) {} }
    }
    @Test fun s5_3_rewards() { val vm = demoVm(); shot("5.3_rewards") { Rewards(vm.data.value.history.first(), vm.data.value) {} } }
    @Test fun s5_4_updateTemplate() {
        val vm = demoVm()
        val t = vm.data.value.templates.first()
        val changes = listOf(
            TemplateDiff.Change.MoreSets(t.exercises[1].exerciseId, 4),
            TemplateDiff.Change.Swapped(4, t.exercises[4].exerciseId, "oh_ext"),
        )
        shot("5.4_update_template") {
            WorkoutSummary(vm.data.value.history.first(), vm.data.value.history.drop(1), vm.data.value, vm, {}) {}
            UpdateTemplateSheet(t, changes, vm, {}) {}
        }
    }

    // 06 History
    @Test fun s6_1_shelf() { val vm = demoVm(); shot("6.1_history_shelf") { HistoryScreen(vm.data.value, vm, nav) } }
    @Test fun s6_2_session() {
        val vm = demoVm()
        val w = vm.data.value.history.first()
        vm.setNote(w.id, "Grip went on the last set. Try straps.")
        shot("6.2_session_detail") { WorkoutDetail(w.id, vm.data.value, vm, nav) {} }
    }
    @Test fun s6_3_calendar() { val vm = demoVm(); shot("6.3_history_calendar") { HistoryScreen(vm.data.value, vm, nav, initialTab = 1) } }

    // 07 Metrics
    @Test fun s7_1_overview() { val vm = demoVm(); shot("7.1_metrics_overview") { MetricsScreen(vm.data.value, vm, nav) } }
    @Test fun s7_2_lift() { val vm = demoVm(); shot("7.2_lift_progress") { LiftDetail("bench", vm.data.value, vm, nav) } }
    @Test fun s7_2b_lifts() { val vm = demoVm(); shot("7.2b_metrics_lifts") { MetricsScreen(vm.data.value, vm, nav, initialTab = 1) } }
    @Test fun s7_3_balance() { val vm = demoVm(); shot("7.3_metrics_balance") { MetricsScreen(vm.data.value, vm, nav, initialTab = 2) } }
    @Test fun s7_4_cabinet() { val vm = demoVm(); shot("7.4_metrics_cabinet") { MetricsScreen(vm.data.value, vm, nav, initialTab = 3) } }

    // v1.1 · library, tracking types, form, cardio
    /** Demo data plus a template of [ids], with [past] logged a week ago, then a session of it started. */
    private fun sessionWith(name: String, ids: List<String>, past: List<ExerciseEntry>): AppViewModel {
        val d = demoVm().data.value
        val t = Template(9_001, name, ids.map { TemplateExercise.fresh(it) })
        val at = System.currentTimeMillis() - 6 * 86_400_000L
        val w = Workout(9_002, name, t.id, at, at + 3_000_000, past)
        ForgeStore(app.filesDir).write(d.copy(templates = d.templates + t, history = listOf(w) + d.history))
        ForgeRepository.reset()
        return AppViewModel(app).also { it.startWorkout(it.data.value.templates.first { x -> x.id == t.id }) }
    }

    @Test fun v11_pickerSearch() {
        val vm = demoVm()
        shot("v11_1_picker_search") { Box(Modifier.statusBarsPadding()) { ExercisePicker(vm.data.value, vm, nav, false, null, initialQuery = "pull down") {} } }
    }
    @Test fun v11_pickerTypo() {
        val vm = demoVm()
        shot("v11_1b_picker_typo_alias") { Box(Modifier.statusBarsPadding()) { ExercisePicker(vm.data.value, vm, nav, false, null, initialQuery = "romainan") {} } }
    }
    @Test fun v11_assistedSet() {
        val past = ExerciseEntry(9_003, "assisted_pullup", List(3) { SetEntry(9_010L + it, weightKg = 20.0, reps = 12, done = true) }, tracking = Tracking.ASSISTED)
        val vm = sessionWith("Pull", listOf("assisted_pullup"), listOf(past))
        shot("v11_2_assisted_set") { Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {} } }
    }
    @Test fun v11_assistedSetLogged() {
        val past = ExerciseEntry(9_003, "assisted_pullup", List(3) { SetEntry(9_010L + it, weightKg = 20.0, reps = 10, done = true) }, tracking = Tracking.ASSISTED)
        val vm = sessionWith("Pull", listOf("assisted_pullup"), listOf(past))
        vm.toggleDone(0, 0); vm.skipRest()
        shot("v11_2b_assisted_set_stepper") {
            Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {} }
            SetStepperSheet(vm.data.value.active!!.exercises[0], 1, vm.data.value, vm, {}) { _, _ -> }
        }
    }
    @Test fun v11_timedSet() {
        val past = ExerciseEntry(9_003, "plank", listOf(60, 55, 50).mapIndexed { i, s -> SetEntry(9_010L + i, weightKg = 0.0, durationSec = s, done = true) }, tracking = Tracking.DURATION)
        val vm = sessionWith("Core", listOf("plank", "farmers"), listOf(past))
        vm.toggleDone(0, 0); vm.skipRest()
        shot("v11_3_timed_set") {
            Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {} }
            SetStepperSheet(vm.data.value.active!!.exercises[0], 1, vm.data.value, vm, {}) { _, _ -> }
        }
    }
    @Test fun v11_formSection() {
        val vm = demoVm()
        shot("v11_4_form_section") { Box(Modifier.statusBarsPadding()) { LiftDetail("squat", vm.data.value, vm, nav, formOpen = true) } }
    }
    @Test fun v11_formSheet() {
        val vm = demoVm()
        shot("v11_4b_form_sheet") {
            Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.also { it.startWorkout(it.data.value.templates.first()) }.data.value.active!!, vm.data.value, vm, nav) {} }
            FormSheet(vm.exercise("incline_db")) {}
        }
    }
    @Test fun v11_cardioEntry() {
        val past = ExerciseEntry(9_003, "rower", listOf(SetEntry(9_010, durationSec = 1_200, distanceM = 4_600.0, level = 6, heartRate = 141, done = true)), tracking = Tracking.CARDIO)
        val vm = sessionWith("Row", listOf("rower"), listOf(past))
        shot("v11_5_cardio_entry") { Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {} } }
    }
    @Test fun v11_cardioSheet() {
        val vm = demoVm()
        vm.startCardio(listOf("rower"))
        shot("v11_5b_cardio_sheet") {
            Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {} }
            SetStepperSheet(vm.data.value.active!!.exercises[0], 0, vm.data.value, vm, {}) { _, _ -> }
        }
    }

    // v1.0.1 · skipped sets
    @Test fun v101_skippedSet() {
        val vm = workoutVm().also { it.skipSet(0, 2) }
        shot("v101_1_skipped_set") { ForgeApp(vm) }
    }
    @Test fun v101_summarySkipped() {
        val vm = workoutVm()
        val prior = vm.data.value.history
        vm.data.value.active!!.exercises.forEachIndexed { ex, e -> e.sets.indices.forEach { if (it < 2) vm.logSet(ex, it, 20.0, 8, false) } }
        vm.skipSet(0, 2)
        val saved = vm.finishWorkout()!!
        shot("v101_2_summary_skipped") { WorkoutSummary(saved, prior, vm.data.value, vm, {}) {} }
    }

    /** Six weeks of assisted pull-ups, from 30 kg of help down to 15. */
    private fun assistedVm(): AppViewModel {
        val d = demoVm().data.value
        val history = (0 until 6).map { i ->
            val at = System.currentTimeMillis() - (6 - i) * 7 * 86_400_000L
            Workout(9_100L + i, "Pull", null, at, at + 3_000_000, listOf(
                ExerciseEntry(9_200L + i, "pullup", List(3) { s -> SetEntry(9_300L + i * 10 + s, weightKg = 30.0 - i * 3, reps = 8, done = true) }, tracking = Tracking.ASSISTED),
            ))
        }
        ForgeStore(app.filesDir).write(d.copy(history = (history + d.history).sortedByDescending { it.startedAt }, trackingModes = mapOf("pullup" to Tracking.ASSISTED)))
        ForgeRepository.reset()
        return AppViewModel(app)
    }

    @Test fun v101_assistedLift() { val vm = assistedVm(); shot("v101_3_assisted_lift") { Box(Modifier.statusBarsPadding()) { LiftDetail("pullup", vm.data.value, vm, nav) } } }
    @Test fun v101_assistSwitch() {
        val vm = assistedVm().also { it.startWorkout(it.data.value.templates.first { t -> t.name == "Pull" }) }
        shot("v101_4_assist_switch") {
            Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {} }
            BottomSheet({}) { AssistSwitch(vm.exercise("pullup"), vm.data.value, vm) }
        }
    }
    @Test fun v101_unassisted() {
        val vm = assistedVm().also { it.startWorkout(it.data.value.templates.first { t -> t.name == "Pull" }) }
        shot("v101_5_unassisted") {
            Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {} }
            UnassistedSheet(vm.exercise("pullup"), {}) {}
        }
    }

    @Test fun v101_lockEdit() {
        val vm = workoutVm()
        shot("v101_6_lock_edit") { LockEditScreen(vm.data.value, Session.next(vm.data.value)!!, { _, _ -> }, {}, {}) }
    }

    @Test fun v101_shareTemplate() {
        val vm = demoVm()
        shot("v101_7_share_template") { HomeScreen(vm.data.value, vm, nav, {}); ShareTemplateSheet(vm.data.value.templates.first(), vm.data.value, vm) {} }
    }
    @Test fun v101_importPreview() {
        val vm = demoVm()
        val custom = Exercise("custom_1", "Corner Press", "Shoulders", Equipment.BARBELL, custom = true)
        val t = vm.data.value.templates.first().let { it.copy(exercises = it.exercises + TemplateExercise("custom_1", 3, 10, repMin = 10, repMax = 12)) }
        val text = TemplateShare.encode(TemplateShare.from(t, vm.data.value.copy(customExercises = listOf(custom))))
        shot("v101_8_import_preview") { HomeScreen(vm.data.value, vm, nav, {}); TemplateImportPreview(text, vm.data.value, vm) {} }
    }

    /** The second shelf, all earned, to see every cover. */
    private fun newCovers() = Cabinet.entries(AppData(settings = Settings(bodyweightKg = 80.0))).filter { it.no > 9 }.map { it.copy(earnedOn = java.time.LocalDate.of(2026, 11, 1)) }
    @Test fun v101_newCovers() { val all = newCovers(); shot("v101_9_new_covers") { Box(Modifier.statusBarsPadding().padding(horizontal = 24.dp)) { CabinetGrid(all.take(12)) } } }
    @Test fun v101_newCovers2() { val all = newCovers(); shot("v101_9b_new_covers") { Box(Modifier.statusBarsPadding().padding(horizontal = 24.dp)) { CabinetGrid(all.drop(12)) } } }

    // 12 The Sunday issue
    @Test fun s12_2_issue() {
        val vm = demoVm()
        val key = Issues.latest(vm.data.value, java.time.LocalDateTime.now(Weeks.zone))!!.key
        shot("12.2_sunday_issue") { Box(Modifier.statusBarsPadding()) { IssueScreen(key, vm.data.value, nav) } }
    }
    @Test fun s12_2b_archive() { val vm = demoVm(); shot("12.2b_issue_archive") { Box(Modifier.statusBarsPadding()) { IssuesScreen(vm.data.value, nav) } } }

    // 13 Honest XP
    @Test fun s13_1_howXp() { val vm = demoVm(); shot("13.1_how_xp_works") { Box(Modifier.statusBarsPadding()) { HowXpScreen(vm.data.value, nav) } } }
    @Test fun s13_2_unlocks() { val vm = demoVm(); shot("13.2_unlocks") { Box(Modifier.statusBarsPadding()) { UnlocksScreen(vm.data.value, vm, null) } } }
    @Test fun s13_3_levelUp() {
        shot("13.3_level_up") {
            Box(Modifier.statusBarsPadding()) {
                LevelUpScreen(4_000, newTier = true, Unlocks.New(listOf(Unlocks.Icon.PAPER), listOf(Unlocks.Spine.LINEN)), {}, {})
            }
        }
    }

    // 02 Streaks: demo data with the streak state replaced, written to disk and loaded fresh.
    private fun streakVm(transform: (StreakState, AppData) -> StreakState): AppViewModel {
        val d = demoVm().data.value
        val today = Weeks.today()
        val lastWeek = Weeks.key(Weeks.monday(today).minusWeeks(1))
        ForgeStore(app.filesDir).write(d.copy(streak = transform(d.streak.copy(evaluatedThrough = lastWeek), d)))
        ForgeRepository.reset() // as if the app had restarted
        return AppViewModel(app)
    }
    private val thisWeek get() = Weeks.key(Weeks.today())
    private val lastWeek get() = Weeks.key(Weeks.monday(Weeks.today()).minusWeeks(1))

    @Test fun s2_1_freezeUsed() {
        val vm = streakVm { s, _ -> s.copy(weeks = 6, freezes = 1, towardFreeze = 1, notice = StreakNotice(StreakNotice.Kind.FREEZE_USED, lastWeek, 2, 4)) }
        shot("2.1_freeze_used") { ForgeApp(vm) }
    }
    @Test fun s2_2_repairOffered() {
        val vm = streakVm { s, _ -> s.copy(weeks = 6, freezes = 0, repair = RepairQuest(thisWeek, 5, 6, 1), notice = StreakNotice(StreakNotice.Kind.REPAIR_OFFERED, lastWeek, 1, 4)) }
        shot("2.2_repair_quest") { ForgeApp(vm) }
    }
    @Test fun s2_2b_repairCard() {
        val vm = streakVm { s, _ -> s.copy(weeks = 6, repair = RepairQuest(thisWeek, 5, 6, 1, accepted = true)) }
        shot("2.2b_home_repair_card") { ForgeApp(vm) }
    }
    @Test fun s2_3_freshStart() {
        val vm = streakVm { s, _ -> s.copy(weeks = 0, bestStreakWeeks = 9, comebackWeek = thisWeek, notice = StreakNotice(StreakNotice.Kind.FRESH_START, lastWeek, 1, 4)) }
        shot("2.3_fresh_start") { ForgeApp(vm) }
    }
    @Test fun s2_4_pause() { val vm = demoVm(); shot("2.4_pause_streak") { PauseScreen(vm.data.value, vm, nav) } }
    @Test fun s2_4b_paused() {
        val vm = streakVm { s, _ -> s.copy(pausedWeeks = s.pausedWeeks + thisWeek, pauseReason = "Holiday") }
        shot("2.4b_home_paused") { ForgeApp(vm) }
    }
    @Test fun s2_4c_pausedScreen() {
        val vm = streakVm { s, _ -> s.copy(pausedWeeks = s.pausedWeeks + thisWeek, pauseReason = "Holiday") }
        shot("2.4c_pause_active") { PauseScreen(vm.data.value, vm, nav) }
    }

    // 10 Progression engine
    /** Demo data where Legs leads with RDL 8–12 and last time hit 12 on every set at 62.5 kg (as in Figma 10.2). */
    private fun levelUpVm(): AppViewModel {
        val d = demoVm().data.value
        val legs = d.templates.first { it.name.contains("Legs") }
        val rdl = legs.exercises.first { it.exerciseId == "rdl" }.copy(repMin = 8, repMax = 12, reps = 8)
        val t = legs.copy(exercises = listOf(rdl) + legs.exercises.filter { it.exerciseId != "rdl" })
        val last = d.history.indexOfFirst { w -> w.exercises.any { it.exerciseId == "rdl" } }
        val history = d.history.mapIndexed { i, w ->
            if (i != last) w else w.copy(exercises = w.exercises.map { e ->
                if (e.exerciseId != "rdl") e else e.copy(sets = e.sets.map { it.copy(weightKg = 62.5, reps = 12) })
            })
        }
        ForgeStore(app.filesDir).write(d.copy(templates = d.templates.map { if (it.id == t.id) t else it }, history = history))
        ForgeRepository.reset() // as if the app had restarted
        return AppViewModel(app).also { it.startWorkout(it.data.value.templates.first { x -> x.id == t.id }) }
    }

    @Test fun s10_1_rules() {
        val vm = demoVm()
        val legs = vm.data.value.templates.first { it.name.contains("Legs") }
        val rule = legs.exercises.first { it.exerciseId == "rdl" }.copy(repMin = 8, repMax = 12)
        shot("10.1_progression_rules") {
            Box(Modifier.statusBarsPadding()) { RulesScreen(rule, "Romanian Deadlift", legs.name, WeightUnit.KG, List(3) { SetEntry(it.toLong(), weightKg = 60.0, reps = 10, done = true) }, nav) {} }
        }
    }
    @Test fun s10_2_target() {
        val vm = levelUpVm()
        shot("10.2_todays_target") { Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {} } }
    }
    @Test fun s10_2b_targetUsed() {
        val vm = levelUpVm()
        val t = Progression.target(vm.data.value.templates.first { it.name.contains("Legs") }.exercises[0], Stats.previousSets(vm.data.value.history, "rdl"))!!
        vm.applyTarget(0, t.weightKg!!, t.reps!!)
        shot("10.2b_target_used") { Box(Modifier.statusBarsPadding()) { WorkoutScreen(vm.data.value.active!!, vm.data.value, vm, nav) {} } }
    }
    @Test fun s10_3_stall() {
        val vm = demoVm()
        val h = vm.data.value.history
        val series = Stats.e1rmSeries(h, "squat").dropLast(3).let { s ->
            val best = s.maxOf { it.second }
            val at = h.first().startedAt
            s + listOf(at - 2 * 86_400_000L to best * 0.98, at - 86_400_000L to best, at to best * 0.99)
        }
        val stall = Progression.Stall("squat", series.dropLast(3).maxOf { it.second }, series, series[series.size - 3].first)
        shot("10.3_stall_lighter_week") {
            WorkoutSummary(h.first(), h.drop(1), vm.data.value, vm, {}) {}
            StallSheet(stall, "Back Squat", 80.0, 8, vm.exercise("pause_squat"), WeightUnit.KG, {}) {}
        }
    }

    /** Demo data where the last three Back Squat sessions didn't beat the best before them. */
    private fun stalledVm(): AppViewModel {
        val d = demoVm().data.value
        val squats = d.history.filter { w -> w.exercises.any { it.exerciseId == "squat" } }.take(3).map { it.id }.toSet()
        val history = d.history.map { w ->
            if (w.id !in squats) w else w.copy(exercises = w.exercises.map { e ->
                if (e.exerciseId != "squat") e else e.copy(sets = e.sets.map { it.copy(weightKg = 85.0, reps = 4) })
            })
        }
        ForgeStore(app.filesDir).write(d.copy(history = history))
        ForgeRepository.reset() // as if the app had restarted
        return AppViewModel(app)
    }
    @Test fun s10_3b_metricsStallTag() { val vm = stalledVm(); shot("10.3b_metrics_stall_tag") { LiftDetail("squat", vm.data.value, vm, nav) } }

    // 14 Profile & account, without and with a photo
    private fun photo(): android.graphics.Bitmap = android.graphics.Bitmap.createBitmap(900, 1200, android.graphics.Bitmap.Config.ARGB_8888).apply {
        val c = android.graphics.Canvas(this)
        c.drawColor(android.graphics.Color.rgb(120, 72, 40))
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(226, 214, 196) }
        c.drawCircle(450f, 470f, 150f, p); c.drawOval(180f, 700f, 720f, 1150f, p)
    }
    private fun withPhoto(vm: AppViewModel) = vm.also { kotlinx.coroutines.runBlocking { it.saveAvatar(photo()).join() } }

    @Test fun s14_1_profile() { val vm = demoVm(); shot("14.1_profile") { ProfileScreen(vm.data.value, vm) } }
    @Test fun s14_1_profilePhoto() { val vm = withPhoto(demoVm()); shot("14.1b_profile_photo") { ProfileScreen(vm.data.value, vm) } }
    @Test fun s14_2_edit() { val vm = demoVm(); shot("14.2_edit_profile") { EditProfileScreen(vm.data.value, vm, nav) } }
    @Test fun s14_2_editPhoto() { val vm = withPhoto(demoVm()); shot("14.2b_edit_profile_photo") { EditProfileScreen(vm.data.value, vm, nav) } }
    @Test fun s14_3_photoSheet() {
        val vm = withPhoto(demoVm())
        shot("14.3_change_photo") {
            EditProfileScreen(vm.data.value, vm, nav)
            PhotoChooser(PhotoRequest(true, {}, {}), {}) { _, _ -> }
        }
    }
    @Test fun s14_3_photoSheetNoPhoto() { val vm = demoVm(); shot("14.3b_change_photo_none") { EditProfileScreen(vm.data.value, vm, nav); PhotoChooser(PhotoRequest(false, {}, {}), {}) { _, _ -> } } }
    @Test fun s14_4_crop() { val p = photo(); shot("14.4_crop") { CropScreen(null, nav, preview = p) {} } }
    @Test fun s1_1_homePhoto() { val vm = withPhoto(demoVm()); shot("1.1b_home_photo") { ForgeApp(vm) } }
}
