package app.forge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.animation.scaleOut
import app.forge.ui.Haptics
import app.forge.ui.Motion
import app.forge.ui.reduceMotion
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.AppViewModel
import app.forge.R
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.Overlay
import app.forge.ui.clock
import app.forge.ui.components.*
import app.forge.ui.rememberNow
import app.forge.ui.restClockText
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which set's values the 4.2 stepper sheet is editing. */
private data class SetRef(val ex: Int, val set: Int)

@Composable
fun WorkoutScreen(w: Workout, data: AppData, vm: AppViewModel, nav: Nav, onMinimise: () -> Unit) {
    val unit = data.settings.unit
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { w.exercises.size.coerceAtLeast(1) }
    var editing by remember { mutableStateOf<SetRef?>(null) }
    var typeMenu by remember { mutableStateOf<SetRef?>(null) }
    var exerciseMenu by remember { mutableStateOf<Int?>(null) }
    var formFor by remember { mutableStateOf<Exercise?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var finishCheck by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** E: the exercise that was just done with no assistance for the first time. */
    var unassisted by remember { mutableStateOf<String?>(null) }
    val view = LocalView.current
    val template = remember(data.templates, w.templateId) { data.templates.firstOrNull { it.id == w.templateId } }

    // 11 B: 300 ms in, 2.6 s hold, then 500 ms out.
    LaunchedEffect(toast) { if (toast != null) { delay(Motion.PR_IN_MS + Motion.PR_HOLD_MS); toast = null } }

    // 9.3 "Edit" from the notification: jump to that set's exercise and open the 4.2 stepper.
    val editRequest by vm.editRequest.collectAsState()
    LaunchedEffect(editRequest) {
        val id = editRequest ?: return@LaunchedEffect
        vm.requestEdit(null)
        val ex = w.exercises.indexOfFirst { e -> e.sets.any { it.id == id } }
        if (ex < 0) return@LaunchedEffect
        pager.scrollToPage(ex)
        editing = SetRef(ex, w.exercises[ex].sets.indexOfFirst { it.id == id })
    }

    fun finish(logUnticked: Boolean) {
        val prior = data.history
        val saved = vm.finishWorkout(logUnticked) ?: return
        nav.push(Overlay.Finish(saved, prior))
    }

    /** After a set is logged: PR toast (the set tick has its own haptic), then move on if the exercise is complete. */
    fun afterLog(ex: Int, set: Int) {
        val d = vm.data.value
        val entry = d.active?.exercises?.getOrNull(ex) ?: return
        if (Stats.firstUnassisted(entry, set, d.history)) { unassisted = entry.exerciseId; Haptics.success(view) }
        Stats.setPr(entry, set, d.history, unit)?.let { pr ->
            toast = "NEW PR · ${vm.exercise(entry.exerciseId).name.uppercase()}" to pr.detail
        }
        // Done with this one: on to the next unfinished exercise (after a reorder that isn't always ex + 1).
        val all = d.active?.exercises.orEmpty()
        if (Stats.finished(entry)) ((ex + 1..all.lastIndex) + (0 until ex)).firstOrNull { !Stats.finished(all[it]) }
            ?.let { scope.launch { pager.animateScrollToPage(it) } }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Header: minimise, name, live timer, finish.
            Row(
                Modifier.fillMaxWidth().padding(start = Forge.Gutter - 8.dp, end = Forge.Gutter, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTap(R.drawable.ic_chevron_down, Forge.Text, 22.dp, description = "Minimise workout", onClick = onMinimise)
                Text(
                    w.name, style = Type.DisplayM, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).tap(action = "Rename session") { renaming = true },
                )
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier.panel(radius = Forge.RTile).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
                    PulseDot(Forge.Red, modifier = Modifier.alpha(pulse))
                    ElapsedClock(w.startedAt)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "FINISH", style = Type.DataM13.copy(color = Forge.OnLime),
                    modifier = Modifier.clip(RoundedCornerShape(Forge.RRow)).background(Forge.Lime).tap {
                        when {
                            // Nothing logged and nothing that could be: offer to discard. Counts every kind of entry, cardio too.
                            // Skipped sets are neither: they don't ask to be logged.
                            w.exercises.none { e -> e.sets.any { it.done } } && w.exercises.none { e -> Stats.openSets(e).any { Measure.hasValue(e.tracking, Stats.ghostValues(e, it, emptyList())) } } -> confirmDiscard = true
                            w.exercises.any { e -> Stats.openSets(e).isNotEmpty() } -> finishCheck = true
                            else -> finish(false)
                        }
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }

            // Context layer O.3/O.4: after the check-in, the coach's banner until Accept or Ignore (or the first set);
            // after Accept, a pill with Undo. Nothing changes the session until Accept.
            val checkedIn = LifeContext.checkInFor(data.checkIns, w.id) != null
            LaunchedEffect(checkedIn, w.id) { if (checkedIn) vm.prepareCoach(w.id) }
            val coach = data.coachFor(w.id)
            val started = w.exercises.any { e -> e.sets.any { it.done } }
            if (coach != null && (coach.decision == Decision.ACCEPTED || (coach.decision == null && !started))) Box(Modifier.padding(horizontal = Forge.Gutter).padding(top = 12.dp)) {
                val weight: (String, Double?) -> String = { id, kg -> if (kg == null) "—" else Measure.weight(vm.exercise(id).tracking, kg, unit) }
                if (coach.decision == Decision.ACCEPTED) AppliedPill(coach, { vm.exercise(it).name }, weight) { vm.undoTweaks(w.id) }
                else TweaksBanner(
                    coach, name = { vm.exercise(it).name },
                    weight = weight,
                    onAccept = { vm.acceptTweaks(w.id) }, onIgnore = { vm.ignoreTweaks(w.id) },
                )
            }

            // Progress: exercise n of m, sets and volume, one segment per exercise.
            Column(Modifier.padding(horizontal = Forge.Gutter).padding(top = 18.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (w.exercises.isEmpty()) "NO EXERCISES YET" else "EXERCISE ${pager.currentPage + 1} OF ${w.exercises.size}", style = Type.Label)
                    Text(
                        if (w.cardioOnly) "CARDIO · " + Measure.time(w.exercises.sumOf { e -> e.sets.filter { it.done }.sumOf { it.durationSec ?: 0 } }) + " LOGGED"
                        else "${Stats.doneSets(w)} / ${Stats.totalSets(w)} SETS · ${Units.grouped(Stats.volume(w), unit)} ${unit.label.uppercase()}",
                        style = Type.Label.copy(color = Forge.Text, letterSpacing = 0.sp),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    w.exercises.forEachIndexed { i, e ->
                        val lit = i == pager.currentPage || Stats.finished(e)
                        Box(
                            Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (lit) Forge.Lime else Forge.Border)
                                .tap(label = "Exercise ${i + 1}: ${vm.exercise(e.exerciseId).name}", action = "Go to exercise") { scope.launch { pager.animateScrollToPage(i) } },
                        )
                    }
                }
            }

            if (w.exercises.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(Forge.Gutter), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("An empty session. Add what you're doing today.", style = Type.BodyMuted)
                    AddExerciseRow { nav.push(Overlay.Picker { ids -> vm.addExercises(ids) }) }
                }
            } else HorizontalPager(pager, Modifier.weight(1f), verticalAlignment = Alignment.Top, key = { w.exercises.getOrNull(it)?.id ?: it }) { page ->
                val entry = w.exercises.getOrNull(page) ?: return@HorizontalPager
                ExercisePage(
                    page, entry, w, template, data, vm,
                    onEdit = { set -> editing = SetRef(page, set) },
                    onSetMenu = { set -> typeMenu = SetRef(page, set) },
                    onMenu = { exerciseMenu = page },
                    onCheck = { set, previous ->
                        val wasDone = entry.sets.getOrNull(set)?.done == true
                        if (vm.toggleDone(page, set, shownDone = wasDone) && !wasDone) afterLog(page, set)
                    },
                    onJump = { i -> scope.launch { pager.animateScrollToPage(i) } },
                    onMove = { id, by ->
                        // Only this session's order changes; stay on the exercise being looked at.
                        vm.moveUpcoming(id, by, entry.id)
                        val at = vm.data.value.active?.exercises?.indexOfFirst { it.id == entry.id } ?: -1
                        if (at >= 0 && at != pager.currentPage) scope.launch { pager.scrollToPage(at) }
                    },
                    onAddExercise = { nav.push(Overlay.Picker { ids -> vm.addExercises(ids) }) },
                )
            }
        }

        RestPill(
            w, data.settings.restSeconds, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            onStart = vm::startRest, onAdjust = vm::adjustRest, onSkip = vm::skipRest,
        )

        // 11 B · PR moment: drops in on a spring with visible overshoot; leaves by shrinking into a dot that
        // flies up to the progress bar, "collected" into today's session.
        val reduce = reduceMotion()
        val lastToast = remember { mutableStateOf("" to "") }
        toast?.let { lastToast.value = it }
        AnimatedVisibility(
            toast != null, Modifier.align(Alignment.TopCenter).padding(horizontal = Forge.Gutter, vertical = 8.dp),
            enter = if (reduce) fadeIn(tween(Motion.FADE_MS)) else slideInVertically(Motion.dropSpring()) { -it * 2 } + fadeIn(tween(120)),
            exit = if (reduce) fadeOut(tween(Motion.FADE_MS)) else
                scaleOut(tween(Motion.PR_OUT_MS, easing = FastOutLinearInEasing), targetScale = 0.04f, transformOrigin = TransformOrigin(0.5f, 0f)) +
                    slideOutVertically(tween(Motion.PR_OUT_MS, easing = FastOutLinearInEasing)) { -it } + fadeOut(tween(Motion.PR_OUT_MS, delayMillis = 200)),
        ) {
            val (kicker, detail) = lastToast.value
            PrToast(kicker, detail, animate = !reduce) { toast = null }
        }
    }

    editing?.let { ref ->
        val entry = w.exercises.getOrNull(ref.ex)
        if (entry == null || ref.set !in entry.sets.indices) editing = null
        else SetStepperSheet(entry, ref.set, data, vm.exercise(entry.exerciseId).name, onDismiss = { editing = null },
            barbell = vm.exercise(entry.exerciseId).equipment == Equipment.BARBELL,
            onAddWarmups = { ramp -> vm.addWarmups(ref.ex, ramp.map { Units.toKg(it.weight, data.settings.unit) to it.reps }); editing = null },
        ) { values, applyAll ->
            val wasDone = entry.sets[ref.set].done
            if (wasDone) vm.updateValues(ref.ex, ref.set, values, applyAll)
            else if (vm.logValues(ref.ex, ref.set, values, applyAll)) afterLog(ref.ex, ref.set)
            editing = null
        }
    }
    typeMenu?.let { ref ->
        val set = w.exercises.getOrNull(ref.ex)?.sets?.getOrNull(ref.set)
        if (set == null) typeMenu = null else BottomSheet({ typeMenu = null }) {
            SheetHeader("Set ${ref.set + 1}")
            listOf(SetType.NORMAL to "Working set", SetType.WARMUP to "Warm-up", SetType.DROP to "Drop set", SetType.FAILURE to "To failure").forEach { (t, label) ->
                OptionCard(label, typeCaption(t), set.type == t) { vm.setType(ref.ex, ref.set, t); typeMenu = null }
            }
            // v1.0.1: the set stays on the page, struck through, and is left out of everything that counts.
            if (set.skipped) SecondaryButton("Undo skip", Modifier.fillMaxWidth()) { vm.skipSet(ref.ex, ref.set, false); typeMenu = null }
            else SecondaryButton("Skip set", Modifier.fillMaxWidth()) { vm.skipSet(ref.ex, ref.set); typeMenu = null }
            SecondaryButton("Delete set", Modifier.fillMaxWidth(), color = Forge.Red) { vm.removeSet(ref.ex, ref.set); typeMenu = null }
        }
    }
    exerciseMenu?.let { i ->
        val entry = w.exercises.getOrNull(i)
        if (entry == null) exerciseMenu = null else BottomSheet({ exerciseMenu = null }) {
            val ex = vm.exercise(entry.exerciseId)
            SheetHeader(ex.name)
            // v1.1: form cues for the lift, one tap from the set you're on.
            if (ex.cues.isNotEmpty() || ex.custom) SecondaryButton("Form", Modifier.fillMaxWidth()) { exerciseMenu = null; formFor = ex }
            if (Library.canSwitchAssist(ex)) AssistSwitch(ex, data, vm, started = entry.sets.any { it.done })
            if (ex.equipment.isUnilateralLoad && entry.tracking != Tracking.CARDIO) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Log weight per hand", style = Type.BodyM)
                    Text("Volume counts both hands", style = Type.BodyS)
                }
                Switch(entry.perHand, label = "Log weight per hand") { vm.togglePerHand(i, it) }
            }
            SecondaryButton("Swap exercise", Modifier.fillMaxWidth()) {
                exerciseMenu = null
                nav.push(Overlay.Picker(single = true, muscle = ex.muscle) { ids -> ids.firstOrNull()?.let { vm.swapExercise(i, it) } })
            }
            if (i > 0) SecondaryButton("Move earlier", Modifier.fillMaxWidth()) { vm.moveExercise(i, -1); exerciseMenu = null; scope.launch { pager.scrollToPage(i - 1) } }
            if (i < w.exercises.lastIndex) SecondaryButton("Move later", Modifier.fillMaxWidth()) { vm.moveExercise(i, 1); exerciseMenu = null; scope.launch { pager.scrollToPage(i + 1) } }
            SecondaryButton("Remove exercise", Modifier.fillMaxWidth(), color = Forge.Red) { vm.removeExercise(i); exerciseMenu = null }
            GhostButton("Discard workout", Modifier.fillMaxWidth(), color = Forge.Red) { exerciseMenu = null; confirmDiscard = true }
        }
    }
    formFor?.let { ex -> FormSheet(ex, onDismiss = { formFor = null }) }
    unassisted?.let { id -> UnassistedSheet(vm.exercise(id), onDismiss = { unassisted = null }) { vm.setTrackingMode(id, Tracking.BODYWEIGHT_REPS); unassisted = null } }
    if (renaming) {
        var name by remember { mutableStateOf(w.name) }
        BottomSheet({ renaming = false }) {
            SheetHeader("Rename session")
            ForgeTextField(name, { name = it.take(30) }, "Workout name", Modifier.fillMaxWidth())
            PrimaryButton("Save", Modifier.fillMaxWidth()) { vm.renameActive(name.ifBlank { w.name }); renaming = false }
        }
    }
    // Context layer I.4: before the first set of a lifting session, once per session (a skip counts as an answer).
    val needsCheckIn = w.exercises.any { it.tracking.isLifting } && w.exercises.none { e -> e.sets.any { it.done } } &&
        LifeContext.checkInFor(data.checkIns, w.id) == null
    if (needsCheckIn) CheckInSheet { sleep, soreness, energy -> vm.saveCheckIn(w.id, sleep, soreness, energy) }
    if (finishCheck) FinishCheckSheet(w, data, vm, onKeepGoing = { finishCheck = false }) { logUnticked -> finishCheck = false; finish(logUnticked) }
    if (confirmDiscard) ConfirmSheet(
        "Discard this session?", "Nothing from it will be saved.", "Discard", destructive = true,
        onDismiss = { confirmDiscard = false },
    ) { confirmDiscard = false; vm.cancelWorkout() }
}

