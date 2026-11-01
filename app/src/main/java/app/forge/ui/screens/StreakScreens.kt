package app.forge.ui.screens

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.AppViewModel
import app.forge.R
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import java.time.LocalDate

/** Home header pill: the streak in weeks, or PAUSED in ice while a pause covers this week. Tap to pause or resume. */
@Composable
fun StreakPill(weeks: Int, paused: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.clip(shape).background(Forge.Surface).border(1.dp, if (paused) Forge.Ice else Forge.Border, shape).tap(label = if (paused) "Streak paused" else "Streak, $weeks weeks", action = if (paused) "Resume streak" else "Pause streak", onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ForgeIcon(if (paused) R.drawable.ic_pause else R.drawable.ic_flame, 16.dp, if (paused) Forge.Ice else Forge.Lime)
        Text(if (paused) "PAUSED" else "$weeks WK", style = Type.DataM.copy(fontSize = 13.sp, color = if (paused) Forge.Ice else Forge.Text))
    }
}

/** 2.2: on Home the quest card replaces the nudge banner until it's done or the week ends. */
@Composable
fun RepairCard(quest: RepairQuest, done: Int, today: LocalDate) {
    val daysLeft = Weeks.daysLeft(today)
    Column(Modifier.fillMaxWidth().panel(bg = Forge.Surface, border = Forge.Lime, radius = Forge.RCard).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ForgeIcon(R.drawable.ic_refresh, 20.dp, Forge.Lime)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Repair quest: ${minOf(done, quest.target)} of ${quest.target}", style = Type.TitleM.copy(fontSize = 14.sp))
                Text(
                    if (done >= quest.target) "Done. Last week counts and your streak is back."
                    else "$daysLeft ${if (daysLeft == 1) "day" else "days"} left. Hit ${quest.target} and your ${quest.streakAtStake}-week streak is restored.",
                    style = Type.BodyS,
                )
            }
        }
        Segments(done, quest.target, height = 6.dp)
    }
}

@Composable
private fun Segments(done: Int, total: Int, height: androidx.compose.ui.unit.Dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { i -> Box(Modifier.weight(1f).height(height).clip(RoundedCornerShape(3.dp)).background(if (i < done) Forge.Lime else Forge.Border)) }
    }
}

/** Sheets 2.1–2.3, shown on Home after the weekly check. */
@Composable
fun StreakNoticeSheet(data: AppData, vm: AppViewModel, today: LocalDate = Weeks.today()) {
    val s = data.streak
    val notice = s.notice ?: return
    val goal = data.settings.weeklyGoal
    BottomSheet(onDismiss = { if (notice.kind == StreakNotice.Kind.REPAIR_OFFERED) vm.acceptRepair() else vm.dismissStreakNotice() }) {
        when (notice.kind) {
            StreakNotice.Kind.FREEZE_USED -> {
                NoticeHead(R.drawable.ic_shield, Forge.Ice, Forge.OnLime, "Streak protected", Forge.Ice, "Last week got away from you. We had your back.")
                Text(
                    "You hit ${notice.sessions} of ${notice.goal} sessions, so a freeze was used automatically. Your streak is still ${s.weeks} ${if (s.weeks == 1) "week" else "weeks"}.",
                    style = Type.BodyMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoticeBox("Freezes left", "${s.freezes}", Forge.Ice, Modifier.weight(1f))
                    val next = Streaks.weeksToNextFreeze(s)
                    NoticeBox("Next freeze", if (s.freezes >= Streaks.MAX_FREEZES) "BANK FULL" else "IN $next ${if (next == 1) "WEEK" else "WEEKS"}", Forge.Text, Modifier.weight(1f))
                }
                PrimaryButton("Back to it", Modifier.fillMaxWidth()) { vm.dismissStreakNotice() }
            }
            StreakNotice.Kind.REPAIR_OFFERED -> {
                val q = s.repair
                if (q == null) { LaunchedEffect(Unit) { vm.dismissStreakNotice() }; return@BottomSheet }
                NoticeHead(R.drawable.ic_refresh, Forge.Lime, Forge.OnLime, "Repair quest", Forge.Lime, "Do ${q.target} this week to restore your ${q.streakAtStake}-week streak.")
                Text("One extra session on top of your goal. Hit it by Sunday and last week counts.", style = Type.BodyMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                val done = Weeks.sessionsIn(data.history, Weeks.monday(today)).size
                Column(Modifier.fillMaxWidth().panel(bg = Forge.Bg, radius = Forge.RButton).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("PROGRESS", style = Type.Label)
                        val days = Weeks.daysLeft(today)
                        Text("$done / ${q.target} · $days ${if (days == 1) "DAY" else "DAYS"} LEFT", style = Type.DataS.copy(fontWeight = FontWeight.Medium, color = Forge.Lime))
                    }
                    Segments(done, q.target, 6.dp)
                }
                PrimaryButton("Accept quest", Modifier.fillMaxWidth()) { vm.acceptRepair() }
                GhostButton("Start fresh instead", Modifier.fillMaxWidth()) { vm.declineRepair() }
            }
            StreakNotice.Kind.FRESH_START -> {
                val comeback = s.comebackWeek == Weeks.key(today)
                NoticeHead(R.drawable.ic_flame, Forge.Surface2, Forge.Lime, "Fresh start", Forge.Lime, "New streak starts now.")
                Text(
                    "Your best streak of ${s.bestStreakWeeks} ${if (s.bestStreakWeeks == 1) "week" else "weeks"} stays on your profile. " +
                        if (comeback) "This week’s target is ${Streaks.COMEBACK_TARGET} sessions to ease back in, then it goes back to $goal."
                        else "You already hit last week’s goal, so the new streak starts at ${s.weeks}.",
                    style = Type.BodyMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoticeBox("Best streak", "${s.bestStreakWeeks} WK", Forge.Lime, Modifier.weight(1f))
                    NoticeBox("This week", "${Streaks.target(s, goal, today)} SESSIONS", Forge.Text, Modifier.weight(1f))
                }
                PrimaryButton("Let’s go", Modifier.fillMaxWidth()) { vm.dismissStreakNotice() }
            }
        }
    }
}

