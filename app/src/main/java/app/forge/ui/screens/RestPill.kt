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

/** Floating rest countdown (4.1). Idle: tap to start the default rest. Running: −15 / +15 / skip inline. */
@Composable
internal fun RestPill(
    w: Workout, defaultSec: Int, modifier: Modifier,
    onStart: (Int) -> Unit, onAdjust: (Int) -> Unit, onSkip: () -> Unit,
) {
    // The ticking clock is read here, not in WorkoutScreen, so only the pill recomposes each second.
    val now by rememberNow()
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
            .tap(enabled = !running, action = "Start rest") { onStart(defaultSec) }
            .padding(start = 18.dp, end = if (running) 10.dp else 22.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ForgeIcon(R.drawable.ic_clock, 22.dp, if (running) Forge.Lime else Forge.Muted)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (running) "REST" else "TAP TO REST", style = Type.Label)
            if (ends != null) RestClock(w, now, "rest-pill-clock")
            else Text(clock(remaining * 1000L), style = Type.DataM.copy(fontSize = 16.sp, color = Forge.Muted))
        }
        if (running) {
            RestChip("−15", label = "15 seconds less rest") { onAdjust(-15) }
            RestChip("+15", label = "15 seconds more rest") { onAdjust(15) }
            RestChip("SKIP", Forge.Lime, label = "Skip rest") { onSkip() }
        }
    }
}

/** The rest countdown, shared by the pill and the two set editors: counts down, then on past zero in lime as "−0:12". */
@Composable
internal fun RestClock(w: Workout, now: Long, tag: String = "rest-clock", size: TextUnit = 16.sp) {
    val over = Session.rest(w, now) == RestPhase.OVER
    Text(
        restClockText(Session.signedSecondsLeft(w, now)),
        style = Type.DataM.copy(fontSize = size, color = if (over) Forge.Lime else Forge.Text), maxLines = 1,
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

@Composable
private fun RestChip(text: String, color: Color = Forge.Text, label: String, onClick: () -> Unit) {
    Text(
        text, style = Type.DataM12.copy(color = color),
        modifier = Modifier.clip(CircleShape).background(Forge.Surface2).tap(label = label, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
