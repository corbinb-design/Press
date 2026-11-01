package app.forge

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.forge.data.Unlocks

/** Switches the launcher icon (13.2) by enabling exactly one of the activity-alias entries in the manifest. */
object AppIcons {
    private fun component(ctx: Context, icon: Unlocks.Icon) = ComponentName(ctx.packageName, "app.forge.${icon.alias}")

    fun apply(ctx: Context, icon: Unlocks.Icon) {
        val pm = ctx.packageManager
        // Enable the new one first so there is never a moment with no launcher entry.
        pm.setComponentEnabledSetting(component(ctx, icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        Unlocks.Icon.entries.filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(component(ctx, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }

    /** The icon the launcher shows now. */
    fun current(ctx: Context): Unlocks.Icon = Unlocks.Icon.entries.firstOrNull {
        val state = ctx.packageManager.getComponentEnabledSetting(component(ctx, it))
        state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && it == Unlocks.Icon.LIME)
    } ?: Unlocks.Icon.LIME
}
