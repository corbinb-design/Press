package app.forge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.AppViewModel
import app.forge.R
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.TemplateColors
import app.forge.ui.theme.Type

// ---- 7.4 XP bar -------------------------------------------------------------------------------

/** Above the Cabinet covers: XP so far and the distance to the next tier. Tap for 13.1. */
@Composable
fun XpBar(xp: Int, onClick: () -> Unit) {
    val next = Xp.nextTier(xp)
    val from = Xp.tier(xp).from
    Column(Modifier.fillMaxWidth().tap(action = "How XP works", onClick = onClick).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%,d XP".format(xp), style = Type.DataM.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
            Text(next?.let { "%,d to ${it.label}".format(it.from - xp).uppercase() } ?: "FORGED", style = Type.Label)
        }
        val frac = if (next == null) 1f else ((xp - from).toFloat() / (next.from - from)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Forge.Border)) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Forge.Lime))
        }
    }
}

// ---- 13.1 How XP works ------------------------------------------------------------------------

@Composable
fun HowXpScreen(data: AppData, nav: Nav) {
    val xp = remember(data) { Xp.total(data) }
    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_back, { nav.pop() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 18.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Level ${Xp.level(xp)} · ${Xp.tier(xp).label} · %,d XP".format(xp).uppercase(), style = Type.Label)
                Text("How XP works", style = Type.DisplayL)
            }
            XpGroup("Earns XP") {
                XpRow("Finish a workout", "+${Xp.WORKOUT}")
                XpRow("Set a personal record", "+${Xp.RECORD} each")
                XpRow("Hit your weekly goal", "+${Xp.WEEKLY_GOAL}")
                XpRow("Complete a repair quest", "+${Xp.REPAIR_QUEST}")
                XpRow("Take a suggested lighter week", "+${Xp.LIGHTER_WEEK}")
                XpRow("Finish a comeback week", "+${Xp.COMEBACK}")
            }
            XpGroup("Never earns XP") {
                NeverRow("Opening the app or daily check-ins")
                NeverRow("Sets you didn’t actually tick")
                NeverRow("Streak days on their own")
            }
            XpGroup("Levels") {
                val tier = Xp.tier(xp)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Xp.Tier.entries.forEach { t ->
                        val reached = xp >= t.from
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (t == tier) Forge.Lime else Forge.Border))
                            Text(t.label, style = Type.BodyS.copy(fontSize = 12.sp, color = if (t == tier) Forge.Lime else if (reached) Forge.Text else Forge.Muted))
                            Text(if (t.from == 0) "0" else "${t.from / 1000}k", style = Type.DataS.copy(fontSize = 10.sp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun XpGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = Type.BodyMuted, modifier = Modifier.padding(bottom = 10.dp))
        Hairline()
        content()
    }
}

@Composable
private fun XpRow(label: String, xp: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.BodyM, modifier = Modifier.weight(1f))
        Text(xp, style = Type.DataM.copy(fontSize = 13.sp, color = Forge.Lime))
    }
    Hairline()
}

@Composable
private fun NeverRow(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ForgeIcon(R.drawable.ic_close, 14.dp, Forge.Muted)
        Text(label, style = Type.BodyMuted)
    }
    Hairline()
}

// ---- 13.2 Unlocks -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnlocksScreen(data: AppData, vm: AppViewModel, nav: Nav?) {
    val p = remember(data) { Unlocks.progress(data) }
    val icon = Unlocks.Icon.of(data.settings.appIcon)
    val spine = Unlocks.Spine.of(data.settings.spineFinish)
    Column(Modifier.fillMaxSize()) {
        if (nav != null) IconTopBar(R.drawable.ic_back, { nav.pop() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("COSMETIC · NEVER AFFECTS TRAINING", style = Type.Label)
                Text("Unlocks", style = Type.DisplayL)
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LabelHeader("App icon", "${Unlocks.icons(p).size} of ${Unlocks.Icon.entries.size}")
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(16.dp), maxItemsInEachRow = 3) {
                    Unlocks.Icon.entries.forEach { i ->
                        val owned = i.met(p)
                        UnlockItem(
                            i.label, caption = when { i == icon -> "In use"; owned -> "Owned"; else -> i.requirement },
                            inUse = i == icon, owned = owned, modifier = Modifier.width(106.dp),
                            onClick = { vm.setAppIcon(i) },
                        ) { IconTile(i, 72.dp, selected = i == icon) }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LabelHeader("Spine finish", "${Unlocks.spines(p).size} of ${Unlocks.Spine.entries.size}")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Unlocks.Spine.entries.forEach { s ->
                        val owned = s.met(p)
                        UnlockItem(
                            s.label, caption = when { s == spine -> "In use"; owned -> "Owned"; else -> s.requirement },
                            inUse = s == spine, owned = owned, modifier = Modifier.weight(1f),
                            onClick = { vm.setSpineFinish(s) },
                        ) { SpineSample(s, Modifier.size(72.dp, 96.dp)) }
                    }
                }
            }
            Text("Your spine finish restyles the shelf in History. Icons change how Press looks on your home screen.", style = Type.BodyS)
        }
    }
}

@Composable
private fun LabelHeader(title: String, trailing: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = Type.BodyMuted, modifier = Modifier.weight(1f))
            Text(trailing.uppercase(), style = Type.Label)
        }
        Hairline()
    }
}

