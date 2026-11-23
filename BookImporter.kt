package tr.alihoca.turku.yeni.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

data class ImportResult(val bookName: String, val pages: Int, val failed: Int, val error: String? = null)
data class BookInfo(val dir: File, val name: String, val pages: Int)

class BookImporter(private val ctx: Context) {

    fun booksDir() = File(ctx.filesDir, "books").apply { mkdirs() }

    fun listBooks(): List<BookInfo> =
        (booksDir().listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
            val n = File(d, "pages").listFiles { f -> f.extension == "jpg" }?.size ?: 0
            if (n == 0) null else BookInfo(d, File(d, "ad.txt").takeIf { it.exists() }?.readText() ?: d.name, n)
        }.sortedBy { it.name.lowercase() }

    /** Seçilen klasör = kitap. Klasör adı kitabın adı olur. Alt klasörler okunmaz. */
    fun import(treeUri: Uri, cancel: AtomicBoolean, onProgress: (done: Int, total: Int, msg: String) -> Unit): ImportResult {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return ImportResult("", 0, 0, "Klasör açılamadı")
        val bookName = root.name ?: "Kitap"
        val items = root.listFiles()
            .filter { it.isFile && ImageSources.isSupported(it.name ?: "") }
            .sortedWith { a, b -> ImageSources.natural(a.name ?: "", b.name ?: "") }
        if (items.isEmpty()) return ImportResult(bookName, 0, 0, "Klasörde desteklenen dosya yok (JPEG, PNG, PDF, TIFF...)")

        // Toplam sayfa sayısı
        var total = 0
        for (it in items) {
            total += if (ImageSources.ext(it.name ?: "") == ImageSources.PDF_EXT) pdfPageCount(it.uri) else 1
        }

        val safe = bookName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Kitap" }
        val bookDir = File(booksDir(), safe)
        val outDir = File(bookDir, "pages")
        outDir.deleteRecursively()
        outDir.mkdirs()
        File(bookDir, "ad.txt").writeText(bookName)

        var done = 0
        var failed = 0
        var index = 1

        fun save(bmp: Bitmap) {
            val out = PageCleaner.process(bmp)
            bmp.recycle()
            FileOutputStream(File(outDir, "sayfa_%04d.jpg".format(index))).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            out.recycle()
            index++
        }

        for (item in items) {
            if (cancel.get()) break
            val name = item.name ?: ""
            if (ImageSources.ext(name) == ImageSources.PDF_EXT) {
                try {
                    ctx.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { r ->
                            for (p in 0 until r.pageCount) {
                                if (cancel.get()) break
                                try { save(ImageSources.renderPdfPage(r, p)) }
                                catch (e: Throwable) { failed++ }
                                done++
                                onProgress(done, total, name)
                            }
                        }
                    }
                } catch (e: Throwable) { failed++ }
            } else {
                try {
                    val bmp = ImageSources.decodeImage(ctx, item.uri, name)
                    if (bmp == null) failed++ else save(bmp)
                } catch (e: Throwable) { failed++ }
                done++
                onProgress(done, total, name)
            }
        }
        return ImportResult(bookName, index - 1, failed)
    }

    private fun pdfPageCount(uri: Uri): Int = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { PdfRenderer(it).use { r -> r.pageCount } } ?: 0
    } catch (e: Throwable) { 0 }
}