/** E: the first set with no assistance. Offers to track the lift as Weighted from the next session. */
@Composable
internal fun UnassistedSheet(ex: Exercise, onDismiss: () -> Unit, onSwitch: () -> Unit) {
    BottomSheet(onDismiss) {
        Text("UNASSISTED", style = Type.Kicker)
        Text("Your first ${ex.name.lowercase()} with no help.", style = Type.DisplayM)
        Text(
            "No machine, no band. From your next session you could log it as Weighted: your bodyweight, " +
                "plus any weight you add. Today's sets stay as they are.",
            style = Type.BodyMuted,
        )
        PrimaryButton("Switch to weighted", Modifier.fillMaxWidth(), onClick = onSwitch)
        GhostButton("Keep it assisted", Modifier.fillMaxWidth(), color = Forge.Muted, onClick = onDismiss)
    }
}

/**
 * C: how a pull-up or dip is done, remembered per exercise. Assisted logs the machine's help (less is
 * progress); Weighted logs bodyweight plus anything added.
 */
@Composable
internal fun AssistSwitch(ex: Exercise, data: AppData, vm: AppViewModel, started: Boolean = false) {
    val undecided = Migrate.undecided(data, ex.id)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DONE AS", style = Type.Label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Assisted", ex.tracking == Tracking.ASSISTED, Modifier.weight(1f)) { vm.setTrackingMode(ex.id, Tracking.ASSISTED) }
            Chip("Weighted", ex.tracking == Tracking.BODYWEIGHT_REPS, Modifier.weight(1f)) { vm.setTrackingMode(ex.id, Tracking.BODYWEIGHT_REPS) }
        }
        Text(
            listOfNotNull(
                if (ex.tracking == Tracking.ASSISTED) "Machine or band help. Less is progress; 0 is unassisted." else "Bodyweight, plus any weight you add.",
                "Today's logged sets stay as they are; it applies from your next session.".takeIf { started },
                "Also sets how $undecided earlier ${if (undecided == 1) "session reads" else "sessions read"}, from before tracking types.".takeIf { undecided > 0 },
            ).joinToString(" "),
            style = Type.BodyS,
        )
    }
}

