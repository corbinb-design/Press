package app.forge.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One short in-app message, in place of the system Toast. Call [show] from anywhere on the main thread; [MessageHost] draws it. */
object Messages {
    private val _current = MutableStateFlow<Pair<Long, String>?>(null)
    val current: StateFlow<Pair<Long, String>?> = _current
    private var seq = 0L
    fun show(text: String) { _current.value = ++seq to text }
    fun dismiss(id: Long) { if (_current.value?.first == id) _current.value = null }
}

@Composable
fun MessageHost() {
    val msg by Messages.current.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf("") }
    msg?.let { last = it.second }
    // Keyed on the id so a repeat of the same text restarts the timer.
    LaunchedEffect(msg?.first) { if (msg != null) { delay(2500); Messages.dismiss(msg!!.first) } }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = Forge.Gutter, vertical = 96.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(msg != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            // Keep the last text on screen while it fades out.
            Text(
                last, style = Type.BodyM,
                modifier = Modifier.background(Forge.Surface2, RoundedCornerShape(Forge.RSheet))
                    .border(1.dp, Forge.Border, RoundedCornerShape(Forge.RSheet)).padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
