package app.forge.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forge.AppViewModel
import app.forge.HealthState
import app.forge.R
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Context layer · Inputs (Figma 5100): I.1 connect Health Connect, I.2 the "what happened" note, I.3 confirm
 * the parse, I.4 the readiness check-in. Nothing is saved until Confirm (note) or a tap (check-in).
 */

private enum class NoteStep { CONNECT, NOTE, CONFIRM }

/** The note flow, from Home: connect (unless connected or skipped before), note, confirm. */
@Composable
fun ContextFlow(data: AppData, vm: AppViewModel, nav: Nav) {
    var step by remember { mutableStateOf<NoteStep?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var parsed by remember { mutableStateOf<Notes.Parsed?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val health by vm.health.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        val ready = health is HealthState.Ready
        val granted = runCatching { vm.healthReader.granted() }.getOrDefault(emptySet())
        step = if (ready || granted.isNotEmpty() || data.settings.healthSkipped) NoteStep.NOTE else NoteStep.CONNECT
    }
    LaunchedEffect(step) { if (step == NoteStep.NOTE) vm.refreshHealth() }

    when (step) {
        null -> {}
        NoteStep.CONNECT -> ConnectHealthScreen(vm, onClose = { nav.pop() }, onDone = { step = NoteStep.NOTE }, onSkip = { vm.skipHealth(); step = NoteStep.NOTE })
        NoteStep.NOTE -> NoteScreen(text, { text = it }, health, vm, onClose = { nav.pop() }, onConnect = { step = NoteStep.CONNECT }) { p, why ->
            parsed = p; problem = why; step = NoteStep.CONFIRM
        }
        NoteStep.CONFIRM -> ConfirmNoteScreen(parsed!!, problem, onBack = { step = NoteStep.NOTE }) { activities ->
            vm.confirmNote(text, activities, parsed!!.fromAi)
            nav.pop()
        }
    }
}

// ---- I.1 Connect Health Connect --------------------------------------------------------------------

@Composable
private fun ConnectHealthScreen(vm: AppViewModel, onClose: () -> Unit, onDone: () -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val status = remember { vm.healthReader.status() }
    // Whatever is granted (even nothing), carry on: the note screen says plainly what the watch could give.
    val ask = rememberLauncherForActivityResult(app.forge.HealthConnectReader.permissionContract()) { onDone() }

    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_close, onClose)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("NEW · CONTEXT", style = Type.Label)
                Text("Press only sees the gym.", style = Type.DisplayL)
                Text(
                    "Your watch already knows about the walks, runs, rides and hikes. Let Press read them, so an active week away from the gym doesn’t look like a week off.",
                    style = Type.BodyL.copy(color = Forge.Muted),
                )
            }
            HealthReadList()
            when (status) {
                HealthConnectClient.SDK_UNAVAILABLE -> Notice("Health Connect isn’t available on this phone. You can still tell Press what happened with a note.")
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Notice("Health Connect needs installing or updating first. Or skip it and use a note.")
            }
        }
        Column(Modifier.padding(horizontal = Forge.Gutter).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (status) {
                HealthConnectClient.SDK_AVAILABLE -> PrimaryButton("Connect Health Connect", Modifier.fillMaxWidth()) { ask.launch(vm.healthReader.permissions) }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> PrimaryButton("Get Health Connect", Modifier.fillMaxWidth()) {
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")))
                    }
                }
            }
            GhostButton("Skip, use a note only", Modifier.fillMaxWidth(), onClick = onSkip)
        }
    }
}

/** What Press reads, and the promise about it. Also the body of the Health Connect rationale screen. */
@Composable
fun HealthReadList() {
    Column {
        SectionHeader("Press will read", "Last ${LifeContext.DAYS} days")
        listOf(
            Triple(R.drawable.ic_compass, "Exercise sessions", "Walks, runs, rides, hikes, sport"),
            Triple(R.drawable.ic_steps, "Steps and distance", "Big walking days"),
            Triple(R.drawable.ic_mountain, "Elevation gained", "Climbing load"),
            Triple(R.drawable.ic_moon, "Sleep (if recorded)", "Optional; your check-in covers it"),
        ).forEach { (icon, title, caption) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ForgeIcon(icon, 18.dp, Forge.Text)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = Type.TitleM.copy(fontSize = 15.sp))
                    Text(caption, style = Type.BodyS)
                }
            }
            Hairline()
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ForgeIcon(R.drawable.ic_lock, 14.dp, Forge.Muted)
            Text("Read-only. Raw records never leave your phone; only short summaries are used.", style = Type.BodyS)
        }
    }
}

// ---- I.2 "What happened" note -------------------------------------------------------------------------

