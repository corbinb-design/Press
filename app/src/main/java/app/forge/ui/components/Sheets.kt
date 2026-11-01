package app.forge.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Sheets currently open, oldest first. The last one is on top and gets Back. */
class SheetHostState {
    internal val sheets = mutableStateListOf<SheetEntry>()
}

internal class SheetEntry(val onDismiss: State<() -> Unit>, val content: State<@Composable ColumnScope.() -> Unit>)

val LocalSheetHost = staticCompositionLocalOf<SheetHostState?> { null }

/** Draws every [BottomSheet] opened below it over [content], in the same window. */
@Composable
fun SheetHost(content: @Composable () -> Unit) {
    val state = remember { SheetHostState() }
    CompositionLocalProvider(LocalSheetHost provides state) {
        Box(Modifier.fillMaxSize()) {
            // Like a dialog: with a sheet open, TalkBack only sees the sheet.
            Box(Modifier.fillMaxSize().then(if (state.sheets.isNotEmpty()) Modifier.clearAndSetSemantics { } else Modifier)) { content() }
            state.sheets.forEach { entry -> key(entry) { SheetLayer(entry, top = entry == state.sheets.lastOrNull()) } }
        }
    }
}

@Composable
internal fun HostedSheet(host: SheetHostState, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val dismiss = rememberUpdatedState(onDismiss)
    val body = rememberUpdatedState(content)
    DisposableEffect(host) {
        val entry = SheetEntry(dismiss, body)
        host.sheets.add(entry)
        onDispose { host.sheets.remove(entry) }
    }
}

@Composable
private fun SheetLayer(entry: SheetEntry, top: Boolean) {
    BackHandler(enabled = top) { entry.onDismiss.value() }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).scrimTap { entry.onDismiss.value() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Never taller than the screen below the status bar; longer sheets scroll inside.
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 24.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(shown, enter = slideInVertically { it / 3 } + fadeIn()) {
                SheetPanel(Modifier.blockTaps().navigationBarsPadding().imePadding()) { entry.content.value(this) }
            }
        }
    }
}