@Composable
private fun NoticeHead(icon: Int, circle: Color, iconTint: Color, kicker: String, kickerColor: Color, title: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) { ForgeIcon(icon, 28.dp, iconTint) }
        Text(kicker.uppercase(), style = Type.DataS.copy(fontWeight = FontWeight.Medium, color = kickerColor))
        Text(title, style = Type.DisplayM, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NoticeBox(label: String, value: String, valueColor: Color, modifier: Modifier) {
    Column(modifier.panel(bg = Forge.Bg, radius = Forge.RRow).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = Type.Label)
        Text(value, style = Type.DataM.copy(fontSize = 15.sp, color = valueColor))
    }
}

// ---- 2.4 Pause streak -----------------------------------------------------------------------

private val reasons = listOf("Holiday", "Injury", "Illness", "Busy")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PauseScreen(data: AppData, vm: AppViewModel, nav: Nav, today: LocalDate = Weeks.today()) {
    val s = data.streak
    val pausedNow = Streaks.pausedNow(s, today)
    val left = Streaks.pausesLeft(s, today)
    val streak = Streaks.display(s, data.history, data.settings.weeklyGoal, today)
    var length by remember { mutableStateOf(if (left >= 2) Streaks.PauseLength.TWO else Streaks.PauseLength.ONE) }
    var reason by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = Forge.Gutter - 9.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap(R.drawable.ic_back, Forge.Text, 22.dp, description = "Back") { nav.pop() }
            Text("Pause streak", style = Type.DisplayM)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().panel(radius = 14.dp).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(96.dp).border(6.dp, Forge.Ice, CircleShape), contentAlignment = Alignment.Center) { ForgeIcon(R.drawable.ic_pause, 30.dp, Forge.Ice) }
                Text(
                    when {
                        pausedNow && s.pausedFrom != null -> "Your $streak-week streak is paused until you resume${s.pauseReason?.let { " (${it.lowercase()})" } ?: ""}."
                        pausedNow -> "Your $streak-week streak is paused this week${s.pauseReason?.let { " (${it.lowercase()})" } ?: ""}. It picks up where you left off."
                        else -> "Going away or injured? Pause your $streak-week streak and it picks up where you left off."
                    },
                    style = Type.BodyMuted, textAlign = TextAlign.Center,
                )
            }
            if (!pausedNow) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelRow("How long")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Streaks.PauseLength.ONE to "1 week", Streaks.PauseLength.TWO to "2 weeks", Streaks.PauseLength.UNTIL_RESUMED to "Until I resume").forEach { (l, label) ->
                            val ok = (l.weeks ?: 1) <= left
                            Chip(label, length == l, enabled = ok) { length = l }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelRow("Reason (optional)")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        reasons.forEach { r -> Chip(r, reason == r) { reason = if (reason == r) null else r } }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Forge.RButton)).background(Forge.Surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ForgeIcon(R.drawable.ic_shield, 16.dp, Forge.Ice)
                Text("Pauses don’t use freezes. Up to ${Streaks.MAX_PAUSED_PER_YEAR} paused weeks a year; $left left this year.", style = Type.BodyS)
            }
            if (pausedNow) PrimaryButton("Resume streak", Modifier.fillMaxWidth()) { vm.resumeStreak(); nav.pop() }
            else PrimaryButton(
                when (length) { Streaks.PauseLength.ONE -> "Pause this week"; Streaks.PauseLength.TWO -> "Pause for 2 weeks"; Streaks.PauseLength.UNTIL_RESUMED -> "Pause until I resume" },
                Modifier.fillMaxWidth(), enabled = left > 0,
            ) { vm.pauseStreak(length, reason); nav.pop() }
        }
    }
}
