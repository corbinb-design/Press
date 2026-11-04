package app.forge.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import app.forge.Brand
import app.forge.R
import app.forge.ui.components.PressMark
import app.forge.data.Issues
import app.forge.data.Units
import app.forge.data.WeightUnit
import app.forge.ui.theme.Forge
import app.forge.ui.theme.TemplateColors
import java.io.File

/**
 * 12.3 shareable posters: story-sized (1080×1920), Ink or Paper edition. Typeset like a book cover:
 * big serif headline, the week's spines as the illustration, mono details. Drawn with
 * android.graphics so it needs no screenshot of the app.
 */
object Posters {
    const val W = 1080
    const val H = 1920

    fun render(ctx: Context, issue: Issues.Issue, name: String, unit: WeightUnit, paper: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Figma's poster is 340pt wide; everything below is in those units.
        val s = W / 340f
        val bg = (if (paper) Forge.Paper else Forge.Bg).toArgb()
        val ink = if (paper) Forge.Bg.toArgb() else Forge.Text.toArgb()
        val muted = if (paper) 0xFF6B665C.toInt() else Forge.Muted.toArgb()
        val accent = if (paper) Forge.Bg.toArgb() else Forge.Lime.toArgb()
        c.drawColor(bg)

        val serif = font(ctx, R.font.instrument_serif) ?: Typeface.SERIF
        val italic = font(ctx, R.font.instrument_serif_italic) ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        val mono = font(ctx, R.font.dm_mono_500) ?: Typeface.MONOSPACE
        fun paint(tf: Typeface, size: Float, color: Int, spacing: Float = 0f) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size * s; this.color = color; letterSpacing = spacing
        }
        val pad = 26 * s

        // Masthead.
        val label = paint(mono, 7.5f, ink, 0.08f)
        c.drawText("${Brand.NAME.uppercase()} WEEKLY", pad, 34 * s, label)
        val no = "NO. ${issue.number}"
        c.drawText(no, W - pad - label.measureText(no), 34 * s, paint(mono, 7.5f, muted, 0.08f))

        // Headline and standfirst, wrapped.
        var y = 62 * s
        y += layout(c, issue.headline, paint(serif, 54f, ink, -0.01f), pad, y, W - 2 * pad - 60 * s, spacingMult = 0.86f)
        y += 14 * s
        layout(c, issue.standfirst.replaceFirst("Your best", "Best"), paint(italic, 19f, accent), pad, y, W - 2 * pad, spacingMult = 1.1f)

        // The week as spines, standing on a hairline. A week with outside context makes room for its lines below.
        val context = issue.outside != null || issue.movement != null
        val base = (if (context) 385 else 455) * s
        val top = (if (context) 265 else 300) * s
        val maxVol = issue.spines.maxOfOrNull { it.volume }?.takeIf { it > 0 } ?: 1.0
        val count = issue.spines.size.coerceAtLeast(1)
        val gap = 5 * s
        val sw = (W - 2 * pad - gap * (count - 1)) / count
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        issue.spines.forEachIndexed { i, sp ->
            val h = ((sp.volume / maxVol) * (base - top)).toFloat().coerceAtLeast(30 * s)
            val x = pad + i * (sw + gap)
            if (sp.cardioMinutes != null) {
                // Cardio: a thin ice spine sized by minutes.
                val ch = (sp.cardioMinutes / 80f).coerceIn(0.2f, 1f) * (base - top)
                fill.color = Forge.Ice.toArgb()
                c.drawRect(RectF(x + sw * 0.4f, base - ch, x + sw * 0.6f, base), fill)
                return@forEachIndexed
            }
            fill.color = (sp.colorKey?.let { TemplateColors.of(it) } ?: Forge.Surface2).toArgb()
            c.drawRect(RectF(x, base - h, x + sw, base), fill)
            if (sp.record) { fill.color = Forge.Lime.toArgb(); c.drawCircle(x + sw / 2, base - h - 9 * s, 3.2f * s, fill) }
        }
        fill.color = muted
        c.drawRect(pad, base, W - pad, base + 0.75f * s, fill)

