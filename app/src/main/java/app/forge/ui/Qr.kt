package app.forge.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.WriterException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** F: QR codes for shared templates. Drawn here; read from a photo or screenshot, so no camera permission. */
object Qr {
    /** [text] as a black-on-white QR code [size] px square, or null when it's too long to fit in one. */
    fun bitmap(text: String, size: Int = 720): Bitmap? {
        val m = try {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
        } catch (_: WriterException) { return null } catch (_: IllegalArgumentException) { return null }
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    /** The text of a QR code anywhere in [b], or null when there isn't a readable one. */
    fun read(b: Bitmap): String? {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        val source = RGBLuminanceSource(b.width, b.height, px)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        return runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }
            .recoverCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }
            .getOrNull()
    }
}
