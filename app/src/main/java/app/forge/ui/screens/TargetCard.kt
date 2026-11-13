package app.forge.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.R
import app.forge.data.*
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type

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
