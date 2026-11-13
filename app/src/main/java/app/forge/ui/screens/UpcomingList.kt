package app.forge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.forge.R
import app.forge.data.*
import app.forge.ui.Haptics
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import androidx.compose.ui.zIndex

/** "3 × 8–12", "3 timed sets", "cardio": what an exercise in the session asks for. */
internal fun plannedText(e: ExerciseEntry, template: Template?): String {
    val n = e.sets.count { !it.skipped }
    val te = template?.exercises?.firstOrNull { it.exerciseId == e.exerciseId }
    return when (e.tracking) {
        Tracking.CARDIO -> "cardio"
        Tracking.DURATION -> "$n timed ${if (n == 1) "set" else "sets"}"
        Tracking.DISTANCE_WEIGHT -> "$n carries"
        else -> "$n × " + (te?.let { rangeText(it.repMin, it.repMax) } ?: (e.sets.firstOrNull()?.reps ?: ""))
    }
}

/**
 * A: the exercises still to come, collapsed: name, sets × target, last time. Drag the handle to reorder
 * (TalkBack: Move up / Move down); tap a tile to jump to it. Order changes only this session.
 */
@Composable
internal fun UpcomingList(
    items: List<Int>, w: Workout, template: Template?, history: List<Workout>, unit: WeightUnit, nameOf: (String) -> String,
    onJump: (Int) -> Unit, onMove: (id: Long, by: Int) -> Unit,
) {
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
                val name = nameOf(e.exerciseId)
                val lastEntry = remember(history, e.exerciseId, e.tracking) {
                    history.firstNotNullOfOrNull { h -> h.exercises.firstOrNull { it.exerciseId == e.exerciseId && it.tracking == e.tracking } }
                }
                val last = remember(lastEntry) { lastEntry?.let { Stats.topSet(it) } }
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
                        Text(name, style = Type.TitleM14, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(plannedText(e, template), last?.let { "last " + Measure.format(lastEntry!!, it, unit) }).joinToString(" · "),
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
