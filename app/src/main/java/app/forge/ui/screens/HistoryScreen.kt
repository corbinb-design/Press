package app.forge.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.AppViewModel
import app.forge.R
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.Overlay
import app.forge.ui.clock
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.TemplateColors
import app.forge.ui.theme.Type
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

@Composable
fun HistoryScreen(data: AppData, vm: AppViewModel, nav: Nav, initialTab: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val h = data.history
    val prs = remember(h) { Stats.prsBySession(h) }
    val today = Weeks.today()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Forge.Gutter, end = Forge.Gutter, top = 16.dp, bottom = 32.dp)) {
        item {
            val first = h.minOfOrNull { it.startedAt }?.let(Weeks::date)
            val since = first?.let {
                " since " + it.format(DateTimeFormatter.ofPattern(if (it.year == today.year) "MMM" else "MMM yyyy", Locale.US))
            } ?: ""
            Text("${h.size} ${if (h.size == 1) "session" else "sessions"}$since".uppercase(), style = Type.Label)
            Spacer(Modifier.height(8.dp))
            Text("History", style = Type.DisplayL)
            Spacer(Modifier.height(32.dp))
            Tabs(listOf("Shelf", "Calendar"), tab) { tab = it }
            Spacer(Modifier.height(32.dp))
        }
        if (h.isEmpty()) item {
            Text("Nothing on the shelf yet.", style = Type.DisplayItalic.copy(color = Forge.Muted))
            Spacer(Modifier.height(8.dp))
            Text("Finished sessions line up here like books, tallest on your heaviest days.", style = Type.BodyMuted)
        } else if (tab == 0) {
            item {
                val drop by vm.shelfDrop.collectAsState()
                Shelf(h, data, prs, today, dropOnShelf = drop, onDropped = { vm.dropOnShelf(null) }) { nav.push(Overlay.WorkoutDetail(it)) }
                Spacer(Modifier.height(32.dp))
            }
            // 12.2: the latest Sunday issue, with the archive one tap away.
            Issues.latest(data, java.time.LocalDateTime.now(Weeks.zone))?.let { latest ->
                item(key = "issue") {
                    SectionHeader("The Sunday issue", "All issues", trailingColor = Forge.Lime) { nav.push(Overlay.Issues) }
                    IssueRow(latest) { nav.push(Overlay.Issue(latest.key)) }
                    Spacer(Modifier.height(32.dp))
                }
            }
            // Catalogue, by week.
            val byWeek = h.groupBy { Weeks.monday(Weeks.date(it.startedAt)) }.toSortedMap(compareByDescending { it })
            byWeek.forEach { (monday, sessions) ->
                item(key = "wk$monday") {
                    val weeksAgo = ChronoUnit.WEEKS.between(monday, Weeks.monday(today))
                    val title = when (weeksAgo) { 0L -> "This week"; 1L -> "Last week"; else -> "Week of " + monday.format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) }
                    val paused = Weeks.key(monday) in data.streak.pausedWeeks
                    SectionHeader(title, if (paused) "Paused" else "${sessions.size} of ${data.settings.weeklyGoal}", trailingColor = if (paused) Forge.Ice else Forge.Muted)
                }
                items(sessions, key = { it.id }) { w -> SessionRow(w, prs[w.id] ?: 0, data.settings.unit) { nav.push(Overlay.WorkoutDetail(w.id)) } }
                item { Spacer(Modifier.height(28.dp)) }
            }
        } else {
            item { CalendarTab(h, data, prs, today) { nav.push(Overlay.WorkoutDetail(it)) } }
        }
    }
}

