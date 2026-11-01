package app.forge

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import app.forge.data.*
import app.forge.ui.screens.crop
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ProfileTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun clean() { app.filesDir.listFiles()?.forEach { it.delete() }; ForgeRepository.reset() }

    /** Left half red, right half blue, 1000×800. */
    private fun halves(): Bitmap = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until width) for (y in 0 until height) setPixel(x, y, if (x < width / 2) Color.RED else Color.BLUE)
    }

    @Test fun cropAtRestTakesTheCentreAndMovingRightShowsTheLeft() {
        val src = halves()
        val radius = 165f
        val scale = 2 * radius / 800 // smallest scale that covers the circle
        val centre = crop(src, IntSize(400, 600), scale, Offset.Zero, radius)
        assertEquals(512, centre.width); assertEquals(512, centre.height)
        assertEquals(Color.RED, centre.getPixel(10, 256)); assertEquals(Color.BLUE, centre.getPixel(500, 256))
        // Dragging the image right moves the circle over its left (red) side.
        val maxX = src.width * scale / 2 - radius
        val left = crop(src, IntSize(400, 600), scale, Offset(maxX, 0f), radius)
        // The boundary moves from x=256 to x=320 of the crop, so pixel 300 flips from blue to red.
        assertEquals(Color.BLUE, centre.getPixel(300, 256))
        assertEquals(Color.RED, left.getPixel(300, 256))
    }

    @Test fun saveAvatarWrites512JpegAndDeletesTheOld() {
        val vm = AppViewModel(app)
        val old = File(app.filesDir, "avatar_old.jpg").apply { writeText("x") }
        vm.updateSettings { it.copy(avatarPath = old.absolutePath) }
        kotlinx.coroutines.runBlocking { vm.saveAvatar(halves()).join() }
        val path = vm.data.value.settings.avatarPath!!
        assertNotEquals(old.absolutePath, path)
        assertFalse(old.exists())
        val saved = BitmapFactory.decodeFile(path)
        assertEquals(512, saved.width); assertEquals(512, saved.height)
        vm.removeAvatar()
        assertNull(vm.data.value.settings.avatarPath)
        assertFalse(File(path).exists())
    }

    @Test fun newSettingsFieldsDefaultForOldFiles() {
        val s = ForgeStore(app.filesDir).decode("""{"history":[],"templates":[],"settings":{"name":"Corbin"},"seeded":true}""").settings
        assertNull(s.bodyweightKg)
        assertTrue(s.notifyRest); assertTrue(s.notifySunday); assertFalse(s.notifyNudges)
    }

    @Test fun lifetimeNumbers() {
        assertEquals("1.21M", Units.abbreviated(1_210_000.0, WeightUnit.KG))
        assertEquals("413k", Units.abbreviated(412_860.0, WeightUnit.KG))
        assertEquals("9,870", Units.abbreviated(9_870.0, WeightUnit.KG))
        assertEquals(8, Xp.level(3_820)); assertEquals(Xp.Tier.IRON, Xp.tier(3_820)); assertEquals(Xp.Tier.STEEL, Xp.tier(4_000))
    }
}
