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

    private val lastBuzzed = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * The one buzz at the end of the rest that ends at [ends] (docs/rest-design.md, D1 option b). The app's
     * effect and the alarm may both call this; the atomic swap lets only the first through, so a rest buzzes
     * once. In memory only: both callers share the process, and after process death only the alarm is left.
     * Returns true if this call buzzed.
     */
    fun buzzOnce(ctx: Context, ends: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (lastBuzzed.getAndSet(ends) == ends) return false
        // Drawn at the end time at the earliest, so an alarm a moment early still posts Over, not Resting.
        RestNotifier.buzz(ctx, ForgeRepository.get(ctx).value, maxOf(now, ends))
        return true
    }

    /** Debug builds only: a buzz [afterMs] from now, to check on the phone with the app swiped away. */
    fun scheduleDebugBuzz(ctx: Context, afterMs: Long) {
        val at = System.currentTimeMillis() + afterMs
        val pi = PendingIntent.getBroadcast(
            ctx, 5, Intent(ctx, RestActionReceiver::class.java).setAction(RestActionReceiver.DEBUG_BUZZ)
                .putExtra(RestActionReceiver.EXTRA_ENDS, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ctx.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC_WAKEUP, at, 3_000, pi)
    }

    @androidx.annotation.VisibleForTesting fun resetBuzzed() = lastBuzzed.set(0)
}

/**
 * Section 09: the ongoing workout notification (9.1 shade, 9.2 lock screen) (the heads-up is retired: Over is shown here). [sync] is called by [ForgeRepository] whenever the session changes, from the app or
 * from a notification action, so the notification always shows the saved state.
 */
