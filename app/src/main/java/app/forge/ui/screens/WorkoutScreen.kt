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
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which set's values the 4.2 stepper sheet is editing. */
private data class SetRef(val ex: Int, val set: Int)

@Composable
fun WorkoutScreen(w: Workout, data: AppData, vm: AppViewModel, nav: Nav, onMinimise: () -> Unit) {
    val unit = data.settings.unit
    val now by rememberNow()
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
                    Text(clock(now - w.startedAt), style = Type.DataM.copy(fontSize = 13.sp))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "FINISH", style = Type.DataM.copy(fontSize = 13.sp, color = Forge.OnLime),
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
                if (coach.decision == Decision.ACCEPTED) AppliedPill(coach) { vm.undoTweaks(w.id) }
                else TweaksBanner(
                    coach, name = { vm.exercise(it).name },
                    weight = { id, kg -> if (kg == null) "—" else Measure.weight(vm.exercise(id).tracking, kg, unit) },
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
                        style = Type.Label.copy(color = Forge.Lime, letterSpacing = 0.sp),
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
                        if (vm.toggleDone(page, set, previous) && !wasDone) afterLog(page, set)
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

        RestPill(w, data.settings.restSeconds, now, vm, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))

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
        else SetStepperSheet(entry, ref.set, data, vm, onDismiss = { editing = null }) { values, applyAll ->
            val wasDone = entry.sets[ref.set].done
            if (wasDone) vm.updateValues(ref.ex, ref.set, values, applyAll)
            else { vm.logValues(ref.ex, ref.set, values, applyAll); afterLog(ref.ex, ref.set) }
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
                val (cellA, cellB) = Measure.cells(t, v.weightKg, v.reps, v.durationSec, v.distanceM, unit)
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
                ForgeIcon(R.drawable.ic_plus, 14.dp, Forge.Lime)
                Text("ADD SET", style = Type.DataM.copy(fontSize = 12.sp, color = Forge.Lime))
            }
        }
        // A: everything still to do, in order: tap to jump there, drag the handle to reorder.
        val upcoming = w.exercises.indices.filter { it != index && !Stats.finished(w.exercises[it]) }
        if (upcoming.isNotEmpty()) {
            Text("UP NEXT", style = Type.Label, modifier = Modifier.padding(top = 6.dp))
            UpcomingList(upcoming, w, template, data, vm, onJump, onMove)
        }
        AddExerciseRow(onAddExercise)
    }
}

