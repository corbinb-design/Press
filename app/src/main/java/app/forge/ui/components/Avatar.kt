package app.forge.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.forge.data.AppData
import app.forge.ui.theme.Forge
import app.forge.ui.theme.TemplateColors
import app.forge.ui.theme.Type
import java.io.File

/** The colour behind the initial: the template you've trained most often (lime if none yet). */
fun avatarColor(data: AppData): Color {
    val counts = data.history.mapNotNull { it.templateId }.groupingBy { it }.eachCount()
    val top = data.templates.maxByOrNull { counts[it.id] ?: 0 }?.takeIf { (counts[it.id] ?: 0) > 0 } ?: data.templates.firstOrNull()
    return top?.colorKey?.let(TemplateColors::of) ?: Forge.Lime
}

/**
 * The saved photo, or the first initial in Instrument Serif on [color].
 */
@Composable
fun Avatar(
    path: String?, name: String, color: Color, size: Dp, modifier: Modifier = Modifier,
    description: String = "Profile photo", action: String? = "Change photo", onClick: (() -> Unit)? = null,
) {
    val bitmap: ImageBitmap? = remember(path, path?.let { File(it).lastModified() }) {
        path?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
    }
    val base = modifier.size(size).clip(CircleShape).then(if (onClick != null) Modifier.tap(label = description, action = action, onClick = onClick) else Modifier)
    if (bitmap != null) {
        Image(bitmap, null, base.border(1.dp, Forge.Border, CircleShape), contentScale = ContentScale.Crop)
    } else Box(base.background(color), contentAlignment = Alignment.Center) {
        // Serif at every size: single initials are an allowed exception to the 24sp serif rule (see Type).
        Text(name.trim().firstOrNull()?.uppercase() ?: "F", style = Type.DisplayM.copy(fontSize = (size.value * 0.5f).sp, color = Forge.OnLime))
    }
}
