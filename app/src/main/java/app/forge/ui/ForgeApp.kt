package app.forge.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forge.AppViewModel
import app.forge.ForgeActivity
import app.forge.R
import app.forge.RestAlarm
import app.forge.data.Stats
import app.forge.data.Template
import app.forge.data.TemplateExercise
import app.forge.data.Workout
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.forge.ui.components.*
import app.forge.ui.screens.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import kotlinx.coroutines.delay

enum class Tab(val label: String, val icon: Int) {
    HOME("Home", R.drawable.ic_layout),
    HISTORY("History", R.drawable.ic_clock),
    METRICS("Metrics", R.drawable.ic_trending),
    PROFILE("Profile", R.drawable.ic_user),
}

/** Full-screen layers stacked above the tabs. */
sealed interface Overlay {
    /** 3.3. [single] = swap one exercise (picks immediately); [muscle] pre-filters. */
    class Picker(val single: Boolean = false, val muscle: String? = null, val onPick: (List<String>) -> Unit) : Overlay
    data object QuickBuild : Overlay
    class EditTemplate(val template: Template?, val drafted: Boolean = false) : Overlay
    class WorkoutDetail(val id: Long) : Overlay
    /** 5.2 → 5.3 (→ 5.4), shown after finishing; [prior] is history before this session. */
    class Finish(val workout: Workout, val prior: List<Workout>) : Overlay
    class Lift(val exerciseId: String) : Overlay
    class Rules(val rule: TemplateExercise, val templateName: String, val onSave: (TemplateExercise) -> Unit) : Overlay
    data object EditProfile : Overlay
    data object Pause : Overlay
    data object HowXp : Overlay
    data object Unlocks : Overlay
    class Issue(val weekKey: String) : Overlay
    data object Issues : Overlay
    class Crop(val uri: android.net.Uri, val onCropped: (android.graphics.Bitmap) -> Unit) : Overlay
    /** Context layer I.1–I.3: connect Health Connect, the "what happened" note, confirm. */
    data object Context : Overlay
}

class Nav(val push: (Overlay) -> Unit, val pop: () -> Unit, val goTab: (Tab) -> Unit, val replaceTop: (Overlay) -> Unit = { push(it) })

/** Wall clock that ticks on each second boundary. */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) { value = System.currentTimeMillis(); delay(1000 - value % 1000) }
}