/** "3 × 8–12", "3 timed sets", "cardio": what an exercise in the session asks for. */
private fun plannedText(e: ExerciseEntry, template: Template?): String {
    val n = e.sets.count { !it.skipped }
    val te = template?.exercises?.firstOrNull { it.exerciseId == e.exerciseId }
    return when (e.tracking) {
        Tracking.CARDIO -> "cardio"
        Tracking.DURATION -> "$n timed ${if (n == 1) "set" else "sets"}"
        Tracking.DISTANCE_WEIGHT -> "$n carries"
        else -> "$n × " + (te?.let { rangeText(it.repMin, it.repMax) } ?: (e.sets.firstOrNull()?.reps ?: ""))
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
 * A: the exercises still to come, collapsed: name, sets × target, last time. Drag the handle to reorder
 * (TalkBack: Move up / Move down); tap a tile to jump to it. Order changes only this session.
 */
@Composable
private fun UpcomingList(
    items: List<Int>, w: Workout, template: Template?, data: AppData, vm: AppViewModel,
    onJump: (Int) -> Unit, onMove: (id: Long, by: Int) -> Unit,
) {
    val unit = data.settings.unit
    val gap = 8.dp
    // The drag is drawn here and committed once, on release: changing the session's order mid-drag
    // recomposes the pager's pages, which would end the gesture.
    var dragging by remember { mutableStateOf<Long?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    var stepPx by remember { mutableFloatStateOf(0f) }
    val count by rememberUpdatedState(items.size)
    val move by rememberUpdatedState(onMove)
    val view = LocalView.current
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { gap.toPx() }
    val from = items.indexOfFirst { w.exercises[it].id == dragging }
    // How many places the dragged tile has travelled, within the list.
    fun slots(pos: Int) = if (stepPx <= 0f) 0 else kotlin.math.round(offset / stepPx).toInt().coerceIn(-pos, count - 1 - pos)
    val target = if (from < 0) -1 else from + slots(from)
    LaunchedEffect(target) { if (dragging != null) Haptics.light(view) }
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        items.forEachIndexed { pos, i ->
            val e = w.exercises[i]
            key(e.id) {
                val name = vm.exercise(e.exerciseId).name
                val lastEntry = data.history.firstNotNullOfOrNull { h -> h.exercises.firstOrNull { it.exerciseId == e.exerciseId && it.tracking == e.tracking } }
                val last = lastEntry?.let { Stats.topSet(it) }
                val lifted = dragging == e.id
                // Tiles between where the dragged one was and where it's going step aside.
                val shift = when {
                    from < 0 || lifted -> 0f
                    pos in (from + 1)..target -> -stepPx
                    pos in target until from -> stepPx
                    else -> 0f
                }
                val actions = buildList {
                    if (pos > 0) add(androidx.compose.ui.semantics.CustomAccessibilityAction("Move up") { move(e.id, -1); true })
                    if (pos < items.lastIndex) add(androidx.compose.ui.semantics.CustomAccessibilityAction("Move down") { move(e.id, 1); true })
                }
                Row(
                    Modifier.fillMaxWidth()
                        .onSizeChanged { stepPx = it.height + gapPx }
                        .zIndex(if (lifted) 1f else 0f)
                        .graphicsLayer { translationY = if (lifted) offset else shift; shadowElevation = if (lifted) 8.dp.toPx() else 0f }
                        .panel(bg = if (lifted) Forge.Surface2 else Forge.Surface, border = if (lifted) Forge.Lime else Forge.Border, radius = Forge.RButton)
                        .tap(label = name, action = "Go to exercise") { onJump(i) }
                        .semantics { customActions = actions }
                        .padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(name, style = Type.TitleM.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(plannedText(e, template), last?.let { "last " + Measure.format(lastEntry, it, unit) }).joinToString(" · "),
                            style = Type.BodyS, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // The handle: a 48 dp target that takes the drag, so the page scroll and pager don't.
                    Box(
                        Modifier.size(48.dp).pointerInput(e.id, pos) {
                            detectDragGestures(
                                onDragStart = { dragging = e.id; offset = 0f; Haptics.light(view) },
                                onDragEnd = {
                                    val by = slots(pos)
                                    dragging = null; offset = 0f
                                    if (by != 0) move(e.id, by)
                                },
                                onDragCancel = { dragging = null; offset = 0f },
                            ) { change, drag -> change.consume(); offset += drag.y }
                        }.semantics(mergeDescendants = true) { contentDescription = "Drag to reorder $name"; customActions = actions },
                        contentAlignment = Alignment.Center,
                    ) { ForgeIcon(R.drawable.ic_grip, 18.dp, Forge.Muted) }
                }
            }
        }
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

/** 10.2 Today's target: shown once at the start of an exercise when the rule fires. */
@Composable
internal fun TargetCard(
    target: Progression.Target, rule: TemplateExercise, unit: WeightUnit, tracking: Tracking = Tracking.WEIGHT_REPS,
    onUse: () -> Unit, onKeep: () -> Unit,
) {
    val assisted = tracking == Tracking.ASSISTED
    // Weight × reps reads "65 kg"; assisted "−17.5 kg" (help); bodyweight "BW +2.5 kg".
    val kg = if (tracking == Tracking.WEIGHT_REPS) Units.w(target.weightKg ?: 0.0, unit) else Measure.weight(tracking, target.weightKg, unit)
    val from = target.fromKg?.let { if (tracking == Tracking.WEIGHT_REPS) Units.w(it, unit) else Measure.weight(tracking, it, unit) }
    val lighter = target.kind == Progression.Kind.LIGHTER
    if (tracking != Tracking.WEIGHT_REPS) {
        TargetCardOther(target, kg, from, assisted, lighter, onUse, onKeep)
        return
    }
    Column(
        Modifier.fillMaxWidth().panel(bg = Forge.Lime.copy(alpha = 0.08f), border = Forge.Lime.copy(alpha = 0.5f), radius = 16.dp).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ForgeIcon(R.drawable.ic_arrow_up, 14.dp, Forge.Lime, Modifier.rotate(if (lighter) 180f else 0f))
            Text(if (lighter) "LIGHTER WEEK" else "LEVEL UP TODAY", style = Type.Kicker)
        }
        Text("$kg ${unit.label} × ${target.reps}", style = Type.FigureL.copy(fontSize = 48.sp))
        val unitLabel = unit.label
        Text(
            when {
                lighter -> "Planned after a stall: about 10% under ${from ?: kg} $unitLabel. Back to the normal plan next session."
                rule.method == ProgressionMethod.LINEAR -> "You hit ${target.fromReps} on every set last time at $from $unitLabel. Add ${Units.w(target.weightKg!! - (target.fromKg ?: 0.0), unit)} $unitLabel today."
                else -> "You hit ${target.fromReps} on every set last time at $from $unitLabel. New weight, back to ${target.reps} reps."
            },
            style = Type.BodyMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Use $kg $unitLabel", Modifier.weight(1f), onClick = onUse)
            SecondaryButton(if (from != null) "Keep $from" else "Keep", Modifier.weight(1f), onClick = onKeep)
        }
    }
}

/** 10.2 for assisted and bodyweight work: the same card, worded for help taken off or weight added. */
@Composable
private fun TargetCardOther(
    target: Progression.Target, kg: String, from: String?, assisted: Boolean, lighter: Boolean, onUse: () -> Unit, onKeep: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().panel(bg = Forge.Lime.copy(alpha = 0.08f), border = Forge.Lime.copy(alpha = 0.5f), radius = 16.dp).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ForgeIcon(R.drawable.ic_arrow_up, 14.dp, Forge.Lime, Modifier.rotate(if (lighter) 180f else 0f))
            Text(if (lighter) "LIGHTER WEEK" else if (assisted) "LESS HELP TODAY" else "LEVEL UP TODAY", style = Type.Kicker)
        }
        Text("$kg × ${target.reps}", style = Type.FigureL.copy(fontSize = 48.sp))
        Text(
            when {
                lighter && assisted -> "Planned after a stall: a little more help than ${from ?: kg}. Back to the normal plan next session."
                lighter -> "Planned after a stall: about 10% under ${from ?: kg}. Back to the normal plan next session."
                assisted -> "You hit ${target.fromReps} on every set last time at ${from ?: "BW"}. Take some help off, back to ${target.reps} reps."
                else -> "You hit ${target.fromReps} on every set last time at ${from ?: "BW"}. Add weight, back to ${target.reps} reps."
            },
            style = Type.BodyMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Use $kg", Modifier.weight(1f), onClick = onUse)
            SecondaryButton(if (from != null) "Keep $from" else "Keep", Modifier.weight(1f), onClick = onKeep)
        }
    }
}

@Composable
private fun AddExerciseRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().dashedBorder(Forge.Lime, Forge.RButton).clip(RoundedCornerShape(Forge.RButton)).tap(onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeIcon(R.drawable.ic_plus, 16.dp, Forge.Lime)
        Spacer(Modifier.width(8.dp))
        Text("ADD EXERCISE", style = Type.DataM.copy(fontSize = 12.sp, color = Forge.Lime))
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Forge.Lime)
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
            Text(detail, style = Type.TitleM.copy(fontSize = 14.sp, color = Forge.OnLime))
        }
    }
}

