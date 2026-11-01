package app.forge.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp

/**
 * The Press mark (Figma "15 · Logo explorations" › Final · Press): the loaded end of a barbell. A bar,
 * one plate, and the newest plate as the accent, on a 96×96 grid. This is the one source of the shape:
 * the Compose [Mark], the poster footer, and (via scratchpad tooling) the launcher, themed and
 * notification vectors all use these numbers.
 */
object PressMark {
    const val GRID = 96f

    /** x, y, width, height, corner radius, on the 96 grid. */
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float, val accent: Boolean)

    val BAR = Rect(4f, 42f, 88f, 12f, 3f, accent = false)
    val PLATE = Rect(40f, 22f, 18f, 52f, 4f, accent = false)
    val ACCENT = Rect(64f, 10f, 20f, 76f, 4f, accent = true)
    val rects = listOf(BAR, PLATE, ACCENT)
}

/** The mark at [size] (the 96 grid scaled to fit), bar and plate in [fg], newest plate in [accent]. */
@Composable
fun Mark(fg: Color, accent: Color, size: Dp, modifier: Modifier = Modifier, description: String? = null) {
    Canvas(modifier.size(size).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val s = this.size.minDimension / PressMark.GRID
        PressMark.rects.forEach { r ->
            drawRoundRect(
                if (r.accent) accent else fg, Offset(r.x * s, r.y * s), Size(r.w * s, r.h * s), CornerRadius(r.r * s),
            )
        }
    }
}
