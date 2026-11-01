package app.forge.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.data.Library
import app.forge.data.WeightUnit
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type

/** Figma 1.0: the one setup screen, shown only on first launch. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstLaunchScreen(onDone: (name: String, unit: WeightUnit, weeklyGoal: Int, split: Library.Split) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf(WeightUnit.KG) }
    var goal by rememberSaveable { mutableIntStateOf(4) }
    var split by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(horizontal = Forge.Gutter).padding(bottom = 40.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PRESS // SETUP", style = Type.Kicker)
                Text("Three taps and you’re lifting.", style = Type.DisplayM)
            }
            Field("What should we call you?") {
                ForgeTextField(
                    name, { name = it.take(24) }, "Your name", Modifier.fillMaxWidth(), style = Type.TitleM,
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
            Field("Units") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WeightUnit.entries.forEach { u -> Chip(u.name, unit == u) { unit = u } }
                }
            }
            Field("Sessions per week", trailing = "Sets your streak goal") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (2..6).forEach { n -> Chip("$n", goal == n) { goal = n } }
                }
            }
            Field("Start from") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Library.splits.forEachIndexed { i, s -> Chip(s.label, split == i) { split = i } }
                }
            }
        }
        PrimaryButton("GO TO MY WORKOUTS", Modifier.fillMaxWidth(), enabled = name.isNotBlank()) {
            onDone(name, unit, goal, Library.splits[split])
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "You can change all of this later in Profile.", style = Type.BodyS,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Field(label: String, trailing: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), style = Type.Label)
            if (trailing != null) Text(trailing.uppercase(), style = Type.Label.copy(color = Forge.Lime))
        }
        content()
    }
}
