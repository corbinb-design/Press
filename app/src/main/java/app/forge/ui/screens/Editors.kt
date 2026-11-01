package app.forge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.forge.AppViewModel
import app.forge.R
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.Overlay
import app.forge.ui.components.*
import app.forge.ui.restLabel
import app.forge.ui.theme.Forge
import app.forge.ui.theme.TemplateColors
import app.forge.ui.theme.Type

/** "6–8", or "5" when the range is a single number. */
fun rangeText(min: Int, max: Int) = if (min == max) "$min" else "$min–$max"

/** "4 × 6–8 · 2:30 rest" */
fun prescription(te: TemplateExercise, defaultRest: Int, tracking: Tracking = Tracking.WEIGHT_REPS) = when (tracking) {
    Tracking.CARDIO -> "Cardio"
    Tracking.DURATION -> "${te.sets} timed ${if (te.sets == 1) "set" else "sets"} · ${restLabel(te.restSec ?: defaultRest)} rest"
    Tracking.DISTANCE_WEIGHT -> "${te.sets} ${if (te.sets == 1) "carry" else "carries"} · ${restLabel(te.restSec ?: defaultRest)} rest"
    else -> "${te.sets} × ${rangeText(te.repMin, te.repMax)} · ${restLabel(te.restSec ?: defaultRest)} rest"
}

// ---- 3.1 Quick build ------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickBuildScreen(data: AppData, vm: AppViewModel, nav: Nav) {
    var name by remember { mutableStateOf("") }
    var focus by remember { mutableStateOf(QuickBuild.Focus.UPPER) }
    var goal by remember { mutableStateOf(QuickBuild.Goal.MUSCLE) }
    var kit by remember { mutableStateOf(QuickBuild.Kit.GYM) }
    var minutes by remember { mutableIntStateOf(60) }

    Column(Modifier.fillMaxSize()) {
        TextTopBar("New template", "Cancel", { nav.pop() }, right = "Blank") { nav.replaceTop(Overlay.EditTemplate(null)) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Field("Name") {
                ForgeTextField(
                    name, { name = it.take(30) }, "e.g. ${focus.label} A", Modifier.fillMaxWidth(),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
            Field("Focus") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickBuild.Focus.entries.forEach { f -> Chip(f.label, f == focus) { focus = f } }
                }
            }
            Field("Goal", trailing = "Sets rep ranges") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickBuild.Goal.entries.forEach { g -> Chip("${g.label} · ${g.range}", g == goal) { goal = g } }
                }
            }
            Field("Equipment") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickBuild.Kit.entries.forEach { k -> Chip(k.label, k == kit) { kit = k } }
                }
            }
            Field("Time") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickBuild.minutes.forEach { m -> Chip("$m min", m == minutes) { minutes = m } }
                }
            }
        }
        Hairline()
        Column(Modifier.padding(horizontal = Forge.Gutter).padding(top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PrimaryButton("Build template", Modifier.fillMaxWidth()) {
                val t = QuickBuild.build(name, focus, goal, kit, minutes, vm.allExercises)
                nav.replaceTop(Overlay.EditTemplate(t, drafted = true))
            }
            GhostButton("Start blank instead", Modifier.fillMaxWidth()) { nav.replaceTop(Overlay.EditTemplate(null)) }
        }
    }
}

@Composable
private fun Field(label: String, trailing: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabelRow(label, trailing = trailing)
        content()
    }
}

// ---- 3.2 Template editor --------------------------------------------------------------------

