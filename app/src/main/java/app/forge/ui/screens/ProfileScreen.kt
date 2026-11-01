package app.forge.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.AppViewModel
import app.forge.BuildConfig
import app.forge.R
import app.forge.data.*
import app.forge.ui.components.*
import app.forge.ui.restLabel
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class ProfileSheet { GOAL, UNITS, REST, RESET, RESET_CONFIRM, IMPORT_TEMPLATE }

/** 14.1: photo, name and lifetime numbers, then every setting as a catalogue grouped by topic. */
@Composable
fun ProfileScreen(
    data: AppData, vm: AppViewModel, onEditProfile: () -> Unit = {}, onChangePhoto: () -> Unit = {}, onPause: () -> Unit = {},
    onUnlocks: () -> Unit = {}, onHowXp: () -> Unit = {},
) {
    val s = data.settings
    val ctx = LocalContext.current
    val unit = s.unit
    var sheet by remember { mutableStateOf<ProfileSheet?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri) { ok -> Toast.makeText(ctx, if (ok) "Backup saved" else "Couldn't save the backup", Toast.LENGTH_SHORT).show() }
    }
    var pendingImport by remember { mutableStateOf<AppData?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    // Some file managers don't tag .json files as application/json, so accept those too; parsing decides.
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readBackup(uri) { r ->
            r.onSuccess { pendingImport = it }
                .onFailure { importError = if (it is ForgeStore.NotABackupException) it.message else "The file couldn't be read as Press data." }
        }
    }
    fun export() = exporter.launch("press-backup-${LocalDate.now()}.json")

    val xp = remember(data.history, s.weeklyGoal, data.streak) { Xp.total(data) }
    val since = remember(data.history) {
        val first = data.history.minOfOrNull { it.startedAt }
            ?: runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime }.getOrDefault(System.currentTimeMillis())
        Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US))
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(104.dp)) {
                Avatar(s.avatarPath, s.name, avatarColor(data), 104.dp, onClick = onChangePhoto)
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(32.dp).clip(CircleShape).background(Forge.Lime)
                        .border(3.dp, Forge.Bg, CircleShape).tap(label = "Change photo", onClick = onChangePhoto),
                    contentAlignment = Alignment.Center,
                ) { ForgeIcon(R.drawable.ic_edit, 14.dp, Forge.OnLime) }
            }
            Text(s.name.ifBlank { "Lifter" }, style = Type.DisplayL, textAlign = TextAlign.Center)
            Text("Level ${Xp.level(xp)} · ${Xp.tier(xp).label} · member since $since".uppercase(), style = Type.Label, textAlign = TextAlign.Center)
            SecondaryButton("Edit profile", onClick = onEditProfile)
        }
        LifetimeColumns(data, unit)
        Row(
            Modifier.fillMaxWidth().panel(radius = Forge.RCard).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ForgeIcon(R.drawable.ic_phone, 20.dp, Forge.Muted)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("On this phone only", style = Type.TitleM.copy(fontSize = 15.sp))
                Text("No account needed. Your data stays on this device; back it up with Export.", style = Type.BodyS)
            }
        }
        Group("Training") {
            SettingRow("Weekly goal", "${s.weeklyGoal} sessions") { sheet = ProfileSheet.GOAL }
            SettingRow("Units", unit.label) { sheet = ProfileSheet.UNITS }
            SettingRow("Default rest", restLabel(s.restSeconds)) { sheet = ProfileSheet.REST }
            // v1.1: standalone cardio of 20+ minutes counts as a session for the weekly goal.
            ToggleRow("Cardio of 20+ min counts toward the goal", s.cardioCountsForGoal) { v -> vm.updateSettings { it.copy(cardioCountsForGoal = v) } }
            // Information only: rules live with each exercise in the template editor (10.1).
            SettingRow("Progression", "Per exercise", chevron = false)
        }
        Group("Streak") {
            SettingRow("Pause streak", if (Streaks.pausedNow(data.streak, Weeks.today())) "Paused" else null, onClick = onPause)
            SettingRow("Freezes banked", "${data.streak.freezes}", chevron = false)
        }
        Group("Appearance") {
            SettingRow("App icon", Unlocks.Icon.of(s.appIcon).label, onClick = onUnlocks)
            SettingRow("Spine finish", Unlocks.Spine.of(s.spineFinish).label, onClick = onUnlocks)
            SettingRow("Unlocks", onClick = onUnlocks)
        }
        Group("Notifications") {
            ToggleRow("Rest timer", s.notifyRest) { v -> vm.updateSettings { it.copy(notifyRest = v) } }
            if (s.notifyRest) ToggleRow("Log sets from the lock screen", s.logFromLockScreen) { v -> vm.updateSettings { it.copy(logFromLockScreen = v) } }
            ToggleRow("Sunday issue", s.notifySunday) { v -> vm.updateSettings { it.copy(notifySunday = v) } }
        }
        Group("Your data") {
            SettingRow("Export data", onClick = ::export)
            SettingRow("Import data") { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
            // F: adds a template someone shared; never replaces anything.
            SettingRow("Import template") { sheet = ProfileSheet.IMPORT_TEMPLATE }
            SettingRow("Reset all data", labelColor = Forge.Red) { sheet = ProfileSheet.RESET }
        }
        Group("Coming soon") {
            SettingRow("Train with a partner", chevron = false, enabled = false)
            SettingRow("Import workout history (CSV)", chevron = false, enabled = false)
        }
        Group("About") {
            SettingRow("How XP works", onClick = onHowXp)
            SettingRow("Version", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", chevron = false)
        }
    }

    when (sheet) {
        ProfileSheet.GOAL -> BottomSheet({ sheet = null }) {
            SheetHeader("Weekly goal", "Sessions per Monday–Sunday week. Hitting it keeps your streak going.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                (2..6).forEach { n -> Chip("$n", s.weeklyGoal == n) { vm.updateSettings { it.copy(weeklyGoal = n) }; sheet = null } }
            }
        }
        ProfileSheet.UNITS -> BottomSheet({ sheet = null }) {
            SheetHeader("Units", "Weights are stored in kilograms and shown in your choice.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                WeightUnit.entries.forEach { u -> Chip(u.name, s.unit == u) { vm.updateSettings { it.copy(unit = u) }; sheet = null } }
            }
        }
        ProfileSheet.REST -> BottomSheet({ sheet = null }) {
            SheetHeader("Default rest", "Used when an exercise has no rest of its own.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Chip("−15", false) { vm.updateSettings { it.copy(restSeconds = (it.restSeconds - 15).coerceAtLeast(15)) } }
                Text(restLabel(s.restSeconds), style = Type.FigureM, modifier = Modifier.padding(horizontal = 24.dp))
                Chip("+15", false) { vm.updateSettings { it.copy(restSeconds = (it.restSeconds + 15).coerceAtMost(600)) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Start rest automatically after a set", style = Type.BodyM, modifier = Modifier.weight(1f))
                Switch(s.autoRest, label = "Start rest automatically after a set") { v -> vm.updateSettings { it.copy(autoRest = v) } }
            }
        }
        ProfileSheet.IMPORT_TEMPLATE -> ImportTemplateSheet(vm) { sheet = null }
        ProfileSheet.RESET -> BottomSheet({ sheet = null }) {
            SheetHeader("Reset all data?", "This deletes every session, template, setting and your photo from this phone. Export a backup first if you might want any of it back.")
            SecondaryButton("Export first", Modifier.fillMaxWidth()) { sheet = null; export() }
            SecondaryButton("Continue to reset", Modifier.fillMaxWidth(), color = Forge.Red) { sheet = ProfileSheet.RESET_CONFIRM }
            GhostButton("Cancel", Modifier.fillMaxWidth(), color = Forge.Muted) { sheet = null }
        }
        ProfileSheet.RESET_CONFIRM -> ConfirmSheet(
            "Are you sure?", "${data.history.size} sessions and ${data.templates.size} templates will be gone for good. This can't be undone.",
            "Reset everything", destructive = true, onDismiss = { sheet = null },
        ) { sheet = null; vm.resetAll() }
        null -> {}
    }

    importError?.let { msg ->
        BottomSheet({ importError = null }) {
            SheetHeader("Import failed", "$msg Nothing on this phone was changed.")
            PrimaryButton("OK", Modifier.fillMaxWidth()) { importError = null }
        }
    }
    pendingImport?.let { d ->
        val latest = d.history.maxOfOrNull { it.startedAt }?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
        } ?: "none"
        ConfirmSheet(
            "Import this backup?",
            "${d.history.size} sessions · ${d.templates.size} templates\nLatest workout: $latest\n\n" +
                "This replaces the data on this phone. A copy of the current data is kept on the phone first.",
            "Replace", destructive = true, onDismiss = { pendingImport = null },
        ) {
            pendingImport = null
            vm.importData(d)
            Toast.makeText(ctx, "Data imported", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Sessions · Lifted · Best streak, serif figures between hairlines (14.1). */
@Composable
private fun LifetimeColumns(data: AppData, unit: WeightUnit) {
    val lifted = remember(data.history) { data.history.sumOf { Stats.volume(it) } }
    val best = data.streak.bestStreakWeeks
    val stats = listOf("Sessions" to "${data.history.size}", "Lifted" to "${Units.abbreviated(lifted, unit)} ${unit.label}", "Best streak" to "$best wk")
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        stats.forEachIndexed { i, (label, value) ->
            if (i > 0) VerticalHairline()
            Column(Modifier.weight(1f).padding(start = if (i == 0) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = Type.BodyS)
                Text(value, style = Type.FigureM.copy(fontSize = 28.sp, color = if (i == 2 && best > 0) Forge.Lime else Forge.Text), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Group(label: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = Type.BodyMuted, modifier = Modifier.padding(bottom = 10.dp))
        Hairline()
        rows()
    }
}

@Composable
private fun SettingRow(
    label: String, value: String? = null, chevron: Boolean = true, labelColor: Color = Forge.Text, enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.tap(onClick = onClick) else Modifier).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Type.BodyM.copy(color = labelColor), modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = Type.DataS)
        if (!enabled) Tag("Soon", Forge.Muted)
        else if (chevron) ForgeIcon(R.drawable.ic_chevron_right, 16.dp, Forge.Muted)
    }
    Hairline()
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.BodyM, modifier = Modifier.weight(1f))
        Switch(on, label = label, onChange = onChange)
    }
    Hairline()
}
