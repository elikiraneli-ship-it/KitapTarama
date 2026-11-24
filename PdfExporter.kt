package tr.alihoca.turku.yeni.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/** İşlenmiş sayfaları belleği şişirmeden PDF'e dizer; JPG ve metin dosyalarını İndirilenler'e kaydeder. */
object PdfExporter {
    private const val SUBDIR = "KitapOkuyucu"

    fun exportToDownloads(ctx: Context, book: BookInfo): Uri? {
        val pages = File(book.dir, "pages").listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name } ?: return null
        if (pages.isEmpty()) return null
        return saveStream(ctx, safeName(book.name) + ".pdf", "application/pdf") { writePdf(pages, it) }
    }

    fun saveFile(ctx: Context, src: File, displayName: String, mime: String): Uri? =
        saveStream(ctx, safeName(displayName), mime) { out -> src.inputStream().use { it.copyTo(out) } }

    fun saveText(ctx: Context, text: String, displayName: String): Uri? =
        saveStream(ctx, safeName(displayName), "text/plain") { it.write(text.toByteArray(Charsets.UTF_8)) }

    private fun safeName(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun saveStream(ctx: Context, name: String, mime: String, writer: (OutputStream) -> Unit): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { writer(it) }
        cv.clear()
        cv.put(MediaStore.Downloads.IS_PENDING, 0)
        ctx.contentResolver.update(uri, cv, null, null)
        return uri
    }

    private class Out(val os: OutputStream) {
        var n = 0L
        fun w(s: String) = w(s.toByteArray(Charsets.ISO_8859_1))
        fun w(b: ByteArray) { os.write(b); n += b.size }
    }

    private fun writePdf(files: List<File>, os: OutputStream) {
        val o = Out(os)
        val offs = HashMap<Int, Long>()
        val count = files.size
        fun obj(num: Int, body: () -> Unit) {
            offs[num] = o.n
            o.w("$num 0 obj\n"); body(); o.w("endobj\n")
        }
        o.w("%PDF-1.4\n")
        obj(1) { o.w("<< /Type /Catalog /Pages 2 0 R >>\n") }
        obj(2) {
            val kids = (0 until count).joinToString(" ") { "${3 + 3 * it} 0 R" }
            o.w("<< /Type /Pages /Kids [$kids] /Count $count >>\n")
        }
        files.forEachIndexed { i, f ->
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, opt)
            val bytes = f.readBytes()
            val pg = 3 + 3 * i; val ct = pg + 1; val im = pg + 2
            // Döndürülmüş (yatay) sayfalar yatay A4 olur
            val landscape = opt.outWidth > opt.outHeight
            val pw = if (landscape) "841.89" else "595.28"
            val ph = if (landscape) "595.28" else "841.89"
            val cs = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q"
            obj(pg) {
                o.w("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] " +
                    "/Resources << /XObject << /Im0 $im 0 R >> >> /Contents $ct 0 R >>\n")
            }
            obj(ct) { o.w("<< /Length ${cs.length} >>\nstream\n$cs\nendstream\n") }
            obj(im) {
                o.w("<< /Type /XObject /Subtype /Image /Width ${opt.outWidth} /Height ${opt.outHeight} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n")
                o.w(bytes); o.w("\nendstream\n")
            }
        }
        val size = 3 + 3 * count
        val xref = o.n
        o.w("xref\n0 $size\n0000000000 65535 f \n")
        for (n in 1 until size) o.w("%010d 00000 n \n".format(offs[n] ?: 0L))
        o.w("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }
}