/** Floating rest countdown (4.1). Idle: tap to start the default rest. Running: −15 / +15 / skip inline. */
@Composable
private fun RestPill(w: Workout, defaultSec: Int, now: Long, vm: AppViewModel, modifier: Modifier) {
    val ends = w.restEndsAt
    val running = ends != null && ends > now
    val remaining = if (running) ((ends!! - now + 999) / 1000).toInt() else defaultSec
    // 11 A, last frame: once a set is ticked the rest pill slides up from the bottom edge, already counting (280–420 ms).
    val reduce = reduceMotion()
    val rise = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (!running || reduce) return@LaunchedEffect
        rise.snapTo(1f); delay(280); rise.animateTo(0f, tween(140, easing = Motion.Wipe))
    }
    Row(
        modifier.graphicsLayer { translationY = rise.value * 72.dp.toPx(); alpha = 1f - rise.value }
            .clip(CircleShape).background(Forge.Surface)
            .then(Modifier.panel(bg = Forge.Surface, border = if (running) Forge.Lime else Forge.Border, radius = 100.dp))
            .tap(enabled = !running, action = "Start rest") { vm.startRest(defaultSec) }
            .padding(start = 18.dp, end = if (running) 10.dp else 22.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ForgeIcon(R.drawable.ic_clock, 22.dp, if (running) Forge.Lime else Forge.Muted)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (running) "REST" else "TAP TO REST", style = Type.Label)
            Text(clock(remaining * 1000L), style = Type.DataM.copy(fontSize = 16.sp, color = if (running) Forge.Text else Forge.Muted))
        }
        if (running) {
            RestChip("−15", label = "15 seconds less rest") { vm.adjustRest(-15) }
            RestChip("+15", label = "15 seconds more rest") { vm.adjustRest(15) }
            RestChip("SKIP", Forge.Lime, label = "Skip rest") { vm.skipRest() }
        }
    }
}