private val scheme = darkColorScheme(
    primary = Forge.Lime, onPrimary = Forge.OnLime, background = Forge.Bg, onBackground = Forge.Text,
    surface = Forge.Surface2, onSurface = Forge.Text, surfaceContainer = Forge.Surface2, surfaceVariant = Forge.Surface,
    onSurfaceVariant = Forge.Muted, outline = Forge.Border,
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ForgeApp(vm: AppViewModel) = MaterialTheme(colorScheme = scheme) { SheetHost { ForgeAppContent(vm) } }

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ForgeAppContent(vm: AppViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // A running workout is full-screen focus mode (no nav) unless minimised with the chevron.
    var minimised by rememberSaveable { mutableStateOf(false) }
    val overlays = remember { mutableStateListOf<Overlay>() }
    val nav = remember {
        Nav(
            push = { overlays.add(it) }, pop = { overlays.removeLastOrNull() }, goTab = { tab = it },
            replaceTop = { overlays.removeLastOrNull(); overlays.add(it) },
        )
    }
    val ctx = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // 14.3: one photo chooser for Profile and Edit profile; the result goes back to whoever asked.
    var photoRequest by remember { mutableStateOf<PhotoRequest?>(null) }

    val active = data.active
    val restEnds = active?.restEndsAt
    LaunchedEffect(restEnds) {
        if (restEnds == null) return@LaunchedEffect
        val wait = restEnds - System.currentTimeMillis()
        delay(wait.coerceAtLeast(0))
        // Don't buzz for a timer that ran out while the app was closed.
        if (wait > -2000 && ForgeActivity.inForeground) RestAlarm.buzz(ctx)
        vm.skipRest()
    }

    // 12.1: the Sunday notification opens its issue.
    val issueRequest by vm.issueRequest.collectAsStateWithLifecycle()
    LaunchedEffect(issueRequest) {
        val key = issueRequest ?: return@LaunchedEffect
        vm.requestIssue(null)
        overlays.clear(); minimised = true; tab = Tab.HISTORY
        nav.push(Overlay.Issue(key))
    }

    // F: a shared template (opened from outside, or picked or scanned in Profile): previewed, then added.
    val templateImport by vm.templateImport.collectAsStateWithLifecycle()
    templateImport?.let { text ->
        TemplateImportPreview(text, data, vm) { added ->
            vm.requestTemplateImport(null)
            if (added != null) {
                android.widget.Toast.makeText(ctx, "Added ${added.name} to your templates", android.widget.Toast.LENGTH_SHORT).show()
                overlays.clear(); tab = Tab.HOME
            }
        }
    }

    // 9.3 "Edit" from the notification: bring the session to the front; WorkoutScreen opens the stepper.
    val editRequest by vm.editRequest.collectAsStateWithLifecycle()
    LaunchedEffect(editRequest) {
        if (editRequest != null && active != null) { overlays.clear(); minimised = false }
        else if (editRequest != null) vm.requestEdit(null)
    }

    val start: (Template?) -> Unit = { t ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        vm.startWorkout(t); minimised = false; tab = Tab.HOME
    }
    // v1.1: a standalone cardio session, starting from the cardio picker.
    val startCardio: () -> Unit = { nav.push(Overlay.Picker(single = true, muscle = "Cardio") { ids -> vm.startCardio(ids); minimised = false; tab = Tab.HOME }) }

    if (data.settings.needsSetup) {
        Box(Modifier.fillMaxSize().background(Forge.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
            FirstLaunchScreen { name, unit, goal, split ->
                vm.completeSetup(name, unit, goal, split)
                if (split.templates.isEmpty()) nav.push(Overlay.EditTemplate(null))
            }
        }
        return
    }

    // 11 C, then Home: after Done on the summary, History shows the session joining the shelf and returns
    // to Home once it has settled (about 1.5 s), or straight away on any tap.
    var returnHome by remember { mutableStateOf(false) }
    fun goHome() { returnHome = false; vm.dropOnShelf(null); tab = Tab.HOME }
    LaunchedEffect(returnHome) { if (returnHome) { delay(Motion.SHELF_RETURN_MS); if (returnHome) goHome() } }
    LaunchedEffect(tab) { if (tab != Tab.HISTORY) returnHome = false }

    val focusMode = active != null && !minimised
    BackHandler(enabled = overlays.isEmpty() && !focusMode && tab != Tab.HOME) { tab = Tab.HOME }
    BackHandler(enabled = overlays.isEmpty() && focusMode) { minimised = true }

    Box(Modifier.fillMaxSize().background(Forge.Bg)) {
        // Hide the nav only while the keyboard actually takes up space (isImeVisible misreports in some environments).
        val imeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        if (focusMode) {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                WorkoutScreen(active!!, data, vm, nav, onMinimise = { minimised = true })
            }
        } else Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.HOME -> HomeScreen(data, vm, nav, onStart = start, onCardio = startCardio)
                    Tab.HISTORY -> HistoryScreen(data, vm, nav)
                    Tab.METRICS -> MetricsScreen(data, vm, nav)
                    Tab.PROFILE -> ProfileScreen(
                        data, vm, onEditProfile = { nav.push(Overlay.EditProfile) }, onPause = { nav.push(Overlay.Pause) },
                        onUnlocks = { nav.push(Overlay.Unlocks) }, onHowXp = { nav.push(Overlay.HowXp) },
                        onChangePhoto = { photoRequest = PhotoRequest(data.settings.avatarPath != null, { vm.saveAvatar(it) }, vm::removeAvatar) },
                    )
                }
            }
            if (!imeVisible) {
                if (active != null) ResumeBar(active) { minimised = false }
                BottomNav(tab) { tab = it }
            }
        }
        // While the shelf moment plays, any tap anywhere skips to Home (and doesn't open what's under it).
        if (returnHome && overlays.isEmpty()) Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    goHome()
                }
            },
        )

        overlays.forEach { o ->
            key(o) {
                Box(
                    Modifier.fillMaxSize().background(Forge.Bg).statusBarsPadding().navigationBarsPadding().imePadding()
                ) {
                    when (o) {
                        is Overlay.Picker -> ExercisePicker(data, vm, nav, o.single, o.muscle, onPick = o.onPick)
                        is Overlay.QuickBuild -> QuickBuildScreen(data, vm, nav)
                        is Overlay.EditTemplate -> TemplateEditor(o.template, o.drafted, data, vm, nav)
                        is Overlay.WorkoutDetail -> WorkoutDetail(o.id, data, vm, nav, onRepeat = { w ->
                            vm.repeatWorkout(w); overlays.clear(); minimised = false; tab = Tab.HOME
                        })
                        // 11 C: Done sends the session to the History shelf, where it drops into place, then back Home.
                        is Overlay.Finish -> FinishFlow(o.workout, o.prior, data, vm, onDone = { vm.dropOnShelf(o.workout.id); overlays.clear(); tab = Tab.HISTORY; returnHome = true })
                        is Overlay.Lift -> LiftDetail(o.exerciseId, data, vm, nav)
                        is Overlay.Rules -> RulesScreen(
                            o.rule, vm.exercise(o.rule.exerciseId).name, o.templateName, data.settings.unit,
                            Stats.previousSets(data.history, o.rule.exerciseId), nav, o.onSave,
                        )
                        is Overlay.EditProfile -> EditProfileScreen(data, vm, nav) { has, picked, remove -> photoRequest = PhotoRequest(has, picked, remove) }
                        is Overlay.Crop -> CropScreen(o.uri, nav, onCropped = o.onCropped)
                        is Overlay.Pause -> PauseScreen(data, vm, nav)
                        is Overlay.HowXp -> HowXpScreen(data, nav)
                        is Overlay.Unlocks -> UnlocksScreen(data, vm, nav)
                        is Overlay.Issue -> IssueScreen(o.weekKey, data, nav)
                        is Overlay.Issues -> IssuesScreen(data, nav)
                        is Overlay.Context -> ContextFlow(data, vm, nav)
                    }
                }
            }
        }
        if (overlays.isNotEmpty()) BackHandler { nav.pop() }
        // Sheets 2.1–2.3 after the weekly streak check, on Home only.
        if (!focusMode && overlays.isEmpty() && tab == Tab.HOME) StreakNoticeSheet(data, vm)
        PhotoChooser(photoRequest, onDismiss = { photoRequest = null }) { uri, request -> nav.push(Overlay.Crop(uri, request.onPicked)) }
    }
}

