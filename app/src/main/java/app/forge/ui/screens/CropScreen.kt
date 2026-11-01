package app.forge.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.forge.R
import app.forge.ui.Nav
import app.forge.ui.components.*
import app.forge.ui.theme.Forge
import app.forge.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 14.4: circular move-and-scale crop. Returns a 512×512 square; the avatar draws it as a circle. */
@Composable
fun CropScreen(uri: Uri?, nav: Nav, preview: Bitmap? = null, onCropped: (Bitmap) -> Unit) {
    val ctx = LocalContext.current
    // [preview] skips loading (screenshot tests); otherwise the photo is decoded off the main thread.
    val bitmap by produceState(preview, uri) { if (preview == null && uri != null) value = withContext(Dispatchers.IO) { loadBitmap(ctx, uri) } }
    var stage by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val radius = with(LocalDensity.current) { 165.dp.toPx() } // 330dp circle, as in Figma
    LaunchedEffect(bitmap) { bitmap?.let { scale = minScale(it, radius); offset = Offset.Zero } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TextTopBar("Move and scale", "Cancel", { nav.pop() }, right = "Use", rightEnabled = bitmap != null && scale > 0f) {
            val src = bitmap ?: return@TextTopBar
            onCropped(crop(src, stage, scale, offset, radius))
            nav.pop()
        }
        Spacer(Modifier.height(40.dp))
        Box(
            Modifier.fillMaxWidth().weight(1f).onSizeChanged { stage = it }.pointerInput(bitmap, stage) {
                val src = bitmap ?: return@pointerInput
                val min = minScale(src, radius)
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(min, min * 6)
                    offset = clamp(offset + pan, src, scale, radius)
                }
            },
        ) {
            val src = bitmap
            if (src != null && stage != IntSize.Zero) {
                val image = remember(src) { src.asImageBitmap() }
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2, size.height / 2) + offset
                    val w = src.width * scale; val h = src.height * scale
                    drawImage(
                        image, dstOffset = IntOffset((c.x - w / 2).roundToInt(), (c.y - h / 2).roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()), filterQuality = FilterQuality.High,
                    )
                    // Dim everything outside the circle, then outline it.
                    val hole = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, radius)) }
                    clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
                    drawCircle(Color.White, radius, center, style = Stroke(1.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ForgeIcon(R.drawable.ic_move, 16.dp, Forge.Muted)
            Spacer(Modifier.width(8.dp))
            Text("Pinch to zoom, drag to move", style = Type.BodyS)
        }
    }
}

/** Smallest scale at which the image still covers the circle. */
private fun minScale(src: Bitmap, radius: Float) = 2 * radius / minOf(src.width, src.height)

/** Keeps the circle inside the image. */
private fun clamp(o: Offset, src: Bitmap, scale: Float, radius: Float): Offset {
    val maxX = (src.width * scale / 2 - radius).coerceAtLeast(0f)
    val maxY = (src.height * scale / 2 - radius).coerceAtLeast(0f)
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
}

/** The circle's bounding square in bitmap pixels, scaled to 512×512. */
internal fun crop(src: Bitmap, stage: IntSize, scale: Float, offset: Offset, radius: Float): Bitmap {
    // Circle centre is the stage centre; the image centre is shifted by [offset]. In bitmap pixels the
    // circle centre sits at the bitmap centre minus offset/scale.
    val cx = src.width / 2f - offset.x / scale
    val cy = src.height / 2f - offset.y / scale
    val half = radius / scale
    val side = (2 * half).roundToInt().coerceAtMost(minOf(src.width, src.height)).coerceAtLeast(1)
    val x = (cx - half).roundToInt().coerceIn(0, src.width - side)
    val y = (cy - half).roundToInt().coerceIn(0, src.height - side)
    val square = Bitmap.createBitmap(src, x, y, side, side)
    return Bitmap.createScaledBitmap(square, 512, 512, true)
}

