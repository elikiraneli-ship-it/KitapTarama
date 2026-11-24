package tr.alihoca.turku.yeni.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ImportResult(
    val bookName: String,
    val pages: Int,
    val failed: Int,
    val error: String? = null,
    val report: List<String> = emptyList()
)
class BookInfo(val dir: File, val name: String, val pages: Int)

class BookImporter(private val ctx: Context) {

    private class Src(val name: String, val uri: Uri)

    fun booksDir() = File(ctx.filesDir, "books").apply { mkdirs() }

    fun listBooks(): List<BookInfo> =
        (booksDir().listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
            val n = File(d, "pages").listFiles { f -> f.extension == "jpg" }?.size ?: 0
            if (n == 0) null else BookInfo(d, File(d, "ad.txt").takeIf { it.exists() }?.readText() ?: d.name, n)
        }.sortedBy { it.name.lowercase() }

    /** Seçilen klasör = kitap. Klasör adı kitabın adı olur. Alt klasörler okunmaz. */
    fun import(treeUri: Uri, cancel: AtomicBoolean, onProgress: (done: Int, total: Int, msg: String) -> Unit): ImportResult {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return ImportResult("", 0, 0, "Klasör açılamadı")
        val name = root.name ?: "Kitap"
        val srcs = root.listFiles()
            .filter { it.isFile && ImageSources.isSupported(it.name ?: "") }
            .sortedWith { a, b -> ImageSources.natural(a.name ?: "", b.name ?: "") }
            .map { Src(it.name ?: "dosya", it.uri) }
        return run(name, srcs, cancel, onProgress)
    }

    /** Tek tek seçilen JPEG/PNG/TIFF/PDF dosyaları. Kitap adı ilk dosyanın adıdır. */
    fun importFiles(uris: List<Uri>, cancel: AtomicBoolean, onProgress: (done: Int, total: Int, msg: String) -> Unit): ImportResult {
        val srcs = uris.map { Src(displayName(it), it) }
            .sortedWith { a, b -> ImageSources.natural(a.name, b.name) }
        val name = srcs.firstOrNull()?.name?.substringBeforeLast('.') ?: "Kitap"
        return run(name, srcs, cancel, onProgress)
    }

