package app.forge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.forge.AppViewModel
import app.forge.R
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.Overlay
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val shortMonth = DateTimeFormatter.ofPattern("MMM", Locale.US)
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.US)

@Composable
fun MetricsScreen(data: AppData, vm: AppViewModel, nav: Nav, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val h = data.history
    val cabinet = remember(data) { Cabinet.entries(data) }
    val xp = remember(h, data.settings.weeklyGoal, data.streak) { Xp.total(data) }
    val level = "Level ${Xp.level(xp)} · ${Xp.tier(xp).label}"
    val kicker = when (tab) {
        0 -> level
        1 -> "Estimated 1RM · Epley"
        2 -> "Sets per muscle · last 7 days"
        else -> "$level · ${cabinet.count { it.earnedOn != null }} of ${cabinet.size} earned"
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(25.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(kicker.uppercase(), style = Type.Label)
            Text("Metrics", style = Type.DisplayL)
        }
        Tabs(listOf("Overview", "Lifts", "Balance", "Cabinet"), tab) { tab = it }
        if (h.isEmpty() && tab != 3) {
            Text("No data, no story.", style = Type.DisplayItalic.copy(color = Forge.Muted))
            Text("Finish a session and the numbers start here.", style = Type.BodyMuted)
            // Debug builds only: it fills the log with made-up sessions.
            if (app.forge.BuildConfig.DEBUG) SecondaryButton("Load demo history", Modifier.fillMaxWidth(), color = Forge.Lime) { vm.loadDemoHistory() }
        } else when (tab) {
            0 -> Overview(data, vm, nav)
            1 -> LiftsList(data, vm, nav)
            2 -> Balance(data, vm)
            else -> { XpBar(xp) { nav.push(Overlay.HowXp) }; CabinetGrid(cabinet) }
        }
    }
}

// ---- 7.1 Overview ---------------------------------------------------------------------------

private enum class Range(val label: String, val weeks: Long?, val phrase: String) {
    W4("4W", 4, "4 weeks"), M3("3M", 13, "3 months"), Y1("1Y", 52, "year"), ALL("All", null, "")
}

@Composable
private fun Overview(data: AppData, vm: AppViewModel, nav: Nav) {
    var range by rememberSaveable { mutableStateOf(Range.M3) }
    val unit = data.settings.unit
    val h = data.history
    val today = Weeks.today()
    val thisMonday = Weeks.monday(today)
    val firstMonday = Weeks.monday(h.minOf { Weeks.date(it.startedAt) })
    val weeks = range.weeks ?: (java.time.temporal.ChronoUnit.WEEKS.between(firstMonday, thisMonday) + 1)
    val from = thisMonday.minusWeeks(weeks - 1)
    val inRange = h.filter { !Weeks.date(it.startedAt).isBefore(from) }
    val before = range.weeks?.let { n -> h.filter { Weeks.date(it.startedAt).let { d -> !d.isBefore(from.minusWeeks(n)) && d.isBefore(from) } } }
    val lifted = inRange.sumOf { Stats.volume(it) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Range.entries.forEach { r -> Chip(r.label, r == range) { range = r } } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (range == Range.ALL) "Lifted since you started" else "Lifted in the last ${range.phrase}", style = Type.BodyS.copy(fontSize = 13.sp))
        Text("${Units.grouped(lifted, unit)} ${unit.label}", style = Type.FigureL)
        before?.sumOf { Stats.volume(it) }?.takeIf { it > 0 }?.let { prev ->
            val pct = ((lifted - prev) / prev * 100).roundToInt()
            DeltaLine(pct, if (pct >= 0) "$pct% vs the ${range.phrase} before" else "${abs(pct)}% down on the ${range.phrase} before")
        }
    }
    val vols = (0 until weeks).map { i -> from.plusWeeks(i) to Weeks.allIn(h, from.plusWeeks(i)).sumOf { Stats.volume(it) } }
    val best = vols.maxByOrNull { it.second }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Weekly volume", best?.takeIf { it.second > 0 }?.let { "Best week: wk ${Weeks.weekOfUse(h, it.first)}" })
        VolumeBars(vols, best?.first, thisMonday, data.streak.pausedWeeks.toSet())
    }
    // Strength: the four lifts done most often in range, est. 1RM now and change across the range.
    // Ties go to the stronger lift, so the big compounds lead; unloaded bodyweight lifts have no 1RM to show.
    // Only weight × reps lifts have an estimated 1RM in kg; other kinds live on their own lift pages.
    val weighted = inRange.flatMap { w -> w.exercises.filter { it.tracking == Tracking.WEIGHT_REPS }.map { it.exerciseId } }
    val best1rm = weighted.distinct().associateWith { id -> Stats.e1rmSeries(h, id).maxOfOrNull { it.second } ?: 0.0 }
    val lifts = weighted.groupingBy { it }.eachCount().entries
        .filter { (best1rm[it.key] ?: 0.0) > 0 }
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { best1rm[it.key] })
        .take(4).map { it.key }
    Column {
        SectionHeader("Strength", "Est. 1RM")
        lifts.forEach { id -> StrengthRow(id, h, from, unit, vm) { nav.push(Overlay.Lift(id)) } }
    }
    CardioCard(h, thisMonday, unit)
}

