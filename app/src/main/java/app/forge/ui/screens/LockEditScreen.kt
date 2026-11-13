package app.forge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.R
import app.forge.data.*
import app.forge.ui.components.*
import app.forge.ui.rememberNow
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import app.forge.ui.theme.ForgeTheme

/**
 * B: the lock-screen stepper. The next set only: weight ±, reps ±, Log set. Everything else in Press
 * needs the phone unlocked ("Open Press").
 */
@Composable
fun LockEditScreen(d: AppData, first: Session.Next, onLog: (setId: Long, weightKg: Double?, reps: Int) -> Unit, onOpenApp: (setId: Long) -> Unit, onClose: () -> Unit) {
    // D7: [d] is live. When the set this editor shows gets logged elsewhere (the shade, the app, the watch),
    // switch to the new next set with a one-line note. Typed values are never carried over to another set.
    val live = Session.next(d)
    var shown by remember { mutableStateOf(first) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(live?.setEntry?.id) {
        if (live == null || !live.entry.tracking.usesReps) onClose()   // nothing left that can be edited here
        else if (live.setEntry.id != shown.setEntry.id) {
            val where = if (live.entry.exerciseId == shown.entry.exerciseId) "set ${live.set + 1}"
                else "${Session.exercise(d, live.entry.exerciseId).name}, set ${live.set + 1}"
            note = "Set ${shown.set + 1} was logged. Now $where."
            shown = live
        }
    }
    EditBody(d, shown, note, onLog, onOpenApp, onClose)
}

@Composable
private fun EditBody(d: AppData, next: Session.Next, note: String?, onLog: (setId: Long, weightKg: Double?, reps: Int) -> Unit, onOpenApp: (setId: Long) -> Unit, onClose: () -> Unit) {
    val unit = d.settings.unit
    val t = next.entry.tracking
    val start = next.planned ?: next.setEntry
    var weight by remember(next.setEntry.id) { mutableDoubleStateOf(Units.toDisplay(start.weightKg ?: 0.0, unit)) }
    var reps by remember(next.setEntry.id) { mutableIntStateOf(start.reps ?: 8) }
    val step = if (unit == WeightUnit.KG) 2.5 else 5.0
    val now by rememberNow()
    val name = Session.exercise(d, next.entry.exerciseId).name
    ForgeTheme {
        Column(
            Modifier.fillMaxSize().background(Forge.Bg).systemBarsPadding().padding(horizontal = Forge.Gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SET ${next.set + 1} OF ${next.entry.sets.size}", style = Type.Kicker, modifier = Modifier.weight(1f))
                IconTap(R.drawable.ic_close, Forge.Muted, 20.dp, description = "Close", onClick = onClose)
            }
            Text(name, style = Type.DisplayM)
            note?.let { Text(it, style = Type.BodyS, color = Forge.Lime, modifier = Modifier.testTag("lock-note")) }
            RestHeader(d.active, now)
            Spacer(Modifier.weight(1f))
            val bw = t == Tracking.BODYWEIGHT_REPS
            val weightLabel = when {
                t == Tracking.ASSISTED -> "ASSISTANCE · LESS IS HARDER"
                bw -> "ADDED WEIGHT · OPTIONAL"
                else -> "WEIGHT" + if (next.entry.perHand) " PER HAND" else ""
            }
            val shown = when {
                bw -> Measure.bodyweightField(weight) + if (weight > 0) " " + unit.label else ""
                else -> (if (t == Tracking.ASSISTED && weight > 0) "−" else "") + Units.num(weight) + " " + unit.label
            }
            LockStepper(weightLabel, shown, "weight",
                onMinus = { weight = (weight - step).coerceAtLeast(0.0) }, onPlus = { weight += step })
            LockStepper("REPS", "$reps", "reps", onMinus = { reps = (reps - 1).coerceAtLeast(0) }, onPlus = { reps += 1 })
            Spacer(Modifier.weight(1f))
            PrimaryButton("Log set", Modifier.fillMaxWidth(), enabled = reps > 0) {
                onLog(next.setEntry.id, if (t == Tracking.WEIGHT_REPS || weight > 0) Units.toKg(weight, unit) else null, reps)
            }
            Text("Everything else in Press needs your phone unlocked.", style = Type.BodyS, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            GhostButton("Open Press", Modifier.fillMaxWidth(), color = Forge.Muted, onClick = { onOpenApp(next.setEntry.id) })
        }
    }
}

@Composable
private fun LockStepper(label: String, value: String, what: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Type.Label)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).panel(bg = Forge.Surface2, radius = Forge.RCard).stepper("Less $what", onMinus), contentAlignment = Alignment.Center) {
                ForgeIcon(R.drawable.ic_minus, 24.dp, Forge.Text)
            }
            Box(Modifier.weight(1f).height(72.dp).panel(bg = Forge.Bg, border = Forge.Border, radius = Forge.RCard).testTag("lock-$what"), contentAlignment = Alignment.Center) {
                Text(value, style = Type.FigureM.copy(fontSize = 34.sp), maxLines = 1)
            }
            Box(Modifier.size(72.dp).panel(bg = Forge.Surface2, radius = Forge.RCard).stepper("More $what", onPlus), contentAlignment = Alignment.Center) {
                ForgeIcon(R.drawable.ic_plus, 24.dp, Forge.Text)
            }
        }
    }
}