@Composable
private fun UnlockItem(
    label: String, caption: String, inUse: Boolean, owned: Boolean, modifier: Modifier, onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Column(
        modifier.tap(enabled = owned, role = androidx.compose.ui.semantics.Role.RadioButton, action = "Use", onClick = onClick).semantics { selected = inUse },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.alpha(if (owned) 1f else 0.35f)) { preview() }
        Text(label, style = Type.BodyS.copy(fontSize = 12.sp, color = if (owned) Forge.Text else Forge.Muted))
        Text(caption.uppercase(), style = Type.Label.copy(fontSize = 8.sp, color = if (inUse) Forge.Lime else Forge.Muted), textAlign = TextAlign.Center)
    }
}

/** On a launcher icon the 96 grid spans 96 × 31/50.6 of the 108dp canvas, of which the middle 72dp show. */
private const val ICON_MARK_SCALE = 96f * (31f / 50.6f) / 72f

/** A launcher-icon preview: the Press mark on the icon's colour. */
@Composable
fun IconTile(icon: Unlocks.Icon, size: Dp, selected: Boolean = false) {
    val shape = RoundedCornerShape(size * 0.25f)
    Box(
        Modifier.size(size).clip(shape).background(Color(icon.bg))
            .then(
                when {
                    selected -> Modifier.border(2.dp, Forge.Text, shape)
                    icon == Unlocks.Icon.INK -> Modifier.border(1.dp, Forge.Border, shape)
                    else -> Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Same proportion as the launcher: the 96 grid at 31/50.6 of the 108dp canvas, of which 72dp shows.
        Mark(Color(icon.fg), Color(icon.accent), size * ICON_MARK_SCALE)
    }
}

/** Four spines in the given finish, as on 13.2. */
@Composable
fun SpineSample(finish: Unlocks.Spine, modifier: Modifier) {
    val spines = listOf(TemplateColors.Push to 70f, TemplateColors.Pull to 90f, TemplateColors.Legs to 80f, TemplateColors.Upper to 62f)
    Canvas(modifier) {
        val w = size.width / 72f * 14f
        val gap = (size.width - 4 * w) / 3
        spines.forEachIndexed { i, (c, h) ->
            val height = size.height * h / 96f
            drawSpine(finish, c, Offset(i * (w + gap), size.height - height), Size(w, height))
        }
    }
}

/** Draws one spine in [finish]. Shared by the 13.2 samples and the History shelf. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpine(
    finish: Unlocks.Spine, color: Color, topLeft: Offset, size: Size,
) {
    val r = CornerRadius(2.dp.toPx())
    when (finish) {
        Unlocks.Spine.FOIL -> drawRoundRect(
            Brush.horizontalGradient(listOf(color, Color.White, color), startX = topLeft.x, endX = topLeft.x + size.width),
            topLeft, size, r,
        )
        else -> drawRoundRect(color, topLeft, size, r)
    }
    when (finish) {
        Unlocks.Spine.LINEN -> {
            // Fine horizontal weave every 5dp.
            val step = 5.dp.toPx()
            var y = topLeft.y + 4.dp.toPx()
            while (y < topLeft.y + size.height) {
                drawRect(Color.Black.copy(alpha = 0.25f), Offset(topLeft.x, y), Size(size.width, 1.dp.toPx()))
                y += step
            }
        }
        Unlocks.Spine.BOUND -> drawRect(Forge.Bg, Offset(topLeft.x, topLeft.y + 8.dp.toPx()), Size(size.width, 4.dp.toPx()))
        else -> {}
    }
}

// ---- 13.3 Level-up moment ---------------------------------------------------------------------

/** After the summary that tipped you over: quiet rings, the new cosmetic, one-tap equip. */
@Composable
fun LevelUpScreen(xp: Int, newTier: Boolean, unlocked: Unlocks.New, onUseIcon: (Unlocks.Icon) -> Unit, onLater: () -> Unit) {
    val icon = unlocked.icons.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 32.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    listOf(150.dp, 115.dp, 80.dp).forEachIndexed { i, r ->
                        drawCircle(Forge.Lime.copy(alpha = 0.18f + i * 0.12f), r.toPx(), style = Stroke(1.dp.toPx()))
                    }
                }
                if (icon != null) IconTile(icon, 110.dp)
                else Box(Modifier.size(110.dp).clip(CircleShape).background(Forge.Surface), contentAlignment = Alignment.Center) {
                    Text("${Xp.level(xp)}", style = Type.FigureL.copy(color = Forge.Lime))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("LEVEL ${Xp.level(xp)}", style = Type.Kicker)
            Spacer(Modifier.height(14.dp))
            Text(if (newTier) "${Xp.tier(xp).label}." else "Level ${Xp.level(xp)}.", style = Type.DisplayXL.copy(fontSize = 72.sp))
            Spacer(Modifier.height(16.dp))
            Text(
                "%,d XP, every bit of it lifted.".format(xp),
                style = Type.DisplayItalic.copy(fontSize = 22.sp, color = Forge.Muted, fontStyle = FontStyle.Italic), textAlign = TextAlign.Center,
            )
            if (unlocked.icons.isNotEmpty() || unlocked.spines.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth()) {
                    Text("Unlocked", style = Type.BodyMuted, modifier = Modifier.padding(bottom = 10.dp))
                    Hairline()
                    (unlocked.icons.map { "${it.label} app icon" } + unlocked.spines.map { "${it.label} spine finish" }).forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item, style = Type.BodyM, modifier = Modifier.weight(1f))
                            Text("NEW", style = Type.Label.copy(color = Forge.Lime))
                        }
                        Hairline()
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = Forge.Gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) PrimaryButton("Use ${icon.label} icon", Modifier.fillMaxWidth()) { onUseIcon(icon) }
            GhostButton(if (icon != null) "Later" else "Continue", Modifier.fillMaxWidth(), onClick = onLater)
        }
    }
}
