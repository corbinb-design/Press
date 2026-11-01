package app.forge.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Section 11 motion: the timings, curves and haptics from the Figma storyboards in one place.
 * Everyday interactions stay under 300 ms; celebration is kept for things actually earned. With
 * animations removed in system settings, every moment falls back to a short crossfade.
 */
object Motion {
    /** A · set tick: the check's spring (stiffness 500, damping 22 at mass 1 ≈ damping ratio 0.49). */
    fun <T> tickSpring(): SpringSpec<T> = spring(dampingRatio = 0.49f, stiffness = 500f)
    /** A · the lime wipe and the next row lifting: cubic-bezier(0.2, 0.8, 0.2, 1). */
    val Wipe = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    /** B · PR toast drop-in: a spring with visible overshoot (~8 px). */
    fun <T> dropSpring(): SpringSpec<T> = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
    /** C · a spine growing to its height with ~6% overshoot: cubic-bezier(0.34, 1.56, 0.64, 1). */
    val Overshoot = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    /** D · the week ring's arc: cubic-bezier(0.65, 0, 0.35, 1). */
    val Ring = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

    const val TICK_MS = 420
    const val TICK_HAPTIC_MS = 160L
    const val PR_IN_MS = 300
    const val PR_HOLD_MS = 2_600L
    const val PR_OUT_MS = 500
    const val SHELF_MS = 900
    /** After the shelf moment settles, back to Home. */
    const val SHELF_RETURN_MS = 1_500L
    const val RING_MS = 800
    const val RING_GOAL_MS = 1_200
    /** The fallback crossfade with motion reduced. */
    const val FADE_MS = 150

    fun reduced(ctx: android.content.Context): Boolean =
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
}

/** True when the system's "Remove animations" is on. Screenshots and tests can provide a value. */
val LocalReduceMotion = compositionLocalOf<Boolean?> { null }

@Composable
fun reduceMotion(): Boolean {
    LocalReduceMotion.current?.let { return it }
    val ctx = LocalContext.current
    return remember(ctx) { Motion.reduced(ctx) }
}

/** The three haptics the storyboards call for. */
object Haptics {
    /** A light tick: a set landing, a count-up. */
    fun light(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    /** Success (heavy + light): a PR, the week's goal. */
    fun success(v: View) {
        if (Build.VERSION.SDK_INT >= 30) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); v.postDelayed({ v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }, 90) }
    }
    /** A soft thud: a spine landing on the shelf. */
    fun thud(v: View) = v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
}