private val examples = listOf("Ill for 3 days", "Moved house", "Football on Sunday")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteScreen(
    text: String, onText: (String) -> Unit, health: HealthState, vm: AppViewModel,
    onClose: () -> Unit, onConnect: () -> Unit, onParsed: (Notes.Parsed, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_close, onClose, title = "Context")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("What happened?", style = Type.DisplayL)
            WatchCard(health, vm, onConnect)
            NoteField(text, onText)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                examples.forEach { e -> Chip(e, on = false) { onText(e) } }
            }
        }
        Hairline()
        PrimaryButton(
            if (reading) "Reading…" else "Read my note",
            Modifier.fillMaxWidth().padding(horizontal = Forge.Gutter, vertical = 12.dp), enabled = text.isNotBlank() && !reading,
        ) {
            reading = true
            scope.launch {
                val (parsed, why) = vm.parseNote(text.trim())
                reading = false
                onParsed(parsed, why)
            }
        }
    }
}

/** Why there's no Health Connect data to show, in plain words; null once a read is ready. */
fun healthProblem(health: HealthState): String? = when (health) {
    HealthState.Idle, HealthState.Loading -> "Reading Health Connect…"
    is HealthState.Unavailable -> if (health.updateRequired) "Health Connect needs installing or updating." else "Health Connect isn’t available on this phone."
    HealthState.Denied -> "Press can’t read Health Connect: permission wasn’t given."
    HealthState.NoData -> "Health Connect has nothing from the last ${LifeContext.DAYS} days."
    is HealthState.Failed -> "Health Connect couldn’t be read (${health.message})."
    is HealthState.Ready -> null
}

/** What the watch found, first, so the note only fills the gaps. Every unhappy path says so plainly. */
@Composable
private fun WatchCard(health: HealthState, vm: AppViewModel, onConnect: () -> Unit) {
    val outings = remember(health) { vm.outings() }
    // Only outings of an hour or more: the short walks to the shop aren't what the note is about.
    val long = outings.filter { it.minutes >= LifeContext.OTHER_MIN_MINUTES }
    val shown = long.groupBy { it.date }.map { (_, v) -> v.maxBy { it.minutes } }.sortedByDescending { it.minutes }.take(2).sortedBy { it.date }
    val days = long.map { it.date }.distinct()

    Column(Modifier.fillMaxWidth().panel(radius = Forge.RCard).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ForgeIcon(R.drawable.ic_heart, 14.dp, Forge.Ice)
            val head = if (days.isEmpty()) "From your watch" else "From your watch · ${LifeContext.range(days.min(), days.max())}"
            Text(head.uppercase() + if ((health as? HealthState.Ready)?.demo == true) " · SNAPSHOT" else "", style = Type.Label.copy(color = Forge.Ice))
        }
        val message = healthProblem(health)?.let { "$it The note works on its own." }
            ?: if (health is HealthState.Ready && shown.isEmpty()) "Nothing over an hour on your watch in the last ${LifeContext.DAYS} days." else null
        if (message != null) Text(message, style = Type.BodyS)
        shown.forEach { o ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(LifeContext.day(o.date), style = Type.DataS, modifier = Modifier.width(44.dp))
                Text(LifeContext.describe(o), style = Type.BodyM.copy(fontSize = 13.sp))
            }
        }
        if (days.size > shown.size) Text("+ ${days.size - shown.size} more ${if (days.size - shown.size == 1) "day" else "days"}", style = Type.BodyS)
        if (health == HealthState.Denied) Text("CONNECT", style = Type.Label.copy(color = Forge.Lime), modifier = Modifier.tap(onClick = onConnect))
    }
}

@Composable
private fun NoteField(value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value, onValueChange = { onChange(it.replace('\n', ' ').take(Notes.MAX_CHARS)) },
        textStyle = Type.BodyL, cursorBrush = SolidColor(Forge.Lime),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Column(
                Modifier.fillMaxWidth().panel(bg = Forge.Surface, border = if (focused || value.isNotEmpty()) Forge.Lime else Forge.Border, radius = Forge.RCard)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    if (value.isEmpty()) Text("Hiking in Scotland, 4 days, heavy pack", style = Type.BodyL.copy(color = Forge.Faint))
                    inner()
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("One line is enough", style = Type.BodyS, modifier = Modifier.weight(1f))
                    Text("${value.length} / ${Notes.MAX_CHARS}", style = Type.DataS)
                }
            }
        },
    )
}

