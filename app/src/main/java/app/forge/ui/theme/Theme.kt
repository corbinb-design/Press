package app.forge.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.forge.R

/**
 * Forge design tokens: the "FORGE" variable collection and text styles in Figma.
 * Layout follows Stripe Press: wide margins, hairline rules between rows, serif display type.
 */
object Forge {
    // Surfaces
    val Bg = Color(0xFF0E0D0B)          // "ink"
    val Surface = Color(0xFF171613)     // cards, sheets
    val Surface2 = Color(0xFF24221E)    // raised controls, inactive bars
    val Border = Color(0xFF2E2B26)      // hairlines

    // Text
    val Text = Color(0xFFEEEAE2)        // "bone"
    val Muted = Color(0xFF9A9488)       // "ash"

    // Signals. Lime means success only: PRs, ticked sets, primary buttons, active states.
    val Lime = Color(0xFFCCFF00)
    val OnLime = Color(0xFF0E0D0B)
    val Amber = Color(0xFFE2B33C)       // nudges, under-target warnings
    val Ice = Color(0xFF7CC8FF)         // protection: streak freeze, pause
    val Red = Color(0xFFFF3B30)         // live timer dot and destructive actions
    val Green = Color(0xFF9BE15D)       // "good" check-in answers (the Legs template green); Lime stays for success

    // Derived from the above (not separate Figma variables)
    val Faint = Muted.copy(alpha = 0.55f)   // placeholders, disabled labels
    val Lime30 = Lime.copy(alpha = 0.30f)
    val Lime11 = Lime.copy(alpha = 0.11f)
    val AmberBg = Color(0xFF1C1710)         // nudge banner (Figma "amber-bg")
    val WarmBg = Ice.copy(alpha = 0.08f)    // warm-up set row
    val DropBg = Amber.copy(alpha = 0.08f)  // drop set row
    val FailBg = Text.copy(alpha = 0.06f)   // failure set row
    val Paper = Color(0xFFF1EBDF)           // light poster / book covers

    // Layout
    val Gutter = 24.dp          // side margins
    val SectionGap = 28.dp      // between sections
    val RCard = 12.dp           // template cards, sheets
    val RButton = 10.dp
    val RRow = 8.dp             // chips, set rows
    val RTile = 6.dp            // value fields
}

/** Template colours, keyed by [app.forge.data.Template.colorKey]. */
object TemplateColors {
    val Push = Color(0xFFFF6A3D)
    val Pull = Color(0xFF4D7CFF)
    val Legs = Color(0xFF9BE15D)
    val Upper = Color(0xFFB18CFF)
    val Lower = Color(0xFF2FD3B0)
    val FullBody = Color(0xFFE8C872)

    val all = linkedMapOf("push" to Push, "pull" to Pull, "legs" to Legs, "upper" to Upper, "lower" to Lower, "full" to FullBody)

    fun of(key: String?): Color = all[key] ?: Forge.Muted
}

// Static instances per weight: variable-font axes aren't reliably honoured on every Android build.
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_400, FontWeight.Normal),
    Font(R.font.instrument_sans_500, FontWeight.Medium),
    Font(R.font.instrument_sans_600, FontWeight.SemiBold),
    Font(R.font.instrument_sans_700, FontWeight.Bold),
)

val DmMono = FontFamily(
    Font(R.font.dm_mono_400, FontWeight.Normal),
    Font(R.font.dm_mono_500, FontWeight.Medium),
)

val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

/**
 * Type scale from the Figma text styles.
 *
 * Serif only at 24sp and up, never in buttons, inputs or set tables. Instrument Serif has long
 * descenders, so every serif style keeps a line height of at least 1.15x and never trims the
 * first/last line ([NoTrim]). Line heights are in em so `.copy(fontSize = …)` keeps the ratio.
 *
 * Exception: a single uppercase initial (the avatar fallback) may use the serif at any size, since
 * one capital has no descenders to clip and reads as a monogram rather than as text.
 */
object Type {
    /** Keeps ascenders on the first line and descenders on the last line inside the text's bounds. */
    val NoTrim = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

    private fun serif(size: Int, lh: Float, ls: Float = 0f, italic: Boolean = false) = TextStyle(
        fontFamily = InstrumentSerif, fontSize = size.sp, lineHeight = lh.em, letterSpacing = ls.em,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal, color = Forge.Text, lineHeightStyle = NoTrim,
    )
    private fun sans(size: Int, lh: Float, w: FontWeight = FontWeight.Normal, c: Color = Forge.Text) = TextStyle(
        fontFamily = InstrumentSans, fontSize = size.sp, lineHeight = lh.em, fontWeight = w, color = c, lineHeightStyle = NoTrim,
    )
    private fun mono(size: Int, w: FontWeight, c: Color = Forge.Text, ls: Float = 0f, lh: TextUnit = TextUnit.Unspecified) = TextStyle(
        fontFamily = DmMono, fontSize = size.sp, fontWeight = w, color = c, letterSpacing = ls.em, lineHeight = lh, lineHeightStyle = NoTrim,
    )

    // Display (Instrument Serif)
    val DisplayXL = serif(52, 1.15f, -0.015f)
    val DisplayL = serif(42, 1.15f)                              // screen titles: "History", "Metrics"
    val DisplayM = serif(30, 1.2f, -0.005f)                      // sheet titles, template card titles
    val DisplayS = serif(24, 1.25f)                              // exercise names, session titles
    val DisplayItalic = serif(30, 1.2f, italic = true)           // standfirsts
    val FigureL = serif(52, 1.15f)                               // hero numbers, rest countdown
    val FigureM = serif(36, 1.2f)                                // stat numbers, steppers

    // Text (Instrument Sans)
    val TitleM = sans(16, 1.3f, FontWeight.SemiBold)             // row titles, option titles
    val BodyL = sans(16, 1.45f)
    val BodyM = sans(14, 1.45f)                                  // default body, section labels
    val BodyMuted = BodyM.copy(color = Forge.Muted)
    val BodyS = sans(12, 1.4f, c = Forge.Muted)                  // captions
    val SectionLabel = sans(13, 1.3f, FontWeight.Medium, Forge.Muted)  // "The last four weeks", "Strength"

    // Data (DM Mono). Callers uppercase Label and Button text.
    val Label = mono(10, FontWeight.Medium, Forge.Muted, 0.04f, 1.2.em)   // kickers, metadata
    val Kicker = Label.copy(color = Forge.Lime)
    val DataM = mono(14, FontWeight.Medium)                      // set values, weights, times
    val DataS = mono(11, FontWeight.Normal, Forge.Muted)         // secondary numbers
    val Button = mono(13, FontWeight.Medium, Forge.OnLime, 0.06f, 1.em)
}