/** v1.1: a small card with cardio minutes per week (last 6 weeks), shown once any cardio is logged. */
@Composable
private fun CardioCard(h: List<Workout>, thisMonday: LocalDate, unit: WeightUnit) {
    fun cardioSec(w: Workout) = w.exercises.filter { it.tracking == Tracking.CARDIO }.sumOf { e -> e.sets.filter { it.done }.sumOf { it.durationSec ?: 0 } }
    if (h.none { cardioSec(it) > 0 }) return
    val weeks = (5 downTo 0).map { thisMonday.minusWeeks(it.toLong()) }
    val mins = weeks.map { m -> Weeks.allIn(h, m).sumOf(::cardioSec) / 60 }
    val km = Weeks.allIn(h, thisMonday).sumOf { w -> w.exercises.filter { it.tracking == Tracking.CARDIO }.sumOf { e -> e.sets.sumOf { it.distanceM ?: 0.0 } } }
    Column(
        Modifier.fillMaxWidth().panel(radius = Forge.RCard).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Cardio", style = Type.BodyMuted, modifier = Modifier.weight(1f))
            Text("MINUTES PER WEEK", style = Type.Label)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${mins.last()}", style = Type.FigureM.copy(color = Forge.Ice))
            Text(
                "min this week" + (km.takeIf { it > 0 }?.let { " · ${Measure.cardioDistance(it, unit)}" } ?: ""),
                style = Type.BodyS, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        val max = (mins.maxOrNull() ?: 0).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            mins.forEachIndexed { i, m ->
                Box(
                    Modifier.weight(1f).fillMaxHeight((m.toFloat() / max).coerceAtLeast(0.04f))
                        .clip(RoundedCornerShape(2.dp)).background(if (i == mins.lastIndex) Forge.Ice else Forge.Ice.copy(alpha = 0.35f)),
                )
            }
        }
        Text("Cardio never counts toward kg lifted, records or Balance.", style = Type.BodyS)
    }
}

@Composable
private fun DeltaLine(pct: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (pct > 0) ForgeIcon(R.drawable.ic_arrow_up, 12.dp, Forge.Lime)
        Text(text, style = Type.DataS.copy(fontWeight = FontWeight.Medium, color = if (pct > 0) Forge.Lime else Forge.Muted))
    }
}

/** Weekly volume bars: best week lime, this week outlined, month labels underneath. */
@Composable
private fun VolumeBars(vols: List<Pair<LocalDate, Double>>, best: LocalDate?, thisMonday: LocalDate, paused: Set<String> = emptySet()) {
    val measurer = rememberTextMeasurer()
    val label = Type.Label
    val pausedLabel = Type.DataS.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Forge.Ice)
    val max = vols.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val base = 100.dp.toPx()
        val n = vols.size
        val gap = (if (n > 20) 2.dp else 7.83.dp).toPx()
        val bw = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        var lastMonth = -1
        var lastLabelX = -1000f
        vols.forEachIndexed { i, (monday, v) ->
            val x = i * (bw + gap)
            val hgt = (v / max * 92.dp.toPx()).toFloat()
            when {
                // 7.1: a paused week is a short dashed ice bar, so a holiday never looks like failure.
                Weeks.key(monday) in paused && v == 0.0 -> {
                    val ph = 10.dp.toPx()
                    drawRoundRect(
                        Forge.Ice, Offset(x, base - ph), Size(bw, ph), CornerRadius(2.dp.toPx()),
                        style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                    )
                    if (bw >= 6.dp.toPx()) {
                        val t = measurer.measure("paused", pausedLabel)
                        drawText(t, topLeft = Offset((x + bw / 2 - t.size.width / 2).coerceIn(0f, size.width - t.size.width), base - ph - 6.dp.toPx() - t.size.height))
                    }
                }
                monday == thisMonday -> drawRoundRect(Forge.Lime, Offset(x, base - hgt.coerceAtLeast(2f)), Size(bw, hgt.coerceAtLeast(2f)), CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                v > 0 -> drawRoundRect(if (monday == best) Forge.Lime else Forge.Surface2, Offset(x, base - hgt), Size(bw, hgt), CornerRadius(2.dp.toPx()))
            }
            val m = monday.plusDays(6).monthValue
            if (m != lastMonth && (i == 0 || monday.plusDays(6).dayOfMonth <= 7)) {
                if (x - lastLabelX >= 36.dp.toPx()) { lastLabelX = x; drawText(measurer, monday.plusDays(6).format(shortMonth).uppercase(), Offset(x.coerceAtMost(size.width - 24.dp.toPx()), base + 6.dp.toPx()), label) }
                lastMonth = m
            }
        }
        drawRect(Forge.Border, Offset(0f, base), Size(size.width, 1.dp.toPx()))
    }
}