private fun typeCaption(t: SetType) = when (t) {
    SetType.NORMAL -> "Counts toward volume and records"
    SetType.WARMUP -> "Logged, but left out of volume and records"
    SetType.DROP -> "A lighter set straight after the last"
    SetType.FAILURE -> "Taken to failure"
}

@Composable
private fun ExercisePage(
    index: Int, entry: ExerciseEntry, w: Workout, template: Template?, data: AppData, vm: AppViewModel,
    onEdit: (Int) -> Unit, onSetMenu: (Int) -> Unit, onMenu: () -> Unit, onCheck: (Int, SetEntry?) -> Unit,
    onJump: (Int) -> Unit, onMove: (id: Long, by: Int) -> Unit, onAddExercise: () -> Unit,
) {
    val unit = data.settings.unit
    val ex = vm.exercise(entry.exerciseId)
    val prev = remember(data.history, entry.exerciseId, entry.tracking) { Stats.previousSets(data.history, entry.exerciseId, entry.tracking) }
    val activeIdx = Stats.openSets(entry).firstOrNull() ?: -1

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 10.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // A: finished exercises collapse above the current one.
        w.exercises.forEachIndexed { i, e -> if (i != index && Stats.finished(e)) key(e.id) { FinishedTile(e, vm, unit) { onJump(i) } } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ex.name, style = Type.DisplayS, modifier = Modifier.weight(1f))
            IconTap(R.drawable.ic_more, Forge.Muted, 20.dp, description = "${ex.name} options", onClick = onMenu)
        }
        val t = entry.tracking
        if (t == Tracking.CARDIO) {
            // Cardio is one entry: time (required), distance, level, heart rate. Tap to fill it in.
            val v = Stats.ghostValues(entry, 0, prev)
            CardioCard(v, entry.sets.firstOrNull()?.done == true, unit, onEdit = { onEdit(0) }, onCheck = { onCheck(0, prev.firstOrNull()) })
            prev.firstOrNull()?.takeIf { it.durationSec != null }?.let { Text("Last time: ${Measure.cardio(it, unit)}", style = Type.BodyS) }
        } else {
            val rule = remember(template, entry.exerciseId, prev) { ruleFor(template, w, entry.exerciseId, prev, t) }
            // Context layer: readiness and the return-from-a-break cap gate the progression engine; they never replace it.
            val gate = remember(data.checkIns, data.outsideDays, data.history, w.id) {
                Rules.gate(data, LifeContext.checkInFor(data.checkIns, w.id), System.currentTimeMillis())
            }
            val target = remember(rule, prev, unit, t, gate) { Rules.gated(Progression.target(rule, prev, unit, t), gate) }
            val card = target != null && target.weightKg != null && target.reps != null &&
                (target.kind == Progression.Kind.LEVEL_UP || target.kind == Progression.Kind.LIGHTER) && !entry.targetSeen && entry.sets.none { it.done }
            if (card) TargetCard(target!!, rule, unit, t, onUse = { vm.applyTarget(index, target.weightKg!!, target.reps!!) }, onKeep = { vm.keepTarget(index) })
            else hintFor(target, entry, unit)?.let { text ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeIcon(R.drawable.ic_trending, 14.dp, Forge.Muted)
                    Text(text, style = Type.BodyS)
                }
            }
            val (colA, colB) = Measure.columns(t, unit, entry.perHand)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SET", style = Type.Label, modifier = Modifier.width(32.dp))
                Text("PREVIOUS", style = Type.Label, modifier = Modifier.weight(1f))
                Text(colA, style = Type.Label, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center, maxLines = 1)
                Text(colB, style = Type.Label, modifier = Modifier.width(64.dp), textAlign = TextAlign.Center, maxLines = 1)
                Spacer(Modifier.width(40.dp))
            }
            var working = 0
            entry.sets.forEachIndexed { si, set ->
                val label = when (set.type) { SetType.WARMUP -> "W"; SetType.DROP -> "D"; SetType.FAILURE -> "F"; else -> (++working).toString() }
                val labelColor = when (set.type) { SetType.WARMUP -> Forge.Ice; SetType.DROP -> Forge.Amber; SetType.FAILURE -> Forge.Text; else -> null }
                val v = Stats.ghostValues(entry, si, prev)
                val (cellA, cellB) = Measure.cells(t, v.weightKg, v.reps, v.durationSec, v.distanceM, unit, entry.withBodyweight)
                val previous = prev.getOrNull(si)
                key(set.id) {
                    SetRow(
                        set = label,
                        previous = previous?.takeIf { Measure.hasValue(t, it) }?.let { Measure.format(t, it, unit, bodyweight = entry.withBodyweight) } ?: "—",
                        kg = cellA, reps = cellB,
                        state = when { set.skipped -> SetState.Skipped; set.done -> SetState.Done; si == activeIdx -> SetState.Active; else -> SetState.Upcoming },
                        setColor = labelColor,
                        onSet = { onSetMenu(si) },
                        onKg = { onEdit(si) }, onReps = { onEdit(si) },
                        onCheck = { onCheck(si, previous) },
                        labels = colA.ifEmpty { "weight" } to colB,
                        onLongPress = { onSetMenu(si) },
                        onUndoSkip = { vm.skipSet(index, si, false) },
                    )
                }
            }
            Row(
                Modifier.tap { vm.addSet(index) }.padding(start = 4.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ForgeIcon(R.drawable.ic_plus, 14.dp, Forge.Text)
                Text("ADD SET", style = Type.DataM12)
            }
        }
        // A: everything still to do, in order: tap to jump there, drag the handle to reorder.
        val upcoming = w.exercises.indices.filter { it != index && !Stats.finished(w.exercises[it]) }
        if (upcoming.isNotEmpty()) {
            Text("UP NEXT", style = Type.Label, modifier = Modifier.padding(top = 6.dp))
            UpcomingList(upcoming, w, template, data.history, unit, { vm.exercise(it).name }, onJump, onMove)
        }
        AddExerciseRow(onAddExercise)
    }
}

