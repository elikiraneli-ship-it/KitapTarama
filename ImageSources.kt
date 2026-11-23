package tr.alihoca.turku.yeni.scan

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/**
 * Android BitmapFactory TIFF çözemez. TIFF için bir kütüphane eklenirse (README'ye bakın)
 * buraya bağlanır: TiffDecoder.decode = { ctx, uri -> ... }
 */
object TiffDecoder {
    @Volatile var decode: ((Context, Uri) -> Bitmap?)? = null
}

object ImageSources {
    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "tif", "tiff")
    const val PDF_EXT = "pdf"
    private const val MAX_SIDE = 4200
    const val PDF_RENDER_WIDTH = PageCleaner.A4_W

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isSupported(name: String) = ext(name) in IMAGE_EXT || ext(name) == PDF_EXT

    /** "1 (2)" dosyası "1 (10)" dosyasından önce gelsin diye doğal sıralama. */
    fun natural(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0'); val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    fun decodeImage(ctx: Context, uri: Uri, name: String): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth > 0) {
            var s = 1
            val m = max(bounds.outWidth, bounds.outHeight)
            while (m / s > MAX_SIDE) s *= 2
            val o = BitmapFactory.Options().apply { inSampleSize = s }
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
            return rotateByExif(cr, uri, bmp)
        }
        val e = ext(name)
        if (e == "tif" || e == "tiff") return TiffDecoder.decode?.invoke(ctx, uri)
        return null
    }

    private fun rotateByExif(cr: ContentResolver, uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        val m = Matrix().apply { postRotate(deg) }
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (r !== bmp) bmp.recycle()
        return r
    }

    fun renderPdfPage(r: PdfRenderer, index: Int): Bitmap {
        r.openPage(index).use { page ->
            val scale = PDF_RENDER_WIDTH / page.width.toFloat()
            val bmp = Bitmap.createBitmap(PDF_RENDER_WIDTH, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }
}
