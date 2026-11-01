package app.forge.ui.screens

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.AppViewModel
import app.forge.R
import app.forge.data.*
import app.forge.ui.clock
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Step { SUMMARY, LEVEL_UP, REWARDS }

/** 5.2 Summary → (10.3 stalls) → (13.3 level-up) → 5.3 Rewards → (5.4 if the session changed its template) → Home. */
@Composable
fun FinishFlow(w: Workout, prior: List<Workout>, data: AppData, vm: AppViewModel, onDone: () -> Unit) {
    var step by remember { mutableStateOf(Step.SUMMARY) }
    var askUpdate by remember { mutableStateOf(false) }
    val template = data.templates.firstOrNull { it.id == w.templateId }
    val changes = remember(template, w) { template?.let { TemplateDiff.changes(it, w) }.orEmpty() }

    // 10.3: lifts that stalled with this session, asked about one at a time after the summary.
    val stalls = remember(w) { Progression.stalls(template, w, listOf(w) + prior) }
    var stallAt by remember { mutableStateOf<Int?>(null) }

    // 13.3: shown only when this session tipped XP into a new level, never mid-set.
    val before = remember(w) { data.copy(history = prior) }
    val xpBefore = remember(w) { Xp.total(before) }
    val xpAfter = remember(w) { Xp.total(data.history.let { h -> if (h.any { it.id == w.id }) data else data.copy(history = listOf(w) + prior) }) }
    val unlocked = remember(w) { Unlocks.newlyUnlocked(Unlocks.progress(before), Unlocks.progress(data.copy(history = listOf(w) + prior))) }
    val afterSummary = if (Xp.level(xpAfter) > Xp.level(xpBefore)) Step.LEVEL_UP else Step.REWARDS

    when (step) {
        Step.SUMMARY -> WorkoutSummary(w, prior, data, vm, onClose = onDone) { if (stalls.isNotEmpty()) stallAt = 0 else step = afterSummary }
        Step.LEVEL_UP -> LevelUpScreen(
            xpAfter, Xp.tier(xpAfter) != Xp.tier(xpBefore), unlocked,
            onUseIcon = { vm.setAppIcon(it); step = Step.REWARDS }, onLater = { step = Step.REWARDS },
        )
        Step.REWARDS -> Rewards(w, data) { if (changes.isNotEmpty()) askUpdate = true else onDone() }
    }
    stallAt?.let { n ->
        val (index, stall) = stalls[n]
        fun next() { if (n + 1 < stalls.size) stallAt = n + 1 else { stallAt = null; step = afterSummary } }
        val rule = data.templates.firstOrNull { it.id == w.templateId }?.exercises?.getOrNull(index)
        if (rule == null || template == null) { next(); return@let }
        val sets = w.exercises.first { it.exerciseId == rule.exerciseId }.sets
        val lighter = Progression.lighterKg(rule, sets, data.settings.unit)
        val variation = Progression.variationFor(rule.exerciseId, vm.allExercises)
        key(n) {
            StallSheet(stall, vm.exercise(rule.exerciseId).name, lighter, rule.repMin, variation, data.settings.unit, onDismiss = ::next) { choice ->
                vm.answerStall(rule.exerciseId, choice, lighter, variation?.id, w.startedAt)
                next()
            }
        }
    }
    if (askUpdate && template != null) UpdateTemplateSheet(template, changes, vm, onUpdate = { vm.updateTemplateFrom(w) }) { askUpdate = false; onDone() }
}

