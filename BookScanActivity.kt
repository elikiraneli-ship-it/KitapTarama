package tr.alihoca.turku.yeni.scan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Kitap Tarama: klasör/dosya yükle (JPEG, PNG, TIFF, PDF; bozuklar onarılır) -> temizle -> Oku.
 * Okuyucuda Normal Mod (üst: Ana sayfa, belge adı, Büyük Okuma, İşlemler; alt: Sayfa X / Y)
 * ve Büyük Okuma Modu (bar yok, kaydırarak sayfa çevirme, iki parmakla yakınlaştırma).
 */
class BookScanActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private val pageExec = Executors.newSingleThreadExecutor()
    private val cancel = AtomicBoolean(false)
    private lateinit var importer: BookImporter

    private lateinit var frame: FrameLayout
    private var libBg = Color.WHITE

    // Kitaplık ekranı
    private lateinit var library: LinearLayout
    private lateinit var btnLoad: Button
    private lateinit var btnFiles: Button
    private lateinit var btnCancel: Button
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var list: ListView
    private var books: List<BookInfo> = emptyList()

    // Okuyucu ekranı
    private lateinit var reader: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var pageView: PageView
    private lateinit var pageInput: EditText
    private lateinit var totalTv: TextView
    private lateinit var exitBtn: Button
    private var curBook: BookInfo? = null
    private val files = ArrayList<File>()
    private var pos = 0
    private var big = false
    private var loadToken = 0

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) startImport { c, p -> importer.import(uri, c, p) }
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) startImport { c, p -> importer.importFiles(uris, c, p) }
    }
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (big) setBig(false) else { showLibrary(); refresh() }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Kitap Tarama"
        supportActionBar?.hide()
        importer = BookImporter(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
        libBg = tv.data
        frame = FrameLayout(this)
        buildLibrary()
        buildReader()
        frame.addView(library, FrameLayout.LayoutParams(-1, -1))
        frame.addView(reader, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        // Kenardan kenara çizimde durum/gezinme çubuğu ve çentik altında kalmasın
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, back)
        showLibrary()
        refresh()
    }

    // ------------------------------------------------------------------ Kitaplık

    private fun buildLibrary() {
        val pad = dp(16)
        library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        btnLoad = Button(this).apply {
            text = "Kitap klasörü yükle ve tara"; minHeight = dp(56)
            setOnClickListener { picker.launch(null) }
        }
        btnFiles = Button(this).apply {
            text = "Dosya seç: JPEG / TIFF / PDF"; minHeight = dp(52)
            setOnClickListener { pickFiles.launch(arrayOf("*/*")) }   // bozuk dosyaların MIME'ı yanlış olabilir
        }
        btnCancel = Button(this).apply {
            text = "İptal"; minHeight = dp(52); visibility = View.GONE
            setOnClickListener { cancel.set(true) }
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { visibility = View.GONE }
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        list = ListView(this)
        library.addView(btnLoad); library.addView(btnFiles); library.addView(btnCancel); library.addView(bar); library.addView(status)
        library.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.setOnItemClickListener { _, _, p, _ -> bookMenu(books[p]) }
    }

    private fun refresh() {
        books = importer.listBooks()
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, books.map { "${it.name}  (${it.pages} sayfa)" })
    }

    private fun setBusy(b: Boolean) {
        btnLoad.isEnabled = !b; btnFiles.isEnabled = !b
        val v = if (b) View.VISIBLE else View.GONE
        bar.visibility = v; btnCancel.visibility = v
        if (b) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startImport(job: (AtomicBoolean, (Int, Int, String) -> Unit) -> ImportResult) {
        cancel.set(false); setBusy(true); status.text = "Hazırlanıyor..."
        io.execute {
            val res = try {
                job(cancel) { d, t, m -> runOnUiThread { bar.max = t; bar.progress = d; status.text = "$m  ($d/$t)" } }
            } catch (e: Throwable) { ImportResult("", 0, 0, e.message ?: "Hata") }
            runOnUiThread {
                setBusy(false)
                status.text = res.error ?: "\"${res.bookName}\": ${res.pages} sayfa hazır" +
                    (if (res.failed > 0) ", ${res.failed} dosya/sayfa okunamadı" else "")
                refresh()
                if (res.report.isNotEmpty()) showReport(res.report)
            }
        }
    }

    private fun showReport(lines: List<String>) {
        AlertDialog.Builder(this).setTitle("Onarım raporu")
            .setMessage(lines.joinToString("\n\n")).setPositiveButton("Tamam", null).show()
    }

    private fun bookMenu(b: BookInfo) {
        AlertDialog.Builder(this).setTitle(b.name)
            .setItems(arrayOf("Oku", "PDF olarak İndirilenler'e kaydet", "Sil")) { _, which ->
                when (which) {
                    0 -> openBook(b)
                    1 -> exportPdf(b)
                    else -> { b.dir.deleteRecursively(); refresh() }
                }
            }.show()
    }

    private fun exportPdf(b: BookInfo) {
        setBusy(true); bar.isIndeterminate = true; status.text = "PDF hazırlanıyor..."
        io.execute {
            val ok = try { PdfExporter.exportToDownloads(this, b) != null } catch (e: Throwable) { false }
            runOnUiThread {
                bar.isIndeterminate = false; setBusy(false)
                status.text = if (ok) "PDF İndirilenler/KitapOkuyucu klasörüne kaydedildi" else "PDF oluşturulamadı"
                Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLibrary() {
        setBig(false)
        reader.visibility = View.GONE
        library.visibility = View.VISIBLE
        frame.setBackgroundColor(libBg)
        back.isEnabled = false
        curBook = null
    }

    // ------------------------------------------------------------------ Okuyucu

    private fun smallBtn(text: String, action: (View) -> Unit) = Button(this).apply {
        this.text = text; textSize = 11f; setAllCaps(false)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { action(it) }
    }

    private fun buildReader() {
        reader = FrameLayout(this).apply { setBackgroundColor(0xFF0E1A14.toInt()); visibility = View.GONE }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        titleTv = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }
        topBar.addView(smallBtn("⌂ Ana sayfa") { showLibrary(); refresh() })
        topBar.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))
        topBar.addView(smallBtn("📖 Büyük Okuma") { setBig(true) })
        topBar.addView(smallBtn("☰ İşlemler ▾") { showMenu(it) })

        pageView = PageView(this).apply { onSwipe = { d -> go(pos + d) } }

        bottomBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        pageInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setSingleLine()
            setOnEditorActionListener { v, _, _ ->
                v.text.toString().toIntOrNull()?.let { go(it - 1) }
                v.clearFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                true
            }
        }
        totalTv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(6), 0, dp(10), 0) }
        bottomBar.addView(smallBtn("‹") { go(pos - 1) }.apply { textSize = 18f })
        bottomBar.addView(TextView(this).apply { text = "Sayfa "; setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(10), 0, dp(4), 0) })
        bottomBar.addView(pageInput, LinearLayout.LayoutParams(dp(64), -2))
        bottomBar.addView(totalTv)
        bottomBar.addView(smallBtn("›") { go(pos + 1) }.apply { textSize = 18f })

        col.addView(topBar); col.addView(pageView, LinearLayout.LayoutParams(-1, 0, 1f)); col.addView(bottomBar)
        exitBtn = smallBtn("✕ Normal Mod") { setBig(false) }.apply { visibility = View.GONE }
        reader.addView(col, FrameLayout.LayoutParams(-1, -1))
        reader.addView(exitBtn, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
    }

    private fun openBook(b: BookInfo) {
        val fs = File(b.dir, "pages").listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
        if (fs.isEmpty()) { Toast.makeText(this, "Sayfa yok", Toast.LENGTH_SHORT).show(); return }
        curBook = b
        files.clear(); files.addAll(fs)
        pos = 0
        titleTv.text = b.name
        library.visibility = View.GONE
        reader.visibility = View.VISIBLE
        frame.setBackgroundColor(Color.BLACK)
        back.isEnabled = true
        loadPage()
    }

    private fun go(i: Int) {
        if (i < 0 || i >= files.size || i == pos) {
            pageInput.setText((pos + 1).toString()); return
        }
        pos = i
        loadPage()
    }

    private fun decodeForView(f: File, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        val m = max(o.outWidth, o.outHeight)
        while (m / s > maxSide) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun loadPage() {
        val f = files.getOrNull(pos) ?: return
        pageInput.setText((pos + 1).toString())
        totalTv.text = "/ ${files.size}"
        val tok = ++loadToken
        pageExec.execute {
            val bmp = try { decodeForView(f, 3600) } catch (t: Throwable) { null }
            runOnUiThread {
                if (tok == loadToken && bmp != null) {
                    val old = pageView.bmp
                    pageView.bmp = bmp
                    if (old != null && old !== bmp) old.recycle()
                } else bmp?.recycle()
            }
        }
    }

    /** Büyük Okuma: üst/alt barlar ve sistem çubukları gizlenir; sağ üstte yalnızca "Normal Mod" düğmesi kalır. */
    private fun setBig(on: Boolean) {
        big = on
        val v = if (on) View.GONE else View.VISIBLE
        topBar.visibility = v
        bottomBar.visibility = v
        exitBtn.visibility = if (on) View.VISIBLE else View.GONE
        val c: WindowInsetsControllerCompat? = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c?.hide(WindowInsetsCompat.Type.systemBars())
        } else c?.show(WindowInsetsCompat.Type.systemBars())
        pageView.resetZoom()
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "↻ Sayfayı döndür")
        pm.menu.add(0, 2, 1, "🗑 Sayfayı sil")
        pm.menu.add(0, 3, 2, "Metin tanıma (OCR)")
        pm.menu.add(0, 4, 3, "Bu sayfayı JPG kaydet")
        pm.menu.add(0, 5, 4, "Kitabı PDF kaydet")
        pm.menu.add(0, 6, 5, "Yakınlaştırmayı sıfırla")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> rotatePage()
                2 -> deletePage()
                3 -> ocrPage()
                4 -> saveJpg()
                5 -> curBook?.let { b -> toastAsync("PDF hazırlanıyor...") { PdfExporter.exportToDownloads(this, b) != null } }
                6 -> pageView.resetZoom()
            }
            true
        }
        pm.show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun toastAsync(start: String, work: () -> Boolean) {
        toast(start)
        io.execute {
            val ok = try { work() } catch (e: Throwable) { false }
            runOnUiThread { toast(if (ok) "İndirilenler/KitapOkuyucu klasörüne kaydedildi" else "Kaydedilemedi") }
        }
    }

    private fun rotatePage() {
        val f = files.getOrNull(pos) ?: return
        pageExec.execute {
            try {
                val b = BitmapFactory.decodeFile(f.path) ?: throw IllegalStateException()
                val r = PageCleaner.rotate90(b)
                b.recycle()
                FileOutputStream(f).use { r.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                r.recycle()
                runOnUiThread { loadPage() }
            } catch (t: Throwable) { runOnUiThread { toast("Döndürülemedi") } }
        }
    }

    private fun deletePage() {
        val f = files.getOrNull(pos) ?: return
        AlertDialog.Builder(this).setTitle("Sayfa ${pos + 1} silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                f.delete()
                files.removeAt(pos)
                if (files.isEmpty()) { toast("Kitapta sayfa kalmadı"); curBook?.dir?.deleteRecursively(); showLibrary(); refresh() }
                else { pos = min(pos, files.size - 1); loadPage() }
            }.setNegativeButton("Vazgeç", null).show()
    }

    private fun saveJpg() {
        val f = files.getOrNull(pos) ?: return
        val name = (curBook?.name ?: "Kitap") + "_sayfa_${pos + 1}.jpg"
        toastAsync("Kaydediliyor...") { PdfExporter.saveFile(this, f, name, "image/jpeg") != null }
    }

    private fun ocrPage() {
        val f = files.getOrNull(pos) ?: return
        if (Ocr.engine == null) { toast("Metin tanıma (OCR) bu sürümde kapalı: ek bileşen gerekir"); return }
        toast("Metin tanınıyor...")
        pageExec.execute {
            val bmp = try { decodeForView(f, 3000) } catch (t: Throwable) { null }
            runOnUiThread {
                if (bmp == null) toast("Sayfa okunamadı")
                else Ocr.recognize(bmp, { text -> bmp.recycle(); showOcr(text) }, { e -> bmp.recycle(); toast("OCR hatası: $e") })
            }
        }
    }

    private fun showOcr(text: String) {
        val tv = TextView(this).apply { this.text = text.ifBlank { "(Metin bulunamadı)" }; setTextIsSelectable(true); textSize = 15f; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val sv = ScrollView(this).apply { addView(tv) }
        val name = (curBook?.name ?: "Kitap") + "_sayfa_${pos + 1}.txt"
        AlertDialog.Builder(this).setTitle("Metin (OCR)").setView(sv)
            .setPositiveButton("Kopyala") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OCR", text))
                toast("Kopyalandı")
            }
            .setNeutralButton("TXT kaydet") { _, _ -> toastAsync("Kaydediliyor...") { PdfExporter.saveText(this, text, name) != null } }
            .setNegativeButton("Kapat", null).show()
    }

    override fun onDestroy() {
        pageView.bmp = null
        super.onDestroy()
    }
}

