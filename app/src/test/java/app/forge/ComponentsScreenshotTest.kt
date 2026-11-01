package app.forge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.TemplateColors
import app.forge.ui.theme.Type
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shared components (Figma node 23:16) and the type scale, for side-by-side checks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class ComponentsScreenshotTest {
    private fun shot(name: String, content: @Composable ColumnScope.() -> Unit) =
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            Column(
                Modifier.fillMaxSize().background(Forge.Bg).padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content,
            )
        }

    @Test fun components() = shot("c1_components") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Chip("Push", false) {}; Chip("Push", true) {} }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Start", Modifier.weight(1f)) {}
            SecondaryButton("Start", Modifier.weight(1f)) {}
            GhostButton("Start", Modifier.weight(1f)) {}
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SetRow("2", "30kg × 7", "30", "7", SetState.Done)
            SetRow("2", "30kg × 7", "30", "7", SetState.Active)
            SetRow("2", "30kg × 7", "30", "7", SetState.Upcoming)
        }
        TemplateCard("Push", "Last: 2 days ago", "Chest · Shoulders · Triceps · 6 exercises", TemplateColors.Legs, nextUp = true)
        TemplateCard("Push", "Last: 2 days ago", "Chest · Shoulders · Triceps · 6 exercises", TemplateColors.Legs, nextUp = false)
        LabelRow("Sessions per week", trailing = "Sets your streak goal")
        Tabs(listOf("Overview", "Lifts", "Balance", "Cabinet"), 0) {}
        StatColumns(listOf("Sessions" to "18", "Volume" to "412.9k kg", "Avg time" to "58 MIN"), highlight = 0)
    }

    @Test fun sheet() = shot("c2_sheet") {
        Spacer(Modifier.weight(1f))
        SheetPanel {
            Text("Pausing your streak", style = Type.DisplayM)
            Text("Paused weeks don't use a freeze and don't break the streak. You get four a year.", style = Type.BodyMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f)) {}
                PrimaryButton("Pause", Modifier.weight(1f)) {}
            }
        }
    }

    /** Every style, with descender-heavy words, wrapped to two lines where it matters. */
    @Test fun typeScale() = shot("c3_type") {
        Text("Strongest jump", style = Type.DisplayXL)
        Text("Legs, gym, Pausing", style = Type.DisplayL)
        Text("Your best week since August, by a guy who skipped legs.", style = Type.DisplayM)
        Text("Romanian Deadlift · Weighted Pull-up", style = Type.DisplayS)
        Text("Your best week since August.", style = Type.DisplayItalic)
        Text("412,860 kg", style = Type.FigureL)
        Text("62.5 · 1:30", style = Type.FigureM)
        Text("Title/M · Upper body, quickly", style = Type.TitleM)
        Text("Body/L — a paragraph that wraps onto a second line to check the bottom of the paragraph.", style = Type.BodyL)
        Text("Body/M — default body, section labels, typography.", style = Type.BodyM)
        Text("Body/S — captions, joyful glyphs", style = Type.BodyS)
        Text("LABEL · THU 15 OCT · WEEK 12", style = Type.Label)
        Text("62.5 kg × 10", style = Type.DataM)
        Text("54 min · 11,460 kg", style = Type.DataS)
    }
}
