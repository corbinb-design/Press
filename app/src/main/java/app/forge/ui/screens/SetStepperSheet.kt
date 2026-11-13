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
// ---- 4.2 Adjust a set -----------------------------------------------------------------------

@Composable
internal fun SetStepperSheet(
    entry: ExerciseEntry, setIndex: Int, data: AppData, exerciseName: String, onDismiss: () -> Unit,
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
        val cardio = t == Tracking.CARDIO
    val now by rememberNow()
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
            Text((if (cardio) "CARDIO" else "SET ${setIndex + 1}") + " · ${exerciseName.uppercase()}", style = Type.Label, textAlign = TextAlign.Center)
            Text(
                previous?.takeIf { Measure.hasValue(t, it) }?.let { "Previous ${Measure.format(entry, it, unit)}" } ?: "Nothing to compare yet",
                style = Type.BodyS.copy(fontSize = 13.sp), textAlign = TextAlign.Center,
            )
            RestHeader(data.active, now)
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
            val bwHold = t == Tracking.DURATION && entry.withBodyweight
            val bw = t == Tracking.BODYWEIGHT_REPS || bwHold
            val weightLabel = when {
                t == Tracking.ASSISTED -> "ASSISTANCE · LESS IS HARDER"
                bw -> "ADDED WEIGHT · OPTIONAL"
                t == Tracking.DURATION -> "ADDED WEIGHT"
                else -> "WEIGHT" + if (entry.perHand) " PER HAND" else ""
            }
            // Timed bodyweight holds lead with the time: until weight is added, the row is one chip.
            var weightOpen by remember { mutableStateOf(weight > 0) }
            if (bwHold && !weightOpen && weight <= 0.0) {
                Chip("BW · add weight", false, Modifier.fillMaxWidth()) { weightOpen = true }
            } else {
                StepperSection("$weightLabel · ±${Units.num(step)}") {
                    StepperRow(
                        value = if (bw) Measure.bodyweightField(weight) else (if (t == Tracking.ASSISTED && weight > 0) "−" else "") + Units.num(weight),
                        suffix = if (bw && weight <= 0.0) null else unit.label, decimal = true, what = "weight",
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
            Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp).testTag("value-$what").panel(bg = Forge.Bg, border = Forge.Border, radius = Forge.RCard).tap(action = "Type a value") { typing = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (typing) NumberField(edit, decimal, time, onTyped) { typing = false }
            else {
                Text(value, style = Type.FigureM)
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