@Composable
private fun StrengthRow(id: String, history: List<Workout>, from: LocalDate, unit: WeightUnit, vm: AppViewModel, onClick: () -> Unit) {
    val ex = vm.exercise(id)
    val tracking = remember(history, id, ex.tracking) {
        if (Library.canSwitchAssist(ex)) ex.tracking else history.firstNotNullOfOrNull { w -> w.exercises.firstOrNull { it.exerciseId == id }?.tracking } ?: Tracking.WEIGHT_REPS
    }
    if (tracking != Tracking.WEIGHT_REPS) { OtherLiftRow(id, history, tracking, unit, vm, onClick); return }
    val series = remember(history, id) { Stats.e1rmSeries(history, id) }
    val now = series.lastOrNull()?.second ?: return
    val start = series.firstOrNull { !Weeks.date(it.first).isBefore(from) }?.second ?: now
    val delta = Units.toDisplay(now, unit) - Units.toDisplay(start, unit)
    Row(Modifier.fillMaxWidth().tap(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(vm.exercise(id).name, style = Type.BodyM.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Sparkline(series.takeLast(8).map { it.second }, Modifier.size(56.dp, 18.dp))
        Text("${Units.num(Units.toDisplay(now, unit).roundToInt().toDouble())} ${unit.label}", style = Type.DataM.copy(fontSize = 13.sp), textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
        Text(
            if (abs(delta) < 0.5) "=" else (if (delta > 0) "+" else "−") + abs(delta).roundToInt(),
            style = Type.DataS.copy(fontWeight = FontWeight.Medium, color = if (delta >= 0.5) Forge.Lime else Forge.Muted),
            textAlign = TextAlign.End, modifier = Modifier.width(28.dp),
        )
    }
    Hairline()
}

/** A lift that isn't weight × reps: its own headline (least help, longest hold, last run…) and a sparkline. */
@Composable
private fun OtherLiftRow(id: String, history: List<Workout>, tracking: Tracking, unit: WeightUnit, vm: AppViewModel, onClick: () -> Unit) {
    val lens = lenses(tracking, unit).first()
    val values = remember(history, id, tracking) {
        history.sortedBy { it.startedAt }.mapNotNull { w -> w.exercises.firstOrNull { it.exerciseId == id && it.tracking == tracking }?.let(lens.value) }
    }
    if (values.isEmpty()) return
    val shown = if (tracking == Tracking.CARDIO) values.last() else if (lens.lowerBetter) values.min() else values.max()
    Row(Modifier.fillMaxWidth().tap(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(vm.exercise(id).name, style = Type.BodyM.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Sparkline(values.takeLast(8).map { if (lens.lowerBetter) -it else it }, Modifier.size(56.dp, 18.dp))
        Text(lens.fmt(shown), style = Type.DataM.copy(fontSize = 13.sp, color = if (tracking == Tracking.CARDIO) Forge.Ice else Forge.Text), textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.widthIn(min = 64.dp))
        Spacer(Modifier.width(28.dp))
    }
    Hairline()
}

@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min(); val hi = values.max().takeIf { it > lo } ?: (lo + 1)
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = i * size.width / (values.size - 1)
            val y = size.height - ((v - lo) / (hi - lo) * size.height).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, Forge.Lime, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ---- Lifts tab + 7.2 Lift progress ------------------------------------------------------------

@Composable
private fun LiftsList(data: AppData, vm: AppViewModel, nav: Nav) {
    val counts = data.history.flatMap { w -> w.exercises.map { it.exerciseId } }.groupingBy { it }.eachCount()
    Column {
        SectionHeader("Every lift you've logged", "${counts.size}")
        counts.entries.sortedByDescending { it.value }.forEach { (id, _) ->
            StrengthRow(id, data.history, LocalDate.MIN, data.settings.unit, vm) { nav.push(Overlay.Lift(id)) }
        }
    }
}

/**
 * One way to chart a lift: what a session's value is, how it reads, and whether lower is better
 * (assistance). Each tracking type gets its own set (v1.1).
 */
private class Lens(val label: String, val headline: String, val value: (ExerciseEntry) -> Double?, val fmt: (Double) -> String, val lowerBetter: Boolean = false)

private fun lenses(t: Tracking, unit: WeightUnit): List<Lens> {
    fun kg(v: Double) = "${Units.w(v, unit)} ${unit.label}"
    fun working(e: ExerciseEntry) = e.sets.filter { Measure.counts(e, it) }
    val volume = Lens("Volume", "Best session volume", { e -> Stats.volume(e).takeIf { it > 0 } }, { "${Units.grouped(it, unit)} ${unit.label}" })
    return when (t) {
        Tracking.WEIGHT_REPS -> listOf(
            Lens("Est. 1RM", "Estimated 1RM", { e -> working(e).maxOfOrNull { Measure.score(e, it) } }, { "${Units.toDisplay(it, unit).roundToInt()} ${unit.label}" }),
            Lens("Best set", "Best set", { e -> Stats.topSet(e)?.let { Stats.setLoad(e, it) } }, ::kg),
            volume,
        )
        Tracking.BODYWEIGHT_REPS -> listOf(
            Lens("Reps", "Most reps in a set", { e -> working(e).maxOfOrNull { it.reps!!.toDouble() } }, { "${it.roundToInt()} reps" }),
            Lens("Added", "Most weight added", { e -> working(e).maxOfOrNull { Stats.setLoad(e, it) } }, { if (it <= 0) "BW" else "+${kg(it)}" }),
            volume,
        )
        Tracking.ASSISTED -> listOf(
            Lens("Assistance", "Least assistance", { e -> working(e).minOfOrNull { Stats.setLoad(e, it) } }, { if (it <= 0) "None" else "−${kg(it)}" }, lowerBetter = true),
            Lens("Reps", "Most reps in a set", { e -> working(e).maxOfOrNull { it.reps!!.toDouble() } }, { "${it.roundToInt()} reps" }),
        )
        Tracking.DURATION -> listOf(
            Lens("Time", "Longest set", { e -> working(e).maxOfOrNull { it.durationSec!!.toDouble() } }, { Measure.time(it.roundToInt()) }),
            Lens("Added", "Most weight added", { e -> working(e).maxOfOrNull { Stats.setLoad(e, it) } }, { if (it <= 0) "None" else "+${kg(it)}" }),
        )
        Tracking.DISTANCE_WEIGHT -> listOf(
            Lens("Weight", "Heaviest carry", { e -> working(e).maxOfOrNull { Stats.setLoad(e, it) } }, ::kg),
            Lens("Distance", "Farthest carry", { e -> working(e).maxOfOrNull { it.distanceM ?: 0.0 } }, { Measure.carryDistance(it, unit) }),
        )
        Tracking.CARDIO -> listOf(
            Lens("Minutes", "Last session", { e -> e.sets.sumOf { it.durationSec ?: 0 }.toDouble().takeIf { it > 0 } }, { Measure.time(it.roundToInt()) }),
            Lens("Distance", "Last distance", { e -> e.sets.sumOf { it.distanceM ?: 0.0 }.takeIf { it > 0 } }, { Measure.cardioDistance(it, unit) }),
        )
    }
}

@Composable
fun LiftDetail(exerciseId: String, data: AppData, vm: AppViewModel, nav: Nav, formOpen: Boolean = false) {
    val unit = data.settings.unit
    val ex = vm.exercise(exerciseId)
    val all = remember(data.history, exerciseId) {
        data.history.sortedBy { it.startedAt }.mapNotNull { w -> w.exercises.firstOrNull { it.exerciseId == exerciseId }?.let { w to it } }
    }
    // The type comes from the Assisted / Weighted switch where there is one, else how it was last logged,
    // else the library. Only sessions logged that way chart together: assistance isn't added weight.
    val tracking = if (Library.canSwitchAssist(ex)) ex.tracking else all.lastOrNull()?.second?.tracking ?: ex.tracking
    val sessions = all.filter { it.second.tracking == tracking }
    val lensList = remember(tracking, unit) { lenses(tracking, unit) }
    var lensAt by remember(tracking) { mutableIntStateOf(0) }
    val lens = lensList[lensAt.coerceIn(lensList.indices)]
    val raw = sessions.mapNotNull { (w, e) -> lens.value(e)?.let { w.startedAt to it } }
    // Charted so that up is always better: assistance is drawn upside down.
    val points = raw.map { (at, v) -> at to (if (lens.lowerBetter) -v else v) }
    val prev = Stats.previousSets(data.history, exerciseId, tracking)
    // 10.3 from Metrics: same rules and reset as after the summary, applied to every template with this lift.
    val stall = remember(data.templates, data.history, exerciseId, tracking) { Progression.liftStall(data.templates, data.history, exerciseId, System.currentTimeMillis(), tracking) }
    var stallOpen by remember { mutableStateOf(false) }
    if (stallOpen && stall != null) {
        val rule = data.templates.flatMap { it.exercises }.first { it.exerciseId == exerciseId }
        val lighter = Progression.lighterKg(rule, prev, unit)
        val variation = Progression.variationFor(exerciseId, vm.allExercises)
        StallSheet(stall, ex.name, lighter, rule.repMin, variation, unit, onDismiss = { stallOpen = false }) { choice ->
            vm.answerStall(exerciseId, choice, lighter, variation?.id, System.currentTimeMillis())
            stallOpen = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_back, { nav.pop() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(listOf(ex.muscle, ex.equipment.label, "${sessions.size} ${if (sessions.size == 1) "session" else "sessions"}").joinToString(" · ").uppercase(), style = Type.Label)
                Text(ex.name, style = Type.DisplayL)
            }
            // v1.1: form cues, collapsed, for every exercise (even ones not logged yet).
            FormSection(ex, vm, startOpen = formOpen)
            if (Library.canSwitchAssist(ex)) AssistSwitch(ex, data, vm)
            if (sessions.isEmpty() || raw.isEmpty()) { Text("Not logged yet.", style = Type.BodyMuted); return@Column }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(lens.headline, style = Type.BodyS.copy(fontSize = 13.sp))
                    // The best ever for most lenses; for cardio, the latest session.
                    val headline = if (tracking == Tracking.CARDIO) raw.last().second else if (lens.lowerBetter) raw.minOf { it.second } else raw.maxOf { it.second }
                    Text(lens.fmt(headline), style = Type.FigureL, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (stall != null) Text(
                        "STALLED · ${Progression.STALL_SESSIONS} SESSIONS",
                        style = Type.DataS.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Forge.Amber),
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Forge.Amber.copy(alpha = 0.12f))
                            .tap(action = "Choose what to do") { stallOpen = true }.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
                if (raw.size > 1) {
                    val first = raw.first().second; val last = raw.last().second
                    val better = if (lens.lowerBetter) first - last else last - first
                    val month = Weeks.date(raw.first().first).format(DateTimeFormatter.ofPattern("MMMM", Locale.US))
                    val change = when {
                        kotlin.math.abs(better) < 0.01 -> "level since you started"
                        tracking == Tracking.WEIGHT_REPS && lensAt == 0 -> "${Units.toDisplay(kotlin.math.abs(last - first), unit).roundToInt()} ${unit.label} ${if (better > 0) "up" else "down"} since $month"
                        lens.lowerBetter -> "${Units.w(kotlin.math.abs(last - first), unit)} ${unit.label} ${if (better > 0) "less" else "more"} help since $month"
                        else -> (if (better > 0) "up from " else "down from ") + lens.fmt(first) + " in $month"
                    }
                    DeltaLine(if (better > 0.01) 1 else 0, change)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { lensList.forEachIndexed { i, l -> Chip(l.label, i == lensAt) { lensAt = i } } }
                LineChart(points.map { if (tracking == Tracking.WEIGHT_REPS || lens.label == "Volume") Units.toDisplay(it.second, unit) else it.second })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(Weeks.date(points.first().first).format(shortMonth).uppercase(), style = Type.Label)
                    Text(if (lens.lowerBetter) "UP = LESS HELP · DOT = RECORD" else "DOT = NEW RECORD", style = Type.Label)
                    Text(Weeks.date(points.last().first).format(shortMonth).uppercase(), style = Type.Label)
                }
            }
            Column {
                SectionHeader("Records")
                Records(sessions, tracking, unit)
            }
        }
        // What the progression engine will suggest next time, by the rule of the first template using this lift.
        val rule = data.templates.flatMap { it.exercises }.firstOrNull { it.exerciseId == exerciseId }
            ?: Progression.working(prev, tracking).firstOrNull()?.reps?.let { r -> TemplateExercise(exerciseId, reps = r, repMin = r, repMax = r) }
        rule?.takeIf { tracking.usesReps }?.let { Progression.target(it, prev, unit, tracking) }
            ?.takeIf { it.weightKg != null && (it.weightKg > 0 || tracking != Tracking.WEIGHT_REPS) && it.reps != null }?.let { t ->
            Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = Forge.Gutter, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ForgeIcon(R.drawable.ic_sparkle, 18.dp, Forge.Lime)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Next time: " + Measure.format(tracking, SetEntry(0, weightKg = t.weightKg, reps = t.reps), unit), style = Type.TitleM.copy(fontSize = 14.sp))
                    Text(
                        when (t.kind) {
                            Progression.Kind.LEVEL_UP -> "You hit your reps on every set last time."
                            Progression.Kind.LIGHTER -> "A planned lighter week."
                            else -> "Same weight; aim for ${t.reps} on every set."
                        },
                        style = Type.BodyS,
                    )
                }
            }
        }
    }
}

/** The lift page's records, per tracking type. */
@Composable
private fun Records(sessions: List<Pair<Workout, ExerciseEntry>>, t: Tracking, unit: WeightUnit) {
    val all = sessions.flatMap { (w, e) -> e.sets.filter { Measure.counts(e, it) }.map { Triple(w, e, it) } }
    @Composable fun row(label: String, x: Triple<Workout, ExerciseEntry, SetEntry>?) = x?.let { (w, e, s) -> RecordRow(label, Measure.format(e, s, unit), w.startedAt) }
    when (t) {
        Tracking.WEIGHT_REPS -> {
            row("Heaviest set", all.maxWithOrNull(compareBy({ Stats.setLoad(it.second, it.third) }, { it.third.reps })))
            // Most reps at the load used most often.
            all.groupBy { Units.w(Stats.setLoad(it.second, it.third), unit) }.maxByOrNull { it.value.size }?.let { (load, sets) ->
                sets.maxBy { it.third.reps!! }.let { (w, _, s) -> RecordRow("Most reps at $load ${unit.label}", "${s.reps}", w.startedAt) }
            }
            sessions.maxByOrNull { Stats.volume(it.second) }?.let { (w, e) -> RecordRow("Best session volume", "${Units.grouped(Stats.volume(e), unit)} ${unit.label}", w.startedAt) }
        }
        Tracking.BODYWEIGHT_REPS -> {
            row("Most reps", all.maxWithOrNull(compareBy({ it.third.reps }, { Stats.setLoad(it.second, it.third) })))
            all.filter { Stats.setLoad(it.second, it.third) > 0 }.maxWithOrNull(compareBy({ Stats.setLoad(it.second, it.third) }, { it.third.reps }))?.let { row("Heaviest added", it) }
        }
        Tracking.ASSISTED -> {
            row("Least assistance", all.minWithOrNull(compareBy<Triple<Workout, ExerciseEntry, SetEntry>>({ Stats.setLoad(it.second, it.third) }, { -(it.third.reps ?: 0) })))
            row("Most reps", all.maxWithOrNull(compareBy({ it.third.reps }, { -Stats.setLoad(it.second, it.third) })))
        }
        Tracking.DURATION -> row("Longest", all.maxWithOrNull(compareBy({ it.third.durationSec }, { Stats.setLoad(it.second, it.third) })))
        Tracking.DISTANCE_WEIGHT -> {
            row("Heaviest", all.maxWithOrNull(compareBy({ Stats.setLoad(it.second, it.third) }, { it.third.distanceM })))
            row("Farthest", all.maxWithOrNull(compareBy({ it.third.distanceM }, { Stats.setLoad(it.second, it.third) })))
        }
        Tracking.CARDIO -> {
            val cardio = sessions.flatMap { (w, e) -> e.sets.filter { it.done && it.durationSec != null }.map { Triple(w, e, it) } }
            row("Longest", cardio.maxByOrNull { it.third.durationSec ?: 0 })
            cardio.filter { (it.third.distanceM ?: 0.0) > 0 }.maxByOrNull { it.third.distanceM!! }?.let { row("Farthest", it) }
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String, at: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = Type.BodyM, modifier = Modifier.weight(1f))
        Text(value, style = Type.DataM.copy(fontSize = 13.sp))
        Text(Weeks.date(at).format(dayMonth).uppercase(), style = Type.Label, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
    }
    Hairline()
}

/** 7.2 line chart: four hairline gridlines, lime line, dots where the value set a new high. */
@Composable
private fun LineChart(values: List<Double>) {
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val top = 10.dp.toPx(); val bottom = size.height - 10.dp.toPx()
        repeat(4) { i -> val y = top + (bottom - top) * i / 3; drawLine(Forge.Border, Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
        if (values.isEmpty()) return@Canvas
        // Padding by magnitude, so it works for assistance drawn as negatives too.
        val lo = values.min().let { it - abs(it) * 0.05 }; val hi = values.max().let { it + abs(it) * 0.02 }.takeIf { it > lo } ?: (lo + 1)
        fun pt(i: Int) = Offset(
            6.dp.toPx() + if (values.size == 1) 0f else i * (size.width - 12.dp.toPx()) / (values.size - 1),
            bottom - ((values[i] - lo) / (hi - lo) * (bottom - top)).toFloat(),
        )
        val p = Path()
        values.indices.forEach { i -> val o = pt(i); if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y) }
        drawPath(p, Forge.Lime, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        var best = Double.NEGATIVE_INFINITY
        values.forEachIndexed { i, v -> if (v > best) { if (i > 0) drawCircle(Forge.Lime, 4.dp.toPx(), pt(i)); best = v } }
    }
}

// ---- 7.3 Balance ----------------------------------------------------------------------------

@Composable
private fun Balance(data: AppData, vm: AppViewModel) {
    val muscleOf: (String) -> String = { vm.exercise(it).muscle }
    val secondaryOf: (String) -> List<String> = { vm.exercise(it).secondary }
    // Secondary muscles count half a set (v1.1), so totals can end in .5.
    val sets = remember(data.history) { Stats.setsPerMuscle(data.history, muscleOf, secondaryOf = secondaryOf) }
    val recent = remember(data.history) { Stats.setsPerMuscle(data.history, muscleOf, days = 28, secondaryOf = secondaryOf) }
    val inTemplates = data.templates.flatMap { t -> t.exercises.map { muscleOf(it.exerciseId) } }.toSet()
    val muscles = Library.strengthMuscles.filter { it in inTemplates || (recent[it] ?: 0.0) > 0 }.ifEmpty { Library.strengthMuscles }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Weekly sets", "Target 10–20")
        muscles.forEach { m ->
            val n = sets[m] ?: 0.0
            val under = n < 8
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(m, style = Type.BodyM.copy(fontSize = 13.sp, color = if (under) Forge.Amber else Forge.Text), modifier = Modifier.width(92.dp))
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    val r = CornerRadius(2.dp.toPx())
                    drawRoundRect(Forge.Surface, size = size, cornerRadius = r)
                    drawRect(Forge.Text.copy(alpha = 0.07f), Offset(size.width * 10 / 24, 0f), Size(size.width * 10 / 24, size.height))
                    val fill = when { under -> Forge.Amber; n < 10 -> Forge.Lime.copy(alpha = 0.5f); else -> Forge.Lime }
                    drawRoundRect(fill, size = Size(size.width * (n.coerceAtMost(24.0) / 24.0).toFloat(), size.height), cornerRadius = r)
                }
                Text(Units.num(n), style = Type.DataM.copy(fontSize = 12.sp, color = if (under) Forge.Amber else Forge.Text), textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(34.dp))
            }
        }
    }
    // One plain-English takeaway: the most under-trained muscle and a one-tap fix.
    val worst = muscles.filter { (sets[it] ?: 0.0) < 8 }.minByOrNull { sets[it] ?: 0.0 } ?: return
    val have = sets[worst] ?: 0.0
    // Fix it where that muscle already lives (Push for chest), with a lift for it that template doesn't have yet,
    // preferring ones you've done before. No template trains it? Use the shortest template.
    val template = data.templates.maxByOrNull { t -> t.exercises.count { muscleOf(it.exerciseId) == worst } * 100 - t.exercises.size }
    val used = data.history.flatMap { w -> w.exercises.map { it.exerciseId } }.groupingBy { it }.eachCount()
    val exercise = vm.allExercises.filter { it.muscle == worst && it.tracking.usesReps && it.tracking != Tracking.ASSISTED && template?.exercises?.none { te -> te.exerciseId == it.id } != false }
        .maxByOrNull { used[it.id] ?: 0 }?.id ?: return
    Column(Modifier.fillMaxWidth().border(width = 0.dp, color = Color.Transparent)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Forge.Amber))
        Spacer(Modifier.height(16.dp))
        Text("$worst ${if (worst.endsWith("s")) "are" else "is"} under target", style = Type.TitleM)
        Spacer(Modifier.height(12.dp))
        val exName = vm.exercise(exercise).name
        Text(
            "${Units.num(have)} ${if (have == 1.0) "set" else "sets"} this week." + (template?.let { " Adding 3 sets of $exName to ${it.name} would bring ${if (worst.endsWith("s")) "them" else "it"} to ${Units.num(have + 3)}, and ${Units.num(have + 6)} next week." } ?: ""),
            style = Type.BodyMuted.copy(fontSize = 13.sp),
        )
        if (template != null) {
            Spacer(Modifier.height(12.dp))
            var done by remember(template.id, exercise) { mutableStateOf(false) }
            if (!done) SecondaryButton("Add $exName to ${template.name}", Modifier.fillMaxWidth()) {
                vm.addToTemplate(template.id, exercise); done = true
            } else Row(
                // Once added: a quiet confirmation, not a button.
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(R.drawable.ic_check, 16.dp, Forge.Lime)
                Spacer(Modifier.width(8.dp))
                Text("Added to ${template.name}", style = Type.Button.copy(color = Forge.Lime))
            }
        }
    }
}

