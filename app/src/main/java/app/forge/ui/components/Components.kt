package app.forge.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.forge.R
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type

// ---- Primitives ---------------------------------------------------------------------------

fun Modifier.panel(
    bg: Color = Forge.Surface,
    border: Color = Forge.Border,
    radius: Dp = Forge.RTile,
): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(bg)
    .border(1.dp, border, RoundedCornerShape(radius))

/**
 * Click without the Material ripple; the UI uses state changes instead. Announced to TalkBack as a
 * button. [label] names an element that has no text of its own (icon-only); [action] says what a
 * double-tap does when the text alone doesn't ("edit weight").
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.tap(
    enabled: Boolean = true, label: String? = null, action: String? = null, role: Role? = Role.Button,
    /** A long press as well (with its haptic); [longAction] is what TalkBack calls it. */
    onLongClick: (() -> Unit)? = null, longAction: String? = null, onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    (if (onLongClick == null) clickable(source, indication = null, enabled = enabled, onClickLabel = action, role = role, onClick = onClick)
    else combinedClickable(source, indication = null, enabled = enabled, onClickLabel = action, role = role, onLongClickLabel = longAction, onLongClick = onLongClick, onClick = onClick))
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
}

/** Press-and-hold timing for [stepper]: the first repeat, then a gap that shrinks to [FASTEST_MS]. */
object Repeat {
    const val HOLD_MS = 400L
    const val EVERY_MS = 100L
    const val FASTEST_MS = 40L
    const val SPEED_UP = 0.85
}

/**
 * A +/− button. Steps on touch down (so a tap that drifts a little still counts), then, held, repeats
 * after [Repeat.HOLD_MS] every [Repeat.EVERY_MS], speeding up. A light haptic on every step. While
 * pressed it keeps the touch, so the sheet's scroll can't take it over. TalkBack's double-tap steps once.
 */
fun Modifier.stepper(label: String, onStep: () -> Unit): Modifier = composed {
    val step by rememberUpdatedState(onStep)
    val view = LocalView.current
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown().consume()
            step(); app.forge.ui.Haptics.light(view)
            var wait = Repeat.HOLD_MS
            var every = Repeat.EVERY_MS.toDouble()
            while (withTimeoutOrNull(wait) { awaitReleaseOrExit(); true } == null) {
                step(); app.forge.ui.Haptics.light(view)
                wait = every.toLong()
                every = (every * Repeat.SPEED_UP).coerceAtLeast(Repeat.FASTEST_MS.toDouble())
            }
        }
    }.semantics { role = Role.Button; contentDescription = label; onClick { step(); true } }
}

/** Returns when the finger lifts, slides off the button, or something else takes the touch. */
private suspend fun AwaitPointerEventScope.awaitReleaseOrExit() {
    while (true) {
        val c = awaitPointerEvent().changes.firstOrNull() ?: return
        if (!c.pressed || c.isConsumed || c.isOutOfBounds(size, extendedTouchPadding)) return
        c.consume()
    }
}

/** Tapping the dim area behind a sheet closes it. Not a TalkBack target: Back does the same. */
fun Modifier.scrimTap(onTap: () -> Unit): Modifier = pointerInput(Unit) { detectTapGestures { onTap() } }

/** Keeps taps on a sheet from reaching the scrim behind it, without making the sheet a button. */
fun Modifier.blockTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

@Composable
fun ForgeIcon(@DrawableRes res: Int, size: Dp, tint: Color, modifier: Modifier = Modifier) =
    Icon(painterResource(res), null, modifier.size(size), tint = tint)

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Forge.Border) =
    Box(modifier.fillMaxWidth().height(1.dp).background(color))

@Composable
fun VerticalHairline(modifier: Modifier = Modifier, color: Color = Forge.Border) =
    Box(modifier.fillMaxHeight().width(1.dp).background(color))

@Composable
fun PulseDot(color: Color, size: Dp = 6.dp, modifier: Modifier = Modifier) = Box(modifier.size(size).clip(CircleShape).background(color))

