package app.forge.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.AppViewModel
import app.forge.data.*
import app.forge.ui.Nav
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type

/**
 * 14.2 Edit profile. Nothing is written until Save (disabled until something changes), including
 * a new or removed photo; [onChangePhoto] opens 14.3 and reports a cropped photo or a removal back here.
 */
@Composable
fun EditProfileScreen(
    data: AppData, vm: AppViewModel, nav: Nav,
    onChangePhoto: (hasPhoto: Boolean, onPicked: (Bitmap) -> Unit, onRemove: () -> Unit) -> Unit = { _, _, _ -> },
) {
    val s = data.settings
    var name by remember { mutableStateOf(s.name) }
    var unit by remember { mutableStateOf(s.unit) }
    val initialBw = s.bodyweightKg?.let { Units.w(it, s.unit) } ?: ""
    var bodyweight by remember { mutableStateOf(initialBw) }
    var photo by remember { mutableStateOf<Bitmap?>(null) }
    var removePhoto by remember { mutableStateOf(false) }

    // Bodyweight is typed in the unit chosen on this screen.
    val bwKg = bodyweight.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { Units.toKg(it, unit) }
    val bwValid = bodyweight.isBlank() || bwKg != null
    val changed = name.trim() != s.name || unit != s.unit || bodyweight.trim() != initialBw || photo != null || removePhoto
    val valid = name.isNotBlank() && bwValid
    val hasPhoto = photo != null || (s.avatarPath != null && !removePhoto)

    Column(Modifier.fillMaxSize()) {
        TextTopBar("Edit profile", "Cancel", { nav.pop() }, right = "Save", rightEnabled = changed && valid) {
            vm.saveProfile(name, if (bodyweight.isBlank()) null else bwKg, unit, photo, removePhoto)
            nav.pop()
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val pending = photo
                val open = { onChangePhoto(hasPhoto, { photo = it; removePhoto = false }, { photo = null; removePhoto = true }) }
                if (pending != null) Image(pending.asImageBitmap(), null, Modifier.size(120.dp).clip(CircleShape).tap(label = "New profile photo", action = "Change photo", onClick = open))
                else Avatar(if (removePhoto) null else s.avatarPath, name.ifBlank { s.name }, avatarColor(data), 120.dp, onClick = open)
                Text("Change photo", style = Type.TitleM.copy(fontSize = 15.sp, color = Forge.Lime), modifier = Modifier.tap(onClick = open))
            }
            Field("Name", "Shown on Home, your Sunday issue and shared posters.") {
                ForgeTextField(
                    name, { name = it.take(30) }, "Your name", Modifier.fillMaxWidth(), style = Type.BodyL,
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                if (name.isBlank()) Text("A name is required.", style = Type.BodyS.copy(color = Forge.Amber))
            }
            Field("Bodyweight (optional)", "Used for pull-ups, dips and other bodyweight lifts.") {
                ForgeTextField(
                    bodyweight, { v -> bodyweight = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) }, "e.g. 82 ${unit.label}",
                    Modifier.fillMaxWidth(), style = Type.BodyL,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                )
                if (!bwValid) Text("Enter a number, or leave it empty.", style = Type.BodyS.copy(color = Forge.Amber))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Units", style = Type.BodyMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WeightUnit.entries.forEach { u ->
                        Chip(u.name, unit == u) {
                            // Keep the typed bodyweight meaning the same weight when switching units.
                            bodyweight.replace(',', '.').toDoubleOrNull()?.let { v -> bodyweight = Units.w(Units.toKg(v, unit), u) }
                            unit = u
                        }
                    }
                }
            }
            if (hasPhoto) Column {
                Hairline()
                Text(
                    "Remove photo", style = Type.BodyM.copy(color = Forge.Red),
                    modifier = Modifier.padding(top = 16.dp).tap { photo = null; removePhoto = true },
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, help: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Type.BodyMuted)
        content()
        Text(help, style = Type.BodyS)
    }
}