object RestNotifier {
    const val ONGOING_ID = 10
    private const val CH_WORKOUT = "workout"
    private const val CH_OLD_REST = "rest" // the retired "Rest's up" heads-up channel, deleted in ensureChannels
    private const val CH_BUZZ = "rest_end"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_WORKOUT) == null) nm.createNotificationChannel(
            NotificationChannel(CH_WORKOUT, "Workout in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The rest countdown and next set while you train"
                setShowBadge(false)
            },
        )
        if (nm.getNotificationChannel(CH_OLD_REST) != null) nm.deleteNotificationChannel(CH_OLD_REST)
        if (nm.getNotificationChannel(CH_BUZZ) == null) nm.createNotificationChannel(
            // Alerting but quiet: vibrates, no sound, and DEFAULT importance never pops a heads-up.
            NotificationChannel(CH_BUZZ, "Rest ended", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A short buzz when rest between sets ends"
                enableVibration(true)
                vibrationPattern = RestAlarm.BUZZ
                setSound(null, null)
                enableLights(false)
                setShowBadge(false)
            },
        )
    }

    /**
     * Buzz by re-posting the ongoing notification on [CH_BUZZ]. It stays there, not alerting again, until
     * the next [sync] puts it back on the quiet channel. Re-posting on the quiet channel straight away
     * could cut the vibration short, since an update without one cancels it. Without notifications
     * allowed it vibrates directly.
     */
    fun buzz(ctx: Context, d: AppData, now: Long) {
        val w = d.active
        if (w == null || !d.settings.notifyRest || !allowed(ctx)) { RestAlarm.buzz(ctx); return }
        ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.notify(ONGOING_ID, ongoing(ctx, d, now, CH_BUZZ))
    }

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Brings the alarm and the ongoing notification in line with [d]. */
    fun sync(ctx: Context, d: AppData, now: Long = System.currentTimeMillis()) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val w = d.active
        val ends = w?.restEndsAt
        if (ends != null && ends > now) RestAlarm.schedule(ctx, ends) else RestAlarm.cancel(ctx)
        if (w == null || !d.settings.notifyRest) { nm.cancel(ONGOING_ID); return }
        if (!allowed(ctx)) return
        ensureChannels(ctx)
        nm.notify(ONGOING_ID, ongoing(ctx, d, now))
    }

    fun ongoing(ctx: Context, d: AppData, now: Long, channel: String = CH_WORKOUT): Notification {
        val w = d.active!!
        val unit = d.settings.unit
        val phase = Session.rest(w, now)
        val resting = phase == RestPhase.RESTING
        val inRest = phase != RestPhase.WORKING // resting or over: the chronometer counts down, through zero
        val next = Session.next(d)
        fun views(layout: Int, full: Boolean) = RemoteViews(ctx.packageName, layout).apply {
            val elapsedNow = SystemClock.elapsedRealtime()
            // Resting counts down to zero; over keeps counting down into the negative (-0:12) by itself.
            setInt(R.id.root, "setBackgroundResource", if (resting) R.drawable.notif_bg_rest else R.drawable.notif_bg)
            if (resting) setTextColor(R.id.timer, ctx.getColor(R.color.forge_bg))
            if (inRest) {
                setChronometer(R.id.timer, elapsedNow + (w.restEndsAt!! - now), null, true)
                setChronometerCountDown(R.id.timer, true)
            } else {
                // Between rests: how long the session has run.
                setChronometer(R.id.timer, elapsedNow - (now - w.startedAt), null, true)
                setChronometerCountDown(R.id.timer, false)
            }
            setTextViewText(R.id.next_values, next?.let { setText(it, unit) } ?: "All sets logged")
            if (resting) setTextColor(R.id.next_values, ctx.getColor(R.color.forge_bg))
            if (!full) return@apply
            setTextViewText(R.id.kicker, when (phase) { RestPhase.WORKING -> "WORKOUT"; RestPhase.RESTING -> "REST"; RestPhase.OVER -> "OVER" })
            if (resting) {
                val muted = ctx.getColor(R.color.forge_ink_muted)
                setTextColor(R.id.kicker, muted); setTextColor(R.id.next_label, muted); setTextColor(R.id.next_name, muted)
                setInt(R.id.log, "setBackgroundResource", R.drawable.notif_pill_ink)
                setTextColor(R.id.log, ctx.getColor(R.color.forge_lime))
                setInt(R.id.plus15, "setBackgroundResource", R.drawable.notif_pill_on_lime)
                setInt(R.id.skip, "setBackgroundResource", R.drawable.notif_pill_on_lime)
                setTextColor(R.id.plus15, ctx.getColor(R.color.forge_bg)); setTextColor(R.id.skip, ctx.getColor(R.color.forge_bg))
            }
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
            setViewVisibility(R.id.plus15, if (inRest) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.skip, if (inRest) View.VISIBLE else View.GONE)
            // Edit (D4): once rest is over, the compact stepper over the lock screen for the next set.
            val canEdit = phase == RestPhase.OVER && next?.canLog == true
            setViewVisibility(R.id.edit, if (canEdit) View.VISIBLE else View.GONE)
            if (canEdit) setOnClickPendingIntent(R.id.edit, edit(ctx, w.id, next!!.setEntry.id))
            setOnClickPendingIntent(R.id.plus15, action(ctx, RestActionReceiver.PLUS15, w.id))
            setOnClickPendingIntent(R.id.skip, action(ctx, RestActionReceiver.SKIP, w.id))
        }
        // Title and text plus wrist actions, for the watch's mirror of this notification. The custom views
        // hide the title and text on the phone, and WearableExtender actions never show on the phone, so
        // the phone look is unchanged.
        val title = when (phase) {
            RestPhase.RESTING -> "Rest ${clock((w.restEndsAt!! - now).coerceAtLeast(0))}"
            RestPhase.OVER -> "Rest over"
            RestPhase.WORKING -> "Workout"
        }
        val nextName = next?.let { Session.exercise(d, it.entry.exerciseId).name }
        val text = next?.let { "$nextName · ${setText(it, unit)}" } ?: "All sets logged"
        val canLog = next != null && next.canLog && (d.settings.logFromLockScreen || !locked(ctx))
        val wrist = Notification.WearableExtender()
        fun wristAction(label: String, pi: PendingIntent) = wrist.addAction(
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_stat_press), label, pi).build(),
        )
        if (canLog) wristAction("Log set", action(ctx, RestActionReceiver.LOG, w.id, next!!.setEntry.id))
        if (inRest) {
            wristAction("+15", action(ctx, RestActionReceiver.PLUS15, w.id))
            wristAction("Skip", action(ctx, RestActionReceiver.SKIP, w.id))
        }
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_press)
            .setContentTitle(title)
            .setContentText(text)
            .extend(wrist)
            .setOngoing(true)
            .setOnlyAlertOnce(channel == CH_WORKOUT)
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

    private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

    private fun setText(n: Session.Next, unit: WeightUnit): String {
        // Each tracking type reads its own way: "62.5 kg × 10", "−20 kg × 8", "1:05", "40 kg × 30 m".
        return Measure.format(n.entry, n.planned ?: n.setEntry, unit)
    }

    private fun locked(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /**
     * The data URI makes each (action, workout, set) its own PendingIntent. With one shared intent, a second
     * tap on a Log button the shade hasn't redrawn yet carried the next set's id and logged that too.
     */
    private fun action(ctx: Context, what: String, workoutId: Long, setId: Long = 0) = PendingIntent.getBroadcast(
        ctx, what.hashCode(),
        Intent(ctx, RestActionReceiver::class.java).setAction(what)
            .setData(android.net.Uri.parse("forge://rest/$what/$workoutId/$setId"))
            .putExtra(RestActionReceiver.EXTRA_WORKOUT, workoutId).putExtra(RestActionReceiver.EXTRA_SET, setId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Edit opens [LockEditActivity], which decides when it opens whether to show over the lock screen or hand over to the app. */
    fun edit(ctx: Context, workoutId: Long, setId: Long) = PendingIntent.getActivity(
        ctx, 4, LockEditActivity.intent(ctx, workoutId, setId).setData(android.net.Uri.parse("forge://rest/edit/$workoutId/$setId")),
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
        if (intent.action == DEBUG_BUZZ) {
            if (BuildConfig.DEBUG) RestAlarm.buzzOnce(context, intent.getLongExtra(EXTRA_ENDS, System.currentTimeMillis()))
            return
        }
        handle(context, intent.action ?: return, intent.getLongExtra(EXTRA_WORKOUT, 0), intent.getLongExtra(EXTRA_SET, 0))
    }

    companion object {
        const val LOG = "app.forge.rest.LOG"
        const val PLUS15 = "app.forge.rest.PLUS15"
        const val SKIP = "app.forge.rest.SKIP"
        const val REST_END = "app.forge.rest.END"
        const val DEBUG_BUZZ = "app.forge.rest.DEBUG_BUZZ"
        const val EXTRA_ENDS = "ends"
        const val EXTRA_WORKOUT = "workout"
        const val EXTRA_SET = "set"

        fun handle(ctx: Context, action: String, workoutId: Long, setId: Long, now: Long = System.currentTimeMillis()) {
            val repo = ForgeRepository.get(ctx)
            val before = repo.value
            val after = when (action) {
                LOG -> repo.update { Session.log(it, workoutId, setId, now = now) }
                PLUS15 -> repo.update { d -> if (d.active?.id == workoutId) Session.adjustRest(d, 15, now) else d }
                SKIP -> repo.update { d -> if (d.active?.id == workoutId) Session.skipRest(d) else d }
                REST_END -> {
                    // Writes nothing: the rest stays on disk and reads as Over by the clock. The alarm may fire up
                    // to 3 s early, and a rest that was replaced since has its own alarm, so check the end.
                    val ends = before.active?.restEndsAt ?: return
                    if (ends > now + 3_000) return
                    // buzzOnce re-posts the ongoing notification as Over on the buzz channel. No sync after it:
                    // that would put the notification back on the quiet channel and could cut the vibration short.
                    RestAlarm.buzzOnce(ctx, ends, now)
                    return
                }
                else -> return
            }
            repo.flush()
            RestNotifier.sync(ctx, after, now)
        }
    }
}