/** A: exercises already finished this session, collapsed above the current one with a tick. Tap to go back. */
@Composable
private fun FinishedTile(e: ExerciseEntry, vm: AppViewModel, unit: WeightUnit, onJump: () -> Unit) {
    val name = vm.exercise(e.exerciseId).name
    val top = Stats.topSet(e)
    Row(
        Modifier.fillMaxWidth().panel(bg = Forge.Bg, radius = Forge.RRow).tap(label = "$name, done", action = "Go to exercise", onClick = onJump)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Forge.Lime), contentAlignment = Alignment.Center) { ForgeIcon(R.drawable.ic_check, 11.dp, Forge.OnLime) }
        Text(name, style = Type.BodyM.copy(color = Forge.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val done = e.sets.count { it.done }
        Text(
            if (e.tracking == Tracking.CARDIO || top == null) "$done ${if (done == 1) "set" else "sets"}" else "$done × " + Measure.format(e, top, unit),
            style = Type.Label.copy(letterSpacing = 0.sp), maxLines = 1,
        )
    }
}

/**
 * The rule for an exercise in this session: its template's (matching a running variation too). An
 * exercise added on the fly keeps last time's reps as a fixed target, so hitting them on every set
 * moves the weight up.
 */
internal fun ruleFor(template: Template?, w: Workout, exerciseId: String, prev: List<SetEntry>, tracking: Tracking = Tracking.WEIGHT_REPS): TemplateExercise =
    template?.exercises?.firstOrNull { it.effectiveId(w.startedAt) == exerciseId }
        ?.let { if (it.exerciseId != exerciseId) it.copy(lighterNextKg = null) else it }
        ?: Progression.working(prev, tracking).firstOrNull()?.reps?.let { r -> TemplateExercise(exerciseId, reps = r, repMin = r, repMax = r) }
        ?: TemplateExercise.fresh(exerciseId)