/** Figma NavBar: hairline on top, 20dp icons, DM Mono 10 labels, lime when active. */
@Composable
private fun BottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Forge.Bg)) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Tab.entries.forEach { t ->
                val on = t == selected
                Column(
                    Modifier.width(72.dp).tap(role = androidx.compose.ui.semantics.Role.Tab) { onSelect(t) }.semantics { this.selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ForgeIcon(t.icon, 20.dp, if (on) Forge.Lime else Forge.Muted)
                    Text(t.label, style = Type.Label.copy(color = if (on) Forge.Lime else Forge.Muted, letterSpacing = Type.DataS.letterSpacing))
                }
            }
        }
    }
}

/** Shown above the nav while a minimised session is running, Strong-style. */
@Composable
private fun ResumeBar(w: Workout, onClick: () -> Unit) {
    val now by rememberNow()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Forge.Gutter, vertical = 8.dp)
            .panel(bg = Forge.Surface, border = Forge.Lime, radius = Forge.RButton)
            .tap(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(Forge.Red)
        Spacer(Modifier.width(8.dp))
        Text(w.name, style = Type.TitleM, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(clock(now - w.startedAt), style = Type.DataM)
        Spacer(Modifier.width(12.dp))
        Text("RESUME", style = Type.DataM.copy(color = Forge.Lime))
    }
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** "1:30" style rest label. */
fun restLabel(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
