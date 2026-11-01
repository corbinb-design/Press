package app.forge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.R
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---- 10.1 Rules per exercise ------------------------------------------------------------------

/** Opened from an exercise in the template editor. Edits a copy; SAVE hands it back. */
@Composable
fun RulesScreen(
    rule0: TemplateExercise, exerciseName: String, templateName: String, unit: WeightUnit, lastSets: List<SetEntry>,
    nav: Nav, onSave: (TemplateExercise) -> Unit,
) {
    var rule by remember { mutableStateOf(rule0) }
    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_back, { nav.pop() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "PROGRESSION · TEMPLATE: ${templateName.ifBlank { "Untitled" }.uppercase()}", style = Type.Label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(exerciseName, style = Type.DisplayL)
            }
            RuleSection("Method") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ProgressionMethod.DOUBLE to "Double", ProgressionMethod.LINEAR to "Linear", ProgressionMethod.MANUAL to "Manual").forEach { (m, label) ->
                        Chip(label, rule.method == m) {
                            // Linear works to one rep target, so the range collapses to its bottom.
                            rule = if (m == ProgressionMethod.LINEAR) rule.copy(method = m, repMax = rule.repMin) else rule.copy(method = m)
                        }
                    }
                }
                Text(methodText(rule.method), style = Type.BodyMuted)
            }
            if (rule.method != ProgressionMethod.MANUAL) {
                RuleSection(if (rule.method == ProgressionMethod.DOUBLE) "Rep range" else "Reps") {
                    if (rule.method == ProgressionMethod.DOUBLE) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RepBox(rule.repMin, Modifier.weight(1f), "lowest reps") { v -> rule = rule.copy(repMin = v, reps = v, repMax = maxOf(rule.repMax, v)) }
                        Text("to", style = Type.BodyMuted)
                        RepBox(rule.repMax, Modifier.weight(1f), "highest reps") { v -> rule = rule.copy(repMax = v, repMin = minOf(rule.repMin, v), reps = minOf(rule.repMin, v)) }
                    } else RepBox(rule.repMin, Modifier.fillMaxWidth(0.5f)) { v -> rule = rule.copy(repMin = v, repMax = v, reps = v) }
                }
                RuleSection("Add weight by", unit.label.uppercase()) {
                    val current = Progression.incrementKg(rule, unit)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Progression.increments(unit).forEach { v ->
                            val kg = Units.toKg(v, unit)
                            Chip(v.toBigDecimal().stripTrailingZeros().toPlainString(), abs(kg - current) < 0.01) { rule = rule.copy(incrementKg = if (unit == WeightUnit.KG) v else kg) }
                        }
                    }
                }
                RuleSection("How it plays out") { Timeline(rule, unit, lastSets) }
            }
            Column {
                Hairline()
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Suggest a lighter week when stuck", style = Type.TitleM.copy(fontSize = 15.sp))
                        Text("After ${Progression.STALL_SESSIONS} sessions without progress", style = Type.BodyS)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(rule.suggestLighter, label = "Suggest a lighter week when stuck") { rule = rule.copy(suggestLighter = it) }
                }
            }
        }
        Hairline()
        // "Done", not "Save": the rule goes back to the template being edited, which is saved with "Save template".
        PrimaryButton("Done", Modifier.fillMaxWidth().padding(horizontal = Forge.Gutter, vertical = 12.dp)) { onSave(rule); nav.pop() }
    }
}

private fun methodText(m: ProgressionMethod) = when (m) {
    ProgressionMethod.DOUBLE -> "Hit the top of the rep range on every set, then add weight and drop back to the bottom."
    ProgressionMethod.LINEAR -> "Hit your reps on every set and the weight goes up next session. Quick gains while you’re new to a lift."
    ProgressionMethod.MANUAL -> "No suggestions. Each set starts from what you did last time and you choose when to add weight."
}

/** Body/M muted title over a hairline, as in 10.1. */
@Composable
private fun RuleSection(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(title, style = Type.BodyMuted, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, style = Type.Label)
            }
            Hairline()
        }
        content()
    }
}

/** −  8  + in a bordered box. */
@Composable
private fun RepBox(value: Int, modifier: Modifier, what: String = "reps", onChange: (Int) -> Unit) {
    Row(modifier.panel(radius = Forge.RCard).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallStep(R.drawable.ic_minus, "Decrease $what, now $value") { onChange((value - 1).coerceAtLeast(1)) }
        Text("$value", style = Type.FigureM, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        SmallStep(R.drawable.ic_plus, "Increase $what, now $value") { onChange((value + 1).coerceAtMost(50)) }
    }
}

@Composable
private fun SmallStep(icon: Int, label: String, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).panel(bg = Forge.Surface2, radius = Forge.RRow).tap(label = label, onClick = onClick), contentAlignment = Alignment.Center) {
        ForgeIcon(icon, 16.dp, Forge.Text)
    }
}

/**
 * Three sessions in plain numbers, from last time's weight (or a typical starting weight): working up
 * the range, topping it out, then the jump. Shows up to three sets per box.
 */
