package app.forge.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.forge.R
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import java.io.File

/** Who asked for a photo, and where the result goes. */
class PhotoRequest(val hasPhoto: Boolean, val onPicked: (Bitmap) -> Unit, val onRemove: () -> Unit)

/**
 * 14.3 Profile photo sheet. Camera via TakePicture into a FileProvider URI (no CAMERA permission),
 * gallery via the system photo picker (no storage permission). [onPhoto] receives the chosen image.
 */
@Composable
fun PhotoChooser(request: PhotoRequest?, onDismiss: () -> Unit, onPhoto: (Uri, PhotoRequest) -> Unit) {
    val ctx = LocalContext.current
    // Kept outside the sheet: the sheet closes while the camera or picker is open.
    var active by remember { mutableStateOf<PhotoRequest?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val r = active
        if (uri != null && r != null) onPhoto(uri, r)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val r = active
        val uri = cameraUri?.let(Uri::parse)
        if (ok && uri != null && r != null) onPhoto(uri, r)
    }

    if (request != null) BottomSheet(onDismiss) {
        Text("Profile photo", style = Type.DisplayS, modifier = Modifier.align(Alignment.CenterHorizontally))
        Column {
            SheetRow(R.drawable.ic_camera, "Take photo") {
                active = request
                val uri = newCameraUri(ctx)
                cameraUri = uri.toString()
                onDismiss()
                camera.launch(uri)
            }
            SheetRow(R.drawable.ic_image, "Choose from photos") {
                active = request
                onDismiss()
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (request.hasPhoto) SheetRow(R.drawable.ic_trash, "Remove photo", Forge.Red) { request.onRemove(); onDismiss() }
        }
        GhostButton("Cancel", Modifier.fillMaxWidth(), onClick = onDismiss)
    }
}

@Composable
private fun SheetRow(icon: Int, label: String, color: Color = Forge.Text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ForgeIcon(icon, 20.dp, if (color == Forge.Text) Forge.Muted else color)
        Text(label, style = Type.BodyL.copy(color = color))
    }
    Hairline()
}

internal fun newCameraUri(ctx: Context): Uri {
    val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() } // only ever one pending shot
    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
}

/** Decodes [uri] no larger than [maxDim] on its long side, upright (camera photos carry EXIF rotation). */
fun loadBitmap(ctx: Context, uri: Uri, maxDim: Int = 2048): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    val bmp = ctx.contentResolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val rotation = runCatching {
        ctx.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (rotation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
}.getOrNull()