/** The session's elapsed time. Owns its own once-a-second clock so the rest of the screen doesn't recompose. */
@Composable
private fun ElapsedClock(startedAt: Long) {
    val now by rememberNow()
    Text(clock(now - startedAt), style = Type.DataM13)
}

/** A cardio entry in a workout: time, distance, level and heart rate in one card, with a tick. */
@Composable
private fun CardioCard(v: SetEntry, done: Boolean, unit: WeightUnit, onEdit: () -> Unit, onCheck: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().panel(bg = if (done) Forge.Surface else Forge.Surface2, border = if (done) Forge.Border else Forge.Ice, radius = Forge.RButton)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CARDIO", style = Type.Label.copy(color = Forge.Ice), modifier = Modifier.weight(1f))
            Box(
                Modifier.size(40.dp).panel(bg = if (done) Forge.Lime else Color.Transparent, border = if (done) Forge.Lime else Forge.Ice, radius = Forge.RTile)
                    .tap(label = if (done) "Cardio logged" else "Log cardio", action = if (done) "Untick" else "Log", onClick = onCheck),
                contentAlignment = Alignment.Center,
            ) { ForgeIcon(R.drawable.ic_check, 16.dp, if (done) Forge.OnLime else Forge.Ice) }
        }
        Row(Modifier.fillMaxWidth().tap(action = "Edit cardio", onClick = onEdit), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "TIME" to (v.durationSec?.let(Measure::time) ?: "–"),
                Measure.distanceLabel(Tracking.CARDIO, unit).uppercase() to (v.distanceM?.let { Units.num(Measure.fromMetres(it, Tracking.CARDIO, unit)) } ?: "–"),
                "LEVEL" to (v.level?.toString() ?: "–"),
                "AVG HR" to (v.heartRate?.toString() ?: "–"),
            ).forEach { (k, value) ->
                Column(
                    Modifier.weight(1f).panel(bg = Forge.Bg, border = Forge.Border, radius = Forge.RTile).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(k, style = Type.Label.copy(fontSize = 9.sp), maxLines = 1)
                    Text(value, style = Type.DataM.copy(color = if (done) Forge.Text else Forge.Ice), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The muted line under the name when there's no card: first time, or what to aim for at the same weight. */
private fun hintFor(target: Progression.Target?, entry: ExerciseEntry, unit: WeightUnit): String? = when {
    target == null || entry.targetSeen -> null
    target.kind == Progression.Kind.BASELINE -> "First time on this one: today sets the baseline."
    target.durationSec != null -> "Aim for ${Measure.time(target.durationSec)} on every set, ${Progression.TIME_STEP_SEC} s more than last time."
    target.kind == Progression.Kind.HOLD && entry.tracking == Tracking.ASSISTED && target.weightKg != null && target.weightKg > 0 ->
        "Same assistance as last time; aim for ${target.reps} reps on every set."
    target.kind == Progression.Kind.HOLD && target.weightKg != null && target.weightKg > 0 ->
        "Same weight as last time; aim for ${target.reps} reps on every set."
    target.kind == Progression.Kind.HOLD -> "Aim for ${target.reps} reps on every set."
    else -> null
}

@Composable
private fun AddExerciseRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().dashedBorder(Forge.Lime, Forge.RButton).clip(RoundedCornerShape(Forge.RButton)).tap(onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeIcon(R.drawable.ic_plus, 16.dp, Forge.Text)
        Spacer(Modifier.width(8.dp))
        Text("ADD EXERCISE", style = Type.DataM12)
    }
}

/** 4.3 lime toast. */
@Composable
fun PrToast(kicker: String, detail: String, animate: Boolean = false, onClick: () -> Unit = {}) {
    // 11 B: the trophy comes in tilted −12° and rights itself as the toast settles (300 ms, success
    // haptic); at 600 ms one light shine sweeps across. No confetti.
    val tilt = remember { Animatable(if (animate) -12f else 0f) }
    val shine = remember { Animatable(0f) }
    val view = LocalView.current
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        launch { delay(120); tilt.animateTo(0f, Motion.tickSpring()) }
        launch { delay(Motion.PR_IN_MS.toLong()); Haptics.success(view) }
        launch { delay(600); shine.animateTo(1f, tween(450, easing = LinearOutSlowInEasing)) }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Forge.RSheet)).background(Forge.Lime)
            .drawWithContent {
                drawContent()
                if (shine.value in 0.001f..0.999f) {
                    val x = -size.width * 0.3f + shine.value * size.width * 1.6f
                    drawRect(
                        Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent), Offset(x, 0f), Offset(x + size.width * 0.25f, size.height)),
                    )
                }
            }
            .tap(action = "Dismiss", onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).graphicsLayer { rotationZ = tilt.value }.clip(CircleShape).background(Forge.OnLime), contentAlignment = Alignment.Center) {
            ForgeIcon(R.drawable.ic_trophy, 20.dp, Forge.Lime)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(kicker, style = Type.DataS.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = Forge.OnLime), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = Type.TitleM14.copy(color = Forge.OnLime))
        }
    }
}

