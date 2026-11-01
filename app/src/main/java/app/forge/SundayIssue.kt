package app.forge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.forge.data.Issues
import app.forge.data.Weeks
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 12.1: one notification a week, Sunday at 19:00 local time, announcing that week's issue. An inexact
 * alarm (no exact-alarm permission); rescheduled after each fire, on boot and after an app update.
 */
object SundayIssue {
    const val NOTIFICATION_ID = 20
    private const val CHANNEL = "weekly"
    const val EXTRA_ISSUE = "issue"

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 4, Intent(ctx, SundayIssueReceiver::class.java).setAction(SundayIssueReceiver.FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Sets the alarm for the next Sunday 19:00 and returns when that is. */
    fun schedule(ctx: Context, now: ZonedDateTime = ZonedDateTime.now(Weeks.zone)): ZonedDateTime {
        val at = Issues.nextPublish(now)
        // A 15-minute window keeps it battery-friendly and still lands "Sunday evening".
        ctx.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), 15 * 60_000L, pending(ctx))
        return at
    }

    fun cancel(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))

    /** Posts this week's issue, if there is one and the user wants it. Returns whether it was posted. */
    fun post(ctx: Context, now: LocalDateTime = LocalDateTime.now(Weeks.zone)): Boolean {
        val d = ForgeRepository.get(ctx).value
        if (!d.settings.notifySunday) return false
        val issue = Issues.latest(d, now) ?: return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "The Sunday issue", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "One short issue about your week, Sunday evenings"
            },
        )
        val open = PendingIntent.getActivity(
            ctx, 5, Intent(ctx, ForgeActivity::class.java).putExtra(EXTRA_ISSUE, issue.key)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_press)
            .setSubText("Press Weekly")
            .setContentTitle("Issue No. ${issue.number}: ${issue.headline}")
            .setContentText(issue.standfirst)
            .setStyle(Notification.BigTextStyle().bigText(issue.standfirst))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        return true
    }
}

class SundayIssueReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            FIRE -> { SundayIssue.post(context); SundayIssue.schedule(context) }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> SundayIssue.schedule(context)
        }
    }

    companion object { const val FIRE = "app.forge.issue.FIRE" }
}
