package app.forge

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.RemoteViews
import app.forge.data.*

/** The alarm at the end of a rest, which wakes [RestActionReceiver] even with the app closed. */
object RestAlarm {
    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, RestActionReceiver::class.java).setAction(RestActionReceiver.REST_END),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context, atMillis: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // A 3s window needs no exact-alarm permission and is plenty precise for rest timing.
        am.setWindow(AlarmManager.RTC_WAKEUP, atMillis, 3_000, pending(ctx))
    }

    fun cancel(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))

    /** 9.3's double buzz. */
    fun buzz(ctx: Context) {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        v.vibrate(VibrationEffect.createWaveform(BUZZ, -1))
    }

    val BUZZ = longArrayOf(0, 250, 120, 250)
}

/**
 * Section 09: the ongoing workout notification (9.1 shade, 9.2 lock screen) and the "Rest's up"
 * heads-up (9.3). [sync] is called by [ForgeRepository] whenever the session changes, from the app or
 * from a notification action, so the notification always shows the saved state.
 */
object RestNotifier {
    const val ONGOING_ID = 10
    const val REST_UP_ID = 11
    private const val CH_WORKOUT = "workout"
    private const val CH_REST = "rest"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_WORKOUT) == null) nm.createNotificationChannel(
            NotificationChannel(CH_WORKOUT, "Workout in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The rest countdown and next set while you train"
                setShowBadge(false)
            },
        )
        if (nm.getNotificationChannel(CH_REST) == null) nm.createNotificationChannel(
            NotificationChannel(CH_REST, "Rest's up", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tells you when rest between sets is over"
                enableVibration(true)
                vibrationPattern = RestAlarm.BUZZ
            },
        )
    }

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Brings the alarm and the ongoing notification in line with [d]. */
    fun sync(ctx: Context, d: AppData, now: Long = System.currentTimeMillis()) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val w = d.active
        val ends = w?.restEndsAt
        if (ends != null && ends > now) RestAlarm.schedule(ctx, ends) else RestAlarm.cancel(ctx)
        if (w == null || !d.settings.notifyRest) { nm.cancel(ONGOING_ID); nm.cancel(REST_UP_ID); return }
        if (ends != null && ends > now) nm.cancel(REST_UP_ID)
        if (!allowed(ctx)) return
        ensureChannels(ctx)
        nm.notify(ONGOING_ID, ongoing(ctx, d, now))
    }

    fun ongoing(ctx: Context, d: AppData, now: Long): Notification {
        val w = d.active!!
        val unit = d.settings.unit
        val resting = w.restEndsAt?.let { it > now } == true
        val next = Session.next(d)
        fun views(layout: Int, full: Boolean) = RemoteViews(ctx.packageName, layout).apply {
            val elapsedNow = SystemClock.elapsedRealtime()
            if (resting) {
                setChronometer(R.id.timer, elapsedNow + (w.restEndsAt!! - now), null, true)
                setChronometerCountDown(R.id.timer, true)
            } else {
                // Between rests: how long the session has run.
                setChronometer(R.id.timer, elapsedNow - (now - w.startedAt), null, true)
                setChronometerCountDown(R.id.timer, false)
            }
            setTextViewText(R.id.next_values, next?.let { setText(it, unit) } ?: "All sets logged")
            if (!full) return@apply
            setTextViewText(R.id.kicker, if (resting) "REST" else "WORKOUT")
            setTextViewText(R.id.next_label, next?.let { "NEXT · SET ${it.set + 1} OF ${it.entry.sets.size}" } ?: "")
            setTextViewText(R.id.next_name, next?.let { Session.exercise(d, it.entry.exerciseId).name } ?: "")
            if (resting) {
                val total = w.restTotalSec.coerceAtLeast(1) * 1000L
                val left = (w.restEndsAt!! - now).coerceIn(0, total)
                setProgressBar(R.id.progress, 1000, (1000 * (total - left) / total).toInt(), false)
                setViewVisibility(R.id.progress, View.VISIBLE)
            } else setViewVisibility(R.id.progress, View.GONE)
            val canLog = next != null && next.canLog && (d.settings.logFromLockScreen || !locked(ctx))
            setViewVisibility(R.id.log, if (next?.canLog == true) View.VISIBLE else View.GONE)
            if (canLog) setOnClickPendingIntent(R.id.log, action(ctx, RestActionReceiver.LOG, w.id, next!!.setEntry.id))
            else setOnClickPendingIntent(R.id.log, open(ctx))
            setViewVisibility(R.id.plus15, if (resting) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.skip, if (resting) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.plus15, action(ctx, RestActionReceiver.PLUS15, w.id))
            setOnClickPendingIntent(R.id.skip, action(ctx, RestActionReceiver.SKIP, w.id))
        }
        val b = Notification.Builder(ctx, CH_WORKOUT)
            .setSmallIcon(R.drawable.ic_stat_press)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSubText(w.name)
            .setContentIntent(open(ctx))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.notif_rest_small, full = false))
            .setCustomBigContentView(views(R.layout.notif_rest, full = true))
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (d.settings.logFromLockScreen) b.setVisibility(Notification.VISIBILITY_PUBLIC)
        else {
            // 9.2 with the toggle off: the lock screen shows the countdown but no actions.
            b.setVisibility(Notification.VISIBILITY_PRIVATE)
            b.setPublicVersion(
                Notification.Builder(ctx, CH_WORKOUT).setSmallIcon(R.drawable.ic_stat_press).setSubText(w.name)
                    .setStyle(Notification.DecoratedCustomViewStyle())
                    .setCustomContentView(views(R.layout.notif_rest_small, full = false)).build(),
            )
        }
        return b.build()
    }

    /** 9.3: rest ended while the app was in the background. */
    fun restUp(ctx: Context, d: AppData) {
        val w = d.active ?: return
        if (!d.settings.notifyRest || !allowed(ctx)) { RestAlarm.buzz(ctx); return }
        ensureChannels(ctx)
        val next = Session.next(d)
        val v = RemoteViews(ctx.packageName, R.layout.notif_rest_up).apply {
            setTextViewText(R.id.title, if (next != null) "Rest’s up. Set ${next.set + 1} is ready." else "Rest’s up.")
            setTextViewText(R.id.detail, next?.let { "${Session.exercise(d, it.entry.exerciseId).name} · ${setText(it, d.settings.unit)}" } ?: "All sets logged")
            if (next?.canLog == true) {
                val canLog = d.settings.logFromLockScreen || !locked(ctx)
                setOnClickPendingIntent(R.id.log, if (canLog) action(ctx, RestActionReceiver.LOG, w.id, next.setEntry.id) else open(ctx))
                // B: Edit opens the compact stepper over the lock screen; it decides when it opens whether
                // the phone is locked and the setting allows it, else it hands over to the app.
                setOnClickPendingIntent(R.id.edit, PendingIntent.getActivity(
                    ctx, 4, LockEditActivity.intent(ctx, w.id, next.setEntry.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ))
            } else setViewVisibility(R.id.buttons, View.GONE)
        }
        val n = Notification.Builder(ctx, CH_REST)
            .setSmallIcon(R.drawable.ic_stat_press)
            .setSubText(w.name)
            .setContentIntent(open(ctx))
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(v)
            .setCustomHeadsUpContentView(v)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(if (d.settings.logFromLockScreen) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(REST_UP_ID, n)
    }

    private fun setText(n: Session.Next, unit: WeightUnit): String {
        // Each tracking type reads its own way: "62.5 kg × 10", "−20 kg × 8", "1:05", "40 kg × 30 m".
        return Measure.format(n.entry, n.planned ?: n.setEntry, unit)
    }

    private fun locked(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun action(ctx: Context, what: String, workoutId: Long, setId: Long = 0) = PendingIntent.getBroadcast(
        ctx, what.hashCode(),
        Intent(ctx, RestActionReceiver::class.java).setAction(what)
            .putExtra(RestActionReceiver.EXTRA_WORKOUT, workoutId).putExtra(RestActionReceiver.EXTRA_SET, setId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Opens the app; with [editSet], straight into the 4.2 stepper for that set. */
    private fun open(ctx: Context, editSet: Long? = null) = PendingIntent.getActivity(
        ctx, if (editSet != null) 3 else 2,
        Intent(ctx, ForgeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (editSet != null) putExtra(ForgeActivity.EXTRA_EDIT_SET, editSet) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * Notification actions and the end-of-rest alarm. Runs whether or not the app is open: changes go
 * through the same [ForgeRepository] as the screens and are saved before returning, so a process that's
 * killed straight after keeps them. Logging names the exact set, so a double tap can't log twice.
 */
class RestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        handle(context, intent.action ?: return, intent.getLongExtra(EXTRA_WORKOUT, 0), intent.getLongExtra(EXTRA_SET, 0))
    }

    companion object {
        const val LOG = "app.forge.rest.LOG"
        const val PLUS15 = "app.forge.rest.PLUS15"
        const val SKIP = "app.forge.rest.SKIP"
        const val REST_END = "app.forge.rest.END"
        const val EXTRA_WORKOUT = "workout"
        const val EXTRA_SET = "set"

        fun handle(ctx: Context, action: String, workoutId: Long, setId: Long, now: Long = System.currentTimeMillis()) {
            val repo = ForgeRepository.get(ctx)
            val before = repo.value
            val after = when (action) {
                LOG -> repo.update { Session.logSet(it, workoutId, setId, now) }
                PLUS15 -> repo.update { d -> if (d.active?.id == workoutId) Session.adjustRest(d, 15, now) else d }
                SKIP -> repo.update { d -> if (d.active?.id == workoutId) Session.skipRest(d) else d }
                REST_END -> {
                    // In the foreground the screen buzzes and clears the rest itself.
                    if (ForgeActivity.inForeground) return
                    val ended = before.active?.restEndsAt?.let { it <= now + 3_000 } == true
                    if (!ended) return
                    repo.update(Session::skipRest).also { RestNotifier.restUp(ctx, it) }
                }
                else -> return
            }
            if (action == LOG && after !== before) ctx.getSystemService(android.app.NotificationManager::class.java).cancel(RestNotifier.REST_UP_ID)
            repo.flush()
            RestNotifier.sync(ctx, after, now)
        }
    }
}