// ---- I.3 Confirm the parse ------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmNoteScreen(parsed: Notes.Parsed, problem: String?, onBack: () -> Unit, onConfirm: (List<NoteActivity>) -> Unit) {
    var activities by remember(parsed) { mutableStateOf(parsed.activities) }
    // The model's question stays until it's answered; "Yes, add them" adds its muscles to the first activity.
    var asking by remember(parsed) { mutableStateOf(parsed.question != null) }
    // Without a parse, open straight into editing: the chips are yours to fill in.
    var editing by remember(parsed) { mutableStateOf(if (parsed.fromAi) -1 else 0) }

    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_back, onBack, title = "Check this")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(if (parsed.fromAi) "Here’s what I understood." else "Fill in what happened.", style = Type.DisplayM)
            if (!parsed.fromAi) AmberCard("Not read", "Press couldn’t read the note${problem?.let { " ($it)" } ?: ""}. Set the chips yourself, then confirm.")
            activities.forEachIndexed { i, a ->
                ActivityCard(
                    i, a, editing = editing == i, canRemove = activities.size > 1,
                    onEdit = { editing = if (editing == i) -1 else i },
                    onChange = { new -> activities = activities.toMutableList().also { it[i] = new } },
                    onRemove = { activities = activities.filterIndexed { j, _ -> j != i }; editing = -1 },
                )
            }
            if (asking && parsed.question != null) {
                AmberCard("Not sure · ${((parsed.confidence ?: 0.0) * 100).roundToInt()}%", parsed.question) {
                    if (parsed.questionMuscles.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Yes, add them", on = false) {
                            activities = activities.mapIndexed { j, a -> if (j == 0) a.copy(muscles = (a.muscles + parsed.questionMuscles).distinct()) else a }
                            asking = false
                        }
                        Chip("No", on = false) { asking = false }
                    } else Chip("Got it", on = false) { asking = false }
                }
            } else if (parsed.unsure) {
                AmberCard("Not sure · ${((parsed.confidence ?: 0.0) * 100).roundToInt()}%", "Check the chips before you confirm.")
            }
        }
        Hairline()
        Column(Modifier.padding(horizontal = Forge.Gutter).padding(top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PrimaryButton("Confirm", Modifier.fillMaxWidth(), enabled = activities.isNotEmpty() && activities.all { it.type.isNotBlank() }) { onConfirm(activities) }
            GhostButton("Start again", Modifier.fillMaxWidth(), onClick = onBack)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityCard(
    index: Int, a: NoteActivity, editing: Boolean, canRemove: Boolean,
    onEdit: () -> Unit, onChange: (NoteActivity) -> Unit, onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().panel(radius = Forge.RCard).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ACTIVITY ${index + 1}", style = Type.Label, modifier = Modifier.weight(1f))
            Row(Modifier.tap(onClick = onEdit), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!editing) ForgeIcon(R.drawable.ic_edit, 12.dp, Forge.Lime)
                Text(if (editing) "Done" else "Edit", style = Type.BodyM.copy(fontSize = 13.sp, color = Forge.Lime))
            }
        }
        if (!editing) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(a.type.ifBlank { "?" }, on = true, onClick = onEdit)
                Chip("${a.days} ${if (a.days == 1) "day" else "days"}", on = true, onClick = onEdit)
                Chip("${a.intensity.label} intensity", on = true, onClick = onEdit)
            }
            Text("MUSCLES LOADED", style = Type.Label)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                a.muscles.forEach { Chip(it, on = true, onClick = onEdit) }
                Text(
                    "+ ADD", style = Type.DataM.copy(fontSize = 12.sp, color = Forge.Muted),
                    modifier = Modifier.dashedBorder(Forge.Border, Forge.RRow).tap(onClick = onEdit).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            if (a.detail.isNotBlank()) Text("“${a.detail}” → ${a.intensity.label.lowercase()} intensity", style = Type.BodyS)
        } else {
            ForgeTextField(a.type, { onChange(a.copy(type = it.take(40))) }, placeholder = "Hiking")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("−", on = false, enabled = a.days > 1) { onChange(a.copy(days = a.days - 1)) }
                Text("${a.days} ${if (a.days == 1) "day" else "days"}", style = Type.DataM, modifier = Modifier.widthIn(min = 64.dp))
                Chip("+", on = false, enabled = a.days < LifeContext.DAYS) { onChange(a.copy(days = a.days + 1)) }
            }
            SegmentedToggle(Intensity.entries.map { it.label }, a.intensity.ordinal) { onChange(a.copy(intensity = Intensity.entries[it])) }
            Text("MUSCLES LOADED", style = Type.Label)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Library.strengthMuscles.forEach { m ->
                    Chip(m, on = m in a.muscles) { onChange(a.copy(muscles = if (m in a.muscles) a.muscles - m else a.muscles + m)) }
                }
            }
            if (canRemove) GhostButton("Remove activity", Modifier.fillMaxWidth(), color = Forge.Muted, onClick = onRemove)
        }
    }
}

/** Amber: low confidence, or a note Press couldn't read. */
@Composable
private fun AmberCard(label: String, body: String, actions: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().panel(bg = Forge.AmberBg, border = Forge.Amber.copy(alpha = 0.6f), radius = Forge.RCard).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ForgeIcon(R.drawable.ic_alert, 14.dp, Forge.Amber)
            Text(label.uppercase(), style = Type.Label.copy(color = Forge.Amber))
        }
        Text(body, style = Type.TitleM.copy(fontSize = 15.sp))
        actions()
    }
}