/** 6.1 catalogue row: day, serif title, quiet metadata, PR tag, chevron. */
@Composable
private fun SessionRow(w: Workout, prs: Int, unit: WeightUnit, onClick: () -> Unit) {
    val d = Weeks.date(w.startedAt)
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.width(40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US).uppercase(), style = Type.Label)
            Text(d.dayOfMonth.toString(), style = Type.DataM.copy(fontSize = 18.sp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(w.name, style = Type.DisplayS, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (w.cardioOnly) w.exercises.flatMap { it.sets }.firstOrNull { it.done }?.let { "CARDIO · " + Measure.cardio(it, unit) } ?: "CARDIO"
                else "${Stats.durationMin(w)} min · ${Units.grouped(Stats.volume(w), unit)} ${unit.label}",
                style = Type.DataS.copy(color = if (w.cardioOnly) Forge.Ice else Forge.Muted), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (prs > 0) Tag("$prs PR")
        ForgeIcon(R.drawable.ic_chevron_right, 16.dp, Forge.Muted)
    }
    Hairline()
}

/**
 * 6.1 shelf: the last four weeks as book spines. Height = volume, colour = template,
 * lime dot = a session with a record, dashed stub = a missed slot against the weekly goal.
 */
@Composable
private fun Shelf(
    history: List<Workout>, data: AppData, prs: Map<Long, Int>, today: LocalDate,
    dropOnShelf: Long? = null, onDropped: () -> Unit = {}, onOpen: (Long) -> Unit,
) {
    val goal = data.settings.weeklyGoal
    val finish = Unlocks.Spine.of(data.settings.spineFinish)
    val pausedWeeks = data.streak.pausedWeeks
    val thisMonday = Weeks.monday(today)
    val weeks = (3 downTo 0).map { thisMonday.minusWeeks(it.toLong()) }
    val sessions = weeks.map { m -> Weeks.allIn(history, m).sortedBy { it.startedAt } }
    val maxVol = sessions.flatten().maxOfOrNull { Stats.volume(it) }?.takeIf { it > 0 } ?: 1.0
    val measurer = rememberTextMeasurer()
    val label = Type.Label
    val hits = remember { mutableStateListOf<Pair<Rect, Long>>() }

    // 11 C · session joins the shelf (900 ms, once, after Done on the summary): a stub drops onto the
    // shelf (200–450 ms, soft thud), grows to its true height with ~6% overshoot (450–700 ms), then its
    // record dot pops (900 ms). With motion reduced it simply fades in.
    val dropId = dropOnShelf
    val reduce = app.forge.ui.reduceMotion()
    val view = androidx.compose.ui.platform.LocalView.current
    val t = remember(dropId) { androidx.compose.animation.core.Animatable(if (dropId == null) 1f else 0f) }
    LaunchedEffect(dropId) {
        if (dropId == null) return@LaunchedEffect
        launch { kotlinx.coroutines.delay(450); if (!reduce) app.forge.ui.Haptics.thud(view) }
        t.animateTo(1f, androidx.compose.animation.core.tween(if (reduce) app.forge.ui.Motion.FADE_MS else app.forge.ui.Motion.SHELF_MS, easing = androidx.compose.animation.core.LinearEasing))
        onDropped()
    }
    fun seg(from: Float, to: Float) = ((t.value - from) / (to - from)).coerceIn(0f, 1f)

    SectionHeader("The last four weeks", "Spine height = kg lifted")
    Spacer(Modifier.height(10.dp))
    Canvas(
        Modifier.fillMaxWidth().height(150.dp)
            // TalkBack reads a summary; the catalogue below lists every session as its own row.
            .semantics { contentDescription = "Shelf of the last four weeks, ${sessions.sumOf { it.size }} sessions. Each session is listed below." }
            .pointerInput(history) {
            detectTapGestures { p -> hits.firstOrNull { it.first.inflate(4.dp.toPx()).contains(p) }?.let { onOpen(it.second) } }
        },
    ) {
        hits.clear()
        val base = 128.dp.toPx()
        val gap = 3.dp.toPx()
        val groupGap = 17.dp.toPx()
        val slots = weeks.indices.map { i -> maxOf(goal, sessions[i].size) }
        val spineW = ((size.width - groupGap * 3 - slots.sumOf { (it - 1) } * gap) / slots.sum()).coerceAtMost(16.dp.toPx())
        var x = 0f
        weeks.forEachIndexed { i, monday ->
            val startX = x
            repeat(slots[i]) { s ->
                val w = sessions[i].getOrNull(s)
                val future = w == null && monday == thisMonday
                if (w != null && w.cardioOnly) {
                    // Cardio: a thin ice spine, height by minutes (an hour reaches about three quarters), never by kg.
                    val mins = w.exercises.sumOf { e -> e.sets.filter { it.done }.sumOf { it.durationSec ?: 0 } } / 60f
                    val h = (mins / 80f).coerceIn(0.2f, 1f) * 122.dp.toPx()
                    val alpha = if (w.id == dropId && t.value < 1f) t.value else 1f
                    drawRoundRect(Forge.Ice.copy(alpha = alpha), Offset(x + spineW * 0.33f, base - h), Size(spineW * 0.34f, h), CornerRadius(2.dp.toPx()))
                    hits += Rect(x, base - h - 10.dp.toPx(), x + spineW, base) to w.id
                } else if (w != null) {
                    val full = (Stats.volume(w) / maxVol * 122.dp.toPx()).toFloat().coerceAtLeast(12.dp.toPx())
                    val dropping = w.id == dropId && t.value < 1f
                    val stub = 24.dp.toPx()
                    // While joining: fall from above as a stub, then grow with overshoot to the full height.
                    val h = if (!dropping || reduce) full else stub + (full - stub) * app.forge.ui.Motion.Overshoot.transform(seg(0.5f, 0.78f))
                    val fall = if (!dropping || reduce) 0f else (1f - androidx.compose.animation.core.FastOutLinearInEasing.transform(seg(0.22f, 0.5f))) * 70.dp.toPx()
                    val alpha = if (dropping && reduce) t.value else if (dropping) seg(0f, 0.22f) else 1f
                    val key = data.templates.firstOrNull { it.id == w.templateId }?.colorKey ?: Library.colorKeyFor(w.name)
                    val color = (if (key != null) TemplateColors.of(key) else Forge.Surface2).copy(alpha = alpha)
                    drawSpine(finish, color, Offset(x, base - h - fall), Size(spineW, h)) // 13.2: only the finish changes
                    if (key == null) drawRoundRect(Forge.Border, Offset(x, base - h), Size(spineW, h), CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx()))
                    val dot = if (dropping && !reduce) app.forge.ui.Motion.Overshoot.transform(seg(0.85f, 1f)) else alpha
                    if ((prs[w.id] ?: 0) > 0 && dot > 0f) drawCircle(Forge.Lime, 3.dp.toPx() * dot, Offset(x + spineW / 2, base - h - 6.dp.toPx()))
                    hits += Rect(x, base - h - 10.dp.toPx(), x + spineW, base) to w.id
                } else if (Weeks.key(monday) in pausedWeeks) {
                    // A paused week: full dashed outlines in ice, so a holiday reads as rest, not a gap.
                    val h = 40.dp.toPx()
                    drawRoundRect(
                        Forge.Ice, Offset(x, base - h), Size(spineW, h), CornerRadius(2.dp.toPx()),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                    )
                } else if (!future) {
                    drawRoundRect(
                        Forge.Border, Offset(x, base - 8.dp.toPx()), Size(spineW, 8.dp.toPx()), CornerRadius(2.dp.toPx()),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                    )
                }
                x += spineW + gap
            }
            drawText(measurer, "WK ${Weeks.weekOfUse(history, monday)}", Offset(startX, base + 8.dp.toPx()), label)
            x += groupGap - gap
        }
        drawRect(Forge.Text.copy(alpha = 0.5f), Offset(0f, base), Size(size.width, 1.dp.toPx()))
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Forge.Lime))
        Text("Record session", style = Type.BodyS.copy(fontSize = 11.sp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(8.dp).border(1.dp, Forge.Muted))
        Text("Missed", style = Type.BodyS.copy(fontSize = 11.sp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(8.dp).border(1.dp, Forge.Ice))
        Text("Paused", style = Type.BodyS.copy(fontSize = 11.sp))
        if (sessions.flatten().any { it.cardioOnly }) {
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(3.dp, 10.dp).background(Forge.Ice))
            Text("Cardio", style = Type.BodyS.copy(fontSize = 11.sp))
        }
    }
}

