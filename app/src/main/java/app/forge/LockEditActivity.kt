package app.forge

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.forge.data.Session
import app.forge.ui.screens.LockEditScreen

/**
 * B: the notification's "Edit", over the lock screen without unlocking. A compact stepper for the next
 * set only (weight ±, reps ±, Log set); nothing else in Press can be reached from here, and "Open Press"
 * asks to unlock first. When the phone isn't locked, when "Log sets from the lock screen" is off, or when
 * the set isn't a weight-and-reps one, it hands straight over to the app's own 4.2 stepper (unlocking
 * first if needed), as Edit always did.
 */
class LockEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#0E0D0B")))
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        val workoutId = intent.getLongExtra(EXTRA_WORKOUT, 0)
        val setId = intent.getLongExtra(EXTRA_SET, 0)
        val d = ForgeRepository.get(this).value
        val next = Session.next(d)?.takeIf { d.active?.id == workoutId && it.setEntry.id == setId }
        if (!locked(this) || !d.settings.logFromLockScreen || next == null || !next.entry.tracking.usesReps) {
            openApp(setId.takeIf { next != null })
            return
        }
        setContent {
            LockEditScreen(d, next, onLog = { kg, reps -> log(workoutId, setId, kg, reps) }, onOpenApp = { openApp(setId) }, onClose = ::finish)
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    /** Same path as the notification's Log: saved straight away, notification refreshed, rest started. */
    private fun log(workoutId: Long, setId: Long, kg: Double?, reps: Int) {
        val repo = ForgeRepository.get(this)
        val now = System.currentTimeMillis()
        val after = repo.update { Session.logSetAs(it, workoutId, setId, kg, reps, now) }
        repo.flush()
        getSystemService(android.app.NotificationManager::class.java)?.cancel(RestNotifier.REST_UP_ID)
        RestNotifier.sync(this, after, now)
        finish()
    }

    /** The app's own stepper for [setId] (or just the app); locked, the phone asks to unlock first. */
    private fun openApp(setId: Long?) {
        val open = {
            startActivity(Intent(this, ForgeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (setId != null) putExtra(ForgeActivity.EXTRA_EDIT_SET, setId) })
            finish()
        }
        if (!locked(this)) { open(); return }
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = open()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    companion object {
        const val EXTRA_WORKOUT = "workout"
        const val EXTRA_SET = "set"

        private fun locked(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

        fun intent(ctx: Context, workoutId: Long, setId: Long): Intent = Intent(ctx, LockEditActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_WORKOUT, workoutId).putExtra(EXTRA_SET, setId)
    }
}