// ---- 7.4 Cabinet ----------------------------------------------------------------------------

@Composable
internal fun CabinetGrid(entries: List<Cabinet.Entry>) {
    // The XP bar above the covers arrives with levels in Phase 6.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { CoverCard(it, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CoverCard(e: Cabinet.Entry, modifier: Modifier) {
    val earned = e.earnedOn != null
    val (bg, ink, sub) = when {
        !earned -> Triple(Color.Transparent, Forge.Muted, Forge.Muted)
        e.cover == Cabinet.Cover.LIME -> Triple(Forge.Lime, Forge.OnLime, Forge.OnLime)
        e.cover == Cabinet.Cover.AMBER -> Triple(Forge.Amber, Forge.OnLime, Forge.OnLime)
        e.cover == Cabinet.Cover.ICE -> Triple(Forge.Ice, Forge.OnLime, Forge.OnLime)
        else -> Triple(Forge.Surface2, Forge.Text, Forge.Muted)
    }
    val shape = RoundedCornerShape(Forge.RTile)
    Column(
        modifier.aspectRatio(110f / 150f).clip(shape).background(bg)
            .then(if (!earned) Modifier.dashedBorder(Forge.Border, Forge.RTile) else if (e.cover == Cabinet.Cover.DARK) Modifier.border(1.dp, Forge.Border, shape) else Modifier)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("No. %02d".format(e.no), style = Type.DataS.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = ink))
            if (!earned) ForgeIcon(R.drawable.ic_lock, 12.dp, Forge.Muted)
        }
        if (earned) Glyph(e.glyph, if (e.cover == Cabinet.Cover.DARK) Forge.Lime else Color(0xFF0A0A0D))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (!earned && e.secret) "???" else e.title, style = Type.TitleM.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ink, lineHeight = 1.15.em))
            Text((e.earnedOn?.format(dayMonth) ?: e.goal).uppercase(), style = Type.Label.copy(color = sub))
        }
    }
}

