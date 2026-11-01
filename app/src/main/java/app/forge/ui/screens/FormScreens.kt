package app.forge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.AppViewModel
import app.forge.R
import app.forge.data.Exercise
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import java.net.URLEncoder

/** "Watch form": a YouTube search for "<name> form" in the browser. No embedded video, no permissions. */
fun watchFormUrl(name: String): String = "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$name form", "UTF-8")

/** The cues, numbered, one line each. */
@Composable
private fun Cues(cues: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cues.forEachIndexed { i, c ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${i + 1}", style = Type.DataM.copy(fontSize = 12.sp, color = Forge.Lime), modifier = Modifier.width(14.dp))
                Text(c, style = Type.BodyM)
            }
        }
    }
}

/**
 * The exercise page's collapsible Form section (v1.1): 2–3 cues and "Watch form". Custom exercises
 * show the user's own cues, with a way to write them.
 */
@Composable
fun FormSection(ex: Exercise, vm: AppViewModel?, startOpen: Boolean = false) {
    var open by remember(ex.id) { mutableStateOf(startOpen) }
    var editing by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Column {
        Row(
            Modifier.fillMaxWidth().tap(action = if (open) "Hide form cues" else "Show form cues") { open = !open }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Form", style = Type.BodyMuted, modifier = Modifier.weight(1f))
            if (ex.cues.isNotEmpty()) Text("${ex.cues.size} CUES", style = Type.Label)
            Spacer(Modifier.width(8.dp))
            ForgeIcon(R.drawable.ic_chevron_down, 16.dp, Forge.Muted, Modifier.rotate(if (open) 180f else 0f))
        }
        Hairline()
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (ex.cues.isNotEmpty()) Cues(ex.cues)
                else Text("No cues yet. Write two or three that you'd tell yourself mid-set.", style = Type.BodyS)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Watch form", Modifier.weight(1f)) { uri.openUri(watchFormUrl(ex.name)) }
                    if (ex.custom && vm != null) GhostButton(if (ex.cues.isEmpty()) "Add cues" else "Edit cues", Modifier.weight(1f)) { editing = true }
                }
                Text("Opens a YouTube search in your browser.", style = Type.BodyS)
            }
        }
    }
    if (editing && vm != null) CueEditor(ex, vm) { editing = false }
}

/** From the exercise menu during a workout: the same cues in a sheet. */
@Composable
fun FormSheet(ex: Exercise, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    BottomSheet(onDismiss) {
        SheetHeader(ex.name)
        if (ex.cues.isNotEmpty()) Cues(ex.cues)
        else Text("No cues for this one yet. Add them on the exercise page in Metrics.", style = Type.BodyMuted)
        SecondaryButton("Watch form", Modifier.fillMaxWidth()) { uri.openUri(watchFormUrl(ex.name)) }
        Text("Opens a YouTube search in your browser.", style = Type.BodyS)
    }
}

/** Up to three one-line cues for a custom exercise. */
@Composable
fun CueEditor(ex: Exercise, vm: AppViewModel, onDone: () -> Unit) {
    val drafts = remember { mutableStateListOf(*Array(3) { ex.cues.getOrElse(it) { "" } }) }
    BottomSheet(onDone) {
        SheetHeader("Form cues", "Short, one line each. Two or three is plenty.")
        drafts.indices.forEach { i ->
            ForgeTextField(drafts[i], { drafts[i] = it.take(60) }, "Cue ${i + 1}", Modifier.fillMaxWidth())
        }
        PrimaryButton("Save cues", Modifier.fillMaxWidth()) { vm.setCues(ex.id, drafts.toList()); onDone() }
    }
}