        // Context layer: the same outside and everyday-movement lines as the issue in the app.
        val outsideInk = if (paper) ink else Forge.Ice.toArgb()
        var cy = base + 20 * s
        issue.outside?.let { line ->
            c.drawText((issue.outsideKinds ?: "Outside").uppercase(), pad, cy, paint(mono, 5.5f, muted, 0.08f))
            cy += 5 * s
            cy += layout(c, line, paint(mono, 8.5f, outsideInk), pad, cy, W - 2 * pad, spacingMult = 1.1f) + 10 * s
        }
        issue.movement?.let { line ->
            c.drawText("EVERYDAY MOVEMENT", pad, cy, paint(mono, 5.5f, muted, 0.08f))
            cy += 5 * s
            layout(c, line, paint(italic, 13f, ink), pad, cy, W - 2 * pad, spacingMult = 1.0f)
        }

        // Three numbers: Streak · Gym · Outside for a week with time outside (as in the app), else Lifted · Records · Streak.
        val stats = if (issue.outside != null) listOf(
            Triple("STREAK", "${issue.streakWeeks} WK", accent),
            Triple("GYM", "${issue.gym}", ink),
            Triple("OUTSIDE", "${issue.outsideDays} ${if (issue.outsideDays == 1) "DAY" else "DAYS"}", outsideInk),
        ) else listOf(
            Triple("LIFTED", "${Units.grouped(issue.lifted, unit)} ${unit.label.uppercase()}", ink),
            Triple("RECORDS", "${issue.records}", ink),
            Triple("STREAK", "${issue.streakWeeks} WK", ink),
        )
        val colW = (W - 2 * pad) / 3
        stats.forEachIndexed { i, (k, v, color) ->
            val x = pad + i * colW
            c.drawText(k, x, 498 * s, paint(mono, 5.5f, muted, 0.08f))
            c.drawText(v, x, 516 * s, paint(mono, 10f, color))
        }

        // Signature, and the app in full with its mark (bar and plate in ink, newest plate lime).
        c.drawText(name.ifBlank { Brand.NAME }, pad, 572 * s, paint(serif, 19f, ink))
        val credit = paint(mono, 7f, ink, 0.06f)
        val text = Brand.FULL.uppercase()
        val tw = credit.measureText(text)
        c.drawText(text, W - pad - tw, 569.5f * s, credit)
        val markSize = 18 * s
        drawMark(c, W - pad - tw - 8 * s - markSize, 560.5f * s - markSize / 2 + 4 * s, markSize, ink, if (paper) ink else Forge.Lime.toArgb())
        return bmp
    }

    /** The Press mark from [PressMark]'s rects, with its 96 grid at [size] px and top-left at ([x], [y]). */
    private fun drawMark(c: Canvas, x: Float, y: Float, size: Float, fg: Int, accent: Int) {
        val k = size / PressMark.GRID
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        PressMark.rects.forEach { r ->
            p.color = if (r.accent) accent else fg
            c.drawRoundRect(RectF(x + r.x * k, y + r.y * k, x + (r.x + r.w) * k, y + (r.y + r.h) * k), r.r * k, r.r * k, p)
        }
    }

    /** Draws wrapped text with its top at [y]; returns its height. */
    private fun layout(c: Canvas, text: String, p: TextPaint, x: Float, y: Float, width: Float, spacingMult: Float): Float {
        val l = StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, spacingMult).setIncludePad(true).build()
        c.save(); c.translate(x, y); l.draw(c); c.restore()
        return l.height.toFloat()
    }

    private fun font(ctx: Context, id: Int): Typeface? = runCatching { ResourcesCompat.getFont(ctx, id) }.getOrNull()

    /** Saves [bmp] to the cache and opens the Android share sheet for it. */
    fun share(ctx: Context, bmp: Bitmap, fileName: String) {
        val dir = File(ctx.cacheDir, "posters").apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share this issue").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
