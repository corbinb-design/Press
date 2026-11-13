package app.forge.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.R
import app.forge.data.*
import app.forge.ui.Motion
import app.forge.ui.clock
import app.forge.ui.components.*
import app.forge.ui.reduceMotion
import app.forge.ui.rememberNow
import app.forge.ui.restClockText
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * Floating rest pill (4.1), one look per [RestPhase] (rest-design step 8, from `Session.rest`):
 * Working = ink pill, session clock counting up, tap to start the default rest (D5);
 * Resting = lime pill, ink text, countdown with −15 / +15 / skip;
 * Over = ink pill, lime negative timer counting on, +15 / skip only (−15 is hidden, D3).
 */
@Composable
internal fun RestPill(
    w: Workout, defaultSec: Int, modifier: Modifier,
    onStart: (Int) -> Unit, onAdjust: (Int) -> Unit, onSkip: () -> Unit,
) {
    // The ticking clock is read here, not in WorkoutScreen, so only the pill recomposes each second.
    val now by rememberNow()
    RestPillBody(w, now, defaultSec, modifier, onStart, onAdjust, onSkip)
}

@Composable
internal fun RestPillBody(
    w: Workout, now: Long, defaultSec: Int, modifier: Modifier,
    onStart: (Int) -> Unit, onAdjust: (Int) -> Unit, onSkip: () -> Unit,
) {
    val phase = Session.rest(w, now)
    val working = phase == RestPhase.WORKING
    val resting = phase == RestPhase.RESTING
    val ink = if (resting) Forge.OnLime else Forge.Text
    val faint = if (resting) Forge.OnLime else Forge.Muted
    // 11 A, last frame: once a set is ticked the rest pill slides up from the bottom edge, already counting (280–420 ms).
    val reduce = reduceMotion()
    val rise = remember { Animatable(0f) }
    LaunchedEffect(resting) {
        if (!resting || reduce) return@LaunchedEffect
        rise.snapTo(1f); delay(280); rise.animateTo(0f, tween(140, easing = Motion.Wipe))
    }
    val bg = if (resting) Forge.Lime else Forge.Surface
    Row(
        modifier.graphicsLayer { translationY = rise.value * 72.dp.toPx(); alpha = 1f - rise.value }
            .clip(CircleShape).background(bg)
            .then(Modifier.panel(bg = bg, border = if (working) Forge.Border else Forge.Lime, radius = 100.dp))
            .tap(enabled = working, action = "Start rest") { onStart(defaultSec) }
            .padding(start = 18.dp, end = if (working) 22.dp else 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ForgeIcon(R.drawable.ic_clock, 22.dp, if (resting) Forge.OnLime else if (working) Forge.Muted else Forge.Lime)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                when (phase) { RestPhase.WORKING -> "TAP TO REST"; RestPhase.RESTING -> "REST"; RestPhase.OVER -> "OVER" },
                style = Type.Label.copy(color = faint),
            )
            if (working) Text(
                clock(now - w.startedAt), style = Type.DataM.copy(fontSize = 16.sp, color = Forge.Muted),
                modifier = Modifier.testTag("rest-pill-session-clock"),
            ) else RestClock(w, now, "rest-pill-clock", color = if (resting) Forge.OnLime else null)
        }
        if (resting) RestChip("−15", ink, label = "15 seconds less rest", onDark = false) { onAdjust(-15) }
        if (!working) {
            RestChip("+15", ink, label = "15 seconds more rest", onDark = !resting) { onAdjust(15) }
            RestChip("SKIP", Forge.Lime, label = "Skip rest", onDark = !resting, solid = resting) { onSkip() }
        }
    }
}

/** The rest countdown, shared by the pill and the two set editors: counts down, then on past zero in lime as "−0:12". */
@Composable
internal fun RestClock(w: Workout, now: Long, tag: String = "rest-clock", size: TextUnit = 16.sp, color: Color? = null) {
    val over = Session.rest(w, now) == RestPhase.OVER
    Text(
        restClockText(Session.signedSecondsLeft(w, now)),
        style = Type.DataM.copy(fontSize = size, color = color ?: if (over) Forge.Lime else Forge.Text), maxLines = 1,
        modifier = Modifier.testTag(tag),
    )
}

/** "REST 1:30" line for the header of a set editor; nothing when no rest is running. */
@Composable
internal fun RestHeader(w: Workout?, now: Long) {
    if (w == null || Session.rest(w, now) == RestPhase.WORKING) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("REST", style = Type.Label)
        RestClock(w, now)
    }
}

/** A pill button. On the lime Resting pill: translucent ink chips, and Skip as a solid ink chip with a lime label. */
@Composable
private fun RestChip(text: String, color: Color = Forge.Text, label: String, onDark: Boolean, solid: Boolean = false, onClick: () -> Unit) {
    val bg = when { solid -> Forge.OnLime; onDark -> Forge.Surface2; else -> Forge.OnLime.copy(alpha = 0.12f) }
    Text(
        text, style = Type.DataM12.copy(color = color),
        modifier = Modifier.clip(CircleShape).background(bg).tap(label = label, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
