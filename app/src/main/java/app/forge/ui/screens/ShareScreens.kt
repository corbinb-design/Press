package app.forge.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.forge.AppViewModel
import app.forge.R
import app.forge.data.*
import app.forge.ui.Qr
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import java.io.File

/** F: writes [t] to the cache as a `.press` file and opens the Android share sheet for it. */
fun shareTemplate(ctx: Context, t: Template, d: AppData) {
    val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, TemplateShare.fileName(t.name))
    file.writeText(TemplateShare.encode(TemplateShare.from(t, d)))
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType(TemplateShare.MIME).putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "${t.name} · a Press template")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Share ${t.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** F: "Share template": a QR code to scan in person (when it fits) and the file for any app. */
@Composable
fun ShareTemplateSheet(t: Template, data: AppData, vm: AppViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val qr = remember(t, data.customExercises) { Qr.bitmap(TemplateShare.qr(TemplateShare.from(t, data)))?.asImageBitmap() }
    // Showing the code is sharing it in person.
    LaunchedEffect(qr) { if (qr != null) vm.markShared() }
    BottomSheet(onDismiss) {
        SheetHeader("Share ${t.name}", "The plan only: exercises, sets, reps, rest and progression. No history, weights or profile.")
        if (qr != null) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(qr, "QR code for ${t.name}", Modifier.size(240.dp).clip(RoundedCornerShape(Forge.RCard)).background(Color.White))
            Text("In Press on the other phone: Profile → Import template → Scan a QR code.", style = Type.BodyS, textAlign = TextAlign.Center)
        } else Text("Too many exercises for a QR code. Send the file instead.", style = Type.BodyS)
        PrimaryButton("Send file", Modifier.fillMaxWidth()) { vm.markShared(); shareTemplate(ctx, t, data) }
    }
}

/** F: Profile → "Import template": pick a `.press` file, or read a QR code from a photo or screenshot. */
@Composable
fun ImportTemplateSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    fun readQr(uri: Uri) {
        val text = loadBitmap(ctx, uri, maxDim = 1600)?.let(Qr::read)
        if (text == null) error = "No QR code found. Fill the frame with the code and try again."
        else { onDismiss(); vm.requestTemplateImport(text) }
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readText(uri) { r ->
            r.onSuccess { onDismiss(); vm.requestTemplateImport(it) }.onFailure { error = "Press couldn't read that file." }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) photo?.let(::readQr) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::readQr) }
    BottomSheet(onDismiss) {
        SheetHeader("Import template", "Adds a template someone shared with you. Nothing you have is replaced.")
        // Files from chat and mail apps rarely keep their type, so anything can be picked; the file decides.
        SecondaryButton("Choose a file", Modifier.fillMaxWidth(), icon = R.drawable.ic_layout) { file.launch(arrayOf("*/*")) }
        SecondaryButton("Scan a QR code", Modifier.fillMaxWidth(), icon = R.drawable.ic_camera) { photo = newCameraUri(ctx).also { camera.launch(it) } }
        GhostButton("QR code from a screenshot", Modifier.fillMaxWidth(), icon = R.drawable.ic_image) {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        error?.let { Text(it, style = Type.BodyS.copy(color = Forge.Red)) }
    }
}

/** F: what a shared template will add, before anything is added; or why it can't be. */
@Composable
fun TemplateImportPreview(text: String, data: AppData, vm: AppViewModel, onDone: (Template?) -> Unit) {
    val parsed = remember(text) { runCatching { TemplateShare.read(text) } }
    val file = parsed.getOrNull()
    BottomSheet({ onDone(null) }) {
        if (file == null) {
            SheetHeader("Couldn't open this template", (parsed.exceptionOrNull() as? TemplateShare.BadFile)?.message ?: "This isn't a Press template.")
            PrimaryButton("OK", Modifier.fillMaxWidth()) { onDone(null) }
            return@BottomSheet
        }
        val p = remember(file, data.templates, data.customExercises) { TemplateShare.preview(data, file) }
        Text("SHARED TEMPLATE", style = Type.Kicker)
        Text(p.name, style = Type.DisplayM)
        Column(Modifier.fillMaxWidth().panel(bg = Forge.Bg, radius = Forge.RCard).padding(horizontal = 14.dp, vertical = 4.dp)) {
            p.rows.forEachIndexed { i, r ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(r.name, style = Type.BodyM, modifier = Modifier.weight(1f))
                    if (r.isNew) Tag("NEW")
                    Text(r.detail, style = Type.DataS.copy(fontSize = 12.sp))
                }
            }
        }
        listOfNotNull(
            "${p.newExercises} custom ${if (p.newExercises == 1) "exercise" else "exercises"} will be added to your library.".takeIf { p.newExercises > 0 },
            "${p.unknown} ${if (p.unknown == 1) "exercise isn't" else "exercises aren't"} in this version of Press and will be left out.".takeIf { p.unknown > 0 },
        ).forEach { Text(it, style = Type.BodyS) }
        PrimaryButton("Add template", Modifier.fillMaxWidth(), enabled = p.rows.isNotEmpty()) { onDone(vm.addSharedTemplate(file)) }
        GhostButton("Cancel", Modifier.fillMaxWidth(), color = Forge.Muted) { onDone(null) }
    }
}
