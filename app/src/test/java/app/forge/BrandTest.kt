package app.forge

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import app.forge.ui.components.Mark
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Forge → Press rename and the new mark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
class BrandTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun launcherLabelIsPress() {
        assertEquals("Press", app.applicationInfo.loadLabel(app.packageManager).toString())
        assertEquals("app.forge.lift", app.packageName) // never changes
    }

    @Test fun oldForgeBackupsStillImport() {
        app.filesDir.listFiles()?.forEach { it.delete() }
        ForgeRepository.reset()
        val vm = AppViewModel(app)
        val old = File(app.cacheDir, "forge-backup-2025-09-01.json").apply {
            writeText("""{"templates":[{"id":1,"name":"Push","exercises":[{"exerciseId":"bench","sets":3,"reps":6}]}],"history":[],"settings":{"name":"Corbin"},"seeded":true}""")
        }
        var result: Result<AppData>? = null
        vm.readBackup(Uri.fromFile(old)) { result = it }
        // The result is delivered on the main thread: keep it running until it arrives.
        val until = System.currentTimeMillis() + 5_000
        while (result == null && System.currentTimeMillis() < until) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(10) }
        assertEquals("Push", result!!.getOrThrow().templates.single().name)
    }

    /** Guards the rename: no string literal shown to people may say Forge (the Forged tier is a lifting word). */
    @Test fun noUserFacingForge() {
        // Whole string literals, matched left to right, so code between two literals is never caught.
        // \bForge\b doesn't match "Forged", so the top tier stays allowed.
        val literal = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val brand = Regex("\\b(Forge|FORGE)\\b")
        val hits = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
                literal.findAll(code).map { it.value }.filter { brand.containsMatchIn(it) }.toList()
                    .takeIf { it.isNotEmpty() }?.let { "${f.name}:${i + 1} $it" }
            }
        }.toList()
        assertTrue("User-facing Forge: $hits", hits.isEmpty())
        val xml = File("src/main/res").walkTopDown().filter { it.extension == "xml" && "values" in it.parent }
            .filter { f -> Regex(">[^<]*\\b(Forge|FORGE)\\b[^<]*<").containsMatchIn(f.readText()) }.toList()
        assertTrue("Forge in resource strings: $xml", xml.isEmpty())
    }

    // ---- Icon check sheet: launcher at real size, themed, notification row, 16dp sharpness ---------------------

    private fun icon(res: Int, px: Int): Bitmap = app.getDrawable(res)!!.toBitmap(px, px)

    /** Renders [res] into a [px]×[px] bitmap, then shows it [zoom]× with no smoothing, to see real pixels. */
    private fun pixels(res: Int, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        app.getDrawable(res)!!.apply { setBounds(0, 0, px, px); draw(Canvas(bmp)) }
        return bmp
    }

    @Test fun iconCheckSheet() {
        val density = app.resources.displayMetrics.density
        val launcherPx = (48 * density).toInt()
        val variants = listOf(
            R.mipmap.ic_launcher to "Lime", R.mipmap.ic_launcher_ink to "Ink", R.mipmap.ic_launcher_paper to "Paper",
            R.mipmap.ic_launcher_ember to "Ember", R.mipmap.ic_launcher_cobalt to "Cobalt", R.mipmap.ic_launcher_foil to "Foil",
        )
        val adaptive = app.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull("themed icons need a monochrome layer", adaptive.monochrome)
        val mono = adaptive.monochrome!!.toBitmap((72 * density).toInt(), (72 * density).toInt())
        val stat16 = pixels(R.drawable.ic_stat_press, 16)
        val stat24 = pixels(R.drawable.ic_stat_press, 24)

        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().background(Forge.Bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("LAUNCHER · 48DP, REAL SIZE", style = Type.Label)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        variants.forEach { (res, name) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Image(icon(res, launcherPx).asImageBitmap(), name, Modifier.size(48.dp))
                                Text(name, style = Type.BodyS.copy(fontSize = 10.sp))
                            }
                        }
                    }
                    Text("THEMED ICON (ANDROID 13+) · MONOCHROME LAYER", style = Type.Label)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        listOf(Color(0xFFD7E8CD) to Color(0xFF2B4A1F), Color(0xFF263328) to Color(0xFFBFD8B4)).forEach { (bg, fg) ->
                            Box(Modifier.size(48.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                                Image(mono.asImageBitmap(), "Themed", Modifier.size(72.dp), colorFilter = ColorFilter.tint(fg))
                            }
                        }
                    }
                    Text("NOTIFICATION SHADE", style = Type.Label)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1F1E1B)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Image(icon(R.drawable.ic_stat_press, (16 * density).toInt()).asImageBitmap(), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(Color(0xFFCCFF00)))
                        Text("Press · Legs · 18:42", style = Type.BodyS.copy(color = Forge.Text))
                    }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF1F1E1B)).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Image(icon(R.drawable.ic_stat_press, (16 * density).toInt()).asImageBitmap(), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(Forge.Text))
                        Text("Press Weekly · now", style = Type.BodyS.copy(color = Forge.Muted))
                    }
                    Text("16 PX AND 24 PX GLYPH, ACTUAL PIXELS ×8 · MARK AT 16DP", style = Type.Label)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.background(Color.Black)) {
                            Image(stat16.asImageBitmap(), null, Modifier.size((16 * 8 / density).dp), filterQuality = FilterQuality.None)
                        }
                        Box(Modifier.background(Color.Black)) {
                            Image(stat24.asImageBitmap(), null, Modifier.size((24 * 8 / density).dp), filterQuality = FilterQuality.None)
                        }
                        Mark(Forge.Text, Forge.Lime, 16.dp)
                        Mark(Forge.Text, Forge.Lime, 24.dp)
                        Mark(Forge.Text, Forge.Lime, 48.dp)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/15_press_icon_check.png")
    }
}
