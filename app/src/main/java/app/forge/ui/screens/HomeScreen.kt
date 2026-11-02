package app.forge.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.AppViewModel
import app.forge.R
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.Overlay
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.TemplateColors
import app.forge.ui.theme.Type
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(data: AppData, vm: AppViewModel, nav: Nav, onStart: (Template?) -> Unit, onCardio: () -> Unit = {}) {
    // F: long-press a template card to share it.
    var sharing by remember { mutableStateOf<Template?>(null) }
    sharing?.let { t -> ShareTemplateSheet(t, data, vm) { sharing = null } }
    val today = Weeks.today()
    val monday = Weeks.monday(today)
    val goal = data.settings.weeklyGoal
    val week = remember(data.history, monday) { Weeks.sessionsIn(data.history, monday) }
    val trainedDays = remember(week) { week.map { Weeks.date(it.startedAt) }.toSet() }
    val streakState = data.streak
    val streak = remember(data.history, streakState, goal) { Streaks.display(streakState, data.history, goal, today) }
    val paused = Streaks.pausedNow(streakState, today)
    // This week's target: the goal, goal + 1 during a repair quest, 2 in a comeback week (0 while paused).
    val target = Streaks.target(streakState, goal, today)
    val lastByTemplate = remember(data.history) {
        data.history.filter { it.templateId != null }.groupBy { it.templateId!! }.mapValues { (_, v) -> v.maxOf { it.startedAt } }
    }
    // NEXT UP = the template you've gone longest without (never-done ones first).
    val nextUp = remember(data.templates, lastByTemplate) { data.templates.minByOrNull { lastByTemplate[it.id] ?: Long.MIN_VALUE } }
    val ordered = remember(data.templates, nextUp) { listOfNotNull(nextUp) + data.templates.filter { it.id != nextUp?.id } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Forge.Gutter, end = Forge.Gutter, top = 12.dp, bottom = 32.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val kicker = today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)) + " · Week " + Weeks.weekOfUse(data.history, today)
                    Text(kicker.uppercase(), style = Type.Kicker)
                    Text("Ready to lift, ${data.settings.name.ifBlank { "lifter" }}?", style = Type.DisplayM)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StreakPill(streak, paused) { nav.push(Overlay.Pause) }
                    Avatar(data.settings.avatarPath, data.settings.name, avatarColor(data), 36.dp, description = "Profile", action = "Open profile") { nav.goTab(app.forge.ui.Tab.PROFILE) }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        item {
            WeekGoalCard(monday, today, trainedDays, week.size, target, paused)
            Spacer(Modifier.height(14.dp))
        }
        item {
            OutsideCard(data) { nav.push(Overlay.Context) }
            Spacer(Modifier.height(14.dp))
        }
        item {
            val quest = streakState.repair?.takeIf { it.week == Weeks.key(today) }
            if (quest != null) {
                RepairCard(quest, week.size, today)
                Spacer(Modifier.height(14.dp))
                return@item
            }
            val needed = (target - week.size).coerceAtLeast(0)
            val daysLeft = Weeks.daysLeft(today) - if (today in trainedDays) 1 else 0
            // Shown only when the maths gets tight (at most one spare day). Once the goal can't be reached
            // any more it's hidden; the repair quest (Phase 3) handles that case.
            if (!paused && needed > 0 && needed <= daysLeft && needed >= daysLeft - 1 && nextUp != null) {
                NudgeBanner(
                    "$needed ${if (needed == 1) "session" else "sessions"} left, $daysLeft ${if (daysLeft == 1) "day" else "days"} to go",
                    if (streak > 0) "${nextUp.name} today keeps your $streak-week streak alive."
                    else "${nextUp.name} today keeps this week on track.",
                )
                Spacer(Modifier.height(14.dp))
            }
        }
        item {
            Spacer(Modifier.height(14.dp))
            SectionHeader("My templates", "${data.templates.size} · unlimited", trailingColor = Forge.Lime)
            Spacer(Modifier.height(16.dp))
        }
        if (data.templates.isEmpty()) item {
            Text("No templates yet. Build one in a few taps, or start an empty session.", style = Type.BodyMuted)
            Spacer(Modifier.height(16.dp))
        }
        items(ordered, key = { it.id }) { t ->
            val isNext = t.id == nextUp?.id
            val last = lastByTemplate[t.id]
            val ago = last?.let { Stats.daysAgo(it) }
            val agoText = when (ago) { null -> "Not done yet"; 0L -> "Today"; 1L -> "Yesterday"; else -> "$ago days ago" }
            TemplateCard(
                title = t.name,
                meta = if (isNext) (if (last == null) "Due · not done yet" else "Due · last ${agoText.lowercase()}") else "Last: $agoText",
                muscles = templateSummary(t, vm),
                color = TemplateColors.of(t.colorKey),
                nextUp = isNext,
                onOpen = { nav.push(Overlay.EditTemplate(t)) },
                onLongPress = { sharing = t },
                onPlay = { onStart(t) },
            )
            Spacer(Modifier.height(14.dp))
        }
        item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f).fillMaxHeight().dashedBorder(Forge.Lime, Forge.RButton).clip(RoundedCornerShape(Forge.RButton))
                        .tap { nav.push(Overlay.QuickBuild) }.padding(16.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    ForgeIcon(R.drawable.ic_plus, 16.dp, Forge.Lime)
                    Spacer(Modifier.width(8.dp))
                    Text("NEW TEMPLATE", style = Type.DataM.copy(fontSize = 12.sp, color = Forge.Lime), maxLines = 1)
                }
                SecondaryButton("Quick start", Modifier.weight(1f)) { onStart(null) }
            }
            Spacer(Modifier.height(8.dp))
            GhostButton("Log cardio", Modifier.fillMaxWidth(), color = Forge.Ice, onClick = onCardio)
        }
    }
}