@Composable
fun TemplateEditor(template: Template?, drafted: Boolean, data: AppData, vm: AppViewModel, nav: Nav) {
    val isNew = template == null || data.templates.none { it.id == template.id }
    var name by remember { mutableStateOf(template?.name ?: "") }
    // null = follow the name ("Legs B" turns green) until a colour is picked.
    var colorKey by remember { mutableStateOf(template?.colorKey) }
    // Each row gets a stable key so a dragged row keeps its gesture while it moves.
    val rows = remember { mutableStateListOf<EditRow>().apply { addAll(template?.exercises.orEmpty().map { EditRow(newId(), it) }) } }
    var expanded by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf(name.isBlank()) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val defaultRest = data.settings.restSeconds

    // Drag to reorder: the grip moves its row; crossing half a row swaps with the neighbour.
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(1f) }

    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_back, { nav.pop() }, title = if (isNew) "New template" else "Edit template") {
            if (!isNew) IconTap(R.drawable.ic_more, Forge.Text, 22.dp, description = "Template options") { menu = true }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState(), enabled = dragging == null)
                .padding(horizontal = Forge.Gutter).padding(top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    name.ifBlank { "Untitled" }, style = Type.DisplayM.copy(color = if (name.isBlank()) Forge.Faint else Forge.Text),
                    modifier = Modifier.tap(action = "Rename template") { renaming = true },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetaPill("${rows.size} ${if (rows.size == 1) "exercise" else "exercises"}")
                    MetaPill("~${estimateMinutes(rows.map { it.te }, defaultRest)} min")
                    rows.groupingBy { rangeText(it.te.repMin, it.te.repMax) }.eachCount().maxByOrNull { it.value }?.let { MetaPill("${it.key} reps") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("COLOUR", style = Type.Label, modifier = Modifier.width(72.dp))
                ColorPicker(colorKey ?: Library.colorKeyFor(name)) { colorKey = it }
            }
            if (drafted) Row(
                Modifier.fillMaxWidth().panel(bg = Forge.Surface, border = Forge.Lime, radius = Forge.RButton).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ForgeIcon(R.drawable.ic_sparkle, 16.dp, Forge.Lime)
                Text("Drafted from your choices. Drag to reorder, tap ↻ to swap an exercise.", style = Type.BodyS.copy(color = Forge.Text))
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEachIndexed { i, row ->
                    val te = row.te
                    key(row.key) {
                        val isDragging = dragging == i
                        ExerciseItem(
                            te, vm.exercise(te.exerciseId).name, defaultRest, expanded = expanded == i, tracking = vm.exercise(te.exerciseId).tracking,
                            modifier = Modifier
                                .onSizeChanged { if (dragging == null) rowHeight = it.height.toFloat() }
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragging) dragOffset else 0f },
                            // TalkBack can't drag: the handle offers Move up / Move down instead.
                            gripModifier = Modifier.semantics {
                                contentDescription = "Reorder ${vm.exercise(te.exerciseId).name}"
                                customActions = listOfNotNull(
                                    CustomAccessibilityAction("Move up") { if (i > 0) { rows.add(i - 1, rows.removeAt(i)); true } else false }.takeIf { i > 0 },
                                    CustomAccessibilityAction("Move down") { if (i < rows.lastIndex) { rows.add(i + 1, rows.removeAt(i)); true } else false }.takeIf { i < rows.lastIndex },
                                )
                            }.pointerInput(rows.size) {
                                detectDragGestures(
                                    onDragStart = { dragging = rows.indexOfFirst { it.key == row.key }; dragOffset = 0f; expanded = null },
                                    onDragEnd = { dragging = null; dragOffset = 0f },
                                    onDragCancel = { dragging = null; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    val from = dragging ?: return@detectDragGestures
                                    dragOffset += amount.y
                                    val step = rowHeight + 8.dp.toPx()
                                    val to = when {
                                        dragOffset > step / 2 && from < rows.lastIndex -> from + 1
                                        dragOffset < -step / 2 && from > 0 -> from - 1
                                        else -> from
                                    }
                                    if (to != from) {
                                        rows.add(to, rows.removeAt(from))
                                        dragging = to
                                        dragOffset -= step * (to - from)
                                    }
                                }
                            },
                            onToggle = { expanded = if (expanded == i) null else i },
                            onSwap = {
                                val muscle = vm.exercise(te.exerciseId).muscle
                                nav.push(Overlay.Picker(single = true, muscle = muscle) { ids -> ids.firstOrNull()?.let { rows[i] = row.copy(te = te.copy(exerciseId = it)) } })
                            },
                            onChange = { rows[i] = row.copy(te = it) },
                            onRules = {
                                nav.push(Overlay.Rules(te, name) { r ->
                                    val at = rows.indexOfFirst { it.key == row.key }
                                    if (at >= 0) rows[at] = rows[at].copy(te = r)
                                })
                            },
                            unit = data.settings.unit,
                            onRemove = { rows.removeAt(i); expanded = null },
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().dashedBorder(Forge.Lime, Forge.RButton).clip(RoundedCornerShape(Forge.RButton))
                    .tap { nav.push(Overlay.Picker { ids -> rows.addAll(ids.map { EditRow(newId(), TemplateExercise.fresh(it)) }) }) }.padding(14.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(R.drawable.ic_plus, 16.dp, Forge.Lime)
                Spacer(Modifier.width(8.dp))
                Text("ADD EXERCISE", style = Type.DataM.copy(fontSize = 12.sp, color = Forge.Lime))
            }
        }
        Hairline()
        PrimaryButton("Save template", Modifier.fillMaxWidth().padding(horizontal = Forge.Gutter, vertical = 12.dp), enabled = name.isNotBlank() && rows.isNotEmpty()) {
            vm.saveTemplate(Template(template?.id ?: newId(), name.trim(), rows.map { it.te }, colorKey ?: Library.colorKeyFor(name)))
            nav.pop()
        }
    }

    if (renaming) {
        var draft by remember { mutableStateOf(name) }
        BottomSheet({ renaming = false }) {
            SheetHeader("Template name")
            ForgeTextField(
                draft, { draft = it.take(30) }, "e.g. Upper A", Modifier.fillMaxWidth(),
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            )
            PrimaryButton("Done", Modifier.fillMaxWidth(), enabled = draft.isNotBlank()) { name = draft.trim(); renaming = false }
        }
    }
    if (menu && template != null) BottomSheet({ menu = false }) {
        SheetHeader(template.name)
        // F: shares the saved template.
        SecondaryButton("Share template", Modifier.fillMaxWidth(), icon = R.drawable.ic_share) { menu = false; sharing = true }
        SecondaryButton("Duplicate", Modifier.fillMaxWidth()) { vm.duplicateTemplate(template); menu = false; nav.pop() }
        SecondaryButton("Delete template", Modifier.fillMaxWidth(), color = Forge.Red) { menu = false; confirmDelete = true }
    }
    if (sharing && template != null) data.templates.firstOrNull { it.id == template.id }?.let { ShareTemplateSheet(it, data, vm) { sharing = false } }
    if (confirmDelete && template != null) ConfirmSheet("Delete “${template.name}”?", "Logged workouts stay in History.", "Delete", destructive = true,
        onDismiss = { confirmDelete = false }) { vm.deleteTemplate(template.id); nav.pop() }
}

private data class EditRow(val key: Long, val te: TemplateExercise)

/** Rough session length: ~45 s per set plus the rest after it, and ~2 min to set up each exercise. */
private fun estimateMinutes(rows: List<TemplateExercise>, defaultRest: Int): Int {
    val sec = rows.sumOf { it.sets * (45 + (it.restSec ?: defaultRest)) + 120 }
    return ((sec / 60 + 2) / 5 * 5).coerceAtLeast(5)
}

@Composable
private fun ExerciseItem(
    te: TemplateExercise, name: String, defaultRest: Int, expanded: Boolean, modifier: Modifier, gripModifier: Modifier,
    onToggle: () -> Unit, onSwap: () -> Unit, onChange: (TemplateExercise) -> Unit, onRules: () -> Unit, unit: WeightUnit, onRemove: () -> Unit,
    tracking: Tracking = Tracking.WEIGHT_REPS,
) {
    Column(modifier.fillMaxWidth().panel(bg = Forge.Surface, border = if (expanded) Forge.Lime else Forge.Border, radius = Forge.RButton)) {
        Row(
            Modifier.fillMaxWidth().tap(action = if (expanded) "Collapse" else "Edit sets, reps and rest", onClick = onToggle).padding(start = 8.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(gripModifier.padding(4.dp)) { ForgeIcon(R.drawable.ic_grip, 18.dp, Forge.Muted) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = Type.TitleM.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(prescription(te, defaultRest, tracking), style = Type.DataS)
            }
            IconTap(R.drawable.ic_refresh, Forge.Muted, 18.dp, description = "Swap $name", onClick = onSwap)
        }
        if (expanded) {
            Hairline()
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Cardio is one entry; timed sets and carries have no rep range.
                if (tracking != Tracking.CARDIO) StepRow("Sets", te.sets.toString(), { onChange(te.copy(sets = (te.sets - 1).coerceAtLeast(1))) }) { onChange(te.copy(sets = (te.sets + 1).coerceAtMost(10))) }
                if (tracking.usesReps) {
                StepRow("Reps from", te.repMin.toString(), { onChange(te.copy(repMin = (te.repMin - 1).coerceAtLeast(1), reps = (te.repMin - 1).coerceAtLeast(1))) }) {
                    val v = (te.repMin + 1).coerceAtMost(50); onChange(te.copy(repMin = v, reps = v, repMax = maxOf(te.repMax, v)))
                }
                StepRow("Reps to", te.repMax.toString(), {
                    val v = (te.repMax - 1).coerceAtLeast(1); onChange(te.copy(repMax = v, repMin = minOf(te.repMin, v), reps = minOf(te.repMin, v)))
                }) { onChange(te.copy(repMax = (te.repMax + 1).coerceAtMost(50))) }
                }
                val rest = te.restSec ?: defaultRest
                if (tracking != Tracking.CARDIO) StepRow("Rest", restLabel(rest), { onChange(te.copy(restSec = (rest - 15).coerceAtLeast(15))) }) { onChange(te.copy(restSec = (rest + 15).coerceAtMost(600))) }
                // 10.1 lives one tap away; the summary says what the rule does without opening it.
                if (tracking.usesReps) Row(Modifier.fillMaxWidth().tap(onClick = onRules).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Progression", style = Type.BodyM, modifier = Modifier.weight(1f))
                    Text(ruleSummary(te, unit), style = Type.DataS, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(6.dp))
                    ForgeIcon(R.drawable.ic_chevron_right, 16.dp, Forge.Muted)
                }
                Text("Remove from template", style = Type.BodyM.copy(color = Forge.Red), modifier = Modifier.tap(onClick = onRemove).padding(vertical = 4.dp))
            }
        }
    }
}

private fun ruleSummary(te: TemplateExercise, unit: WeightUnit) = when (te.method) {
    ProgressionMethod.MANUAL -> "MANUAL"
    ProgressionMethod.DOUBLE -> "DOUBLE · +${Units.w(Progression.incrementKg(te, unit), unit)} ${unit.label.uppercase()}"
    ProgressionMethod.LINEAR -> "LINEAR · +${Units.w(Progression.incrementKg(te, unit), unit)} ${unit.label.uppercase()}"
}

@Composable
private fun StepRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val what = label.lowercase()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.BodyM, modifier = Modifier.weight(1f))
        Box(Modifier.size(36.dp).panel(bg = Forge.Surface2, radius = Forge.RRow).tap(label = "Decrease $what, now $value", onClick = onMinus), contentAlignment = Alignment.Center) {
            ForgeIcon(R.drawable.ic_minus, 16.dp, Forge.Text)
        }
        Text(value, style = Type.DataM, modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box(Modifier.size(36.dp).panel(bg = Forge.Surface2, radius = Forge.RRow).tap(label = "Increase $what, now $value", onClick = onPlus), contentAlignment = Alignment.Center) {
            ForgeIcon(R.drawable.ic_plus, 16.dp, Forge.Text)
        }
    }
}

