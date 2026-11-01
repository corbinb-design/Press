package app.forge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import app.forge.data.TemplateShare
import kotlin.concurrent.thread

/**
 * F: opens a shared `.press` file (from a file manager, WhatsApp, email, Nearby Share…). Reads it and
 * hands the text to the app, which previews it before anything is added. Shows nothing itself.
 */
class ImportTemplateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> intent?.data
        }
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND && uri == null }?.getStringExtra(Intent.EXTRA_TEXT)
        if (uri == null && text == null) { finish(); return }
        thread {
            val read = text ?: runCatching {
                contentResolver.openInputStream(uri!!)!!.use { s ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (out.size() <= TemplateShare.MAX_BYTES) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    out.toByteArray().takeIf { it.size <= TemplateShare.MAX_BYTES }?.decodeToString()
                }
            }.getOrNull()
            runOnUiThread {
                if (read == null) Toast.makeText(this, "Press couldn't read that file.", Toast.LENGTH_LONG).show()
                else startActivity(
                    Intent(this, ForgeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(ForgeActivity.EXTRA_TEMPLATE, read),
                )
                finish()
            }
        }
    }
}