@Composable
private fun Glyph(g: Cabinet.Glyph, color: Color) {
    Box(Modifier.fillMaxWidth().height(40.dp)) {
        when (g) {
            Cabinet.Glyph.CIRCLE -> Image(painterResource(R.drawable.cover_circle), null, Modifier.size(90.dp, 40.dp), colorFilter = ColorFilter.tint(color))
            Cabinet.Glyph.DOT -> Image(painterResource(R.drawable.cover_dot), null, Modifier.size(90.dp, 40.dp), colorFilter = ColorFilter.tint(color))
            Cabinet.Glyph.TRIANGLE -> Image(painterResource(R.drawable.cover_triangle), null, Modifier.size(90.dp, 40.dp), colorFilter = ColorFilter.tint(color))
            Cabinet.Glyph.SQUARE -> Box(Modifier.offset(y = 6.dp).size(28.dp).background(color))
            Cabinet.Glyph.BARS -> Row(Modifier.offset(y = 5.dp).height(30.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                listOf(10, 20, 30).forEach { Box(Modifier.size(8.dp, it.dp).background(color)) }
            }
            // Added with the second shelf of covers: the same flat shapes, same footprint.
            Cabinet.Glyph.RING -> Box(Modifier.offset(y = 5.dp).size(30.dp).border(6.dp, color, CircleShape))
            Cabinet.Glyph.DIAMOND -> Box(Modifier.offset(x = 4.dp, y = 9.dp).size(22.dp).rotate(45f).background(color))
            Cabinet.Glyph.HALF -> Canvas(Modifier.offset(y = 12.dp).size(30.dp, 15.dp)) {
                drawArc(color, 180f, 180f, useCenter = true, topLeft = Offset.Zero, size = androidx.compose.ui.geometry.Size(size.width, size.width))
            }
            Cabinet.Glyph.PLUS -> Box(Modifier.offset(y = 5.dp).size(30.dp)) {
                Box(Modifier.align(Alignment.Center).size(30.dp, 8.dp).background(color))
                Box(Modifier.align(Alignment.Center).size(8.dp, 30.dp).background(color))
            }
        }
    }
}
