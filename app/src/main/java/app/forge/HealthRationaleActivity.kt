package app.forge

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.forge.ui.components.IconTopBar
import app.forge.ui.screens.HealthReadList
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type

/**
 * Context layer: Health Connect opens this from its permission screen to show why Press asks. Health Connect
 * won't show Press's permission dialog at all unless the app declares it (see the manifest).
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#0E0D0B")),
        )
        super.onCreate(savedInstanceState)
        setContent {
            Column(Modifier.fillMaxSize().background(Forge.Bg).statusBarsPadding().navigationBarsPadding()) {
                IconTopBar(R.drawable.ic_close, onLeading = ::finish)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Forge.Gutter).padding(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Text("Why Press reads Health Connect", style = Type.DisplayM)
                    Text(
                        "Press only sees the gym. Reading what your watch recorded lets it count a week of hiking as a real training week, " +
                            "and go easier on your legs afterwards. It never writes to Health Connect.",
                        style = Type.BodyMuted,
                    )
                    HealthReadList()
                }
            }
        }
    }
}