/** 5.4: what changed against the template, applied only if asked. */
@Composable
internal fun UpdateTemplateSheet(template: Template, changes: List<TemplateDiff.Change>, vm: AppViewModel, onUpdate: () -> Unit, onClose: () -> Unit) {
    BottomSheet(onClose) {
        SheetHeader("Save changes to ${template.name}?", "Your new weights carry forward automatically. These changes are about the template itself.")
        Column(Modifier.fillMaxWidth().panel(bg = Forge.Bg, radius = Forge.RCard).padding(4.dp)) {
            changes.forEachIndexed { i, c ->
                if (i > 0) Hairline()
                val (icon, title, sub) = when (c) {
                    is TemplateDiff.Change.Added -> Triple(R.drawable.ic_plus, "Added ${vm.exercise(c.exerciseId).name}", "${c.sets} sets")
                    is TemplateDiff.Change.Swapped -> Triple(R.drawable.ic_swap, "Swapped ${vm.exercise(c.from).name}", "for ${vm.exercise(c.to).name}")
                    is TemplateDiff.Change.MoreSets -> Triple(R.drawable.ic_plus, "Added a ${ordinal(c.sets)} set", vm.exercise(c.exerciseId).name)
                    is TemplateDiff.Change.Reordered -> Triple(R.drawable.ic_move, "New order", c.order.joinToString(" · ") { vm.exercise(it).name })
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ForgeIcon(icon, 18.dp, Forge.Lime)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = Type.TitleM.copy(fontSize = 14.sp))
                        Text(sub, style = Type.BodyS)
                    }
                }
            }
        }
        PrimaryButton("Update template", Modifier.fillMaxWidth()) { onUpdate(); onClose() }
        GhostButton("Just this once", Modifier.fillMaxWidth(), onClick = onClose)
    }
}

private fun ordinal(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

// ---- 5.2 Summary ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutSummary(w: Workout, prior: List<Workout>, data: AppData, vm: AppViewModel, onClose: () -> Unit, onContinue: () -> Unit) {
    val unit = data.settings.unit
    val ctx = LocalContext.current
    val prs = remember(w, prior) { Stats.sessionPrs(w, prior) }
    val headline = remember(w, prior) { Stats.headline(w, prior) }
    val last = remember(w, prior) { Stats.lastLike(w, prior) }
    val planned = data.templates.firstOrNull { it.id == w.templateId }?.exercises?.sumOf { it.sets }

    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_close, onClose) {
            IconTap(R.drawable.ic_share, Forge.Text, 20.dp, description = "Share session") {
                val text = "${w.name} — $headline\n${Stats.durationMin(w)} min · ${Units.grouped(Stats.volume(w), unit)} ${unit.label} · ${Stats.doneSets(w)} sets" +
                    "\n\nLogged with ${app.forge.Brand.FULL}"
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share session"))
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${w.name} · complete · ${Weeks.date(w.startedAt).format(dayFmt)}".uppercase(), style = Type.Kicker)
                Text(headline, style = Type.DisplayM)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                SummaryBox("Duration", clock(Stats.durationMs(w)), Modifier.weight(1f))
                if (!w.cardioOnly) SummaryBox("Total lifted", "${Units.grouped(Stats.volume(w), unit)} ${unit.label}", Modifier.weight(1f)) {
                    last?.let { Stats.volume(it) }?.takeIf { it > 0 }?.let { before ->
                        val pct = ((Stats.volume(w) - before) / before * 100).roundToInt()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (pct > 0) ForgeIcon(R.drawable.ic_arrow_up, 12.dp, Forge.Lime)
                            Text(
                                when { pct > 0 -> "+$pct% vs last"; pct < 0 -> "−${abs(pct)}% vs last"; else -> "= last time" },
                                style = Type.DataS.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = if (pct > 0) Forge.Lime else Forge.Muted),
                            )
                        }
                    }
                }
                if (w.cardioOnly) {
                    // A cardio session: distance and time moving instead of sets.
                    val sets = w.exercises.flatMap { it.sets }.filter { it.done }
                    SummaryBox("Distance", sets.sumOf { it.distanceM ?: 0.0 }.takeIf { it > 0 }?.let { Measure.cardioDistance(it, unit) } ?: "—", Modifier.weight(1f))
                    SummaryBox("Moving", Measure.time(sets.sumOf { it.durationSec ?: 0 }), Modifier.weight(1f))
                } else SummaryBox("Sets", if (planned != null) "${Stats.doneSets(w)} / $planned" else "${Stats.doneSets(w)}", Modifier.weight(1f)) {
                    if (w.skippedSets > 0) Text("${w.skippedSets} skipped", style = Type.DataS.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Forge.Muted))
                }
                SummaryBox("Records", if (prs.isEmpty()) "—" else "${prs.size} new", Modifier.weight(1f), lime = prs.isNotEmpty())
            }
            if (prs.isNotEmpty()) Column(
                Modifier.fillMaxWidth().panel(bg = Forge.Surface, border = Forge.Lime, radius = Forge.RCard).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeIcon(R.drawable.ic_trophy, 16.dp, Forge.Lime)
                    Text("PERSONAL RECORDS", style = Type.DataS.copy(fontWeight = FontWeight.Medium, color = Forge.Lime))
                }
                prs.forEach { pr ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(vm.exercise(pr.exerciseId).name, style = Type.TitleM.copy(fontSize = 15.sp))
                            Text(pr.set?.let { Measure.format(pr.tracking, it, unit, pr.perHand, pr.bodyweight) } ?: "${Units.w(pr.weightKg, unit)} ${unit.label} × ${pr.reps}", style = Type.DataS.copy(fontSize = 12.sp))
                        }
                        Tag(pr.kind)
                    }
                }
            }
            Text("EXERCISES · VS LAST TIME", style = Type.Label)
            Column {
                w.exercises.forEach { e ->
                    val top = Stats.topSet(e)
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(vm.exercise(e.exerciseId).name, style = Type.BodyM.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                top?.let { if (e.tracking == Tracking.CARDIO) Measure.cardio(it, unit) else "${e.sets.count { it.done && it.type != SetType.WARMUP }} × " + Measure.format(e, it, unit) } ?: "",
                                style = Type.DataS,
                            )
                        }
                        // "vs last time" per type: assisted improves as help goes down, timed sets by seconds; cardio has none.
                        val vs = Stats.vsLast(e, prior, w.startedAt, unit)
                        val txt = vs?.text ?: ""
                        val color = if (vs?.better == true) Forge.Lime else Forge.Muted
                        Text(txt, style = Type.DataS.copy(fontWeight = FontWeight.Medium, color = color))
                    }
                    Hairline()
                }
            }
        }
        PrimaryButton("Continue", Modifier.fillMaxWidth().padding(horizontal = Forge.Gutter, vertical = 12.dp), onClick = onContinue)
    }
}