/** Dashed outline, for "missing" placeholders. */
fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        color = color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
        topLeft = Offset(0.5f, 0.5f), size = size.copy(width = size.width - 1, height = size.height - 1),
        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/** Small round icon button (40dp touch target). [description] is what TalkBack reads; it's required. */
@Composable
fun IconTap(@DrawableRes res: Int, tint: Color = Forge.Muted, size: Dp = 18.dp, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).tap(enabled = enabled, label = description, onClick = onClick), contentAlignment = Alignment.Center) {
        ForgeIcon(res, size, tint)
    }
}

// ---- Labels and sections ------------------------------------------------------------------

/** A Label-style row: "SESSIONS PER WEEK ........ SETS YOUR STREAK GOAL". Both sides are uppercased. */
@Composable
fun LabelRow(
    label: String, modifier: Modifier = Modifier, trailing: String? = null,
    trailingColor: Color = Forge.Lime, onTrailing: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), style = Type.Label, modifier = Modifier.weight(1f, fill = false))
        if (trailing != null) Text(
            trailing.uppercase(), style = Type.Label.copy(color = trailingColor),
            modifier = Modifier.padding(start = 12.dp).then(if (onTrailing != null) Modifier.tap(onClick = onTrailing) else Modifier),
        )
    }
}

/** Press-style section label: muted Body/M (13sp medium in Figma) over a hairline, optional Label on the right. */
@Composable
fun SectionHeader(
    title: String, trailing: String? = null, modifier: Modifier = Modifier,
    trailingColor: Color = Forge.Muted, onTrailing: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(title, style = Type.SectionLabel, modifier = Modifier.weight(1f, fill = false))
            if (trailing != null) Text(
                trailing.uppercase(), style = Type.Label.copy(color = trailingColor),
                modifier = Modifier.padding(start = 12.dp).then(if (onTrailing != null) Modifier.tap(onClick = onTrailing) else Modifier),
            )
        }
        Hairline()
    }
}

/** "Cancel · Title · Action" bar used by editors and sheets-as-pages (3.1, 14.2). */
@Composable
fun TextTopBar(
    title: String, left: String, onLeft: () -> Unit, right: String? = null, rightEnabled: Boolean = true,
    modifier: Modifier = Modifier, onRight: () -> Unit = {},
) {
    Box(modifier.fillMaxWidth().padding(horizontal = Forge.Gutter, vertical = 12.dp)) {
        Text(left, style = Type.BodyL.copy(fontSize = 15.sp, color = Forge.Muted), modifier = Modifier.align(Alignment.CenterStart).tap(onClick = onLeft))
        Text(title, style = Type.TitleM, modifier = Modifier.align(Alignment.Center))
        if (right != null) Text(
            right, style = Type.BodyL.copy(fontSize = 15.sp, color = if (rightEnabled) Forge.Lime else Forge.Faint),
            modifier = Modifier.align(Alignment.CenterEnd).tap(enabled = rightEnabled, onClick = onRight),
        )
    }
}