    private fun displayName(uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: "dosya"

    private fun run(bookName: String, srcs: List<Src>, cancel: AtomicBoolean, onProgress: (Int, Int, String) -> Unit): ImportResult {
        if (srcs.isEmpty()) return ImportResult(bookName, 0, 0, "Desteklenen dosya yok (JPEG, PNG, PDF, TIFF...)")
        val safe = bookName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Kitap" }
        val bookDir = File(booksDir(), safe)
        val outDir = File(bookDir, "pages")
        outDir.deleteRecursively()
        outDir.mkdirs()
        File(bookDir, "ad.txt").writeText(bookName)
        val job = Job(outDir, cancel, onProgress, srcs.size)
        for (s in srcs) {
            if (cancel.get()) break
            val before = job.done
            try { job.process(s) } catch (t: Throwable) { job.failed++ }
            if (job.done == before) { job.done++; onProgress(job.done, job.total, s.name) }
        }
        return ImportResult(bookName, job.index - 1, job.failed, null, job.report)
    }

    private inner class Job(
        val outDir: File,
        val cancel: AtomicBoolean,
        val onProgress: (Int, Int, String) -> Unit,
        var total: Int
    ) {
        var done = 0
        var failed = 0
        var index = 1
        val report = ArrayList<String>()

        fun save(bmp: Bitmap) {
            val out = PageCleaner.process(bmp)
            bmp.recycle()
            FileOutputStream(File(outDir, "sayfa_%04d.jpg".format(index))).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            out.recycle()
            index++
        }

        fun fixes(name: String, r: Repaired) { if (r.changed) report.add(name + ": " + r.log.joinToString("; ")) }

        fun process(s: Src) {
            if (ImageSources.ext(s.name) == ImageSources.PDF_EXT) { pdf(s.name, s.uri, null); return }
            val bytes = ctx.contentResolver.openInputStream(s.uri)?.use { it.readBytes() }
            if (bytes == null) { failed++; report.add("${s.name}: dosya okunamadı"); return }
            when (FileRepair.detect(bytes, s.name)) {
                "pdf" -> pdf(s.name, null, bytes)
                "tif" -> tiff(s.name, bytes)
                "jpg" -> {
                    val r = FileRepair.repairJpeg(bytes); fixes(s.name, r)
                    image(s.name, r.bytes)
                }
                "png", "other" -> image(s.name, bytes)
                else -> { failed++; report.add("${s.name}: desteklenmeyen biçim") }
            }
        }

        fun image(name: String, data: ByteArray) {
            val bmp = ImageSources.decodeBytes(data)
            if (bmp == null) { failed++; report.add("$name: görsel çözülemedi") } else save(bmp)
            done++
            onProgress(done, total, name)
        }

        fun tiff(name: String, bytes: ByteArray) {
            val rep = FileRepair.repairTiff(bytes); fixes(name, rep)
            val r = TiffReader(rep.bytes)
            val n = r.pageCount
            if (n == 0) { failed++; report.add("$name: TIFF sayfası bulunamadı"); return }
            total += n - 1
            var err = false
            for (i in 0 until n) {
                if (cancel.get()) break
                try { save(r.decodePage(i)) }
                catch (t: Throwable) { failed++; if (!err) { err = true; report.add("$name: sayfa ${i + 1}: ${t.message}") } }
                done++
                onProgress(done, total, name)
            }
        }

        private fun cacheFile(bytes: ByteArray): ParcelFileDescriptor {
            val f = File(ctx.cacheDir, "onar.pdf")
            f.writeBytes(bytes)
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        private fun tryOpen(pfd: ParcelFileDescriptor): PdfRenderer? =
            try { PdfRenderer(pfd) } catch (t: Throwable) { null }

        /** Önce olduğu gibi açar; olmazsa başlık/sonluk onarır; olmazsa xref'i yeniden kurar. */
        fun pdf(name: String, uri: Uri?, raw: ByteArray?) {
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            try {
                pfd = if (uri != null) ctx.contentResolver.openFileDescriptor(uri, "r") else cacheFile(raw!!)
                renderer = pfd?.let { tryOpen(it) }
            } catch (t: Throwable) { renderer = null }
            if (renderer == null) {
                pfd?.close()
                val bytes = raw ?: uri?.let { ctx.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } }
                if (bytes == null) { failed++; report.add("$name: dosya okunamadı"); return }
                val rep = FileRepair.repairPdf(bytes)
                val notes = ArrayList(rep.log)
                var data = rep.bytes
                pfd = cacheFile(data)
                renderer = tryOpen(pfd)
                if (renderer == null) {
                    pfd.close()
                    data = FileRepair.rebuildPdfXref(data)
                    notes.add("xref tablosu yeniden kuruldu")
                    pfd = cacheFile(data)
                    renderer = tryOpen(pfd)
                }
                if (renderer == null) {
                    pfd.close(); failed++
                    report.add("$name: PDF onarılamadı (nesne/xref akışlı olabilir)")
                    return
                }
                report.add(name + ": " + notes.joinToString("; ").ifEmpty { "PDF onarılarak açıldı" })
            }
            val rr: PdfRenderer = renderer ?: return
            try {
                val n = rr.pageCount
                total += n - 1
                for (p in 0 until n) {
                    if (cancel.get()) break
                    try { save(ImageSources.renderPdfPage(rr, p)) } catch (t: Throwable) { failed++ }
                    done++
                    onProgress(done, total, name)
                }
            } finally {
                rr.close(); pfd?.close()
            }
        }
    }
}