@Composable
private fun SummaryBox(label: String, value: String, modifier: Modifier, lime: Boolean = false, extra: @Composable () -> Unit = {}) {
    Column(modifier.panel(radius = Forge.RButton).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), style = Type.Label)
        Text(value, style = Type.DataM.copy(fontSize = 20.sp, color = if (lime) Forge.Lime else Forge.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        extra()
    }
}

// ---- 5.3 Rewards ------------------------------------------------------------------------------

@Composable
internal fun Rewards(w: Workout, data: AppData, onDone: () -> Unit) {
    val day = Weeks.date(w.startedAt)
    val monday = Weeks.monday(day)
    val done = Weeks.sessionsIn(data.history, monday).size
    val s = data.streak
    // This week's target: the goal, goal + 1 in a repair quest, 2 in a comeback week.
    val target = Streaks.target(s, data.settings.weeklyGoal, day)
    val quest = s.repair?.takeIf { it.week == Weeks.key(day) }
    val streak = Streaks.display(s, data.history, data.settings.weeklyGoal, day)
    val left = (target - done).coerceAtLeast(0)
    val more = if (left == 1) "One more session" else "$left more sessions"
    val message = when {
        target == 0 -> "Your streak is paused this week, so this one's a bonus."
        left == 0 && done == target && quest != null -> "Quest done. Last week counts, and your streak is back to $streak weeks."
        left == 0 && done == target -> "Goal hit for the week. That's $streak ${if (streak == 1) "week" else "weeks"} in a row."
        left == 0 -> "Goal already hit. Everything from here is a bonus."
        quest != null -> "$more by Sunday restores your ${quest.streakAtStake}-week streak."
        s.weeks > 0 -> "$more by Sunday and your streak hits ${s.weeks + 1} weeks."
        else -> "$more by Sunday to hit this week's goal."
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("THIS WEEK", style = Type.Kicker)
            WeekRing(done, target.coerceAtLeast(1), animateFrom = (done - 1).coerceAtLeast(0))
            Text(message, style = Type.TitleM.copy(fontSize = 18.sp), textAlign = TextAlign.Center)
            // XP and milestones (5.3 cards) arrive with Phase 6.
        }
        PrimaryButton("Done", Modifier.fillMaxWidth().padding(horizontal = Forge.Gutter, vertical = 12.dp), onClick = onDone)
    }
}