/** "Chest · Shoulders · Triceps · 6 exercises" */
fun templateSummary(t: Template, vm: AppViewModel): String {
    val muscles = t.exercises.map { vm.exercise(it.exerciseId).muscle }.distinct().take(3)
    val count = "${t.exercises.size} ${if (t.exercises.size == 1) "exercise" else "exercises"}"
    return (muscles + count).joinToString(" · ")
}

/** 1.1 weekly goal: Monday–Sunday strip, ticked days in lime, today outlined. */
@Composable
private fun WeekGoalCard(monday: java.time.LocalDate, today: java.time.LocalDate, trained: Set<java.time.LocalDate>, done: Int, goal: Int, paused: Boolean) {
    Column(Modifier.fillMaxWidth().panel(radius = Forge.RCard).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("THIS WEEK", style = Type.Label)
            Text(
                if (paused) "PAUSED" else "$done / $goal SESSIONS",
                style = Type.DataS.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = if (paused) Forge.Ice else Forge.Lime),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (0 until 7).forEach { i ->
                val d = monday.plusDays(i.toLong())
                val ticked = d in trained
                val isToday = d == today
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("MTWTFSS"[i].toString(), style = Type.Label.copy(color = if (isToday) Forge.Lime else Forge.Muted))
                    Box(
                        Modifier.size(34.dp).clip(CircleShape)
                            .then(if (ticked) Modifier.background(Forge.Lime) else Modifier.border(1.dp, if (isToday) Forge.Lime else Forge.Border, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) { if (ticked) ForgeIcon(R.drawable.ic_check, 14.dp, Forge.OnLime) }
                }
            }
        }
    }
}

/** 1.2 amber nudge. */
@Composable
private fun NudgeBanner(title: String, body: String) {
    Row(
        Modifier.fillMaxWidth().panel(bg = Forge.AmberBg, border = Forge.Amber, radius = Forge.RCard).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ForgeIcon(R.drawable.ic_zap, 20.dp, Forge.Amber)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Type.TitleM.copy(fontSize = 14.sp))
            Text(body, style = Type.BodyS)
        }
    }
}
