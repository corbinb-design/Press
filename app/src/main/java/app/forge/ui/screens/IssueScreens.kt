package app.forge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.R
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.Overlay
import app.forge.ui.Posters
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.TemplateColors
import app.forge.ui.theme.Type
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- 12.2 The issue ---------------------------------------------------------------------------

@Composable
fun IssueScreen(weekKey: String, data: AppData, nav: Nav, showAll: Boolean = true) {
    val issue = remember(weekKey, data) { Issues.issue(data, Streaks.monday(weekKey)) }
    // Context layer (O.1): the AI's checked headline and summary when this week has one, else the template; chips
    // are always facts Press computed.
    val ai = data.issueCoachFor(weekKey)?.text
    val facts = remember(weekKey, data) { Issues.facts(data, Streaks.monday(weekKey)) }
    val evidence = ai?.evidence ?: Issues.templateEvidence(facts).mapNotNull { id -> facts.firstOrNull { it.id == id } }
    var sharing by remember { mutableStateOf(false) }
    val unit = data.settings.unit
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Forge.Gutter - 9.dp, vertical = 2.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) { IconTap(R.drawable.ic_close, Forge.Text, 22.dp, description = "Close") { nav.pop() } }
            Text("ISSUE NO. ${issue.number}", style = Type.Kicker, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd)) { IconTap(R.drawable.ic_share, Forge.Text, 20.dp, description = "Share this issue") { sharing = true } }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(Issues.weekLabel(issue.monday).uppercase(), style = Type.Label)
                Text(ai?.headline ?: issue.headline, style = Type.DisplayL)
                Text(issue.standfirst, style = Type.DisplayItalic.copy(fontSize = 22.sp, color = Forge.Lime, fontStyle = FontStyle.Italic))
            }
            EvidenceChips(evidence)
            Text(ai?.summary ?: issue.paragraph, style = Type.BodyL.copy(fontSize = 15.sp))
            issue.outside?.let { line ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Outside the gym", style = Type.BodyMuted, modifier = Modifier.weight(1f))
                        Text("FROM YOUR WATCH", style = Type.Label)
                    }
                    Hairline()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ForgeIcon(R.drawable.ic_mountain, 16.dp, Forge.Ice)
                        Text(line, style = Type.BodyM)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("The week in spines", style = Type.BodyMuted, modifier = Modifier.weight(1f))
                    Text("${issue.spines.size} ${if (issue.spines.size == 1) "SESSION" else "SESSIONS"}", style = Type.Label)
                }
                Hairline()
                if (issue.spines.isEmpty()) Text("No sessions this week.", style = Type.BodyS)
                else WeekSpines(issue.spines)
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // A week with time outside reads Streak · Gym · Outside (O.1); otherwise the usual Lifted · Records · Streak.
                val outsideDays = Outside.between(data.outsideDays, issue.monday, issue.monday.plusDays(6)).count { it.outings.any(Outside::counts) }
                if (issue.outside != null) {
                    IssueStat("Streak", "${issue.streakWeeks} wk", Modifier.weight(1f), lime = true)
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Forge.Border))
                    IssueStat("Gym", "${Weeks.sessionsIn(data.history, issue.monday).size}", Modifier.weight(1f).padding(start = 12.dp))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Forge.Border))
                    IssueStat("Outside", "$outsideDays ${if (outsideDays == 1) "day" else "days"}", Modifier.weight(1f).padding(start = 12.dp), ice = true)
                } else {
                    IssueStat("Lifted", Units.grouped(issue.lifted, unit), Modifier.weight(1f))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Forge.Border))
                    IssueStat("Records", "${issue.records}", Modifier.weight(1f).padding(start = 12.dp))
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Forge.Border))
                    IssueStat("Streak", "${issue.streakWeeks} wk", Modifier.weight(1f).padding(start = 12.dp), lime = true)
                }
            }
            issue.movement?.let { line ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Everyday movement", style = Type.BodyMuted)
                    Hairline()
                    Text(line, style = Type.BodyM)
                    EvidenceChips(facts.filter { it.id.startsWith("movement.highStepDay.") })
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Next week", style = Type.BodyMuted)
                Hairline()
                Text(issue.nextWeek, style = Type.BodyMuted.copy(fontSize = 13.sp))
            }
            if (ai != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ForgeIcon(R.drawable.ic_sparkle, 12.dp, Forge.Muted)
                Text("Headline and summary written by AI from Press’s numbers.", style = Type.BodyS)
            }
        }
        Hairline()
        Column(Modifier.padding(horizontal = Forge.Gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PrimaryButton("Share this issue", Modifier.fillMaxWidth()) { sharing = true }
            if (showAll) GhostButton("See all issues", Modifier.fillMaxWidth()) { nav.push(Overlay.Issues) }
        }
    }
    if (sharing) PosterSheet(issue, data, onDismiss = { sharing = false })
}