@Composable
private fun RestChip(text: String, color: Color = Forge.Text, label: String, onClick: () -> Unit) {
    Text(
        text, style = Type.DataM.copy(fontSize = 12.sp, color = color),
        modifier = Modifier.clip(CircleShape).background(Forge.Surface2).tap(label = label, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ---- 4.2 Adjust a set -----------------------------------------------------------------------

@Composable
internal fun SetStepperSheet(
    entry: ExerciseEntry, setIndex: Int, data: AppData, vm: AppViewModel, onDismiss: () -> Unit,
    onLog: (values: SetEntry, applyToRemaining: Boolean) -> Unit,
) {
    val unit = data.settings.unit
    val t = entry.tracking
    val prev = remember(data.history, entry.exerciseId, entry.tracking) { Stats.previousSets(data.history, entry.exerciseId, entry.tracking) }
    val g = Stats.ghostValues(entry, setIndex, prev)
    val previous = prev.getOrNull(setIndex) ?: prev.lastOrNull()?.takeIf { t == Tracking.CARDIO }
    val set = entry.sets[setIndex]
    var weight by remember { mutableDoubleStateOf(g.weightKg?.let { Units.toDisplay(it, unit) } ?: 0.0) }
    var reps by remember { mutableIntStateOf(g.reps ?: 8) }
    var seconds by remember { mutableIntStateOf(g.durationSec ?: if (t == Tracking.CARDIO) 20 * 60 else 30) }
    var distance by remember { mutableDoubleStateOf(g.distanceM?.let { Measure.fromMetres(it, t, unit) } ?: 0.0) }
    var level by remember { mutableIntStateOf(g.level ?: 0) }
    var heart by remember { mutableIntStateOf(g.heartRate ?: 0) }
    var applyAll by remember { mutableStateOf(false) }
    val step = if (unit == WeightUnit.KG) 2.5 else 5.0
    val ex = vm.exercise(entry.exerciseId)
    val cardio = t == Tracking.CARDIO
    // The jump chips change numbers a field may be typing over; each tap ends the typing so the new number shows.
    var jumps by remember { mutableIntStateOf(0) }

    fun values() = SetEntry(
        0, weightKg = if (cardio) null else Units.toKg(weight, unit),
        reps = if (t.usesReps) reps else null,
        durationSec = if (t.usesTime) seconds else null,
        distanceM = if (t.usesDistance) distance.takeIf { it > 0 }?.let { Measure.toMetres(it, t, unit) } else null,
        level = if (cardio) level.takeIf { it > 0 } else null, heartRate = if (cardio) heart.takeIf { it > 0 } else null,
    )
    val ready = when {
        t.usesReps -> reps > 0
        t.usesTime -> seconds > 0
        else -> distance > 0
    }

    BottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((if (cardio) "CARDIO" else "SET ${setIndex + 1}") + " · ${ex.name.uppercase()}", style = Type.Label, textAlign = TextAlign.Center)
            Text(
                previous?.takeIf { Measure.hasValue(t, it) }?.let { "Previous ${Measure.format(entry, it, unit)}" } ?: "Nothing to compare yet",
                style = Type.BodyS.copy(fontSize = 13.sp), textAlign = TextAlign.Center,
            )
        }
        if (cardio) {
            StepperSection("TIME · ±1 MIN") { TimeStepper(seconds, step = 60, what = "time", onStep = { seconds = (seconds + it).coerceAtLeast(0) }) { seconds = it } }
            val dl = Measure.distanceLabel(t, unit)
            StepperSection("DISTANCE · ±0.1 ${dl.uppercase()} · OPTIONAL") {
                StepperRow(
                    value = Units.num(distance), suffix = dl, decimal = true, what = "distance",
                    onMinus = { distance = (distance - 0.1).coerceAtLeast(0.0) }, onPlus = { distance += 0.1 },
                    onTyped = { it.toDoubleOrNull()?.let { v -> distance = v.coerceAtLeast(0.0) } },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("LEVEL", style = Type.Label)
                    SmallCounter(level, "level", Modifier.fillMaxWidth()) { level = (level + it).coerceIn(0, 30) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("AVG HEART RATE", style = Type.Label)
                    // Up from "not logged" starts at a typical 130 rather than 1.
                    SmallCounter(heart, "heart rate", Modifier.fillMaxWidth()) { heart = if (heart == 0 && it > 0) 130 else (heart + it).coerceIn(0, 240) }
                }
            }
        } else {
            val weightLabel = when (t) {
                Tracking.ASSISTED -> "ASSISTANCE · LESS IS HARDER"
                Tracking.BODYWEIGHT_REPS, Tracking.DURATION -> "ADDED WEIGHT"
                else -> "WEIGHT" + if (entry.perHand) " PER HAND" else ""
            }
            StepperSection("$weightLabel · ±${Units.num(step)}") {
                StepperRow(
                    value = (if (t == Tracking.ASSISTED && weight > 0) "−" else "") + Units.num(weight), suffix = unit.label, decimal = true, what = "weight",
                    onMinus = { weight = (weight - step).coerceAtLeast(0.0) }, onPlus = { weight += step },
                    onTyped = { it.toDoubleOrNull()?.let { v -> weight = v.coerceAtLeast(0.0) } },
                    edit = Units.num(weight), resetKey = jumps,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.25 to "+1.25", 5.0 to "+5", 10.0 to "+10").forEach { (jump, label) -> Chip(label, false, Modifier.weight(1f)) { weight += jump; jumps++ } }
                Chip("= LAST", false, Modifier.weight(1f)) {
                    jumps++
                    previous?.let { p ->
                        weight = Units.toDisplay(p.weightKg ?: 0.0, unit)
                        p.reps?.let { reps = it }; p.durationSec?.let { seconds = it }; p.distanceM?.let { distance = Measure.fromMetres(it, t, unit) }
                    }
                }
            }
            when {
                t.usesReps -> StepperSection("REPS · ±1") {
                    StepperRow(
                        value = reps.toString(), suffix = null, decimal = false, what = "reps",
                        onMinus = { reps = (reps - 1).coerceAtLeast(0) }, onPlus = { reps += 1 },
                        onTyped = { it.toIntOrNull()?.let { v -> reps = v.coerceIn(0, 999) } }, resetKey = jumps,
                    )
                }
                t == Tracking.DURATION -> StepperSection("TIME · ±5 S") {
                    TimeStepper(seconds, step = 5, what = "time", resetKey = jumps, onStep = { seconds = (seconds + it).coerceAtLeast(0) }) { seconds = it }
                }
                else -> {
                    val dl = Measure.distanceLabel(t, unit)
                    StepperSection("DISTANCE · ±5 ${dl.uppercase()}") {
                        StepperRow(
                            value = Units.num(distance), suffix = dl, decimal = true, what = "distance",
                            onMinus = { distance = (distance - 5).coerceAtLeast(0.0) }, onPlus = { distance += 5 },
                            onTyped = { it.toDoubleOrNull()?.let { v -> distance = v.coerceAtLeast(0.0) } }, resetKey = jumps,
                        )
                    }
                }
            }
            if (entry.sets.drop(setIndex + 1).any { !it.done }) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Apply to remaining sets", style = Type.BodyM, modifier = Modifier.weight(1f))
                Switch(applyAll, label = "Apply to remaining sets") { applyAll = it }
            }
        }
        PrimaryButton(
            when { set.done -> if (cardio) "Update cardio" else "Update set"; cardio -> "Log cardio"; else -> "Log set · start rest" },
            Modifier.fillMaxWidth(), enabled = ready,
        ) { onLog(values(), applyAll) }
        Text("Tap the number to type it instead.", style = Type.BodyS, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StepperSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Type.Label)
        content()
    }
}

/** Time as m:ss with ±[step] seconds; typing takes "1:05" or plain seconds. */
@Composable
private fun TimeStepper(seconds: Int, step: Int, what: String, resetKey: Int = 0, onStep: (Int) -> Unit, onChange: (Int) -> Unit) {
    // Steps go out as ±[step] for the caller to add to its live state: a value captured here can be a
    // frame old, and two taps inside one frame would then both land on it.
    StepperRow(
        value = Measure.time(seconds), suffix = null, decimal = false, what = what, time = true,
        onMinus = { onStep(-step) }, onPlus = { onStep(step) },
        onTyped = { raw -> parseTime(raw)?.let { onChange(it.coerceIn(0, 24 * 3600)) } }, resetKey = resetKey,
    )
}

/** "1:05" → 65, "1:02:05" → 3725, "90" → 90. */
internal fun parseTime(raw: String): Int? {
    val parts = raw.trim().split(':').map { it.toIntOrNull() ?: return null }
    return when (parts.size) {
        1 -> parts[0]
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> null
    }
}

/** A compact −  n  + for level and heart rate; 0 shows as "–" (not logged). [onStep] gets −1 or +1. */
@Composable
private fun SmallCounter(value: Int, what: String, modifier: Modifier, onStep: (Int) -> Unit) {
    Row(modifier.panel(radius = Forge.RCard).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).panel(bg = Forge.Surface2, radius = Forge.RRow).stepper("Less $what") { onStep(-1) }, contentAlignment = Alignment.Center) {
            ForgeIcon(R.drawable.ic_minus, 16.dp, Forge.Text)
        }
        Text(if (value == 0) "–" else "$value", style = Type.DataM.copy(fontSize = 18.sp), textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("value-$what"))
        Box(Modifier.size(48.dp).panel(bg = Forge.Surface2, radius = Forge.RRow).stepper("More $what") { onStep(1) }, contentAlignment = Alignment.Center) {
            ForgeIcon(R.drawable.ic_plus, 16.dp, Forge.Text)
        }
    }
}