/** 180dp ring mirroring the Home week strip: sessions done of the weekly goal. */
@Composable
fun WeekRing(done: Int, goal: Int, size: Int = 180, animateFrom: Int? = null) {
    // 11 D · week ring fills: from the last state the arc sweeps on (800 ms) and the count rolls up. When
    // this session hits the goal the number turns lime, one soft pulse goes outward and the success haptic
    // fires (1,200 ms). Everything else gets a light tick.
    val reduce = app.forge.ui.reduceMotion()
    val view = androidx.compose.ui.platform.LocalView.current
    val from = if (reduce) done else (animateFrom ?: done)
    val g = goal.coerceAtLeast(1)
    val frac = remember { Animatable(from.toFloat() / g) }
    val pulse = remember { Animatable(0f) }
    var shown by remember { mutableIntStateOf(from) }
    val hitGoal = done >= g && from < g
    LaunchedEffect(done, goal) {
        if (from == done) { frac.snapTo(done.toFloat() / g); shown = done; return@LaunchedEffect }
        coroutineScope {
            launch { frac.animateTo(done.toFloat() / g, tween(app.forge.ui.Motion.RING_MS, easing = app.forge.ui.Motion.Ring)) }
            launch { kotlinx.coroutines.delay(400); shown = done; app.forge.ui.Haptics.light(view) }
            if (hitGoal) launch {
                kotlinx.coroutines.delay(app.forge.ui.Motion.RING_MS.toLong() + 150)
                app.forge.ui.Haptics.success(view)
                pulse.animateTo(1f, tween(app.forge.ui.Motion.RING_GOAL_MS - app.forge.ui.Motion.RING_MS - 150))
            }
        }
    }
    val lime = shown >= g && (frac.value >= 0.999f)
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 14.dp.toPx()
            val inset = sw / 2
            val arc = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw)
            val o = androidx.compose.ui.geometry.Offset(inset, inset)
            if (pulse.value in 0.001f..0.999f) drawCircle(
                Forge.Lime.copy(alpha = 0.35f * (1f - pulse.value)), this.size.minDimension / 2 * (1f + 0.12f * pulse.value), style = Stroke(1.5.dp.toPx()),
            )
            drawArc(Forge.Border, 0f, 360f, false, o, arc, style = Stroke(sw))
            val f = frac.value.coerceIn(0f, 1f)
            if (f > 0) drawArc(Forge.Lime, -90f, 360f * f, false, o, arc, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            androidx.compose.animation.AnimatedContent(
                shown, label = "count",
                transitionSpec = {
                    if (reduce) androidx.compose.animation.fadeIn(tween(app.forge.ui.Motion.FADE_MS)) togetherWith androidx.compose.animation.fadeOut(tween(app.forge.ui.Motion.FADE_MS))
                    else (androidx.compose.animation.slideInVertically(tween(220)) { it / 2 } + androidx.compose.animation.fadeIn(tween(220))) togetherWith
                        (androidx.compose.animation.slideOutVertically(tween(220)) { -it / 2 } + androidx.compose.animation.fadeOut(tween(160)))
                },
            ) { n -> Text("$n/$goal", style = Type.FigureL.copy(color = if (lime) Forge.Lime else Forge.Text)) }
            Text("SESSIONS", style = Type.Label)
        }
    }
}