@Composable
private fun IssueStat(label: String, value: String, modifier: Modifier, lime: Boolean = false, ice: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Type.BodyS.copy(fontSize = 12.sp))
        Text(value, style = Type.FigureM.copy(fontSize = 28.sp, color = when { lime -> Forge.Lime; ice -> Forge.Ice; else -> Forge.Text }), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One spine per session, height by volume, lime dot on record days, day and template under each. */
@Composable
fun WeekSpines(spines: List<Issues.Spine>) {
    val maxVol = spines.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
    val day = DateTimeFormatter.ofPattern("EEE", Locale.US)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (spines.size - 1)) / spines.size
            spines.forEachIndexed { i, s ->
                val h = (s.volume / maxVol * (size.height - 14.dp.toPx())).toFloat().coerceAtLeast(16.dp.toPx())
                val x = i * (w + gap)
                val cm = s.cardioMinutes
                if (cm != null) {
                    // Cardio: a thin ice spine sized by minutes.
                    val ch = (cm / 80f).coerceIn(0.2f, 1f) * (size.height - 14.dp.toPx())
                    drawRect(Forge.Ice, Offset(x + w * 0.4f, size.height - ch), Size(w * 0.2f, ch))
                } else drawRect(TemplateColors.of(s.colorKey), Offset(x, size.height - h), Size(w, h))
                if (s.record) drawCircle(Forge.Lime, 3.dp.toPx(), Offset(x + w / 2, size.height - h - 8.dp.toPx()))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            spines.forEach { s ->
                Text(
                    "${s.day.format(day)} · ${s.colorKey ?: ""}".trimEnd(' ', '·').uppercase(), style = Type.Label.copy(fontSize = 8.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 12.3: choose the Ink or Paper edition, then the Android share sheet. */
@Composable
private fun PosterSheet(issue: Issues.Issue, data: AppData, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var paper by remember { mutableStateOf(false) }
    val ink = remember(issue) { Posters.render(ctx, issue, data.settings.name, data.settings.unit, paper = false) }
    val light = remember(issue) { Posters.render(ctx, issue, data.settings.name, data.settings.unit, paper = true) }
    BottomSheet(onDismiss) {
        SheetHeader("Share issue No. ${issue.number}", "A story-sized poster. No app screenshots, just your week.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(false to ink, true to light).forEach { (isPaper, bmp) ->
                Column(
                    Modifier.weight(1f).tap(role = androidx.compose.ui.semantics.Role.RadioButton) { paper = isPaper }
                        .semantics { selected = paper == isPaper },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        bmp.asImageBitmap(), contentDescription = if (isPaper) "Paper edition" else "Ink edition",
                        modifier = Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp))
                            .panel(bg = Forge.Bg, border = if (paper == isPaper) Forge.Lime else Forge.Border, radius = 10.dp),
                    )
                    Text(if (isPaper) "Paper" else "Ink", style = Type.BodyS.copy(fontSize = 12.sp, color = if (paper == isPaper) Forge.Text else Forge.Muted))
                }
            }
        }
        PrimaryButton("Share", Modifier.fillMaxWidth()) {
            Posters.share(ctx, if (paper) light else ink, "press-issue-${issue.number}-${if (paper) "paper" else "ink"}.png")
            onDismiss()
        }
    }
}

// ---- Archive ----------------------------------------------------------------------------------

/** Every issue so far, numbered like back issues. */
@Composable
fun IssuesScreen(data: AppData, nav: Nav) {
    val issues = remember(data) { Issues.archive(data, LocalDateTime.now(Weeks.zone)) }
    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_back, { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 16.dp, bottom = 24.dp)) {
            Text("PRESS WEEKLY · ${issues.size} ${if (issues.size == 1) "ISSUE" else "ISSUES"}", style = Type.Label)
            Spacer(Modifier.height(8.dp))
            Text("The Sunday issue", style = Type.DisplayL)
            Spacer(Modifier.height(24.dp))
            if (issues.isEmpty()) Text("The first issue comes out on the Sunday after your first full week.", style = Type.BodyMuted)
            issues.forEach { IssueRow(it) { nav.push(Overlay.Issue(it.key)) } }
        }
    }
}

@Composable
fun IssueRow(issue: Issues.Issue, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tap(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("No. ${issue.number}", style = Type.DataS.copy(fontSize = 11.sp), modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(issue.headline, style = Type.DisplayS.copy(fontSize = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Issues.weekLabel(issue.monday), style = Type.BodyS)
        }
        ForgeIcon(R.drawable.ic_chevron_right, 16.dp, Forge.Muted)
    }
    Hairline()
}