/** − [ big number ] + ; tapping the number switches to a keyboard field (mono, never serif, inside inputs). */
@Composable
private fun StepperRow(
    value: String, suffix: String?, decimal: Boolean, what: String, onMinus: () -> Unit, onPlus: () -> Unit, onTyped: (String) -> Unit,
    /** Time entry: digits and ":" ("1:05"); a text keyboard, since number pads have no colon. */
    time: Boolean = false,
    /** The number as it's typed, when that differs from how it's shown (assisted "−20" types as "20"). */
    edit: String = value,
    /** Changing it ends typing, for numbers changed from outside the row (the jump chips). */
    resetKey: Int = 0,
) {
    var typing by remember(resetKey) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        // A step while typing shows the number again, so the change is visible (the field kept its own text).
        StepButton(R.drawable.ic_minus, "Less $what") { typing = false; onMinus() }
        Row(
            Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp).testTag("value-$what").panel(bg = Forge.Bg, border = Forge.Lime, radius = Forge.RCard).tap(action = "Type a value") { typing = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (typing) NumberField(edit, decimal, time, onTyped) { typing = false }
            else {
                Text(value, style = Type.FigureM.copy(color = Forge.Lime))
                if (suffix != null) { Spacer(Modifier.width(6.dp)); Text(suffix, style = Type.DataS.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)) }
            }
        }
        StepButton(R.drawable.ic_plus, "More $what") { typing = false; onPlus() }
    }
}