// ---- 5.1 Finish check -----------------------------------------------------------------------

@Composable
internal fun FinishCheckSheet(w: Workout, data: AppData, vm: AppViewModel, onKeepGoing: () -> Unit, onFinish: (logUnticked: Boolean) -> Unit) {
    var logThem by remember { mutableStateOf(true) }
    val open = w.exercises.flatMap { e -> Stats.openSets(e).map { e to it } }
    val names = open.groupBy { it.first }.entries.take(2).joinToString(" and ") { (e, sets) ->
        "${vm.exercise(e.exerciseId).name} set ${sets.joinToString(", ") { (it.second + 1).toString() }}"
    } + if (open.map { it.first }.distinct().size > 2) " and more" else ""
    BottomSheet(onKeepGoing) {
        SheetHeader(
            "${open.size} ${if (open.size == 1) "set isn't" else "sets aren't"} ticked yet",
            "$names still ${if (open.size == 1) "shows" else "show"} last session’s numbers.",
        )
        OptionCard("I did them, log as shown", "Counts toward volume and PRs", logThem) { logThem = true }
        OptionCard("Discard unticked sets", "Only completed sets are saved", !logThem) { logThem = false }
        PrimaryButton("Finish workout", Modifier.fillMaxWidth()) { onFinish(logThem) }
        GhostButton("Keep going", Modifier.fillMaxWidth(), onClick = onKeepGoing)
    }
}