/** 6.3: month grid. Filled = trained, lime = record day, outlined = today. Tap a day to list its sessions. */
@Composable
private fun CalendarTab(history: List<Workout>, data: AppData, prs: Map<Long, Int>, today: LocalDate, onOpen: (Long) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val byDay = remember(history) { history.groupBy { Weeks.date(it.startedAt) } }
    val inMonth = history.filter { YearMonth.from(Weeks.date(it.startedAt)) == month }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(month.format(monthFmt), style = Type.DisplayS, modifier = Modifier.weight(1f))
            IconTap(R.drawable.ic_chevron_left, Forge.Text, 20.dp, description = "Previous month") { month = month.minusMonths(1); selected = null }
            IconTap(R.drawable.ic_chevron_right, if (month < YearMonth.from(today)) Forge.Text else Forge.Faint, 20.dp, description = "Next month", enabled = month < YearMonth.from(today)) {
                if (month < YearMonth.from(today)) { month = month.plusMonths(1); selected = null }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            "MTWTFSS".forEach { Text(it.toString(), style = Type.Label, modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = lead + month.lengthOfMonth()
        (0 until (cells + 6) / 7).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (0 until 7).forEach { col ->
                    val n = row * 7 + col - lead + 1
                    if (n < 1 || n > month.lengthOfMonth()) Spacer(Modifier.size(44.dp))
                    else {
                        val d = month.atDay(n)
                        val ws = byDay[d].orEmpty()
                        val pr = ws.any { (prs[it.id] ?: 0) > 0 }
                        val isToday = d == today
                        val future = d.isAfter(today)
                        Column(
                            Modifier.size(44.dp).clip(RoundedCornerShape(Forge.RRow))
                                .then(when { pr -> Modifier.background(Forge.Lime); ws.isNotEmpty() -> Modifier.background(Forge.Surface2); else -> Modifier })
                                .then(if (isToday) Modifier.border(2.dp, Forge.Text, RoundedCornerShape(Forge.RRow)) else Modifier)
                                .then(if (d == selected) Modifier.border(1.dp, Forge.Lime, RoundedCornerShape(Forge.RRow)) else Modifier)
                                .tap(
                                    enabled = ws.isNotEmpty(), action = "Show sessions",
                                    label = "${d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US))}" + if (ws.isEmpty()) "" else ", ${ws.size} ${if (ws.size == 1) "session" else "sessions"}${if (pr) ", record" else ""}",
                                ) { selected = if (selected == d) null else d },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                "$n", style = Type.DataM.copy(fontSize = 13.sp, color = when { pr -> Forge.OnLime; future -> Forge.Muted.copy(alpha = 0.5f); else -> Forge.Text }),
                            )
                            if (ws.isNotEmpty() && !pr) { Spacer(Modifier.height(3.dp)); Box(Modifier.size(4.dp).clip(CircleShape).background(Forge.Lime)) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        val weeksInMonth = (0 until (cells + 6) / 7).map { first.minusDays(lead.toLong()).plusWeeks(it.toLong()) }.filter { !it.isAfter(today) }
        // Paused weeks are left out of "weeks on goal" altogether.
        val counted = weeksInMonth.filter { Weeks.key(it) !in data.streak.pausedWeeks }
        val onGoal = counted.count { m -> Weeks.sessionsIn(history, m).size >= data.settings.weeklyGoal }
        StatColumns(
            listOf("Sessions" to "${inMonth.size}", "Weeks on goal" to "$onGoal / ${counted.size}", "Records" to "${inMonth.sumOf { prs[it.id] ?: 0 }}"),
        )
        selected?.let { d ->
            Spacer(Modifier.height(24.dp))
            SectionHeader(d.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US)))
            byDay[d].orEmpty().forEach { w -> SessionRow(w, prs[w.id] ?: 0, data.settings.unit) { onOpen(w.id) } }
        }
    }
}

// ---- 6.2 Session detail ---------------------------------------------------------------------

@Composable
fun WorkoutDetail(id: Long, data: AppData, vm: AppViewModel, nav: Nav, onRepeat: (Workout) -> Unit) {
    val w = data.history.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { nav.pop() }; return }
    val unit = data.settings.unit
    val ctx = LocalContext.current
    val chrono = remember(data.history) { data.history.sortedBy { it.startedAt } }
    val number = chrono.indexOfFirst { it.id == id } + 1
    val prs = remember(w, chrono) { Stats.sessionPrs(w, chrono.filter { it.startedAt < w.startedAt }).map { it.exerciseId }.toSet() }
    var menu by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_back, { nav.pop() }) {
            IconTap(R.drawable.ic_share, Forge.Text, 20.dp, description = "Share session") {
                val text = "${w.name} · ${Stats.durationMin(w)} min · ${Units.grouped(Stats.volume(w), unit)} ${unit.label}\n" +
                    w.exercises.joinToString("\n") { e -> "${vm.exercise(e.exerciseId).name}: " + e.sets.filter { it.done }.joinToString(" · ") { "${Units.w(it.weightKg ?: 0.0, unit)} × ${it.reps}" } } +
                    "\n\nLogged with ${app.forge.Brand.FULL}"
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share session"))
            }
            IconTap(R.drawable.ic_more, Forge.Text, 20.dp, description = "Session options") { menu = true }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val d = java.time.Instant.ofEpochMilli(w.startedAt).atZone(Weeks.zone)
                Text("No. $number · ${d.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.US))}".uppercase(), style = Type.Label)
                Text(w.name, style = Type.DisplayL)
            }
            StatColumns(
                listOf("Time" to clock(Stats.durationMs(w)), "Lifted" to Units.grouped(Stats.volume(w), unit), "Sets" to "${Stats.doneSets(w)}", "Records" to "${prs.size}"),
                highlight = if (prs.isNotEmpty()) 3 else -1,
            )
            Column {
                SectionHeader("Exercises", "${w.exercises.size}")
                w.exercises.forEachIndexed { i, e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(24.dp).border(1.dp, Forge.Border, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", style = Type.DataS.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(vm.exercise(e.exerciseId).name, style = Type.TitleM.copy(fontSize = 15.sp), modifier = Modifier.weight(1f, fill = false))
                                if (e.exerciseId in prs) Tag("PR")
                            }
                            Text(
                                e.sets.filter { it.done }.joinToString(" · ") { s ->
                                    (if (s.type == SetType.WARMUP) "W " else "") + Measure.format(e, s, unit)
                                },
                                style = Type.DataS,
                            )
                        }
                    }
                    Hairline()
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Note", style = Type.SectionLabel)
                if (w.note.isNotBlank()) Text("“${w.note}”", style = Type.BodyL.copy(fontSize = 15.sp), modifier = Modifier.tap { editingNote = true })
                else Text("Add a note", style = Type.BodyM.copy(color = Forge.Lime), modifier = Modifier.tap { editingNote = true })
            }
        }
        Hairline()
        Column(Modifier.padding(horizontal = Forge.Gutter).padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PrimaryButton("Repeat this workout", Modifier.fillMaxWidth()) { onRepeat(w) }
            val saved = w.templateId != null && data.templates.any { it.id == w.templateId }
            if (!saved) GhostButton("Save as template", Modifier.fillMaxWidth()) { vm.saveAsTemplate(w) }
        }
    }

    if (menu) BottomSheet({ menu = false }) {
        SheetHeader(w.name)
        SecondaryButton(if (w.note.isBlank()) "Add note" else "Edit note", Modifier.fillMaxWidth()) { menu = false; editingNote = true }
        SecondaryButton("Delete session", Modifier.fillMaxWidth(), color = Forge.Red) { menu = false; confirmDelete = true }
    }
    if (editingNote) {
        var text by remember { mutableStateOf(w.note) }
        BottomSheet({ editingNote = false }) {
            SheetHeader("Note")
            ForgeTextField(text, { text = it.take(280) }, "Grip went on the last set…", Modifier.fillMaxWidth(), style = Type.BodyL)
            PrimaryButton("Save", Modifier.fillMaxWidth()) { vm.setNote(w.id, text); editingNote = false }
        }
    }
    if (confirmDelete) ConfirmSheet("Delete this session?", "It will be removed from History and Metrics.", "Delete", destructive = true,
        onDismiss = { confirmDelete = false }) { confirmDelete = false; vm.deleteWorkout(w.id); nav.pop() }
}
