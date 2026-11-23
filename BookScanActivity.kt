package tr.alihoca.turku.yeni.scan

import android.view.WindowManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Kitap Tarama ekranı: klasör seç -> kitap adıyla içe aktar + temizle -> PDF'e aktar / sil. */
class BookScanActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val cancel = AtomicBoolean(false)
    private lateinit var importer: BookImporter
    private lateinit var btnLoad: Button
    private lateinit var btnCancel: Button
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var list: ListView
    private var books: List<BookInfo> = emptyList()

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) startImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Kitap Tarama"
        importer = BookImporter(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        btnLoad = Button(this).apply { text = "Kitap klasörü yükle ve tara"; setOnClickListener { picker.launch(null) } }
        btnCancel = Button(this).apply {
            text = "İptal"; visibility = android.view.View.GONE
            setOnClickListener { cancel.set(true) }
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = android.view.View.GONE }
        status = TextView(this)
        list = ListView(this)
        root.addView(btnLoad); root.addView(btnCancel); root.addView(bar); root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        list.setOnItemClickListener { _, _, pos, _ -> bookMenu(books[pos]) }
        refresh()
    }

    private fun refresh() {
        books = importer.listBooks()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, books.map { "${it.name}  (${it.pages} sayfa)" })
    }

    private fun setBusy(b: Boolean) {
        btnLoad.isEnabled = !b
        val v = if (b) android.view.View.VISIBLE else android.view.View.GONE
        bar.visibility = v; btnCancel.visibility = v
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startImport(uri: android.net.Uri) {
        cancel.set(false); setBusy(true); status.text = "Hazırlanıyor..."
        io.execute {
            val res = try {
                importer.import(uri, cancel) { d, t, m ->
                    runOnUiThread { bar.max = t; bar.progress = d; status.text = "$m  ($d/$t)" }
                }
            } catch (e: Throwable) { ImportResult("", 0, 0, e.message ?: "Hata") }
            runOnUiThread {
                setBusy(false)
                status.text = res.error ?: "\"${res.bookName}\": ${res.pages} sayfa hazır" +
                    (if (res.failed > 0) ", ${res.failed} dosya/sayfa okunamadı" else "")
                refresh()
            }
        }
    }

    private fun bookMenu(b: BookInfo) {
        AlertDialog.Builder(this).setTitle(b.name)
            .setItems(arrayOf("PDF olarak İndirilenler'e kaydet", "Sil")) { _, which ->
                if (which == 0) exportPdf(b) else { b.dir.deleteRecursively(); refresh() }
            }.show()
    }

    private fun exportPdf(b: BookInfo) {
        setBusy(true); bar.isIndeterminate = true; status.text = "PDF hazırlanıyor..."
        io.execute {
            val ok = try { PdfExporter.exportToDownloads(this, b) != null } catch (e: Throwable) { false }
            runOnUiThread {
                bar.isIndeterminate = false; setBusy(false)
                status.text = if (ok) "PDF İndirilenler klasörüne kaydedildi" else "PDF oluşturulamadı"
                Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