/**
 * The keyboard field (v1.0.1). It opens with the pre-filled number selected, so typing replaces it.
 * Emptied, it shows that number faded and keeps it; a zero opens empty, so "0" is never typed after.
 */
@Composable
private fun RowScope.NumberField(prefilled: String, decimal: Boolean, time: Boolean, onTyped: (String) -> Unit, onDone: () -> Unit) {
    val placeholder = remember { prefilled }
    var field by remember {
        val start = if (placeholder.none { it in '1'..'9' }) "" else placeholder
        mutableStateOf(TextFieldValue(start, TextRange(0, start.length)))
    }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val style = Type.DataM.copy(fontSize = 28.sp, color = Forge.Lime, textAlign = TextAlign.Center)
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        if (field.text.isEmpty()) Text(placeholder, style = style.copy(color = Forge.Lime.copy(alpha = 0.35f)), modifier = Modifier.fillMaxWidth())
        BasicTextField(
            field, { v ->
                val clean = v.text.replace(',', '.').filter { it.isDigit() || (decimal && it == '.') || (time && it == ':') }.take(8)
                if (clean.count { it == '.' } <= 1) {
                    // Losing focus also lands here, text unchanged: only an edit sets the number, or it
                    // would undo a step taken meanwhile.
                    if (clean != field.text) onTyped(clean.ifEmpty { placeholder })
                    field = if (clean == v.text) v else TextFieldValue(clean, TextRange(clean.length))
                }
            },
            singleLine = true, cursorBrush = SolidColor(Forge.Lime), textStyle = style,
            keyboardOptions = KeyboardOptions(keyboardType = if (time) KeyboardType.Text else if (decimal) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().testTag("typing").focusRequester(focus).onFocusChanged { s ->
                if (s.isFocused && !focused) field = field.copy(selection = TextRange(0, field.text.length))
                if (focused && !s.isFocused) onDone()
                focused = s.isFocused
            },
        )
    }
}

@Composable
private fun StepButton(icon: Int, label: String, onStep: () -> Unit) {
    Box(Modifier.size(64.dp).panel(bg = Forge.Surface2, radius = Forge.RCard).stepper(label, onStep), contentAlignment = Alignment.Center) {
        ForgeIcon(icon, 22.dp, Forge.Text)
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