@Composable
private fun Notice(text: String) {
    Row(
        Modifier.fillMaxWidth().panel(bg = Forge.AmberBg, border = Forge.Amber.copy(alpha = 0.6f), radius = Forge.RCard).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeIcon(R.drawable.ic_alert, 16.dp, Forge.Amber)
        Text(text, style = Type.BodyS.copy(color = Forge.Text))
    }
}

// ---- I.4 Readiness check-in ---------------------------------------------------------------------------

/** "Before you start": three taps, or Skip. Dismissing the sheet counts as a skip, so it isn't asked again. */
@Composable
fun CheckInSheet(onAnswer: (SleepAnswer?, Soreness?, Energy?) -> Unit) {
    var sleep by remember { mutableStateOf<SleepAnswer?>(null) }
    var soreness by remember { mutableStateOf<Soreness?>(null) }
    var energy by remember { mutableStateOf<Energy?>(null) }
    BottomSheet(onDismiss = { onAnswer(null, null, null) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Before you start", style = Type.DisplayM)
            Text("Three taps. Your watch can’t tell how you feel.", style = Type.BodyMuted)
        }
        // Selected answers take their meaning's colour: red = bad, amber = middling, green = good (soreness runs backwards).
        CheckInRow("Sleep last night", SleepAnswer.entries, sleep, { it.label }, { tone(it.ordinal) }) { sleep = it }
        CheckInRow("Soreness", Soreness.entries, soreness, { it.label }, { tone(2 - it.ordinal) }) { soreness = it }
        CheckInRow("Energy", Energy.entries, energy, { it.label }, { tone(it.ordinal) }) { energy = it }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PrimaryButton("See today’s plan", Modifier.fillMaxWidth(), enabled = sleep != null && soreness != null && energy != null) {
                onAnswer(sleep, soreness, energy)
            }
            GhostButton("Skip", Modifier.fillMaxWidth()) { onAnswer(null, null, null) }
        }
    }
}

/** 0 = bad (red), 1 = middling (amber), 2 = good (green). */
private fun tone(goodness: Int): Color = when (goodness) { 0 -> Forge.Red; 1 -> Forge.Amber; else -> Forge.Green }

@Composable
private fun <T> CheckInRow(title: String, options: List<T>, selected: T?, label: (T) -> String, color: (T) -> Color, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = Type.TitleM)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> Chip(label(o), on = o == selected, modifier = Modifier.weight(1f), onColor = color(o)) { onPick(o) } }
        }
    }
}

// ---- Home entry ---------------------------------------------------------------------------------------

/** A type's own icon where there is one (hike, walk), else the neutral compass. Mixed types get the compass too. */
fun outsideIcon(types: Collection<OutsideType>): Int = when (types.distinct().singleOrNull()) {
    OutsideType.HIKING -> R.drawable.ic_mountain
    OutsideType.WALKING -> R.drawable.ic_steps
    else -> R.drawable.ic_compass
}

/**
 * On Home: the way into the note. Shows the latest confirmed note from the last two weeks, else what the watch counted
 * in that time by session type ("2 walks · 1 hike"), else the invitation.
 */
@Composable
fun OutsideCard(data: AppData, onOpen: () -> Unit) {
    val today = Weeks.today()
    val recent = data.contextNotes.lastOrNull { Weeks.date(it.confirmedAt).isAfter(today.minusDays(LifeContext.DAYS)) }
    val watched = remember(data.outsideDays, today) {
        Outside.between(data.outsideDays, today.minusDays(LifeContext.DAYS - 1), today).flatMap { it.outings }.filter(Outside::counts).map { it.type }
    }
    val types = recent?.activities?.mapNotNull { LifeContext.typeFromNote(it.type) } ?: watched
    Row(
        Modifier.fillMaxWidth().panel(radius = Forge.RCard).tap(action = "Tell Press what happened", onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ForgeIcon(outsideIcon(types), 20.dp, Forge.Ice)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Outside the gym", style = Type.TitleM.copy(fontSize = 14.sp))
            Text(
                recent?.activities?.joinToString(" · ") { "${it.type} · ${it.days} ${if (it.days == 1) "day" else "days"}" }?.let { "$it. Confirmed." }
                    ?: watched.takeIf { it.isNotEmpty() }?.let { "${LifeContext.kinds(it)} on your watch. Tell Press what happened." }
                    ?: "Walks, runs, rides, sport or time off. Tell Press what happened.",
                style = Type.BodyS,
            )
        }
        ForgeIcon(R.drawable.ic_chevron_right, 16.dp, Forge.Muted)
    }
}