/**
 * OCR için ek bağımlılık gerekmez: motor varsayılan olarak kapalıdır (null).
 * İleride bir OCR kütüphanesi eklenirse yalnızca `Ocr.engine = { bmp, ok, err -> ... }` atanır.
 */
object Ocr {
    var engine: ((Bitmap, (String) -> Unit, (String) -> Unit) -> Unit)? = null

    fun recognize(bmp: Bitmap, onOk: (String) -> Unit, onErr: (String) -> Unit) {
        val e = engine
        if (e == null) onErr("OCR bileşeni bu sürümde yok") else e(bmp, onOk, onErr)
    }
}

/** Tek sayfa görüntüleyici: iki parmakla yakınlaştırma, yakınlaştırılmışken sürükleme, çift dokunma, yakınlaştırılmamışken kaydırarak sayfa çevirme. */
class PageView(ctx: Context) : View(ctx) {
    var bmp: Bitmap? = null
        set(v) { field = v; resetZoom() }
    var onSwipe: ((Int) -> Unit)? = null

    private val m = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private var minScale = 1f
    private var scale = 1f

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(minScale, minScale * 6f)
            val f = ns / scale
            m.postScale(f, f, d.focusX, d.focusY)
            scale = ns
            fix(); invalidate()
            return true
        }
    })

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale > minScale * 1.02f) { m.postTranslate(-dx, -dy); fix(); invalidate() }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scale <= minScale * 1.02f && abs(vx) > 800f && abs(vx) > abs(vy) * 1.5f) {
                onSwipe?.invoke(if (vx < 0) 1 else -1)
                return true
            }
            return false
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > minScale * 1.02f) resetZoom()
            else {
                val f = 2.5f
                m.postScale(f, f, e.x, e.y); scale *= f
                fix(); invalidate()
            }
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); resetZoom() }

    fun resetZoom() {
        val b = bmp
        m.reset()
        if (b != null && width > 0 && height > 0) {
            val s = min(width / b.width.toFloat(), height / b.height.toFloat())
            m.postScale(s, s)
            m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
            minScale = s
            scale = s
        } else { minScale = 1f; scale = 1f }
        invalidate()
    }

    /** Sayfanın görünür alandan taşmasını/kaybolmasını önler. */
    private fun fix() {
        val b = bmp ?: return
        rect.set(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= width) dx = (width - rect.width()) / 2f - rect.left
        else { if (rect.left > 0) dx = -rect.left; if (rect.right < width) dx = width - rect.right }
        if (rect.height() <= height) dy = (height - rect.height()) / 2f - rect.top
        else { if (rect.top > 0) dy = -rect.top; if (rect.bottom < height) dy = height - rect.bottom }
        m.postTranslate(dx, dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        if (!scaleDet.isInProgress) gd.onTouchEvent(e)
        return true
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        if (b.isRecycled) return
        c.drawBitmap(b, m, paint)
    }
}
