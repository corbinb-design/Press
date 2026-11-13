package app.forge

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.forge.ui.ForgeApp
import app.forge.ui.components.Messages

class ForgeActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.parseColor("#0E0D0B")),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        SundayIssue.schedule(this)
        setContent { ForgeApp(vm) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    /** 9.3 "Edit": open the 4.2 stepper for the set the notification showed. */
    private fun handle(intent: Intent?) {
        intent?.getLongExtra(EXTRA_EDIT_SET, 0L)?.takeIf { it != 0L }?.let(vm::requestEdit)
        // 12.1: the Sunday notification opens its issue.
        intent?.getStringExtra(SundayIssue.EXTRA_ISSUE)?.let(vm::requestIssue)
        // F: a shared template opened from outside, previewed before it's added.
        intent?.getStringExtra(EXTRA_TEMPLATE)?.let(vm::requestTemplateImport)
        intent?.getStringExtra(EXTRA_MESSAGE)?.let(Messages::show)
    }

    // Context layer: a fresh Health Connect read on each open, so readiness and the streak see the latest outings.
    override fun onStart() { super.onStart(); vm.refreshStreak(); vm.refreshHealth(); vm.runAutoBackup() }
    // Save straight away when leaving, so a process killed in the background loses nothing.
    override fun onStop() { super.onStop(); vm.flush() }

    companion object {
        const val EXTRA_EDIT_SET = "edit_set"
        const val EXTRA_TEMPLATE = "template_text"
        const val EXTRA_MESSAGE = "message"
    }
}
