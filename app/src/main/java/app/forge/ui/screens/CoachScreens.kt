package app.forge.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.R
import app.forge.data.*
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type

/*
 * Context layer · Outputs (Figma 5200): evidence chips and their fact sheet (O.2), the "Accept tweaks" banner (O.3)
 * and the applied pill with Undo (O.4). Every chip is a Fact Kotlin computed; the AI only ever chose among them.
 */

fun readinessColor(s: ReadinessState): Color = when (s) {
    ReadinessState.GREEN -> Forge.Lime
    ReadinessState.AMBER -> Forge.Amber
    ReadinessState.RED -> Forge.Red
}

/** "● Sat: 540 m climbed". Tap for the fact behind it. */
@Composable
fun EvidenceChip(fact: Fact, onTap: (Fact) -> Unit) {
    Row(
        Modifier.panel(bg = Forge.Surface, border = Forge.Border, radius = Forge.RRow).tap(action = "Show the fact behind it") { onTap(fact) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PulseDot(Forge.Ice, 5.dp)
        Text(fact.text, style = Type.DataS.copy(fontSize = 11.sp, color = Forge.Text))
    }
}

/** A row of evidence chips, with the fact sheet they open. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EvidenceChips(facts: List<Fact>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<Fact?>(null) }
    if (facts.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        facts.forEach { EvidenceChip(it) { f -> open = f } }
    }
    open?.let { f -> FactSheet(f) { open = null } }
}

/** O.2: the fact itself, its id, and that Press computed it. */
@Composable
fun FactSheet(fact: Fact, onDismiss: () -> Unit) {
    BottomSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) { EvidenceChip(fact) {} }
        Text(fact.text, style = Type.DisplayM)
        Hairline()
        Row(Modifier.fillMaxWidth()) {
            Text(fact.id, style = Type.DataS, modifier = Modifier.weight(1f))
            Text("Computed by Press, not AI", style = Type.DataS)
        }
    }
}

private fun tag(t: Tweak): String = when (t.action) {
    ActionId.INCREASE_LOAD -> "UP"
    ActionId.HOLD_WEIGHT -> "HOLD"
    ActionId.REDUCE_LOAD_5 -> "−5%"
    ActionId.REDUCE_LOAD_10 -> "−10%"
    ActionId.DROP_ONE_SET -> "DROP A SET"
}

/** O.3: readiness, the coach's words, each tweak before → after, the evidence, and Accept / Ignore. */
@Composable
fun TweaksBanner(
    coach: SessionCoach, name: (String) -> String, weight: (String, Double?) -> String,
    onAccept: () -> Unit, onIgnore: () -> Unit,
) {
    val color = readinessColor(coach.state)
    Column(
        Modifier.fillMaxWidth().heightIn(max = 480.dp).panel(bg = Forge.Surface, border = color.copy(alpha = 0.6f), radius = Forge.RCard)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PulseDot(color, 8.dp)
            val mood = when (coach.state) { ReadinessState.GREEN -> "Good to go"; ReadinessState.AMBER -> "Steady"; ReadinessState.RED -> "Take it easy" }
            Text(
                listOfNotNull(coach.state.name, mood, "First session back".takeIf { coach.firstBack }, "Partial: no check-in".takeIf { coach.partial })
                    .joinToString(" · ").uppercase(),
                style = Type.Label.copy(color = color),
            )
        }
        Text(coach.text.headline, style = Type.DisplayS)
        Text(coach.text.summary, style = Type.BodyMuted)
        val chips = (coach.text.evidenceFactIds + coach.text.choices.flatMap { it.reasonFactIds }).distinct().mapNotNull(coach.text::fact)
        EvidenceChips(chips)
        if (coach.tweaks.isNotEmpty()) Column {
            coach.tweaks.forEach { t ->
                Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name(t.exerciseId), style = Type.BodyM, modifier = Modifier.weight(1f))
                    val drop = t.action == ActionId.DROP_ONE_SET
                    val from = if (drop) "${t.fromSets} sets" else weight(t.exerciseId, t.fromKg)
                    val to = if (drop) "${t.toSets} sets" else weight(t.exerciseId, t.toKg)
                    if (from != to) Text(from, style = Type.DataS.copy(textDecoration = TextDecoration.LineThrough))
                    if (from != to) ForgeIcon(R.drawable.ic_arrow_right, 12.dp, Forge.Muted)
                    Text(to, style = Type.DataM.copy(fontSize = 13.sp))
                    Tag(tag(t), color = if (t.action == ActionId.INCREASE_LOAD) Forge.Lime else Forge.Muted)
                }
            }
            Hairline()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (coach.tweaks.isNotEmpty()) {
                PrimaryButton("Accept tweaks", Modifier.weight(1f), onClick = onAccept)
                SecondaryButton("Ignore", Modifier.weight(1f), onClick = onIgnore)
            } else SecondaryButton("Got it", Modifier.fillMaxWidth(), onClick = onIgnore)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ForgeIcon(R.drawable.ic_sparkle, 12.dp, Forge.Muted)
            Text(if (coach.text.fromAi) "Words by AI; weights calculated by Press." else "Words and weights by Press.", style = Type.BodyS)
        }
    }
}

/** O.4: once accepted, a pill keeps it visible, with one-tap Undo. */
@Composable
fun AppliedPill(coach: SessionCoach, onUndo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().panel(radius = Forge.RButton).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PulseDot(readinessColor(coach.state), 7.dp)
        val n = coach.tweaks.size
        val what = if (coach.state == ReadinessState.GREEN) "Tweaks applied" else "Lighter day applied"
        Text("$what · $n ${if (n == 1) "tweak" else "tweaks"}", style = Type.BodyM.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
        Text("Undo", style = Type.BodyM.copy(fontSize = 13.sp, color = Forge.Lime), modifier = Modifier.tap(onClick = onUndo))
    }
}