/** Icon top bar: back/close on the left, title in the middle, icons on the right (3.2, 6.2). */
@Composable
fun IconTopBar(
    @DrawableRes leading: Int, onLeading: () -> Unit, modifier: Modifier = Modifier, title: String? = null,
    leadingDescription: String = if (leading == R.drawable.ic_close) "Close" else "Back",
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Box(modifier.fillMaxWidth().padding(horizontal = Forge.Gutter - 9.dp, vertical = 2.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { IconTap(leading, Forge.Text, 22.dp, description = leadingDescription, onClick = onLeading) }
        if (title != null) Text(title, style = Type.TitleM, modifier = Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** Small pill tag, e.g. "PR", "2 PR", "BEST WEIGHT", "SOON". */
@Composable
fun Tag(text: String, color: Color = Forge.Lime, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = Type.DataS.copy(fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = color),
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** Bordered meta pill: "6 EXERCISES", "~60 MIN". */
@Composable
fun MetaPill(text: String) {
    Text(text.uppercase(), style = Type.Label, modifier = Modifier.panel(radius = Forge.RTile).padding(horizontal = 8.dp, vertical = 4.dp))
}

/** iOS-style switch from the Figma sheets (48×28, lime when on). */
@Composable
fun Switch(on: Boolean, modifier: Modifier = Modifier, label: String? = null, onChange: (Boolean) -> Unit) {
    val x by androidx.compose.animation.core.animateDpAsState(if (on) 23.dp else 3.dp, label = "switch")
    Box(
        modifier.size(48.dp, 28.dp).clip(CircleShape).background(if (on) Forge.Lime else Forge.Surface2)
            .border(1.dp, if (on) Forge.Lime else Forge.Border, CircleShape)
            // TalkBack: "<label>, switch, on/off".
            .toggleable(on, remember { MutableInteractionSource() }, indication = null, role = Role.Switch, onValueChange = onChange)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
    ) { Box(Modifier.offset(x = x, y = 3.dp).size(22.dp).clip(CircleShape).background(if (on) Forge.OnLime else Forge.Muted)) }
}

/** A selectable card with title, caption and a round check (5.1). */
@Composable
fun OptionCard(title: String, caption: String, selected: Boolean, mono: Boolean = false, onClick: () -> Unit) {
    Row(
        // The mono variant (10.3) tints the picked card lime, as in Figma.
        Modifier.fillMaxWidth().panel(
            bg = if (!selected) Forge.Bg else if (mono) Forge.Lime.copy(alpha = 0.08f) else Forge.Surface,
            border = if (!selected) Forge.Border else if (mono) Forge.Lime.copy(alpha = 0.5f) else Forge.Lime, radius = Forge.RCard,
        )
            .tap(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Type.TitleM.copy(fontSize = 15.sp))
            Text(caption, style = if (mono) Type.DataS.copy(fontSize = 10.sp) else Type.BodyS)
        }
        Box(
            Modifier.size(22.dp).clip(CircleShape).then(if (selected) Modifier.background(Forge.Lime) else Modifier.border(1.dp, Forge.Border, CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (selected) ForgeIcon(R.drawable.ic_check, 12.dp, Forge.OnLime) }
    }
}

/** Centred serif title + muted body, the head of most sheets. */
@Composable
fun SheetHeader(title: String, body: String? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = Type.DisplayM, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (body != null) Text(body, style = Type.BodyMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Screen opener: a Label kicker over a Display/L title. */
@Composable
fun ChapterTitle(kicker: String, title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
        Text(kicker.uppercase(), style = Type.Label)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.DisplayL, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
    }
}

/** Figures in columns separated by vertical hairlines: label above, value below. */
@Composable
fun StatColumns(stats: List<Pair<String, String>>, modifier: Modifier = Modifier, highlight: Int = -1) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        stats.forEachIndexed { i, (label, value) ->
            if (i > 0) VerticalHairline()
            Column(
                Modifier.weight(1f).padding(start = if (i == 0) 0.dp else 12.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(label, style = Type.BodyS.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(value, style = Type.DataM.copy(fontSize = 18.sp, color = if (i == highlight) Forge.Lime else Forge.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Text tabs; the active one is underlined in lime. */
@Composable
fun Tabs(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Column(Modifier.width(IntrinsicSize.Max).tap(role = Role.Tab) { onSelect(i) }.semantics { this.selected = on }) {
                    Text(label, style = Type.TitleM.copy(color = if (on) Forge.Text else Forge.Muted), modifier = Modifier.padding(vertical = 10.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) Forge.Lime else Color.Transparent))
                }
            }
        }
        Hairline()
    }
}

// ---- Controls -----------------------------------------------------------------------------

enum class ButtonKind { Primary, Secondary, Ghost }

/** Figma "Button": Primary = lime, Secondary = surface + border, Ghost = lime text only. */
@Composable
fun ForgeButton(
    text: String, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Primary, enabled: Boolean = true,
    color: Color? = null, @DrawableRes icon: Int? = null, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Forge.RButton)
    val bg = when {
        kind == ButtonKind.Primary -> if (enabled) Forge.Lime else Forge.Surface2
        kind == ButtonKind.Secondary -> Forge.Surface
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> Forge.Faint
        color != null -> color
        kind == ButtonKind.Primary -> Forge.OnLime
        kind == ButtonKind.Secondary -> Forge.Text
        else -> Forge.Lime
    }
    Row(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (kind == ButtonKind.Secondary) Modifier.border(1.dp, Forge.Border, shape) else Modifier)
            .tap(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { ForgeIcon(icon, 16.dp, fg); Spacer(Modifier.width(8.dp)) }
        Text(text.uppercase(), style = Type.Button.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    ForgeButton(text, modifier, ButtonKind.Primary, enabled, onClick = onClick)

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, color: Color = Forge.Text, @DrawableRes icon: Int? = null, onClick: () -> Unit) =
    ForgeButton(text, modifier, ButtonKind.Secondary, color = color, icon = icon, onClick = onClick)

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = Forge.Lime, @DrawableRes icon: Int? = null, onClick: () -> Unit) =
    ForgeButton(text, modifier, ButtonKind.Ghost, color = color, icon = icon, onClick = onClick)

/** Figma "Chip": On = solid lime, Off = surface with a hairline border. Text is shown as given. */
@Composable
fun Chip(text: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onColor: Color = Forge.Lime, onClick: () -> Unit) {
    Box(
        modifier
            .panel(bg = if (on) onColor else Forge.Surface, border = if (on) onColor else Forge.Border, radius = Forge.RRow)
            .tap(enabled = enabled, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.DataM.copy(fontSize = 12.sp, color = when { on -> Forge.OnLime; enabled -> Forge.Text; else -> Forge.Faint })) }
}

/** Two-to-three way switch: chips in a row. */
@Composable
fun SegmentedToggle(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label -> Chip(label, i == selected) { onSelect(i) } }
    }
}

@Composable
fun ForgeTextField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    style: TextStyle = Type.TitleM, keyboard: KeyboardOptions = KeyboardOptions.Default, @DrawableRes leading: Int? = null,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true, textStyle = style,
        cursorBrush = SolidColor(Forge.Lime), keyboardOptions = keyboard,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().panel(bg = Forge.Surface, border = if (focused) Forge.Lime else Forge.Border, radius = Forge.RButton)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) { ForgeIcon(leading, 16.dp, Forge.Muted); Spacer(Modifier.width(8.dp)) }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = Forge.Faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        },
    )
}

// ---- Rows and cards -----------------------------------------------------------------------

/** [Skipped] (v1.0.1): left out on purpose; shown struck through, with Undo where the tick was. */
enum class SetState { Done, Active, Upcoming, Skipped }

/** Figma "SetRow": set number, previous, kg and reps fields, tick. Values are pre-formatted. */
@Composable
fun SetRow(
    set: String, previous: String, kg: String, reps: String, state: SetState, modifier: Modifier = Modifier,
    setColor: Color? = null, onSet: (() -> Unit)? = null, onKg: () -> Unit = {}, onReps: () -> Unit = {}, onCheck: () -> Unit = {},
    /** What the two value columns are, for TalkBack ("weight", "reps", "TIME"…). */
    labels: Pair<String, String> = "weight" to "reps",
    /** Long-pressing anywhere on the row: the set's menu (type, skip, delete). */
    onLongPress: (() -> Unit)? = null,
    onUndoSkip: () -> Unit = {},
) {
    val active = state == SetState.Active
    val done = state == SetState.Done
    val skipped = state == SetState.Skipped
    val fieldBorder = if (active) Forge.Lime else Forge.Border
    val valueColor = when (state) { SetState.Done -> Forge.Text; SetState.Active -> Forge.Lime; SetState.Upcoming -> Forge.Muted; SetState.Skipped -> Forge.Faint }
    val strike = if (skipped) TextDecoration.LineThrough else null
    val longPress by rememberUpdatedState(onLongPress)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // 11 A · set tick (420 ms): the check squashes to 0.85 and floods lime (80 ms), springs past 1.1
    // (160 ms, light haptic), a lime wipe crosses the row, and the next row lifts 4 dp as its border fades in.
    val reduce = app.forge.ui.reduceMotion()
    val view = androidx.compose.ui.platform.LocalView.current
    val checkScale = remember { androidx.compose.animation.core.Animatable(1f) }
    val wipe = remember { androidx.compose.animation.core.Animatable(0f) }
    val lift = remember { androidx.compose.animation.core.Animatable(0f) }
    var last by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        val was = last
        last = state
        if (reduce || was == state) return@LaunchedEffect
        if (state == SetState.Done) coroutineScope {
            launch { checkScale.animateTo(0.85f, androidx.compose.animation.core.tween(80)); checkScale.animateTo(1f, app.forge.ui.Motion.tickSpring()) }
            launch { kotlinx.coroutines.delay(app.forge.ui.Motion.TICK_HAPTIC_MS); app.forge.ui.Haptics.light(view) }
            launch {
                kotlinx.coroutines.delay(100)
                wipe.snapTo(0f); wipe.animateTo(1f, androidx.compose.animation.core.tween(180, easing = app.forge.ui.Motion.Wipe))
                wipe.animateTo(0f, androidx.compose.animation.core.tween(140))
            }
        }
        if (state == SetState.Active) {
            lift.snapTo(1f); kotlinx.coroutines.delay(160)
            lift.animateTo(0f, androidx.compose.animation.core.tween(120, easing = app.forge.ui.Motion.Wipe))
        }
    }
    val borderColor = if (active) Forge.Lime.copy(alpha = 1f - lift.value) else Forge.Border
    Row(
        modifier.fillMaxWidth()
            .graphicsLayer { translationY = lift.value * 4.dp.toPx() }
            .panel(bg = if (active) Forge.Surface2 else Forge.Surface, border = borderColor, radius = Forge.RRow)
            .drawBehind {
                if (wipe.value > 0f) drawRect(
                    Forge.Lime.copy(alpha = 0.14f * (if (wipe.isRunning && wipe.targetValue == 0f) wipe.value else 1f)),
                    size = androidx.compose.ui.geometry.Size(size.width * wipe.value, size.height),
                )
            }
            // The parts of the row with no tap of their own (previous, padding) take the long press here;
            // the set number and value fields take it themselves.
            .then(if (onLongPress != null) Modifier.pointerInput(Unit) {
                detectTapGestures(onLongPress = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); longPress?.invoke() })
            } else Modifier)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            set, style = Type.DataM.copy(fontSize = 13.sp, color = if (skipped) Forge.Faint else setColor ?: if (done) Forge.Muted else Forge.Text, textDecoration = strike),
            modifier = Modifier.width(32.dp).then(if (onSet != null) Modifier.tap(action = "Set options", onLongClick = onLongPress, longAction = "Set options", onClick = onSet) else Modifier),
        )
        Text(previous, style = Type.DataS.copy(fontSize = 12.sp, color = if (skipped) Forge.Faint else Type.DataS.color, textDecoration = strike), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        listOf(Triple(kg, onKg, labels.first.lowercase()), Triple(reps, onReps, labels.second.lowercase())).forEach { (v, click, what) ->
            Box(
                Modifier.width(64.dp).heightIn(min = 36.dp).panel(bg = Forge.Bg, border = fieldBorder, radius = Forge.RTile)
                    .then(if (skipped) Modifier else Modifier.tap(label = "Set $set $what $v", action = "Edit $what", onLongClick = onLongPress, longAction = "Set options", onClick = click))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(v, style = Type.DataM.copy(color = valueColor, textDecoration = strike), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (skipped) {
            Box(
                Modifier.widthIn(min = 40.dp).heightIn(min = 36.dp).tap(label = "Set $set skipped", action = "Undo skip", onClick = onUndoSkip),
                contentAlignment = Alignment.Center,
            ) { Text("UNDO", style = Type.DataM.copy(fontSize = 12.sp, color = Forge.Lime)) }
            return@Row
        }
        val checkBg by androidx.compose.animation.animateColorAsState(
            if (done) Forge.Lime else Color.Transparent, androidx.compose.animation.core.tween(if (reduce) app.forge.ui.Motion.FADE_MS else 80), label = "check",
        )
        Box(
            Modifier.width(40.dp).heightIn(min = 36.dp)
                .graphicsLayer { scaleX = checkScale.value; scaleY = checkScale.value }
                .panel(bg = checkBg, border = if (done) Forge.Lime else fieldBorder, radius = Forge.RTile)
                .tap(label = if (done) "Set $set logged" else "Log set $set", action = if (done) "Untick" else "Log", onClick = onCheck),
            contentAlignment = Alignment.Center,
        ) {
            ForgeIcon(R.drawable.ic_check, 16.dp, when (state) { SetState.Done -> Forge.OnLime; SetState.Active -> Forge.Lime; SetState.Upcoming, SetState.Skipped -> Forge.Border })
        }
    }
}

/** Figma "TemplateCard": 4dp template-colour spine, kicker, serif title, muscles, play button. */
@Composable
fun TemplateCard(
    title: String, meta: String, muscles: String, color: Color, nextUp: Boolean, modifier: Modifier = Modifier,
    onOpen: () -> Unit = {}, onPlay: () -> Unit = {}, onLongPress: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .panel(bg = Forge.Surface, border = if (nextUp) Forge.Lime else Forge.Border, radius = Forge.RCard)
            .tap(action = "Edit template", onLongClick = onLongPress, longAction = "Share template", onClick = onOpen)
            .height(IntrinsicSize.Min)
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(color))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nextUp) Text(
                    "NEXT UP", style = Type.Label.copy(color = Forge.OnLime, fontSize = 9.sp, letterSpacing = 0.sp),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Forge.Lime).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(meta.uppercase(), style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(title, style = Type.DisplayM, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(muscles, style = Type.BodyMuted.copy(fontSize = 13.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(if (nextUp) Forge.Lime else Forge.Surface2).tap(label = "Start $title", onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) { ForgeIcon(R.drawable.ic_play, 18.dp, if (nextUp) Forge.OnLime else Forge.Lime) }
    }
}

// ---- Sheets -------------------------------------------------------------------------------

/** Scrim + bottom sheet with a grabber. Tapping the scrim or Back dismisses it. */
@Composable
fun BottomSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // Inside the app the sheet is drawn by the SheetHost in the main window, which gets the system bar and
    // keyboard insets right. A separate dialog window didn't on some phones, leaving the bottom of the sheet
    // under the navigation bar. The dialog is only the fallback outside a host.
    val host = LocalSheetHost.current
    if (host != null) { HostedSheet(host, onDismiss, content); return }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The sheet draws its own scrim, so switch off the window's default dim.
        (LocalView.current.parent as? DialogWindowProvider)?.window?.setDimAmount(0f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).scrimTap(onDismiss), contentAlignment = Alignment.BottomCenter) {
            SheetPanel(Modifier.blockTaps().navigationBarsPadding().imePadding(), content)
        }
    }
}

/** The sheet itself, separate from the window so screenshots and previews can render it inline. */
@Composable
fun SheetPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Forge.Surface).border(1.dp, Forge.Border, shape).then(modifier)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Forge.Gutter).padding(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(Forge.Border))
        content()
    }
}

@Composable
fun ConfirmSheet(title: String, body: String, confirm: String, destructive: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    BottomSheet(onDismiss) {
        Text(title, style = Type.DisplayM)
        Text(body, style = Type.BodyMuted)
        // Lime means success; destructive actions are red (as in Figma section 14).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (destructive) {
                GhostButton("Cancel", Modifier.weight(1f), color = Forge.Muted, onClick = onDismiss)
                SecondaryButton(confirm, Modifier.weight(1f), color = Forge.Red) { onConfirm() }
            } else {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                PrimaryButton(confirm, Modifier.weight(1f)) { onConfirm() }
            }
        }
    }
}