/** Template colour swatches; the picked one gets a bone ring. */
@Composable
private fun ColorPicker(selected: String?, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Library.templateColorKeys.forEach { key ->
            val on = key == selected
            Box(
                Modifier.size(32.dp).clip(CircleShape).border(2.dp, if (on) Forge.Text else Color.Transparent, CircleShape)
                    .padding(5.dp).clip(CircleShape).background(TemplateColors.of(key))
                    .tap(label = "${key.replaceFirstChar { it.uppercase() }} colour") { onPick(key) }.semantics { this.selected = on },
            )
        }
    }
}

// ---- 3.3 Exercise picker --------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExercisePicker(data: AppData, vm: AppViewModel, nav: Nav, single: Boolean, muscleFilter: String?, initialQuery: String = "", onPick: (List<String>) -> Unit) {
    var query by remember { mutableStateOf(initialQuery) }
    var muscle by remember { mutableStateOf(muscleFilter) }
    val chosen = remember { mutableStateListOf<String>() }
    var creating by remember { mutableStateOf(false) }
    val unit = data.settings.unit
    // Most recent use per exercise, and the top set from that session ("Last: 20kg × 15").
    val lastUsed = remember(data.history) {
        val m = mutableMapOf<String, Pair<Long, String>>()
        for (w in data.history.sortedByDescending { it.startedAt }) for (e in w.exercises) if (e.exerciseId !in m) {
            val top = Stats.topSet(e)
            m[e.exerciseId] = w.startedAt to (top?.let { "Last: " + Measure.format(e, it, unit) } ?: "")
        }
        m
    }
    val all = vm.allExercises
    val inMuscle = all.filter { muscle == null || it.muscle == muscle }
    // Typing searches names and aliases, forgiving typos (ranked best first); otherwise recent first.
    val list = if (query.isBlank()) inMuscle.sortedWith(compareByDescending<Exercise> { lastUsed[it.id]?.first ?: 0L }.thenBy { it.name })
    else ExerciseSearch.search(inMuscle, query)

    Column(Modifier.fillMaxSize()) {
        IconTopBar(R.drawable.ic_close, { nav.pop() }, title = if (single) "Swap exercise" else "Add exercises")
        Column(Modifier.padding(horizontal = Forge.Gutter).padding(top = 6.dp)) {
            ForgeTextField(query, { query = it }, "Search ${all.size} exercises", Modifier.fillMaxWidth(), style = Type.BodyL, leading = R.drawable.ic_search)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Forge.Gutter, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip("All", muscle == null) { muscle = null } }
            items(Library.muscles) { m -> Chip(m, muscle == m) { muscle = if (muscle == m) null else m } }
        }
        Text(
            if (query.isBlank()) "RECENT · YOUR NUMBERS COME WITH THEM" else "${list.size} RESULTS",
            style = Type.Label, modifier = Modifier.padding(horizontal = Forge.Gutter).padding(bottom = 4.dp),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = Forge.Gutter)) {
            items(list, key = { it.id }) { ex ->
                val on = ex.id in chosen
                Row(
                    Modifier.fillMaxWidth().tap(role = if (single) androidx.compose.ui.semantics.Role.Button else androidx.compose.ui.semantics.Role.Checkbox) {
                        if (single) { onPick(listOf(ex.id)); nav.pop() }
                        else if (on) chosen.remove(ex.id) else chosen.add(ex.id)
                    }.semantics { if (!single) stateDescription = if (on) "Selected" else "Not selected" }.padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ex.name, style = Type.TitleM.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // When the match was an alias ("RDL"), say so; otherwise last time, or muscle and kit.
                        val alias = query.trim().takeIf { it.isNotEmpty() && ExerciseSearch.score(ex.copy(aliases = emptyList()), it) == 0 }
                            ?.let { q -> ex.aliases.firstOrNull { ExerciseSearch.score(ex.copy(name = it, aliases = emptyList()), q) > 0 } }
                        Text(
                            lastUsed[ex.id]?.second?.takeIf { it.isNotEmpty() }
                                ?: (listOfNotNull(ex.muscle, ex.equipment.label.lowercase()) + listOfNotNull(alias?.let { "“$it”" })).joinToString(" · "),
                            style = Type.DataS, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    trackingTag(ex.tracking)?.let { Tag(it, if (ex.tracking == Tracking.CARDIO) Forge.Ice else Forge.Muted) }
                    if (!single) Box(
                        Modifier.size(26.dp).then(
                            if (on) Modifier.clip(RoundedCornerShape(Forge.RTile)).background(Forge.Lime)
                            else Modifier.border(1.dp, Forge.Border, RoundedCornerShape(Forge.RTile))
                        ),
                        contentAlignment = Alignment.Center,
                    ) { if (on) ForgeIcon(R.drawable.ic_check, 14.dp, Forge.OnLime) }
                }
                Hairline()
            }
            item {
                // "Create custom" sits at the end of the results.
                Row(Modifier.fillMaxWidth().tap { creating = true }.padding(horizontal = 4.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ForgeIcon(R.drawable.ic_plus, 14.dp, Forge.Lime)
                    Spacer(Modifier.width(10.dp))
                    Text(if (query.isBlank()) "Can't find it? Create custom" else "Create “${query.trim()}”", style = Type.BodyM.copy(color = Forge.Lime))
                }
            }
        }
        if (!single) {
            Hairline()
            PrimaryButton(
                if (chosen.isEmpty()) "Select exercises" else "Add ${chosen.size} ${if (chosen.size == 1) "exercise" else "exercises"}",
                Modifier.fillMaxWidth().padding(horizontal = Forge.Gutter, vertical = 12.dp), enabled = chosen.isNotEmpty(),
            ) { onPick(chosen.toList()); nav.pop() }
        }
    }

    if (creating) {
        var exName by remember { mutableStateOf(query.trim()) }
        var m by remember { mutableStateOf(muscle ?: "Other") }
        var eq by remember { mutableStateOf(Equipment.BARBELL) }
        var tracking by remember { mutableStateOf(if (muscle == "Cardio") Tracking.CARDIO else Tracking.WEIGHT_REPS) }
        val cues = remember { mutableStateListOf("", "") }
        BottomSheet({ creating = false }) {
            SheetHeader("New exercise")
            ForgeTextField(exName, { exName = it.take(40) }, "Exercise name", Modifier.fillMaxWidth(),
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            LabelRow("What you log")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Tracking.entries.forEach { t -> Chip(t.label, t == tracking) { tracking = t } }
            }
            Text(trackingHelp(tracking), style = Type.BodyS)
            if (tracking != Tracking.CARDIO) {
                LabelRow("Muscle")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Library.muscles.filter { it != "Cardio" }.forEach { Chip(it, it == m) { m = it } }
                }
                LabelRow("Equipment")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Equipment.entries.filter { it != Equipment.CARDIO }.forEach { e -> Chip(e.label, e == eq) { eq = e } }
                }
            }
            LabelRow("Form cues · optional")
            cues.indices.forEach { i -> ForgeTextField(cues[i], { cues[i] = it.take(60) }, "Cue ${i + 1}, e.g. “Chest up, brace”", Modifier.fillMaxWidth()) }
            PrimaryButton("Create", Modifier.fillMaxWidth(), enabled = exName.isNotBlank()) {
                val ex = vm.createExercise(exName, m, eq, tracking, cues.toList())
                creating = false; query = ""
                if (single) { onPick(listOf(ex.id)); nav.pop() } else chosen.add(ex.id)
            }
        }
    }
}

/** A short tag for the picker when an exercise isn't plain weight × reps. */
internal fun trackingTag(t: Tracking): String? = when (t) {
    Tracking.WEIGHT_REPS -> null
    Tracking.ASSISTED -> "Assisted"
    Tracking.BODYWEIGHT_REPS -> "BW"
    Tracking.DURATION -> "Timed"
    Tracking.DISTANCE_WEIGHT -> "Carry"
    Tracking.CARDIO -> "Cardio"
}

/** One line on what each tracking type logs, for creating a custom exercise. */
internal fun trackingHelp(t: Tracking): String = when (t) {
    Tracking.WEIGHT_REPS -> "Weight and reps, like a bench press."
    Tracking.ASSISTED -> "Machine or band assistance and reps. Less assistance is progress."
    Tracking.BODYWEIGHT_REPS -> "Reps with optional added weight, like pull-ups."
    Tracking.DURATION -> "Time instead of reps, like a plank. Optional added weight."
    Tracking.DISTANCE_WEIGHT -> "Weight and distance, like a farmer's carry or sled push."
    Tracking.CARDIO -> "Time, plus distance, level and heart rate. Not counted as lifting."
}
