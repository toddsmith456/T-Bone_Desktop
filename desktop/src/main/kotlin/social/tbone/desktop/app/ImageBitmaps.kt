package social.tbone.desktop.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Image decoding for the desktop port.
 *
 * Upstream used Android's BitmapFactory + Coil. On the JVM the decode is ImageIO,
 * and Compose Desktop ships one public image factory: `loadImageBitmap` (which
 * wraps Skia's decoder). So:
 *
 *  - full-size decode goes straight through `loadImageBitmap` (proper colour
 *    handling, no manual pixel surgery);
 *  - "low quality" mode (upstream's LOW image setting) downscales with ImageIO
 *    first and then re-encodes to PNG for the same decoder, so a 512 px box is
 *    genuinely cheaper to hold in memory rather than just visually smaller.
 */
object ImageBitmaps {

    fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        loadImageBitmap(ByteArrayInputStream(bytes))
    }.getOrNull()

    fun decodeDownscaled(bytes: ByteArray, maxDim: Int): ImageBitmap? = runCatching {
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val scale = minOf(1.0, maxDim.toDouble() / maxOf(src.width, src.height))
        if (scale >= 1.0) return loadImageBitmap(ByteArrayInputStream(bytes))
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())
        val small = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply {
            setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(small, "png", out)
        loadImageBitmap(ByteArrayInputStream(out.toByteArray()))
    }.getOrNull()
}