@Composable
private fun Timeline(rule: TemplateExercise, unit: WeightUnit, lastSets: List<SetEntry>) {
    val inc = Progression.incrementKg(rule, unit)
    val base = Progression.working(lastSets).maxOfOrNull { it.weightKg!! }?.takeIf { it > 0 } ?: Units.toKg(if (unit == WeightUnit.KG) 60.0 else 135.0, unit)
    val n = rule.sets.coerceIn(1, 3)
    fun reps(vararg r: Int) = List(n) { r.getOrElse(it) { r.last() } }
    val steps = if (rule.method == ProgressionMethod.DOUBLE) {
        val mid = ((rule.repMin + rule.repMax + 1) / 2).coerceAtMost(rule.repMax)
        listOf(base to reps(mid, mid, (mid - 1).coerceAtLeast(rule.repMin)), base to reps(rule.repMax), base + inc to reps(rule.repMin))
    } else listOf(base to reps(rule.repMin), base + inc to reps(rule.repMin), base + 2 * inc to reps(rule.repMin))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, (kg, r) ->
            if (i > 0) ForgeIcon(R.drawable.ic_arrow_right, 14.dp, if (i == steps.lastIndex) Forge.Lime else Forge.Muted)
            val last = i == steps.lastIndex
            Column(
                Modifier.weight(1f).panel(
                    bg = if (last) Forge.Lime.copy(alpha = 0.1f) else Forge.Surface,
                    border = if (last) Forge.Lime.copy(alpha = 0.5f) else Forge.Border, radius = Forge.RButton,
                ).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("${Units.w(kg, unit)} ${unit.label}", style = Type.DataM.copy(fontSize = 12.sp, color = if (last) Forge.Lime else Forge.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.joinToString(" · "), style = Type.DataS.copy(fontSize = 10.sp, color = if (last) Forge.Lime else Forge.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- 10.3 Stall → lighter week -----------------------------------------------------------------

typealias StallChoice = Progression.Answer

@Composable
fun StallSheet(
    stall: Progression.Stall, exerciseName: String, lighterKg: Double?, lighterReps: Int, variation: Exercise?, unit: WeightUnit,
    onDismiss: () -> Unit, onAnswer: (StallChoice) -> Unit,
) {
    BottomSheet(onDismiss) { StallSheetContent(stall, exerciseName, lighterKg, lighterReps, variation, unit, onAnswer) }
}

/** The sheet's content, split out so screenshots can render it inline. */
@Composable
fun ColumnScope.StallSheetContent(
    stall: Progression.Stall, exerciseName: String, lighterKg: Double?, lighterReps: Int, variation: Exercise?, unit: WeightUnit,
    onAnswer: (StallChoice) -> Unit,
) {
    var choice by remember { mutableStateOf(if (lighterKg != null) StallChoice.LIGHTER else StallChoice.KEEP) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StallChart(stall, Modifier.fillMaxWidth().height(80.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val first = stall.series.takeLast(CHART_POINTS).first().first
            Text(Weeks.date(first).format(monthFmt).uppercase(), style = Type.Label, modifier = Modifier.weight(1f))
            Text("NO PROGRESS · ${Progression.STALL_SESSIONS} SESSIONS", style = Type.Label.copy(color = Forge.Amber))
        }
    }
    Text("$exerciseName has stalled.", style = Type.DisplayM)
    Text(
        "Three sessions without beating ${Units.toDisplay(stall.bestE1rm, unit).roundToInt()} ${unit.label} estimated max. That’s usually fatigue, not lost strength, " +
            "and a lighter week often unlocks the next PR.",
        style = Type.BodyMuted,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (lighterKg != null) OptionCard(
            "Lighter week", "${Units.w(lighterKg, unit)} ${unit.label} × $lighterReps · about 10% easier · +${Progression.LIGHTER_WEEK_XP} XP",
            choice == StallChoice.LIGHTER, mono = true,
        ) { choice = StallChoice.LIGHTER }
        if (variation != null) OptionCard(
            "Swap to ${variation.name}", "${Progression.VARIATION_WEEKS} weeks, then back to $exerciseName", choice == StallChoice.SWAP, mono = true,
        ) { choice = StallChoice.SWAP }
        OptionCard("Keep pushing", "Same plan next session", choice == StallChoice.KEEP, mono = true) { choice = StallChoice.KEEP }
    }
    PrimaryButton(
        when (choice) { StallChoice.LIGHTER -> "Plan lighter week"; StallChoice.SWAP -> "Swap for ${Progression.VARIATION_WEEKS} weeks"; StallChoice.KEEP -> "Keep pushing" },
        Modifier.fillMaxWidth(),
    ) { onAnswer(choice) }
}

private const val CHART_POINTS = 8
private val monthFmt = DateTimeFormatter.ofPattern("MMM", Locale.US)

/** Estimated max over the last few sessions: amber line, the stalled stretch shaded. */
@Composable
private fun StallChart(stall: Progression.Stall, modifier: Modifier) {
    val pts = stall.series.takeLast(CHART_POINTS)
    Canvas(modifier) {
        if (pts.size < 2) return@Canvas
        val lo = pts.minOf { it.second }; val hi = pts.maxOf { it.second }
        val span = (hi - lo).takeIf { it > 0.01 } ?: 1.0
        val pad = 6.dp.toPx()
        fun x(i: Int) = i * size.width / (pts.size - 1)
        fun y(v: Double) = (pad + (1 - (v - lo) / span) * (size.height - 2 * pad)).toFloat()
        val from = pts.indexOfFirst { it.first >= stall.windowStart }.coerceAtLeast(0)
        // The shaded stretch starts at the best session before the stall.
        val shadeFrom = (from - 1).coerceAtLeast(0)
        drawRect(Forge.Surface2, Offset(x(shadeFrom), 0f), Size(size.width - x(shadeFrom), size.height))
        val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.second)) else lineTo(x(i), y(p.second)) } }
        drawPath(path, Forge.Amber, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
